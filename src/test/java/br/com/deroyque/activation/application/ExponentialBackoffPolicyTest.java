package br.com.deroyque.activation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ExponentialBackoffPolicyTest {

	private static final Duration INITIAL = Duration.ofSeconds(5);
	private static final Duration MAX = Duration.ofMinutes(1);

	@Test
	void ceilingDoublesEachAttemptWithoutJitter() {
		ExponentialBackoffPolicy policy = new ExponentialBackoffPolicy(INITIAL, MAX, () -> 1.0);

		assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
		assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(10));
		assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(20));
	}

	@Test
	void neverExceedsTheMaximum() {
		ExponentialBackoffPolicy policy = new ExponentialBackoffPolicy(INITIAL, MAX, () -> 1.0);

		assertThat(policy.delayAfter(10)).isEqualTo(MAX);
		assertThat(policy.delayAfter(1000)).isEqualTo(MAX);
	}

	@Test
	void jitterSpreadsTheDelayBetweenZeroAndTheCeiling() {
		assertThat(new ExponentialBackoffPolicy(INITIAL, MAX, () -> 0.5).delayAfter(2))
				.isEqualTo(Duration.ofSeconds(5));
		assertThat(new ExponentialBackoffPolicy(INITIAL, MAX, () -> 0.0).delayAfter(2))
				.isEqualTo(Duration.ofMillis(10));
	}
}
