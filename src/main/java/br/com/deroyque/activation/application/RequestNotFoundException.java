package br.com.deroyque.activation.application;

import java.util.UUID;

public class RequestNotFoundException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RequestNotFoundException(UUID id) {
		super("Solicitacao " + id + " nao encontrada");
	}
}
