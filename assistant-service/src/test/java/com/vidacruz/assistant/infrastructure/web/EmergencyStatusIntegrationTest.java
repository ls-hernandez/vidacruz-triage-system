package com.vidacruz.assistant.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.assistant.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;

/**
 * RF-04 B end to end: JSON events written on {@code triaje.eventos} exactly as the triage service publishes them →
 * listener → Redis → {@code GET /asistente/estado-urgencias}.
 * <p>
 * Redis and the topic are shared with the other test classes, so each test compares against a baseline taken at its
 * start and uses fresh patient ids. "All events processed" is detected through the consumer group's committed
 * offsets: with async acks they only move past a record once its {@code Mono} completed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class EmergencyStatusIntegrationTest {

	private static final String PATH = "/asistente/estado-urgencias";
	private static final List<String> LEVELS = List.of("CRITICO", "URGENTE", "URGENTE_2", "NO_URGENTE", "NO_URGENTE_2");

	@Autowired
	private WebTestClient client;

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private KafkaContainer kafka;

	@Value("${spring.kafka.consumer.group-id}")
	private String groupId;

	private AdminClient admin;
	private final Set<Integer> partitionsWritten = new HashSet<>();
	private JsonNode baseline;

	@BeforeEach
	void setUp() {
		admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
		baseline = status();
	}

	@AfterEach
	void tearDown() {
		admin.close();
	}

	@Test
	void countsActivePatientsPerLevelIncludingInConsultationAndEscalated() {
		for (String level : LEVELS) {
			send(UUID.randomUUID(), "paciente_registrado", level, "REGISTRADO");
		}
		UUID inConsultation = UUID.randomUUID();
		send(inConsultation, "paciente_registrado", "CRITICO", "REGISTRADO");
		send(inConsultation, "paciente_en_consulta", "CRITICO", "EN_CONSULTA");
		UUID escalated = UUID.randomUUID();
		send(escalated, "paciente_registrado", "URGENTE", "REGISTRADO");
		send(escalated, "paciente_escalado", "URGENTE", "REGISTRADO");

		awaitAllConsumed();

		JsonNode status = status();
		assertThat(levelNames(status)).containsExactlyElementsOf(LEVELS);
		assertThat(deltas(status)).containsExactlyEntriesOf(deltas(2, 2, 1, 1, 1));
		assertThat(status.get("totalActivos").asLong()).isEqualTo(baseline.get("totalActivos").asLong() + 7);
	}

	@Test
	void invalidMessagesAreSkippedWithoutStoppingThePartition() {
		UUID patient = UUID.randomUUID();
		String key = patient.toString();
		send(patient, "paciente_registrado", "URGENTE_2", "REGISTRADO");
		sendRaw(key, "{no json");
		sendRaw(key, event(patient, "paciente_trasladado", "URGENTE_2", "REGISTRADO"));
		sendRaw(key, event(patient, "paciente_registrado", "GRAVE", "REGISTRADO"));
		// same key → same partition, after the invalid ones: must still be applied
		UUID next = UUID.randomUUID();
		sendRaw(key, event(next, "paciente_registrado", "NO_URGENTE", "REGISTRADO"));

		awaitAllConsumed();

		assertThat(deltas(status())).containsExactlyEntriesOf(deltas(0, 0, 1, 1, 0));
	}

	@Test
	void duplicatedEventsDoNotChangeTheCounts() {
		UUID patient = UUID.randomUUID();
		String registered = event(patient, "paciente_registrado", "CRITICO", "REGISTRADO");
		String escalated = event(patient, "paciente_escalado", "CRITICO", "REGISTRADO");
		sendRaw(patient.toString(), registered);
		sendRaw(patient.toString(), registered);
		sendRaw(patient.toString(), escalated);
		sendRaw(patient.toString(), escalated);

		awaitAllConsumed();

		assertThat(deltas(status())).containsExactlyEntriesOf(deltas(1, 0, 0, 0, 0));
	}

	@Test
	void dischargeRemovesThePatient() {
		UUID discharged = UUID.randomUUID();
		UUID stays = UUID.randomUUID();
		send(discharged, "paciente_registrado", "URGENTE", "REGISTRADO");
		send(stays, "paciente_registrado", "URGENTE", "REGISTRADO");
		send(discharged, "paciente_en_consulta", "URGENTE", "EN_CONSULTA");
		send(discharged, "paciente_alta", "URGENTE", "ALTA");
		send(discharged, "paciente_alta", "URGENTE", "ALTA"); // duplicate discharge

		awaitAllConsumed();

		assertThat(deltas(status())).containsExactlyEntriesOf(deltas(0, 1, 0, 0, 0));
	}

	@Test
	void eventsAfterDischargeDoNotReactivateThePatient() {
		UUID patient = UUID.randomUUID();
		send(patient, "paciente_alta", "NO_URGENTE_2", "ALTA");
		send(patient, "paciente_registrado", "NO_URGENTE_2", "REGISTRADO");
		send(patient, "paciente_escalado", "NO_URGENTE_2", "REGISTRADO");

		awaitAllConsumed();

		assertThat(deltas(status())).containsExactlyEntriesOf(deltas(0, 0, 0, 0, 0));
	}

	// --- helpers ----------------------------------------------------------------------------------------------

	/** Publishes an event keyed by patient id, as the triage service does. */
	private void send(UUID patientId, String eventType, String level, String status) {
		sendRaw(patientId.toString(), event(patientId, eventType, level, status));
	}

	private void sendRaw(String key, String payload) {
		try {
			int partition = kafkaTemplate.send(TestcontainersConfiguration.EVENTS_TOPIC, key, payload)
				.get()
				.getRecordMetadata()
				.partition();
			partitionsWritten.add(partition);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Could not publish test event", ex);
		}
	}

	/** Same JSON as the triage service's {@code PatientEventMessage} (assumption 11). */
	private static String event(UUID patientId, String eventType, String level, String status) {
		return """
				{"eventId":"%s","eventType":"%s","occurredAt":"%s","patientId":"%s","level":"%s","status":"%s"}"""
			.formatted(UUID.randomUUID(), eventType, Instant.now(), patientId, level, status);
	}

	/** Waits until the group committed every record of the partitions this test wrote to. */
	private void awaitAllConsumed() {
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
			Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
			partitionsWritten.forEach(
					p -> latest.put(new TopicPartition(TestcontainersConfiguration.EVENTS_TOPIC, p), OffsetSpec.latest()));
			Map<TopicPartition, ListOffsetsResultInfo> ends = admin.listOffsets(latest).all().get();
			Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
				.partitionsToOffsetAndMetadata()
				.get();
			ends.forEach((partition, end) -> {
				OffsetAndMetadata offset = committed.get(partition);
				assertThat(offset).as("committed offset of %s", partition).isNotNull();
				assertThat(offset.offset()).as("committed offset of %s", partition).isEqualTo(end.offset());
			});
		});
	}

	private JsonNode status() {
		JsonNode body = client.get().uri(PATH).exchange()
			.expectStatus().isOk()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		return body;
	}

	private static List<String> levelNames(JsonNode status) {
		List<String> names = new java.util.ArrayList<>();
		status.get("porNivel").fieldNames().forEachRemaining(names::add);
		return names;
	}

	private Map<String, Long> deltas(JsonNode status) {
		Map<String, Long> deltas = new LinkedHashMap<>();
		for (String level : LEVELS) {
			deltas.put(level, status.get("porNivel").get(level).asLong() - baseline.get("porNivel").get(level).asLong());
		}
		return deltas;
	}

	private static Map<String, Long> deltas(long critical, long urgent, long urgent2, long nonUrgent,
			long nonUrgent2) {
		Map<String, Long> deltas = new LinkedHashMap<>();
		deltas.put("CRITICO", critical);
		deltas.put("URGENTE", urgent);
		deltas.put("URGENTE_2", urgent2);
		deltas.put("NO_URGENTE", nonUrgent);
		deltas.put("NO_URGENTE_2", nonUrgent2);
		return deltas;
	}

}
