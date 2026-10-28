package br.com.deroyque.activation;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import br.com.deroyque.activation.support.AbstractIntegrationTest;
import br.com.deroyque.activation.support.TestTokens;

/** Seguranca ponta a ponta com JWTs reais assinados em teste (sem servidor de autenticacao). */
class SecurityIT extends AbstractIntegrationTest {

	private static final String BODY = json("EXT-SEC", "CUST-1", "INTERNET-500");

	@Test
	void missingTokenIs401() throws Exception {
		mockMvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tokenSignedWithAnotherKeyIs401() throws Exception {
		String forged = TestTokens.token("sys-a", "requests:write", TestTokens.ISSUER, TestTokens.AUDIENCE,
				Instant.now().plusSeconds(60), "another-secret-with-more-than-32-bytes-long!!");

		mockMvc.perform(post("/api/v1/requests").header("Authorization", "Bearer " + forged)
				.contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
	}

	@Test
	void expiredTokenIs401() throws Exception {
		String expired = TestTokens.token("sys-a", "requests:write", TestTokens.ISSUER, TestTokens.AUDIENCE,
				Instant.now().minusSeconds(600), TestTokens.SECRET);

		mockMvc.perform(post("/api/v1/requests").header("Authorization", "Bearer " + expired)
				.contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenForAnotherAudienceIs401() throws Exception {
		String wrongAudience = TestTokens.token("sys-a", "requests:write", TestTokens.ISSUER, "outro-servico",
				Instant.now().plusSeconds(60), TestTokens.SECRET);

		mockMvc.perform(post("/api/v1/requests").header("Authorization", "Bearer " + wrongAudience)
				.contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenFromAnotherIssuerIs401() throws Exception {
		String wrongIssuer = TestTokens.token("sys-a", "requests:write", "https://impostor.exemplo.com",
				TestTokens.AUDIENCE, Instant.now().plusSeconds(60), TestTokens.SECRET);

		mockMvc.perform(post("/api/v1/requests").header("Authorization", "Bearer " + wrongIssuer)
				.contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
	}

	@Test
	void insufficientScopeIs403() throws Exception {
		mockMvc.perform(post("/api/v1/requests").header("Authorization", TestTokens.bearer("sys-a", "requests:read"))
				.contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(get("/api/v1/requests").header("Authorization", TestTokens.bearer("sys-a", "requests:write")))
				.andExpect(status().isForbidden());
	}

	@Test
	void consumerCannotReadOrListRequestsOfAnotherConsumer() throws Exception {
		String body = mockMvc
				.perform(post("/api/v1/requests").header("Authorization", TestTokens.bearer("sys-a", "requests:write"))
						.contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String id = read(body, "$.id");
		String other = TestTokens.bearer("sys-b", "requests:read");

		mockMvc.perform(get("/api/v1/requests/{id}", id).header("Authorization", other))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/requests").header("Authorization", other))
				.andExpect(jsonPath("$.totalElements").value(0));
		mockMvc.perform(get("/api/v1/requests/{id}", id)
				.header("Authorization", TestTokens.bearer("sys-a", "requests:read"))).andExpect(status().isOk());
	}

	@Test
	void oversizedBodyIs413() throws Exception {
		String huge = "{\"externalId\":\"" + "A".repeat(20_000) + "\"}";

		mockMvc.perform(post("/api/v1/requests").header("Authorization", TestTokens.bearer("sys-a", "requests:write"))
				.contentType(MediaType.APPLICATION_JSON).content(huge)).andExpect(status().isPayloadTooLarge())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void healthProbesArePublicButMetricsRequireScope() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
		mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/actuator/prometheus").header("Authorization",
				TestTokens.bearer("prometheus", "metrics:read"))).andExpect(status().isOk())
				.andExpect(content().string(containsString("activation_requests_backlog")));
	}

	@Test
	void openApiDocumentationIsPublic() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/requests']").exists());
	}
}
