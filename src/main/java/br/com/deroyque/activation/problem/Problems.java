package br.com.deroyque.activation.problem;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.deroyque.activation.config.ActivationProperties;
import br.com.deroyque.activation.observability.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fabrica de respostas de erro no formato Problem Details (RFC 9457), usada
 * pelo tratador de excecoes e pelos filtros de seguranca, que respondem antes
 * de chegar aos controllers.
 */
@Component
public class Problems {

	private final String baseUri;
	private final ObjectMapper objectMapper;

	public Problems(ActivationProperties properties, ObjectMapper objectMapper) {
		this.baseUri = properties.problems().baseUri();
		this.objectMapper = objectMapper;
	}

	public ProblemDetail create(HttpStatusCode status, String slug, String title, String detail, String instance) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(java.net.URI.create(baseUri + slug));
		problem.setTitle(title);
		if (instance != null) {
			problem.setInstance(java.net.URI.create(instance));
		}
		String traceId = CorrelationIdFilter.current();
		if (traceId != null) {
			problem.setProperty("traceId", traceId);
		}
		return problem;
	}

	/** Escreve o problema diretamente na resposta (para filtros e handlers de seguranca). */
	public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String slug,
			String title, String detail) throws IOException {
		ProblemDetail problem = create(status, slug, title, detail, request.getRequestURI());
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
