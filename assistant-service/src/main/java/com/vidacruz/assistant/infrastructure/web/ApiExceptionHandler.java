package com.vidacruz.assistant.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps infrastructure failures to {@link ProblemDetail} responses in Spanish. */
@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/** Redis down or unreachable (e.g. {@code RedisConnectionFailureException}): the view cannot be read. */
	@ExceptionHandler(DataAccessResourceFailureException.class)
	ProblemDetail handleViewUnavailable(DataAccessResourceFailureException ex) {
		log.error("Emergency status view unavailable", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"No se pudo leer el estado de urgencias; intenta de nuevo en unos segundos.");
		problem.setTitle("Estado de urgencias no disponible");
		return problem;
	}

}
