package com.vidacruz.triage.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Turns input errors into 400 {@link ProblemDetail} responses in Spanish. Every invalid field is listed in
 * {@code errors} as {@code {campo, mensaje}}; a body that is not valid JSON is a 400, never a 500.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	static final String INVALID_DATA_TITLE = "Datos inválidos";

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
						List.of(new InvalidField(field, expectedType(mismatch.getTargetType()))));
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

	private static Mono<ResponseEntity<Object>> badRequest(String detail, List<InvalidField> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
		problem.setTitle(INVALID_DATA_TITLE);
		problem.setProperty("errors", errors);
		return Mono.just(ResponseEntity.badRequest().body(problem));
	}

	/** Dotted path of the offending JSON property, or {@code null} if the error is not tied to a property. */
	private static String fieldName(JsonMappingException ex) {
		List<String> names = ex.getPath().stream().map(JsonMappingException.Reference::getFieldName)
			.filter(Objects::nonNull)
			.toList();
		return names.isEmpty() ? null : String.join(".", names);
	}

	private static String expectedType(Class<?> targetType) {
		if (targetType == null) {
			return "tiene un tipo de dato inválido";
		}
		if (targetType == Integer.class || targetType == int.class) {
			return "debe ser un número entero";
		}
		if (targetType == BigDecimal.class) {
			return "debe ser un número";
		}
		if (targetType == String.class) {
			return "debe ser un texto";
		}
		return "tiene un tipo de dato inválido";
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
