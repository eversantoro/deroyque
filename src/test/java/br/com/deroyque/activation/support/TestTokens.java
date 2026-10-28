package br.com.deroyque.activation.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Emite JWTs HS256 reais para os testes; nao ha servidor de autenticacao. */
public final class TestTokens {

	public static final String SECRET = "local-development-secret-change-me-0123456789";
	public static final String ISSUER = "http://localhost/local-issuer";
	public static final String AUDIENCE = "activation-service";

	private TestTokens() {
	}

	public static String bearer(String clientId, String scopes) {
		return "Bearer " + token(clientId, scopes, ISSUER, AUDIENCE, Instant.now().plusSeconds(3600), SECRET);
	}

	public static String token(String clientId, String scopes, String issuer, String audience, Instant expiresAt,
			String secret) {
		try {
			JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject(clientId)
					.audience(List.of(audience)).claim("client_id", clientId).claim("scope", scopes)
					.issueTime(new Date()).expirationTime(Date.from(expiresAt)).build();
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
			return jwt.serialize();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
