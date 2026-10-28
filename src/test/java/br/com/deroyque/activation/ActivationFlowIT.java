package br.com.deroyque.activation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import br.com.deroyque.activation.application.StalledRequestReconciler;
import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.RequestStatus;
import br.com.deroyque.activation.support.AbstractIntegrationTest;
import br.com.deroyque.activation.support.TestTokens;

/** Fluxo completo: API -> banco -> worker -> provedor, com cada tipo de retorno. */
class ActivationFlowIT extends AbstractIntegrationTest {

	private static final String PATH = "/provider/v1/activate";
	private static final String AUTH = TestTokens.bearer("sys-a", "requests:read requests:write");

	@Autowired
	StalledRequestReconciler reconciler;

	private UUID create(String externalId, String productCode) throws Exception {
		String body = mockMvc
				.perform(post("/api/v1/requests").header("Authorization", AUTH).header("X-Correlation-Id", "corr-it-1")
						.contentType(MediaType.APPLICATION_JSON).content(json(externalId, "CUST-1", productCode)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(read(body, "$.id"));
	}

	private ActivationRequest awaitStatus(UUID id, RequestStatus expected) {
		await().untilAsserted(
				() -> assertThat(repository.findById(id)).get().extracting(ActivationRequest::getStatus).isEqualTo(expected));
		return repository.findById(id).orElseThrow();
	}

	@Test
	void successCompletesTheRequestAndSendsIdempotencyAndCorrelationHeaders() throws Exception {
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

		UUID id = create("EXT-OK", "INTERNET-500");
		ActivationRequest done = awaitStatus(id, RequestStatus.COMPLETED);

		assertThat(done.getAttempts()).isEqualTo(1);
		PROVIDER.verify(1, postRequestedFor(urlEqualTo(PATH)).withHeader("Idempotency-Key", equalTo(id.toString()))
				.withHeader("X-Correlation-Id", equalTo("corr-it-1")).withHeader("X-Api-Key", equalTo("test-provider-key")));
		mockMvc.perform(get("/api/v1/requests/{id}", id).header("Authorization", AUTH)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void providerRejectionFailsWithoutRetry() throws Exception {
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

		UUID id = create("EXT-400", "INVALID-PRODUCT");
		ActivationRequest failed = awaitStatus(id, RequestStatus.FAILED);

		assertThat(failed.getFailureReason()).contains("400");
		assertThat(failed.getAttempts()).isEqualTo(1);
		PROVIDER.verify(1, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void persistentServerErrorRetriesUntilLimitThenFails() throws Exception {
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

		UUID id = create("EXT-500", "INTERNET-500");
		ActivationRequest failed = awaitStatus(id, RequestStatus.FAILED);

		assertThat(failed.getAttempts()).isEqualTo(3);
		assertThat(failed.getFailureReason()).contains("Tentativas esgotadas").contains("500");
		PROVIDER.verify(3, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void transientErrorIsRetriedAndEventuallyCompletes() throws Exception {
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("recovered")
				.willReturn(aResponse().withStatus(200)));

		UUID id = create("EXT-FLAKY", "INTERNET-500");
		ActivationRequest done = awaitStatus(id, RequestStatus.COMPLETED);

		assertThat(done.getAttempts()).isEqualTo(2);
		PROVIDER.verify(2, postRequestedFor(urlEqualTo(PATH)));
	}

	@Test
	void timeoutOnEveryAttemptEndsAsFailed() throws Exception {
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(2500)));

		UUID id = create("EXT-SLOW", "INTERNET-500");
		ActivationRequest failed = awaitStatus(id, RequestStatus.FAILED);

		assertThat(failed.getAttempts()).isEqualTo(3);
		assertThat(failed.getFailureReason()).containsIgnoringCase("timeout");
	}

	@Test
	void retryAfterTimeoutReusesTheSameIdempotencyKey() throws Exception {
		// 1a chamada demora alem do timeout (resultado ambiguo); a 2a responde rapido.
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).inScenario("ambiguous").whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse().withStatus(200).withFixedDelay(2500)).willSetStateTo("second"));
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).inScenario("ambiguous").whenScenarioStateIs("second")
				.willReturn(aResponse().withStatus(200)));

		UUID id = create("EXT-AMBIGUOUS", "INTERNET-500");
		awaitStatus(id, RequestStatus.COMPLETED);

		PROVIDER.verify(2, postRequestedFor(urlEqualTo(PATH)).withHeader("Idempotency-Key", equalTo(id.toString())));
	}

	@Test
	void reconcilerReturnsStalledRequestsToTheQueue() throws Exception {
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
		Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
		ActivationRequest stalled = ActivationRequest.create("sys-a", "EXT-STALLED", "CUST-1", "INTERNET-500", "corr",
				tenMinutesAgo);
		stalled.startProcessing(tenMinutesAgo); // a instancia caiu logo depois de reservar
		repository.saveAndFlush(stalled);

		assertThat(reconciler.reconcile()).isEqualTo(1);

		ActivationRequest done = awaitStatus(stalled.getId(), RequestStatus.COMPLETED);
		assertThat(done.getAttempts()).isEqualTo(2);
	}

	@Test
	void listsOnlyOwnRequestsWithFiltersAndPagination() throws Exception {
		PROVIDER.stubFor(WireMock.post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
		for (int i = 1; i <= 3; i++) {
			UUID id = create("EXT-LIST-" + i, "INTERNET-500");
			awaitStatus(id, RequestStatus.COMPLETED);
		}
		repository.saveAndFlush(ActivationRequest.create("sys-b", "EXT-OTHER", "CUST-1", "INTERNET-500", "c", Instant.now()));

		String page = mockMvc
				.perform(get("/api/v1/requests").param("status", "COMPLETED").param("customerId", "CUST-1")
						.param("page", "0").param("size", "2").header("Authorization", AUTH))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
				.andReturn().getResponse().getContentAsString();

		List<String> externalIds = com.jayway.jsonpath.JsonPath.read(page, "$.content[*].externalId");
		assertThat(externalIds).containsExactly("EXT-LIST-3", "EXT-LIST-2"); // mais recentes primeiro
		mockMvc.perform(get("/api/v1/requests").param("status", "FAILED").header("Authorization", AUTH))
				.andExpect(jsonPath("$.totalElements").value(0));
	}
}
