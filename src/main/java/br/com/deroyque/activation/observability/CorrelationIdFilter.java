package br.com.deroyque.activation.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Garante um identificador de correlacao por requisicao: usa o cabecalho
 * {@code X-Correlation-Id} quando valido, ou gera um novo. O valor entra no MDC
 * (aparece em todos os logs), volta na resposta e e gravado na solicitacao para
 * seguir ate a chamada ao provedor.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";
	public static final String MDC_KEY = "correlationId";

	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	/** Identificador da requisicao em andamento, ou {@code null} fora de uma requisicao. */
	public static String current() {
		return MDC.get(MDC_KEY);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		String correlationId = incoming != null && VALID.matcher(incoming).matches() ? incoming
				: UUID.randomUUID().toString().replace("-", "");
		MDC.put(MDC_KEY, correlationId);
		response.setHeader(HEADER, correlationId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}
}
