package br.com.deroyque.activation.application;

import java.time.Duration;

/** Estrategia para calcular quanto esperar antes da proxima tentativa. */
@FunctionalInterface
public interface BackoffPolicy {

	/**
	 * @param attempt numero da tentativa que acabou de falhar (1 = primeira)
	 */
	Duration delayAfter(int attempt);
}
