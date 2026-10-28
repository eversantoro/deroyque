package br.com.deroyque.activation.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Configuracao tipada do servico (prefixo {@code activation}). Todos os valores
 * podem ser sobrescritos por variaveis de ambiente.
 */
@Validated
@ConfigurationProperties("activation")
public record ActivationProperties(
		@Valid @DefaultValue Worker worker,
		@Valid @DefaultValue Retry retry,
		@Valid @DefaultValue Reconciliation reconciliation,
		@Valid @DefaultValue Provider provider,
		@Valid @DefaultValue Security security,
		@Valid @DefaultValue Limits limits,
		@Valid @DefaultValue Problems problems) {

	/** Consumidor da fila de solicitacoes pendentes. */
	public record Worker(
			@DefaultValue("true") boolean enabled,
			@NotNull @DefaultValue("PT1S") Duration pollInterval,
			@Min(1) @DefaultValue("10") int batchSize) {
	}

	/** Politica de novas tentativas (backoff exponencial com jitter). */
	public record Retry(
			@Min(1) @DefaultValue("5") int maxAttempts,
			@NotNull @DefaultValue("PT5S") Duration initialDelay,
			@NotNull @DefaultValue("PT5M") Duration maxDelay) {
	}

	/** Recuperacao de solicitacoes presas em PROCESSING. */
	public record Reconciliation(
			@NotNull @DefaultValue("PT30S") Duration interval,
			@NotNull @DefaultValue("PT2M") Duration staleAfter,
			@Min(1) @DefaultValue("50") int batchSize) {
	}

	/** Cliente HTTP do provedor externo de ativacao. */
	public record Provider(
			@NotBlank @DefaultValue("http://localhost:9090") String baseUrl,
			@NotBlank @DefaultValue("/provider/v1/activate") String activatePath,
			@NotNull @DefaultValue("PT2S") Duration connectTimeout,
			@NotNull @DefaultValue("PT5S") Duration readTimeout,
			String apiKey,
			@Valid @DefaultValue CircuitBreaker circuitBreaker) {
	}

	public record CircuitBreaker(
			@Min(1) @DefaultValue("50") int failureRateThreshold,
			@Min(1) @DefaultValue("10") int minimumCalls,
			@Min(1) @DefaultValue("20") int slidingWindowSize,
			@NotNull @DefaultValue("PT30S") Duration openDuration) {
	}

	public record Security(@Valid @DefaultValue Jwt jwt) {
	}

	/**
	 * Validacao do JWT. {@code jwkSetUri} e a fonte de chaves em producao;
	 * {@code localSecret} (HS256) so e usado no perfil {@code local}.
	 */
	public record Jwt(
			@DefaultValue("https://auth.exemplo.com") String issuer,
			@DefaultValue("activation-service") String audience,
			String jwkSetUri,
			String localSecret) {
	}

	public record Limits(
			@NotNull @DefaultValue("8KB") DataSize maxBodySize,
			@Min(1) @DefaultValue("120") int requestsPerMinute,
			@Min(1) @DefaultValue("30") int burst) {
	}

	public record Problems(@NotBlank @DefaultValue("https://api.exemplo.com/problems/") String baseUri) {
	}
}
