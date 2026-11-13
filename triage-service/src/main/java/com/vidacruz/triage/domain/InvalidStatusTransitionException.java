package com.vidacruz.triage.domain;

/**
 * A status change not allowed by RF-02. Carries the internal values; the Spanish message for the API is built at the
 * boundary.
 */
public class InvalidStatusTransitionException extends RuntimeException {

	private final PatientStatus from;
	private final PatientStatus to;

	public InvalidStatusTransitionException(PatientStatus from, PatientStatus to) {
		super("Invalid status transition: " + from + " -> " + to);
		this.from = from;
		this.to = to;
	}

	public PatientStatus from() {
		return from;
	}

	public PatientStatus to() {
		return to;
	}

}
