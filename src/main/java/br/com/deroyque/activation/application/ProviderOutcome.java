package br.com.deroyque.activation.application;

import java.time.Duration;

/**
 * Resultado de uma chamada ao provedor, ja traduzido para a linguagem do
 * dominio. O adaptador HTTP decide o que cada retorno significa; o processador
 * apenas reage ao resultado.
 */
public sealed interface ProviderOutcome {

	/** Provedor confirmou a ativacao (2xx). */
	record Activated() implements ProviderOutcome {
	}

	/** Provedor recusou de forma definitiva; repetir nao adianta (ex.: 400). */
	record Rejected(String reason) implements ProviderOutcome {
	}

	/**
	 * Falha temporaria depois de a chamada ter sido feita (5xx, timeout, 429...).
	 * {@code retryAfter} e a sugestao do provedor, quando houver.
	 */
	record RetryLater(String reason, Duration retryAfter) implements ProviderOutcome {
	}

	/** A chamada nem foi tentada (circuito aberto); nao consome tentativa. */
	record Unavailable(String reason, Duration retryAfter) implements ProviderOutcome {
	}
}
