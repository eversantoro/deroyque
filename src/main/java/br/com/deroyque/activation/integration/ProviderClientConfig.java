package br.com.deroyque.activation.integration;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import br.com.deroyque.activation.application.ActivationProvider;
import br.com.deroyque.activation.config.ActivationProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/** Monta o cliente HTTP (timeouts explicitos) e o circuit breaker do provedor. */
@Configuration(proxyBeanMethods = false)
public class ProviderClientConfig {

	@Bean
	CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
		CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
		TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
		return registry;
	}

	@Bean
	CircuitBreaker providerCircuitBreaker(CircuitBreakerRegistry registry, ActivationProperties properties) {
		ActivationProperties.CircuitBreaker settings = properties.provider().circuitBreaker();
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.failureRateThreshold(settings.failureRateThreshold())
				.minimumNumberOfCalls(settings.minimumCalls())
				.slidingWindowSize(settings.slidingWindowSize())
				.waitDurationInOpenState(settings.openDuration())
				.permittedNumberOfCallsInHalfOpenState(3)
				.recordExceptions(HttpActivationProvider.TransientProviderException.class)
				.build();
		return registry.circuitBreaker("activation-provider", config);
	}

	@Bean
	ActivationProvider activationProvider(RestClient.Builder builder, ActivationProperties properties,
			CircuitBreaker providerCircuitBreaker, MeterRegistry meterRegistry) {
		ActivationProperties.Provider provider = properties.provider();
		// HTTP/1.1 explicito: evita tentativa de upgrade h2c contra servidores simples.
		HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(provider.connectTimeout()).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(provider.readTimeout());
		RestClient client = builder.baseUrl(provider.baseUrl()).requestFactory(requestFactory).build();
		return new HttpActivationProvider(client, provider, providerCircuitBreaker, meterRegistry);
	}
}
