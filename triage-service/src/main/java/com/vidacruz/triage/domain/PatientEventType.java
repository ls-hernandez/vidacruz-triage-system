package com.vidacruz.triage.domain;

/**
 * Kind of change in a patient's lifecycle. Used both as the audit event type (RN-04) and as the type of the
 * event published to Kafka; the Spanish contract names ({@code paciente_registrado}, ...) are mapped at the boundary.
 */
public enum PatientEventType {

	PATIENT_REGISTERED,
	PATIENT_IN_CONSULTATION,
	PATIENT_DISCHARGED,
	PATIENT_ESCALATED

}
