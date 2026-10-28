package br.com.deroyque.activation.web;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import br.com.deroyque.activation.application.DuplicateExternalIdException;
import br.com.deroyque.activation.application.RequestNotFoundException;
import br.com.deroyque.activation.problem.PayloadTooLargeException;
import br.com.deroyque.activation.problem.ProblemSlugs;
import br.com.deroyque.activation.problem.Problems;

/**
 * Converte excecoes em respostas Problem Details. Nunca expoe stack trace nem
 * mensagens internas: o consumidor recebe apenas o {@code traceId}, que permite
 * localizar o erro completo nos logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final Problems problems;

	public GlobalExceptionHandler(Problems problems) {
		this.problems = problems;
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldProblem(error.getField(), messageOf(error))).toList();
		return validationProblem(errors, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new FieldProblem(result.getMethodParameter().getParameterName(),
								error.getDefaultMessage())))
				.toList();
		return validationProblem(errors, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof PayloadTooLargeException) {
				return respond(HttpStatus.PAYLOAD_TOO_LARGE, ProblemSlugs.PAYLOAD_TOO_LARGE,
						"Corpo da requisição muito grande", "O corpo da requisição excede o tamanho máximo permitido.",
						request);
			}
		}
		return respond(HttpStatus.BAD_REQUEST, ProblemSlugs.MALFORMED_REQUEST, "Requisição malformada",
				"O corpo da requisição está ausente ou não é um JSON válido.", request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		// Demais excecoes do Spring MVC (405, 415, tipo de parametro...): padroniza o formato.
		if (body instanceof ProblemDetail problem) {
			String instance = request instanceof ServletWebRequest web ? web.getRequest().getRequestURI() : null;
			ProblemDetail standard = problems.create(statusCode, ProblemSlugs.HTTP_ERROR,
					problem.getTitle() != null ? problem.getTitle() : HttpStatus.valueOf(statusCode.value()).getReasonPhrase(),
					problem.getDetail(), instance);
			return super.handleExceptionInternal(ex, standard, headers, statusCode, request);
		}
		return super.handleExceptionInternal(ex, body, headers, statusCode, request);
	}

	@ExceptionHandler(RequestNotFoundException.class)
	ResponseEntity<Object> handleNotFound(RequestNotFoundException ex, WebRequest request) {
		return respond(HttpStatus.NOT_FOUND, ProblemSlugs.NOT_FOUND, "Solicitação não encontrada",
				"Não existe solicitação com o identificador informado.", request);
	}

	@ExceptionHandler(DuplicateExternalIdException.class)
	ResponseEntity<Object> handleDuplicate(DuplicateExternalIdException ex, WebRequest request) {
		return respond(HttpStatus.CONFLICT, ProblemSlugs.DUPLICATE_REQUEST, "Solicitação duplicada",
				"O externalId informado já foi utilizado em outra solicitação.", request);
	}

	@ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class,
			TransientDataAccessException.class })
	ResponseEntity<Object> handleDatabaseUnavailable(Exception ex, WebRequest request) {
		log.error("Banco de dados indisponivel", ex);
		return respond(HttpStatus.SERVICE_UNAVAILABLE, ProblemSlugs.SERVICE_UNAVAILABLE, "Serviço indisponível",
				"O serviço está temporariamente indisponível. Tente novamente em instantes.", request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Erro inesperado", ex);
		return respond(HttpStatus.INTERNAL_SERVER_ERROR, ProblemSlugs.INTERNAL_ERROR, "Erro interno",
				"Ocorreu um erro inesperado. Informe o traceId ao suporte.", request);
	}

	private ResponseEntity<Object> validationProblem(List<FieldProblem> errors, WebRequest request) {
		ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, ProblemSlugs.VALIDATION_ERROR,
				"Dados inválidos", "Um ou mais campos não passaram na validação.", instanceOf(request));
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	private ResponseEntity<Object> respond(HttpStatus status, String slug, String title, String detail,
			WebRequest request) {
		return ResponseEntity.status(status).body(problems.create(status, slug, title, detail, instanceOf(request)));
	}

	private static String instanceOf(WebRequest request) {
		return request instanceof ServletWebRequest web ? web.getRequest().getRequestURI() : null;
	}

	private static String messageOf(FieldError error) {
		return error.getDefaultMessage() != null ? error.getDefaultMessage() : "valor inválido";
	}

	public record FieldProblem(String field, String message) {
	}
}
