package com.vidacruz.assistant.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.domain.PatientEvent;
import com.vidacruz.assistant.domain.PatientEventType;
import com.vidacruz.assistant.domain.TriageLevel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** The assistant's own DTO reads exactly the JSON the triage service publishes (assumption 11). */
class PatientEventMessageTest {

	/** Same defaults as the Spring Boot ObjectMapper (JavaTimeModule, unknown properties ignored). */
	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	private static final String PATIENT_ID = "3f1c6a52-7a0e-4f7b-9c55-1b2d3e4f5a6b";
	private static final String EVENT_ID = "9b2e1d4c-0a1b-4c2d-8e3f-5a6b7c8d9e0f";

	@ParameterizedTest
	@CsvSource({
			"paciente_registrado,  CRITICO,      REGISTRADO,  PATIENT_REGISTERED,      CRITICAL",
			"paciente_en_consulta, URGENTE,      EN_CONSULTA, PATIENT_IN_CONSULTATION, URGENT",
			"paciente_alta,        URGENTE_2,    ALTA,        PATIENT_DISCHARGED,      URGENT_2",
			"paciente_escalado,    NO_URGENTE,   REGISTRADO,  PATIENT_ESCALATED,       NON_URGENT",
			"paciente_registrado,  NO_URGENTE_2, REGISTRADO,  PATIENT_REGISTERED,      NON_URGENT_2" })
	void readsTheTriageEventFormat(String eventType, String level, String status, PatientEventType expectedType,
			TriageLevel expectedLevel) {
		String json = """
				{"eventId":"%s","eventType":"%s","occurredAt":"2026-11-13T10:15:30.123456Z",\
				"patientId":"%s","level":"%s","status":"%s"}""".formatted(EVENT_ID, eventType, PATIENT_ID, level,
				status);

		PatientEvent event = PatientEventMessage.parse(objectMapper, json);

		assertThat(event).isEqualTo(new PatientEvent(UUID.fromString(EVENT_ID), expectedType,
				Instant.parse("2026-11-13T10:15:30.123456Z"), UUID.fromString(PATIENT_ID), expectedLevel));
	}

	@Test
	void ignoresUnknownProperties() {
		String json = """
				{"eventId":"%s","eventType":"paciente_registrado","occurredAt":"2026-11-13T10:15:30Z",\
				"patientId":"%s","level":"CRITICO","status":"REGISTRADO","newField":{"x":1}}"""
			.formatted(EVENT_ID, PATIENT_ID);

		assertThat(PatientEventMessage.parse(objectMapper, json).type()).isEqualTo(PatientEventType.PATIENT_REGISTERED);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			not json          | {no json                                                                      | not a valid patient event JSON
			json null         | null                                                                          | JSON null
			blank             | '   '                                                                         | empty payload
			patientId missing | {"eventType":"paciente_registrado","level":"CRITICO"}                          | missing patientId
			bad patientId     | {"eventType":"paciente_registrado","level":"CRITICO","patientId":"abc"}       | not a valid patient event JSON
			unknown type      | {"eventType":"paciente_trasladado","level":"CRITICO","patientId":"%s"}        | unknown eventType: paciente_trasladado
			type missing      | {"level":"CRITICO","patientId":"%s"}                                          | unknown eventType: null
			English type      | {"eventType":"PATIENT_REGISTERED","level":"CRITICO","patientId":"%s"}         | unknown eventType: PATIENT_REGISTERED
			unknown level     | {"eventType":"paciente_registrado","level":"GRAVE","patientId":"%s"}          | unknown level: GRAVE
			English level     | {"eventType":"paciente_registrado","level":"CRITICAL","patientId":"%s"}       | unknown level: CRITICAL
			""")
	void rejectsMessagesItCannotUnderstand(String name, String payload, String reason) {
		String json = payload.replace("%s", PATIENT_ID);

		assertThatThrownBy(() -> PatientEventMessage.parse(objectMapper, json))
			.isInstanceOf(InvalidPatientEventException.class)
			.hasMessageContaining(reason);
	}

	@Test
	void rejectsNullPayload() {
		assertThatThrownBy(() -> PatientEventMessage.parse(objectMapper, null))
			.isInstanceOf(InvalidPatientEventException.class)
			.hasMessage("empty payload");
	}

}
