package com.vidacruz.triage.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event published after a patient change has been committed.
 */
public record PatientEvent(
		UUID eventId,
		PatientEventType type,
		Instant occurredAt,
		UUID patientId,
		TriageLevel level,
		PatientStatus status) {

	public PatientEvent {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");
		Objects.requireNonNull(patientId, "patientId must not be null");
		Objects.requireNonNull(level, "level must not be null");
		Objects.requireNonNull(status, "status must not be null");
	}

	/** Status change event built from the persisted row after the change ({@code paciente_en_consulta}/{@code paciente_alta}). */
	public static PatientEvent statusChanged(UUID eventId, Patient updated) {
		return new PatientEvent(eventId, PatientEventType.forStatusChange(updated.status()), updated.statusChangedAt(),
				updated.id(), updated.level(), updated.status());
	}

	public static PatientEvent registered(UUID eventId, Patient patient) {
		return new PatientEvent(eventId, PatientEventType.PATIENT_REGISTERED, patient.registeredAt(), patient.id(),
				patient.level(), patient.status());
	}

}
