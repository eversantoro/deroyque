package br.com.deroyque.activation.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import br.com.deroyque.activation.application.ActivationProvider;
import br.com.deroyque.activation.application.ProviderOutcome;
import br.com.deroyque.activation.application.ProviderRequest;
import br.com.deroyque.activation.config.ActivationProperties;
import br.com.deroyque.activation.support.TestProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Traducao de cada retorno do provedor, contra um servidor HTTP simulado (WireMock). */
class HttpActivationProviderTest {

	private static final String PATH = "/provider/v1/activate";

	@RegisterExtension
	WireMockExtension provider = WireMockExtension.newInstance().build();

	private final ProviderRequest request = new ProviderRequest(UUID.randomUUID(), "EXT-1", "CUST-1", "INTERNET-500",
			"corr-9", 1);

	private ActivationProvider adapter(int minimumCalls, int window) {
		return adapter(provider.baseUrl(), minimumCalls, window);
	}

	private ActivationProvider adapter(String baseUrl, int minimumCalls, int window) {
		ActivationProperties properties = TestProperties.withProvider(baseUrl, Duration.ofSeconds(1),
				Duration.ofSeconds(1), 50, minimumCalls, window);
		ProviderClientConfig config = new ProviderClientConfig();
		return config.activationProvider(RestClient.builder(), properties,
				config.providerCircuitBreaker(CircuitBreakerRegistry.ofDefaults(), properties),
				new SimpleMeterRegistry());
	}

	@Test
	void successIsActivatedAndCarriesIdempotencyAndCorrelationHeaders() {
		provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

		ProviderOutcome outcome = adapter(10, 20).activate(request);

		assertThat(outcome).isInstanceOf(ProviderOutcome.Activated.class);
		provider.verify(postRequestedFor(urlEqualTo(PATH))
				.withHeader("Idempotency-Key", equalTo(request.requestId().toString()))
				.withHeader("X-Correlation-Id", equalTo("corr-9")).withHeader("X-Api-Key", equalTo("test-key"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
						.matchingJsonPath("$.externalId", equalTo("EXT-1"))));
	}

	@Test
	void badRequestIsRejectedWithoutRetry() {
		provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

		ProviderOutcome outcome = adapter(10, 20).activate(request);

		assertThat(outcome).isInstanceOfSatisfying(ProviderOutcome.Rejected.class,
				rejected -> assertThat(rejected.reason()).contains("400"));
	}

	@Test
	void serverErrorIsRetryable() {
		provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

		ProviderOutcome outcome = adapter(10, 20).activate(request);

		assertThat(outcome).isInstanceOfSatisfying(ProviderOutcome.RetryLater.class, retry -> {
			assertThat(retry.reason()).contains("500");
			assertThat(retry.retryAfter()).isNull();
		});
	}

	@Test
	void tooManyRequestsHonorsRetryAfterSeconds() {
		provider.stubFor(post(urlEqualTo(PATH))
				.willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7")));

		ProviderOutcome outcome = adapter(10, 20).activate(request);

		assertThat(outcome).isInstanceOfSatisfying(ProviderOutcome.RetryLater.class,
				retry -> assertThat(retry.retryAfter()).isEqualTo(Duration.ofSeconds(7)));
	}

	@Test
	void serviceUnavailableHonorsRetryAfterSeconds() {
		provider.stubFor(post(urlEqualTo(PATH))
				.willReturn(aResponse().withStatus(503).withHeader("Retry-After", "12")));

		ProviderOutcome outcome = adapter(10, 20).activate(request);

		assertThat(outcome).isInstanceOfSatisfying(ProviderOutcome.RetryLater.class,
				retry -> assertThat(retry.retryAfter()).isEqualTo(Duration.ofSeconds(12)));
	}

	@Test
	void invalidRetryAfterIsIgnored() {
		provider.stubFor(post(urlEqualTo(PATH))
				.willReturn(aResponse().withStatus(503).withHeader("Retry-After", "logo")));

		ProviderOutcome outcome = adapter(10, 20).activate(request);

		assertThat(outcome).isInstanceOfSatisfying(ProviderOutcome.RetryLater.class,
				retry -> assertThat(retry.retryAfter()).isNull());
	}

	@Test
	void responseAfterReadTimeoutIsRetryableTimeout() {
		provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

		ProviderOutcome outcome = adapter(10, 20).activate(request);

		assertThat(outcome).isInstanceOfSatisfying(ProviderOutcome.RetryLater.class,
				retry -> assertThat(retry.reason()).containsIgnoringCase("timeout"));
	}

	@Test
	void connectionFailureIsRetryable() {
		ProviderOutcome outcome = adapter("http://localhost:1", 10, 20).activate(request);

		assertThat(outcome).isInstanceOf(ProviderOutcome.RetryLater.class);
	}

	@Test
	void circuitOpensAfterRepeatedFailuresAndStopsCallingTheProvider() {
		provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
		ActivationProvider adapter = adapter(2, 2);

		adapter.activate(request);
		adapter.activate(request);
		ProviderOutcome third = adapter.activate(request);

		assertThat(third).isInstanceOf(ProviderOutcome.Unavailable.class);
		provider.verify(2, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void clientErrorsDoNotOpenTheCircuit() {
		provider.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));
		ActivationProvider adapter = adapter(2, 2);

		adapter.activate(request);
		adapter.activate(request);
		ProviderOutcome third = adapter.activate(request);

		assertThat(third).isInstanceOf(ProviderOutcome.Rejected.class);
		provider.verify(3, postRequestedFor(urlEqualTo(PATH)));
	}
}
