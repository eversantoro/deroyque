package br.com.deroyque.activation.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.Assert;

import br.com.deroyque.activation.config.ActivationProperties;

/**
 * Decodificadores de JWT. Em qualquer ambiente o token e validado quanto a
 * assinatura, emissor, audiencia e expiracao; so muda a origem da chave:
 * JWKS do servidor de autorizacao (padrao) ou segredo simetrico (perfil
 * {@code local}, para desenvolvimento sem servidor de autenticacao).
 */
@Configuration(proxyBeanMethods = false)
public class JwtDecoderConfig {

	@Bean
	@Profile("!local")
	JwtDecoder jwksJwtDecoder(ActivationProperties properties) {
		ActivationProperties.Jwt jwt = properties.security().jwt();
		Assert.hasText(jwt.jwkSetUri(), "activation.security.jwt.jwk-set-uri (JWT_JWK_SET_URI) e obrigatorio");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.jwkSetUri()).build();
		decoder.setJwtValidator(validator(jwt));
		return decoder;
	}

	@Bean
	@Profile("local")
	JwtDecoder localJwtDecoder(ActivationProperties properties) {
		ActivationProperties.Jwt jwt = properties.security().jwt();
		Assert.hasText(jwt.localSecret(), "activation.security.jwt.local-secret e obrigatorio no perfil local");
		byte[] secret = jwt.localSecret().getBytes(StandardCharsets.UTF_8);
		Assert.isTrue(secret.length >= 32, "O segredo HS256 deve ter ao menos 32 bytes");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
				.macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(validator(jwt));
		return decoder;
	}

	static OAuth2TokenValidator<Jwt> validator(ActivationProperties.Jwt jwt) {
		OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
				aud -> aud != null && aud.contains(jwt.audience()));
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(jwt.issuer()), audience);
	}
}
