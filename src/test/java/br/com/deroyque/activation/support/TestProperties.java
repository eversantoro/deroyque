package br.com.deroyque.activation.support;

import java.time.Duration;

import org.springframework.util.unit.DataSize;

import br.com.deroyque.activation.config.ActivationProperties;

/** Constroi {@link ActivationProperties} para testes sem subir o Spring. */
public final class TestProperties {

	private TestProperties() {
	}

	public static ActivationProperties defaults() {
		return withProvider("http://localhost:9090", Duration.ofSeconds(1), Duration.ofMillis(300), 50, 10, 20);
	}

	public static ActivationProperties withProvider(String baseUrl, Duration connectTimeout, Duration readTimeout,
			int failureRate, int minimumCalls, int window) {
		return new ActivationProperties(
				new ActivationProperties.Worker(true, Duration.ofSeconds(1), 10),
				new ActivationProperties.Retry(3, Duration.ofSeconds(5), Duration.ofMinutes(5)),
				new ActivationProperties.Reconciliation(Duration.ofSeconds(30), Duration.ofMinutes(2), 50),
				new ActivationProperties.Provider(baseUrl, "/provider/v1/activate", connectTimeout, readTimeout,
						"test-key", new ActivationProperties.CircuitBreaker(failureRate, minimumCalls, window,
								Duration.ofSeconds(30))),
				new ActivationProperties.Security(new ActivationProperties.Jwt("https://auth.exemplo.com",
						"activation-service", null, null)),
				new ActivationProperties.Limits(DataSize.ofKilobytes(8), 120, 30),
				new ActivationProperties.Problems("https://api.exemplo.com/problems/"));
	}
}
