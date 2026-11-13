package com.vidacruz.assistant.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.domain.PatientEvent;
import com.vidacruz.assistant.domain.PatientEventType;
import com.vidacruz.assistant.domain.TriageLevel;
import com.vidacruz.assistant.infrastructure.contract.ContractMapper;
import java.time.Instant;
import java.util.UUID;

/**
 * JSON payload of {@code triaje.eventos}, as published by the triage service (assumption 11): keys in English,
 * values in the Spanish contract. The assistant defines its own DTO instead of sharing the publisher's class
 * (assumption 23): the JSON is the only contract. Unknown properties are ignored, so new fields do not break the
 * consumer; {@code status} is informative only, the event type decides the effect.
 */
record PatientEventMessage(
		UUID eventId,
		String eventType,
		Instant occurredAt,
		UUID patientId,
		String level,
		String status) {

	/** @throws InvalidPatientEventException with the reason, if the payload cannot be turned into an event */
	static PatientEvent parse(ObjectMapper objectMapper, String payload) {
		if (payload == null || payload.isBlank()) {
			throw new InvalidPatientEventException("empty payload");
		}
		PatientEventMessage message;
		try {
			message = objectMapper.readValue(payload, PatientEventMessage.class);
		}
		catch (JsonProcessingException ex) {
			throw new InvalidPatientEventException("payload is not a valid patient event JSON", ex);
		}
		if (message == null) {
			throw new InvalidPatientEventException("payload is JSON null");
		}
		return message.toDomain();
	}

	PatientEvent toDomain() {
		if (patientId == null) {
			throw new InvalidPatientEventException("missing patientId");
		}
		PatientEventType type = ContractMapper.parseEventType(eventType)
			.orElseThrow(() -> new InvalidPatientEventException("unknown eventType: " + eventType));
		TriageLevel triageLevel = ContractMapper.parseLevel(level)
			.orElseThrow(() -> new InvalidPatientEventException("unknown level: " + level));
		return new PatientEvent(eventId, type, occurredAt, patientId, triageLevel);
	}

}
