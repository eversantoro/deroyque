package br.com.deroyque.activation.security;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import br.com.deroyque.activation.config.ActivationProperties;
import br.com.deroyque.activation.problem.ProblemSlugs;
import br.com.deroyque.activation.problem.Problems;

/**
 * API como OAuth 2.0 Resource Server: o servico apenas valida JWTs emitidos por
 * um servidor de autorizacao externo (client credentials). Leitura e escrita
 * exigem escopos distintos.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

	public static final String SCOPE_READ = "SCOPE_requests:read";
	public static final String SCOPE_WRITE = "SCOPE_requests:write";
	public static final String SCOPE_METRICS = "SCOPE_metrics:read";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, Problems problems, ActivationProperties properties,
			Clock clock) throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
			response.setHeader("WWW-Authenticate", "Bearer");
			problems.write(request, response, HttpStatus.UNAUTHORIZED, ProblemSlugs.UNAUTHORIZED,
					"Não autenticado", "Token de acesso ausente, inválido ou expirado.");
		};
		AccessDeniedHandler forbidden = (request, response, ex) -> problems.write(request, response,
				HttpStatus.FORBIDDEN, ProblemSlugs.FORBIDDEN, "Acesso negado",
				"O token não possui o escopo necessário para esta operação.");

		http.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
						.requestMatchers("/actuator/**").hasAuthority(SCOPE_METRICS)
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
						.requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*")
						.hasAuthority(SCOPE_READ)
						.anyRequest().denyAll())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
				.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
						.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
				.addFilterBefore(new BodySizeLimitFilter(properties.limits().maxBodySize().toBytes(), problems),
						BearerTokenAuthenticationFilter.class)
				.addFilterAfter(new RateLimitFilter(new TokenBucketRateLimiter(properties.limits().requestsPerMinute(),
						properties.limits().burst(), clock), problems), BearerTokenAuthenticationFilter.class);
		return http.build();
	}
}
