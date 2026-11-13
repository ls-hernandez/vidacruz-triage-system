package com.vidacruz.assistant.infrastructure.messaging;

/** A message on {@code triaje.eventos} that cannot be understood; it is logged and skipped. */
class InvalidPatientEventException extends RuntimeException {

	InvalidPatientEventException(String reason) {
		super(reason);
	}

	InvalidPatientEventException(String reason, Throwable cause) {
		super(reason, cause);
	}

}
