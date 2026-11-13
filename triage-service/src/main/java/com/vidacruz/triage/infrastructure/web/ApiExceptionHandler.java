package com.vidacruz.triage.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.vidacruz.triage.application.ConcurrentPatientUpdateException;
import com.vidacruz.triage.application.PatientNotFoundException;
import com.vidacruz.triage.domain.InvalidStatusTransitionException;
import com.vidacruz.triage.infrastructure.contract.ContractMapper;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Turns input errors into 400 {@link ProblemDetail} responses in Spanish. Every invalid field is listed in
 * {@code errors} as {@code {campo, mensaje}}; a body that is not valid JSON is a 400, never a 500.
 * Invalid query parameters (RF-05) use the same format. Also maps the application errors: unknown patient (404),
 * invalid transition and concurrent modification (409).
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
		if (findCause(ex, TypeMismatchException.class) != null && ex.getMethodParameter() != null) {
			// path variable that cannot be converted, e.g. an id that is not a UUID (assumption 13)
			return badRequest("La solicitud contiene campos inválidos.",
					List.of(new InvalidField(pathVariableName(ex.getMethodParameter()),
							expectedType(ex.getMethodParameter().getParameterType()))));
		}
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

	@ExceptionHandler(InvalidRequestParametersException.class)
	Mono<ResponseEntity<Object>> handleInvalidRequestParameters(InvalidRequestParametersException ex) {
		List<InvalidField> errors = ex.errors()
			.stream()
			.sorted(Comparator.comparing(InvalidField::campo).thenComparing(InvalidField::mensaje))
			.toList();
		return badRequest("La solicitud contiene parámetros inválidos.", errors);
	}

	@ExceptionHandler(PatientNotFoundException.class)
	ProblemDetail handlePatientNotFound(PatientNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Paciente no encontrado",
				"No existe un paciente con id " + ex.patientId() + ".");
	}

	@ExceptionHandler(InvalidStatusTransitionException.class)
	ProblemDetail handleInvalidStatusTransition(InvalidStatusTransitionException ex) {
		String from = ContractMapper.status(ex.from());
		String to = ContractMapper.status(ex.to());
		String detail = ex.from() == ex.to()
				? "No se puede pasar de " + from + " a " + to + ": el paciente ya está en ese estado."
				: "No se puede pasar de " + from + " a " + to + ".";
		return problem(HttpStatus.CONFLICT, "Transición de estado no permitida", detail);
	}

	@ExceptionHandler(ConcurrentPatientUpdateException.class)
	ProblemDetail handleConcurrentUpdate(ConcurrentPatientUpdateException ex) {
		return problem(HttpStatus.CONFLICT, "Modificación concurrente",
				"El paciente fue modificado por otra solicitud; consúltalo de nuevo y reintenta.");
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}

	private static String pathVariableName(MethodParameter parameter) {
		PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
		if (pathVariable != null && !pathVariable.value().isEmpty()) {
			return pathVariable.value();
		}
		return parameter.getParameterName();
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
		if (targetType == UUID.class) {
			return "debe ser un UUID válido";
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
