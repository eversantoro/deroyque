package br.com.deroyque.activation.application;

public class DuplicateExternalIdException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public DuplicateExternalIdException(String externalId) {
		super("externalId " + externalId + " ja utilizado");
	}
}
