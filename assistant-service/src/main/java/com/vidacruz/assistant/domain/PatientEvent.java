package com.vidacruz.assistant.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A patient event as the assistant understands it. The status of the message is not kept: what the event means for
 * the emergency-room view depends only on its type.
 */
public record PatientEvent(UUID eventId, PatientEventType type, Instant occurredAt, UUID patientId, TriageLevel level) {

	public PatientEvent {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(patientId, "patientId");
		Objects.requireNonNull(level, "level");
	}

}
