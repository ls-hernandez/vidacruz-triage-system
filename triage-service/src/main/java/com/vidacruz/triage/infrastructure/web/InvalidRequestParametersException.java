package com.vidacruz.triage.infrastructure.web;

import com.vidacruz.triage.infrastructure.web.ApiExceptionHandler.InvalidField;
import java.util.List;

/** Query parameters that failed validation; answered as a 400 that lists every invalid parameter. */
class InvalidRequestParametersException extends RuntimeException {

	private final transient List<InvalidField> errors;

	InvalidRequestParametersException(List<InvalidField> errors) {
		super("Invalid request parameters: " + errors);
		this.errors = List.copyOf(errors);
	}

	List<InvalidField> errors() {
		return errors;
	}

}
