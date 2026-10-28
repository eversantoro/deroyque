package br.com.deroyque.activation.application;

import java.time.Duration;
import java.util.function.DoubleSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.deroyque.activation.config.ActivationProperties;

/**
 * Backoff exponencial com "full jitter": o atraso e sorteado entre 0 e
 * {@code min(max, inicial * 2^(tentativa-1))}, evitando que varias solicitacoes
 * falhas voltem ao provedor no mesmo instante.
 */
@Component
public class ExponentialBackoffPolicy implements BackoffPolicy {

	private static final Duration MINIMUM_DELAY = Duration.ofMillis(10);

	private final Duration initialDelay;
	private final Duration maxDelay;
	private final DoubleSupplier random;

	@Autowired
	public ExponentialBackoffPolicy(ActivationProperties properties) {
		this(properties.retry().initialDelay(), properties.retry().maxDelay(), Math::random);
	}

	public ExponentialBackoffPolicy(Duration initialDelay, Duration maxDelay, DoubleSupplier random) {
		this.initialDelay = initialDelay;
		this.maxDelay = maxDelay;
		this.random = random;
	}

	@Override
	public Duration delayAfter(int attempt) {
		int exponent = Math.min(Math.max(attempt, 1) - 1, 20);
		long ceilingMillis = Math.min(maxDelay.toMillis(), initialDelay.toMillis() * (1L << exponent));
		Duration jittered = Duration.ofMillis((long) (ceilingMillis * random.getAsDouble()));
		return jittered.compareTo(MINIMUM_DELAY) < 0 ? MINIMUM_DELAY : jittered;
	}
}
