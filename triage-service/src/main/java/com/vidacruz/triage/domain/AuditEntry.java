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

	/**
	 * Status change from {@code previousStatus} to the state of {@code updated}, at its {@code statusChangedAt}.
	 * {@code updated} must be the persisted row after the change.
	 */
	public static AuditEntry statusChange(PatientStatus previousStatus, Patient updated) {
		return new AuditEntry(updated.id(), previousStatus, updated.status(),
				PatientEventType.forStatusChange(updated.status()), updated.statusChangedAt());
	}

	/**
	 * Escalation (RF-03): the status does not change ({@code REGISTERED → REGISTERED}), the event type identifies it.
	 * {@code escalated} must be the persisted row after the escalation.
	 */
	public static AuditEntry escalation(Patient escalated) {
		requireEscalated(escalated);
		return new AuditEntry(escalated.id(), escalated.status(), escalated.status(),
				PatientEventType.PATIENT_ESCALATED, escalated.escalatedAt());
	}

	static void requireEscalated(Patient patient) {
		if (!patient.escalated()) {
			throw new IllegalArgumentException("Patient " + patient.id() + " is not escalated");
		}
	}

	public static AuditEntry registration(Patient patient) {
		return new AuditEntry(patient.id(), null, patient.status(), PatientEventType.PATIENT_REGISTERED,
				patient.registeredAt());
	}

}
