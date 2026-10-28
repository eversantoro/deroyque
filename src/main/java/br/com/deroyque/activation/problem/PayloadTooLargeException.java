package br.com.deroyque.activation.problem;

import java.io.IOException;

/** Corpo da requisicao maior que o limite configurado. */
public class PayloadTooLargeException extends IOException {

	private static final long serialVersionUID = 1L;

	public PayloadTooLargeException(long maxBytes) {
		super("Corpo da requisicao excede " + maxBytes + " bytes");
	}
}
