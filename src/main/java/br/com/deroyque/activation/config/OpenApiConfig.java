package br.com.deroyque.activation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	private static final String BEARER = "bearer-jwt";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI()
				.info(new Info().title("Serviço de ativação de produtos").version("v1").description(
						"Registro de solicitações de ativação, consulta de status e integração com o provedor externo. "
								+ "Autenticação por JWT (OAuth 2.0 client credentials) com os escopos requests:read e requests:write."))
				.components(new Components().addSecuritySchemes(BEARER,
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
				.addSecurityItem(new SecurityRequirement().addList(BEARER));
	}
}
