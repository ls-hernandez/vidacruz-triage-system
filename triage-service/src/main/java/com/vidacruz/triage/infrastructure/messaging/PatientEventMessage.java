package com.vidacruz.triage.infrastructure.messaging;

import com.vidacruz.triage.domain.PatientEvent;
import com.vidacruz.triage.infrastructure.contract.ContractMapper;
import java.time.Instant;
import java.util.UUID;

/**
 * JSON payload published to {@code triaje.eventos}. Keys are in English; values follow the public contract
 * in Spanish ({@code paciente_registrado}, {@code CRITICO}, {@code REGISTRADO}).
 */
record PatientEventMessage(
		UUID eventId,
		String eventType,
		Instant occurredAt,
		UUID patientId,
		String level,
		String status) {

	static PatientEventMessage from(PatientEvent event) {
		return new PatientEventMessage(
				event.eventId(),
				ContractMapper.eventType(event.type()),
				event.occurredAt(),
				event.patientId(),
				ContractMapper.level(event.level()),
				ContractMapper.status(event.status()));
	}

}
