package br.com.deroyque.activation.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identifica o sistema consumidor a partir do JWT. Em client credentials o
 * emissor costuma informar o cliente em {@code client_id} (ou {@code azp});
 * {@code sub} e o ultimo recurso. Cada solicitacao pertence a um consumidor e
 * so ele a enxerga (protecao contra BOLA).
 */
public final class ConsumerIdentity {

	private ConsumerIdentity() {
	}

	public static String ownerOf(Jwt jwt) {
		for (String claim : new String[] { "client_id", "azp" }) {
			String value = jwt.getClaimAsString(claim);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return jwt.getSubject();
	}
}
