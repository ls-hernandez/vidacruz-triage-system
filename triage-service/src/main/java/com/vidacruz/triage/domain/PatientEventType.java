package com.vidacruz.triage.domain;

/**
 * Kind of change in a patient's lifecycle. Used both as the audit event type (RN-04) and as the type of the
 * event published to Kafka; the Spanish contract names ({@code paciente_registrado}, ...) are mapped at the boundary.
 */
public enum PatientEventType {

	PATIENT_REGISTERED,
	PATIENT_IN_CONSULTATION,
	PATIENT_DISCHARGED,
	PATIENT_ESCALATED;

	/** Event produced by a status change into {@code newStatus} (RF-02). */
	public static PatientEventType forStatusChange(PatientStatus newStatus) {
		return switch (newStatus) {
			case IN_CONSULTATION -> PATIENT_IN_CONSULTATION;
			case DISCHARGED -> PATIENT_DISCHARGED;
			case REGISTERED -> throw new IllegalArgumentException("No patient can change its status to REGISTERED");
		};
	}

}
