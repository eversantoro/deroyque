package br.com.deroyque.activation.problem;

/** Sufixos dos tipos de problema (concatenados ao {@code activation.problems.base-uri}). */
public final class ProblemSlugs {

	public static final String VALIDATION_ERROR = "validation-error";
	public static final String MALFORMED_REQUEST = "malformed-request";
	public static final String NOT_FOUND = "not-found";
	public static final String DUPLICATE_REQUEST = "duplicate-request";
	public static final String UNAUTHORIZED = "unauthorized";
	public static final String FORBIDDEN = "forbidden";
	public static final String PAYLOAD_TOO_LARGE = "payload-too-large";
	public static final String RATE_LIMITED = "rate-limited";
	public static final String SERVICE_UNAVAILABLE = "service-unavailable";
	public static final String INTERNAL_ERROR = "internal-error";
	public static final String HTTP_ERROR = "http-error";

	private ProblemSlugs() {
	}
}
