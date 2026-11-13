package com.vidacruz.triage.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Clinical audit trail entry (RN-04). Append-only.
 *
 * @param previousStatus {@code null} for the registration entry
 */
public record AuditEntry(
		UUID patientId,
		PatientStatus previousStatus,
		PatientStatus newStatus,
		PatientEventType eventType,
		Instant occurredAt) {

	public AuditEntry {
		Objects.requireNonNull(patientId, "patientId must not be null");
		Objects.requireNonNull(newStatus, "newStatus must not be null");
		Objects.requireNonNull(eventType, "eventType must not be null");
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");
	}

	public static AuditEntry registration(Patient patient) {
		return new AuditEntry(patient.id(), null, patient.status(), PatientEventType.PATIENT_REGISTERED,
				patient.registeredAt());
	}

}
