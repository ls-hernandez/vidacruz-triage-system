package com.vidacruz.assistant.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.assistant.TestcontainersConfiguration;
import com.vidacruz.assistant.infrastructure.alerts.SseAlertBroadcaster;
import com.vidacruz.assistant.support.ApiKeyTestConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * RF-06 end to end: JSON events written on {@code triaje.eventos} exactly as the triage service publishes them →
 * listener → SSE client of {@code GET /asistente/alertas/stream}.
 * <p>
 * Same configuration as {@link EmergencyStatusIntegrationTest}, so both share one context (and one topic): only alerts
 * of the patients created here are looked at.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({ TestcontainersConfiguration.class, ApiKeyTestConfiguration.class })
class EscalationAlertsIntegrationTest {

	private static final String PATH = "/asistente/alertas/stream";
	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private WebTestClient client;

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private SseAlertBroadcaster broadcaster;

	@Test
	void escalationEventReachesAConnectedClientAndOtherEventsDoNot() {
		UUID other = UUID.randomUUID();
		UUID escalated = UUID.randomUUID();
		Instant escalatedAt = Instant.now();
		int clientsBefore = broadcaster.connectedClients();

		Flux<ServerSentEvent<JsonNode>> stream = client.mutate()
			.responseTimeout(TIMEOUT)
			.build()
			.get()
			.uri(PATH)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus().isOk()
			.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
			.returnResult(new ParameterizedTypeReference<ServerSentEvent<JsonNode>>() {
			})
			.getResponseBody();

		Flux<ServerSentEvent<JsonNode>> ourAlerts = stream
			.filter(event -> EscalationAlertController.EVENT_NAME.equals(event.event()))
			.filter(event -> Set.of(other.toString(), escalated.toString())
				.contains(event.data().get("pacienteId").asText()));

		StepVerifier.create(ourAlerts)
			.then(() -> {
				// the alert stream keeps no history: the client must be connected before the event is consumed
				await().atMost(TIMEOUT).until(() -> broadcaster.connectedClients() > clientsBefore);
				// one key → one partition: the escalation is consumed after the three events that must not alert
				String key = other.toString();
				send(key, event(other, "paciente_registrado", "URGENTE", "REGISTRADO", Instant.now()));
				send(key, event(other, "paciente_en_consulta", "URGENTE", "EN_CONSULTA", Instant.now()));
				send(key, event(other, "paciente_alta", "URGENTE", "ALTA", Instant.now()));
				send(key, event(escalated, "paciente_escalado", "CRITICO", "REGISTRADO", escalatedAt));
			})
			.assertNext(event -> {
				assertThat(event.id()).isNotBlank();
				JsonNode alert = event.data();
				assertThat(alert.get("pacienteId").asText()).isEqualTo(escalated.toString());
				assertThat(alert.get("nivel").asText()).isEqualTo("CRITICO");
				assertThat(Instant.parse(alert.get("fechaEscalamiento").asText())).isEqualTo(escalatedAt);
				assertThat(alert.get("mensaje").asText()).isEqualTo("Paciente CRITICO superó su SLA sin ser atendido");
			})
			.thenCancel()
			.verify(TIMEOUT);
	}

	@Test
	void streamRequiresTheApiKey() {
		client.mutate()
			.defaultHeaders(headers -> headers.remove(ApiKeyTestConfiguration.HEADER))
			.build()
			.get()
			.uri(PATH)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.exchange()
			.expectStatus().isUnauthorized();
	}

	private void send(String key, String payload) {
		try {
			kafkaTemplate.send(TestcontainersConfiguration.EVENTS_TOPIC, key, payload).get();
		}
		catch (Exception ex) {
			throw new IllegalStateException("Could not publish test event", ex);
		}
	}

	/** Same JSON as the triage service's {@code PatientEventMessage} (assumption 11). */
	private static String event(UUID patientId, String eventType, String level, String status, Instant occurredAt) {
		return """
				{"eventId":"%s","eventType":"%s","occurredAt":"%s","patientId":"%s","level":"%s","status":"%s"}"""
			.formatted(UUID.randomUUID(), eventType, occurredAt, patientId, level, status);
	}

}
