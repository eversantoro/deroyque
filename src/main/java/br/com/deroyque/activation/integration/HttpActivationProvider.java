package br.com.deroyque.activation.integration;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import br.com.deroyque.activation.application.ActivationProvider;
import br.com.deroyque.activation.application.ProviderOutcome;
import br.com.deroyque.activation.application.ProviderRequest;
import br.com.deroyque.activation.config.ActivationProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Adaptador HTTP do provedor de ativacao. Traduz cada retorno para um
 * {@link ProviderOutcome}:
 *
 * <ul>
 * <li>2xx: ativado;</li>
 * <li>429 e 408/425: nova tentativa, respeitando {@code Retry-After};</li>
 * <li>5xx (incl. 503) e timeout/erro de rede: nova tentativa; contam para o
 * circuit breaker;</li>
 * <li>demais 4xx (ex.: 400): recusa definitiva.</li>
 * </ul>
 *
 * Todas as chamadas levam {@code Idempotency-Key = id da solicitacao}, entao
 * repetir apos um timeout (resultado ambiguo) nao ativa o produto duas vezes.
 */
public class HttpActivationProvider implements ActivationProvider {

	public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
	public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
	public static final String API_KEY_HEADER = "X-Api-Key";

	private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

	private final RestClient client;
	private final ActivationProperties.Provider config;
	private final CircuitBreaker circuitBreaker;
	private final MeterRegistry meterRegistry;

	public HttpActivationProvider(RestClient client, ActivationProperties.Provider config,
			CircuitBreaker circuitBreaker, MeterRegistry meterRegistry) {
		this.client = client;
		this.config = config;
		this.circuitBreaker = circuitBreaker;
		this.meterRegistry = meterRegistry;
	}

	@Override
	public ProviderOutcome activate(ProviderRequest request) {
		Timer.Sample sample = Timer.start(meterRegistry);
		ProviderOutcome outcome;
		try {
			outcome = circuitBreaker.executeSupplier(() -> call(request));
		} catch (CallNotPermittedException e) {
			outcome = new ProviderOutcome.Unavailable("Circuito do provedor aberto", config.circuitBreaker().openDuration());
		} catch (TransientProviderException e) {
			outcome = e.outcome();
		}
		sample.stop(Timer.builder("activation.provider.calls").description("Chamadas ao provedor de ativacao")
				.tag("outcome", outcome.getClass().getSimpleName().toLowerCase())
				.publishPercentileHistogram().register(meterRegistry));
		return outcome;
	}

	private ProviderOutcome call(ProviderRequest request) {
		try {
			Classified result = client.post().uri(config.activatePath()).contentType(MediaType.APPLICATION_JSON)
					.headers(headers -> addHeaders(headers, request))
					.body(new ActivationPayload(request.requestId(), request.externalId(), request.customerId(),
							request.productCode()))
					.exchange((req, response) -> classify(response.getStatusCode(), response.getHeaders()));
			if (result.countsAsFailure()) {
				throw new TransientProviderException(result.outcome());
			}
			return result.outcome();
		} catch (TransientProviderException e) {
			throw e;
		} catch (RuntimeException e) {
			// Qualquer falha ao falar com o provedor (rede, timeout, cliente HTTP) e temporaria.
			String reason = isTimeout(e) ? "Timeout na chamada ao provedor" : "Falha de comunicacao com o provedor";
			log.warn("{} (solicitacao {}): {}", reason, request.requestId(), e.getMessage());
			throw new TransientProviderException(new ProviderOutcome.RetryLater(reason, null));
		}
	}

	private void addHeaders(HttpHeaders headers, ProviderRequest request) {
		headers.set(IDEMPOTENCY_KEY_HEADER, request.requestId().toString());
		if (request.correlationId() != null) {
			headers.set(CORRELATION_ID_HEADER, request.correlationId());
		}
		if (config.apiKey() != null && !config.apiKey().isBlank()) {
			headers.set(API_KEY_HEADER, config.apiKey());
		}
	}

	static Classified classify(HttpStatusCode status, HttpHeaders headers) {
		int code = status.value();
		if (status.is2xxSuccessful()) {
			return new Classified(new ProviderOutcome.Activated(), false);
		}
		if (code == 429) {
			return new Classified(new ProviderOutcome.RetryLater("Provedor limitou a taxa (HTTP 429)", retryAfter(headers)),
					false);
		}
		if (code == 408 || code == 425) {
			return new Classified(new ProviderOutcome.RetryLater("Provedor respondeu HTTP " + code, null), false);
		}
		if (status.is5xxServerError()) {
			return new Classified(
					new ProviderOutcome.RetryLater("Provedor respondeu HTTP " + code, retryAfter(headers)), true);
		}
		return new Classified(new ProviderOutcome.Rejected("Provedor recusou a solicitacao (HTTP " + code + ")"),
				false);
	}

	/** Interpreta Retry-After em segundos ou como data HTTP; {@code null} se ausente/invalido. */
	static Duration retryAfter(HttpHeaders headers) {
		String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
		} catch (NumberFormatException notSeconds) {
			try {
				Duration until = Duration.between(ZonedDateTime.now(),
						ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
				return until.isNegative() ? Duration.ZERO : until;
			} catch (RuntimeException invalid) {
				return null;
			}
		}
	}

	private static boolean isTimeout(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
					|| cause.getClass().getSimpleName().contains("Timeout")) {
				return true;
			}
		}
		return false;
	}

	record Classified(ProviderOutcome outcome, boolean countsAsFailure) {
	}

	record ActivationPayload(UUID requestId, String externalId, String customerId, String productCode) {
	}

	/** Falha temporaria que deve ser contabilizada pelo circuit breaker. */
	static class TransientProviderException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final transient ProviderOutcome outcome;

		TransientProviderException(ProviderOutcome outcome) {
			super(null, null, false, false);
			this.outcome = outcome;
		}

		ProviderOutcome outcome() {
			return outcome;
		}
	}
}
