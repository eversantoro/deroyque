package br.com.deroyque.activation.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

	/** Relogio manual para avancar o tempo sem dormir. */
	private static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-10-26T14:00:00Z");

		void advanceSeconds(long seconds) {
			now = now.plusSeconds(seconds);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	void allowsUpToTheBurstThenRejectsWithRetryAfter() {
		MutableClock clock = new MutableClock();
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(60, 3, clock);

		for (int i = 0; i < 3; i++) {
			assertThat(limiter.tryAcquire("a").allowed()).isTrue();
		}
		TokenBucketRateLimiter.Decision rejected = limiter.tryAcquire("a");

		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
	}

	@Test
	void refillsOverTime() {
		MutableClock clock = new MutableClock();
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(60, 1, clock);
		limiter.tryAcquire("a");
		assertThat(limiter.tryAcquire("a").allowed()).isFalse();

		clock.advanceSeconds(1);

		assertThat(limiter.tryAcquire("a").allowed()).isTrue();
	}

	@Test
	void keysAreIndependent() {
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(60, 1, new MutableClock());
		limiter.tryAcquire("a");

		assertThat(limiter.tryAcquire("a").allowed()).isFalse();
		assertThat(limiter.tryAcquire("b").allowed()).isTrue();
	}
}
