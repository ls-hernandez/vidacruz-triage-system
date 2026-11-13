package com.vidacruz.assistant.domain;

/** Patient lifecycle events published by the triage service on {@code triaje.eventos}. */
public enum PatientEventType {

	PATIENT_REGISTERED,
	PATIENT_IN_CONSULTATION,
	PATIENT_DISCHARGED,
	PATIENT_ESCALATED

}
