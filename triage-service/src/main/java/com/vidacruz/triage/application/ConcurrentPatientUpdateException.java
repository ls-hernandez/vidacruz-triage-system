package com.vidacruz.triage.application;

import java.util.UUID;

/** The patient changed between reading it and updating it; the client should read it again and retry. */
public class ConcurrentPatientUpdateException extends RuntimeException {

	private final UUID patientId;

	public ConcurrentPatientUpdateException(UUID patientId) {
		super("Patient " + patientId + " was modified concurrently");
		this.patientId = patientId;
	}

	public UUID patientId() {
		return patientId;
	}

}
