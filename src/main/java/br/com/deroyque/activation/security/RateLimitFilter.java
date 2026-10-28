package br.com.deroyque.activation.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import br.com.deroyque.activation.problem.ProblemSlugs;
import br.com.deroyque.activation.problem.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Limita a taxa de chamadas a API por consumidor (ou por IP, se nao autenticado). */
public class RateLimitFilter extends OncePerRequestFilter {

	private final TokenBucketRateLimiter limiter;
	private final Problems problems;

	public RateLimitFilter(TokenBucketRateLimiter limiter, Problems problems) {
		this.limiter = limiter;
		this.problems = problems;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		TokenBucketRateLimiter.Decision decision = limiter.tryAcquire(keyOf(request));
		if (decision.allowed()) {
			chain.doFilter(request, response);
			return;
		}
		response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
		problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, ProblemSlugs.RATE_LIMITED,
				"Limite de requisições excedido", "Muitas requisições. Tente novamente após o intervalo indicado em Retry-After.");
	}

	private static String keyOf(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null
				&& !"anonymousUser".equals(authentication.getName())) {
			return "consumer:" + authentication.getName();
		}
		return "ip:" + request.getRemoteAddr();
	}
}
