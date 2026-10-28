package br.com.deroyque.activation.web;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import br.com.deroyque.activation.application.ActivationRequestService;
import br.com.deroyque.activation.application.ActivationRequestService.CreationResult;
import br.com.deroyque.activation.application.DuplicateExternalIdException;
import br.com.deroyque.activation.application.RequestNotFoundException;
import br.com.deroyque.activation.config.ActivationProperties;
import br.com.deroyque.activation.domain.ActivationRequest;
import br.com.deroyque.activation.domain.RequestStatus;
import br.com.deroyque.activation.problem.Problems;
import br.com.deroyque.activation.security.SecurityConfig;

@WebMvcTest(ActivationRequestController.class)
@Import({ SecurityConfig.class, Problems.class, ActivationRequestControllerTest.ClockConfig.class })
@EnableConfigurationProperties(ActivationProperties.class)
class ActivationRequestControllerTest {

	static class ClockConfig {
		@Bean
		Clock clock() {
			return Clock.systemUTC();
		}
	}

	private static final String VALID_BODY = """
			{"externalId": "EXT-12345", "customerId": "CUST-10001", "productCode": "INTERNET-500"}
			""";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ActivationRequestService service;

	@MockitoBean
	JwtDecoder jwtDecoder; // exigido pelo resource server; os testes usam o post-processor jwt()

	private static RequestPostProcessor token(String clientId, String... scopes) {
		return jwt().jwt(jwt -> jwt.claim("client_id", clientId).claim("scope", String.join(" ", scopes)));
	}

	private static ActivationRequest sample() {
		return ActivationRequest.create("sys-a", "EXT-12345", "CUST-10001", "INTERNET-500", "corr",
				Instant.parse("2026-10-26T14:03:11Z"));
	}

	// ---- criacao ----

