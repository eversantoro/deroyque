package br.com.deroyque.activation.domain;

/**
 * Estados de uma solicitacao e as transicoes permitidas entre eles.
 *
 * <pre>
 * PENDING -> PROCESSING -> COMPLETED
 *                |-------> FAILED
 *                '-------> PENDING   (falha temporaria: nova tentativa)
 * </pre>
 */
public enum RequestStatus {

	PENDING, PROCESSING, COMPLETED, FAILED;

	public boolean canTransitionTo(RequestStatus target) {
		return switch (this) {
			case PENDING -> target == PROCESSING;
			case PROCESSING -> target == COMPLETED || target == FAILED || target == PENDING;
			case COMPLETED, FAILED -> false;
		};
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}
}
