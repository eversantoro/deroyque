package br.com.deroyque.activation.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import br.com.deroyque.activation.problem.PayloadTooLargeException;
import br.com.deroyque.activation.problem.ProblemSlugs;
import br.com.deroyque.activation.problem.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejeita corpos acima do limite: de imediato quando o Content-Length ja
 * excede e, para requisicoes sem tamanho declarado (chunked), interrompendo a
 * leitura ao passar do limite.
 */
public class BodySizeLimitFilter extends OncePerRequestFilter {

	private final long maxBytes;
	private final Problems problems;

	public BodySizeLimitFilter(long maxBytes, Problems problems) {
		this.maxBytes = maxBytes;
		this.problems = problems;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			problems.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, ProblemSlugs.PAYLOAD_TOO_LARGE,
					"Corpo da requisição muito grande", "O corpo da requisição excede o tamanho máximo permitido.");
			return;
		}
		chain.doFilter(new LimitedRequest(request, maxBytes), response);
	}

	private static final class LimitedRequest extends HttpServletRequestWrapper {

		private final long maxBytes;
		private ServletInputStream stream;

		LimitedRequest(HttpServletRequest request, long maxBytes) {
			super(request);
			this.maxBytes = maxBytes;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (stream == null) {
				stream = new LimitedInputStream(super.getInputStream(), maxBytes);
			}
			return stream;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			String encoding = getCharacterEncoding();
			Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}
	}

	private static final class LimitedInputStream extends ServletInputStream {

		private final ServletInputStream delegate;
		private final long maxBytes;
		private long read;

		LimitedInputStream(ServletInputStream delegate, long maxBytes) {
			this.delegate = delegate;
			this.maxBytes = maxBytes;
		}

		@Override
		public int read() throws IOException {
			int value = delegate.read();
			if (value >= 0) {
				count(1);
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int count = delegate.read(buffer, offset, length);
			if (count > 0) {
				count(count);
			}
			return count;
		}

		private void count(int bytes) throws IOException {
			read += bytes;
			if (read > maxBytes) {
				throw new PayloadTooLargeException(maxBytes);
			}
		}

		@Override
		public boolean isFinished() {
			return delegate.isFinished();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			delegate.setReadListener(listener);
		}
	}
}
