package br.com.deroyque.activation.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de taxa por chave (token bucket em memoria). Cada instancia limita
 * por conta propria; para um limite global entre instancias o controle deve ficar
 * no gateway/API management (ver README).
 */
public class TokenBucketRateLimiter {

	private static final int CLEANUP_THRESHOLD = 10_000;

	private final double refillPerMilli;
	private final int capacity;
	private final Clock clock;
	private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	public TokenBucketRateLimiter(int requestsPerMinute, int burst, Clock clock) {
		this.refillPerMilli = requestsPerMinute / 60_000.0;
		this.capacity = burst;
		this.clock = clock;
	}

	public Decision tryAcquire(String key) {
		long now = clock.millis();
		if (buckets.size() > CLEANUP_THRESHOLD) {
			buckets.values().removeIf(bucket -> bucket.isIdle(now));
		}
		return buckets.computeIfAbsent(key, k -> new Bucket(capacity, now)).tryAcquire(now);
	}

	public record Decision(boolean allowed, long retryAfterSeconds) {
	}

	private final class Bucket {

		private double tokens;
		private long lastRefill;

		Bucket(double tokens, long now) {
			this.tokens = tokens;
			this.lastRefill = now;
		}

		synchronized Decision tryAcquire(long now) {
			refill(now);
			if (tokens >= 1) {
				tokens -= 1;
				return new Decision(true, 0);
			}
			long waitMillis = (long) Math.ceil((1 - tokens) / refillPerMilli);
			return new Decision(false, Math.max(1, (waitMillis + 999) / 1000));
		}

		synchronized boolean isIdle(long now) {
			refill(now);
			return tokens >= capacity;
		}

		private void refill(long now) {
			tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerMilli);
			lastRefill = now;
		}
	}
}
