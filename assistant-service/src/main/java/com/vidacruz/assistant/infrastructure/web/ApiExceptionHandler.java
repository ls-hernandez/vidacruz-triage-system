package com.vidacruz.assistant.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.vidacruz.assistant.application.AssistantUnavailableException;
import com.vidacruz.assistant.application.KnowledgeBaseNotReadyException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Error responses as {@link ProblemDetail} in Spanish. Input errors follow the same 400 format as the triage service
 * ({@code title "Datos inválidos"}, {@code errors: [{campo, mensaje}]}), replicated here without sharing code.
 * Unavailable dependencies (AI provider, corpus not indexed yet, Redis) are 503.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	static final String INVALID_DATA_TITLE = "Datos inválidos";
	static final String ASSISTANT_UNAVAILABLE_TITLE = "Asistente clínico no disponible";
	static final String ASSISTANT_UNAVAILABLE_DETAIL =
			"El asistente clínico no está disponible en este momento; intente más tarde.";

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/** One invalid field of the request body. */
	record InvalidField(String campo, String mensaje) {
	}

	@Override
	protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(WebExchangeBindException ex,
			HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
		List<InvalidField> errors = ex.getFieldErrors()
			.stream()
			.map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
			.sorted(Comparator.comparing(InvalidField::campo).thenComparing(InvalidField::mensaje))
			.toList();
		return badRequest("La solicitud contiene campos inválidos.", errors);
	}

	@Override
	protected Mono<ResponseEntity<Object>> handleServerWebInputException(ServerWebInputException ex,
			HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
		JsonProcessingException jsonError = findCause(ex, JsonProcessingException.class);
		if (jsonError instanceof MismatchedInputException mismatch) {
			String field = fieldName(mismatch);
			if (field != null) {
				return badRequest("La solicitud contiene campos inválidos.",
						List.of(new InvalidField(field, "tiene un tipo de dato inválido")));
			}
		}
		if (jsonError != null) {
			return badRequest("El cuerpo de la solicitud no es un JSON válido.", List.of());
		}
		if (ex.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
			return badRequest("El cuerpo de la solicitud es obligatorio y debe ser un JSON válido.", List.of());
		}
		return super.handleServerWebInputException(ex, headers, status, exchange);
	}

	/** RF-04 A: open circuit, timeout or provider failure. */
	@ExceptionHandler(AssistantUnavailableException.class)
	ProblemDetail handleAssistantUnavailable(AssistantUnavailableException ex) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, ASSISTANT_UNAVAILABLE_TITLE, ASSISTANT_UNAVAILABLE_DETAIL);
	}

	/** RF-04 A: the corpus is still being indexed (or the provider was down at startup). */
	@ExceptionHandler(KnowledgeBaseNotReadyException.class)
	ProblemDetail handleKnowledgeBaseNotReady(KnowledgeBaseNotReadyException ex) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, ASSISTANT_UNAVAILABLE_TITLE,
				ASSISTANT_UNAVAILABLE_DETAIL + " Los protocolos clínicos aún se están cargando.");
	}

	/** RF-04 B: Redis down or unreachable (e.g. {@code RedisConnectionFailureException}). */
	@ExceptionHandler(DataAccessResourceFailureException.class)
	ProblemDetail handleViewUnavailable(DataAccessResourceFailureException ex) {
		log.error("Emergency status view unavailable", ex);
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "Estado de urgencias no disponible",
				"No se pudo leer el estado de urgencias; intenta de nuevo en unos segundos.");
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}

	private static Mono<ResponseEntity<Object>> badRequest(String detail, List<InvalidField> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
		problem.setTitle(INVALID_DATA_TITLE);
		problem.setProperty("errors", errors);
		return Mono.just(ResponseEntity.badRequest().body(problem));
	}

	private static String fieldName(JsonMappingException ex) {
		List<String> names = ex.getPath()
			.stream()
			.map(JsonMappingException.Reference::getFieldName)
			.filter(Objects::nonNull)
			.toList();
		return names.isEmpty() ? null : String.join(".", names);
	}

	private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				return type.cast(cause);
			}
		}
		return null;
	}

}
