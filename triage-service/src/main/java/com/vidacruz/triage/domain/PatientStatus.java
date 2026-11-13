package com.vidacruz.triage.domain;

/**
 * Clinical status of a patient (RF-02).
 * Names are stored as-is in the database; the Spanish contract values are mapped at the boundary.
 */
public enum PatientStatus {

	REGISTERED,
	IN_CONSULTATION,
	DISCHARGED

}
