package com.vidacruz.assistant.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * RF-06: a patient exceeded its SLA without being seen and the medical team must be told.
 *
 * @param eventId     id of the {@code paciente_escalado} event that caused the alert (may be {@code null}); lets clients
 *                    tell a redelivered event from a new escalation
 * @param patientId   escalated patient
 * @param level       triage level of the patient (escalation does not change it)
 * @param escalatedAt when the patient was escalated
 */
public record EscalationAlert(UUID eventId, UUID patientId, TriageLevel level, Instant escalatedAt) {

	public EscalationAlert {
		Objects.requireNonNull(patientId, "patientId");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(escalatedAt, "escalatedAt");
	}

}
