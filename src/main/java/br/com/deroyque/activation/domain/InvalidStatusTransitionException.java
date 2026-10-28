package br.com.deroyque.activation.domain;

public class InvalidStatusTransitionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public InvalidStatusTransitionException(RequestStatus from, RequestStatus to) {
		super("Transicao de status invalida: " + from + " -> " + to);
	}
}