	@Test
	void createsRequestAnd201WithLocation() throws Exception {
		ActivationRequest created = sample();
		when(service.create(any(), eq("sys-a"))).thenReturn(new CreationResult(created, true));

		mockMvc.perform(post("/api/v1/requests").with(token("sys-a", "requests:write"))
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", endsWith("/api/v1/requests/" + created.getId())))
				.andExpect(jsonPath("$.id").value(created.getId().toString()))
				.andExpect(jsonPath("$.externalId").value("EXT-12345"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
				.andExpect(jsonPath("$.updatedAt").value("2026-10-26T14:03:11Z"));
	}

	@Test
	void repeatedRequestReturns200WithExistingRecord() throws Exception {
		ActivationRequest existing = sample();
		when(service.create(any(), eq("sys-a"))).thenReturn(new CreationResult(existing, false));

		mockMvc.perform(post("/api/v1/requests").with(token("sys-a", "requests:write"))
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.id").value(existing.getId().toString()));
	}

	@Test
	void missingFieldsReturnProblemDetailsWithErrors() throws Exception {
		mockMvc.perform(post("/api/v1/requests").with(token("sys-a", "requests:write"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"externalId\": \"EXT-1\", \"productCode\": \"  \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/validation-error"))
				.andExpect(jsonPath("$.title").value("Dados inválidos"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.instance").value("/api/v1/requests"))
				.andExpect(jsonPath("$.traceId").isNotEmpty())
				.andExpect(jsonPath("$.errors[*].field").value(hasItem("customerId")))
				.andExpect(jsonPath("$.errors[*].field").value(hasItem("productCode")))
				.andExpect(jsonPath("$.errors[?(@.field=='customerId')].message").value(hasItem("é obrigatório")));
	}

	@Test
	void tooLongAndMalformedIdentifiersAreRejected() throws Exception {
		String body = "{\"externalId\": \"" + "A".repeat(65) + "\", \"customerId\": \"com espaco\", \"productCode\": \"OK-1\"}";

		mockMvc.perform(post("/api/v1/requests").with(token("sys-a", "requests:write"))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='externalId')].message").value(hasItem("deve ter no máximo 64 caracteres")))
				.andExpect(jsonPath("$.errors[?(@.field=='customerId')].message").exists());
	}

	@Test
	void malformedJsonReturns400WithoutInternalDetails() throws Exception {
		mockMvc.perform(post("/api/v1/requests").with(token("sys-a", "requests:write"))
				.contentType(MediaType.APPLICATION_JSON).content("{ nao e json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/malformed-request"))
				.andExpect(content().string(not(containsString("Jackson"))))
				.andExpect(content().string(not(containsString("Exception"))));
	}

	@Test
	void conflictingExternalIdReturns409() throws Exception {
		when(service.create(any(), any())).thenThrow(new DuplicateExternalIdException("EXT-12345"));

		mockMvc.perform(post("/api/v1/requests").with(token("sys-a", "requests:write"))
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/duplicate-request"));
	}

	@Test
	void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
		when(service.create(any(), any())).thenThrow(new IllegalStateException("senha do banco: secreta"));

		mockMvc.perform(post("/api/v1/requests").with(token("sys-a", "requests:write"))
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.title").value("Erro interno"))
				.andExpect(jsonPath("$.traceId").isNotEmpty())
				.andExpect(content().string(not(containsString("secreta"))))
				.andExpect(content().string(not(containsString("IllegalStateException"))));
	}

	@Test
	void unmappedOperationsAreDeniedByDefault() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/v1/requests/" + UUID.randomUUID()).with(token("sys-a", "requests:write", "requests:read")))
				.andExpect(status().isForbidden()); // DELETE nao e mapeado: negado por padrao
	}

	// ---- consulta ----

	@Test
	void getsExistingRequest() throws Exception {
		ActivationRequest existing = sample();
		when(service.get(existing.getId(), "sys-a")).thenReturn(existing);

		mockMvc.perform(get("/api/v1/requests/{id}", existing.getId()).with(token("sys-a", "requests:read")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(existing.getId().toString()))
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void unknownRequestReturns404() throws Exception {
		UUID id = UUID.randomUUID();
		when(service.get(id, "sys-a")).thenThrow(new RequestNotFoundException(id));

		mockMvc.perform(get("/api/v1/requests/{id}", id).with(token("sys-a", "requests:read")))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/not-found"))
				.andExpect(jsonPath("$.instance").value("/api/v1/requests/" + id));
	}

	@Test
	void malformedIdReturns400() throws Exception {
		mockMvc.perform(get("/api/v1/requests/{id}", "nao-e-uuid").with(token("sys-a", "requests:read")))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	// ---- listagem ----

	@Test
	void listsWithFiltersAndPagination() throws Exception {
		ActivationRequest existing = sample();
		when(service.search("sys-a", RequestStatus.PENDING, "CUST-10001", 1, 5))
				.thenReturn(new PageImpl<>(List.of(existing), PageRequest.of(1, 5), 6));

		mockMvc.perform(get("/api/v1/requests").param("status", "PENDING").param("customerId", "CUST-10001")
				.param("page", "1").param("size", "5").with(token("sys-a", "requests:read")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(existing.getId().toString()))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(5))
				.andExpect(jsonPath("$.totalElements").value(6))
				.andExpect(jsonPath("$.totalPages").value(2));
	}

	@Test
	void rejectsOutOfRangePageSizeAndUnknownStatus() throws Exception {
		mockMvc.perform(get("/api/v1/requests").param("size", "1000").with(token("sys-a", "requests:read")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("size"));
		mockMvc.perform(get("/api/v1/requests").param("status", "INEXISTENTE").with(token("sys-a", "requests:read")))
				.andExpect(status().isBadRequest());
	}

	// ---- seguranca ----

	@Test
	void withoutTokenReturns401Problem() throws Exception {
		mockMvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/unauthorized"));
	}

	@Test
	void readOnlyScopeCannotCreate() throws Exception {
		mockMvc.perform(post("/api/v1/requests").with(token("sys-a", "requests:read"))
				.contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("https://api.exemplo.com/problems/forbidden"));
	}

	@Test
	void writeOnlyScopeCannotQuery() throws Exception {
		mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(token("sys-a", "requests:write")))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/requests").with(token("sys-a", "requests:write")))
				.andExpect(status().isForbidden());
	}

	@Test
	void echoesProvidedCorrelationIdAndGeneratesOneOtherwise() throws Exception {
		mockMvc.perform(get("/api/v1/requests/{id}", "x").with(token("sys-a", "requests:read"))
				.header("X-Correlation-Id", "abc-123"))
				.andExpect(header().string("X-Correlation-Id", "abc-123"))
				.andExpect(jsonPath("$.traceId").value("abc-123"));
		mockMvc.perform(get("/api/v1/requests/{id}", "x").with(token("sys-a", "requests:read"))
				.header("X-Correlation-Id", "invalido com espaco"))
				.andExpect(header().string("X-Correlation-Id", not("invalido com espaco")));
	}
}
