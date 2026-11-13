package com.vidacruz.triage.application;

import java.util.UUID;

public class PatientNotFoundException extends RuntimeException {

	private final UUID patientId;

	public PatientNotFoundException(UUID patientId) {
		super("Patient not found: " + patientId);
		this.patientId = patientId;
	}

	public UUID patientId() {
		return patientId;
	}

}
