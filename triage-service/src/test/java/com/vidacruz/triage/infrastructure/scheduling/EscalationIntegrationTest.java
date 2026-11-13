package com.vidacruz.triage.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.TestcontainersConfiguration;
import com.vidacruz.triage.application.EscalateOverduePatients;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.TriageLevel;
import com.vidacruz.triage.domain.VitalSigns;
import com.vidacruz.triage.support.PatientEventsConsumer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * RF-03 end to end against PostgreSQL and Kafka. The scheduler is disabled: each test runs a cycle (or a single
 * escalation) explicitly, so results are deterministic. {@link EscalationSchedulerIntegrationTest} covers the
 * background trigger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "triage.escalation.enabled=false")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class EscalationIntegrationTest {

	private static final String PATH = "/triage/pacientes";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final String REGISTRATION = "null->REGISTERED:PATIENT_REGISTERED";
	private static final String ESCALATION = "REGISTERED->REGISTERED:PATIENT_ESCALATED";
	private static final String TO_CONSULTATION = "REGISTERED->IN_CONSULTATION:PATIENT_IN_CONSULTATION";

	@Autowired
	private WebTestClient client;

	@Autowired
	private DatabaseClient db;

	@Autowired
	private KafkaContainer kafka;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EscalateOverduePatients escalateOverduePatients;

	@Autowired
	private PatientRepository patientRepository;

	@Test
	void escalatesAnOverduePatientOnceWithAuditEventAndFlag() {
		UUID id = registerE1();
		makeOverdue(id);

		StepVerifier.create(escalateOverduePatients.escalateOverdue())
			.assertNext(escalated -> assertThat(escalated).isPositive())
			.verifyComplete();

		Map<String, Object> row = row(id);
		assertThat(row).containsEntry("escalated", true).containsEntry("status", "REGISTERED");
		assertThat(row.get("escalated_at")).isNotNull();
		assertThat(row).as("the escalation does not bump the version (assumption 14)").containsEntry("version", 0L);
		Instant escalatedAt = (Instant) row.get("escalated_at");

		assertThat(auditTrail(id)).containsExactly(REGISTRATION, ESCALATION);

		List<JsonNode> events = events(id, 2);
		assertThat(events).extracting(event -> event.get("eventType").asText())
			.containsExactly("paciente_registrado", "paciente_escalado");
		JsonNode escalationEvent = events.get(1);
		assertThat(escalationEvent.get("patientId").asText()).isEqualTo(id.toString());
		assertThat(escalationEvent.get("level").asText()).isEqualTo("CRITICO");
		assertThat(escalationEvent.get("status").asText()).isEqualTo("REGISTRADO");
		assertThat(Instant.parse(escalationEvent.get("occurredAt").asText())).isEqualTo(escalatedAt);
		assertThat(escalationEvent.get("eventId").asText()).isNotBlank();

		JsonNode patient = get(id);
		assertThat(patient.get("escalado").asBoolean()).isTrue();
		assertThat(Instant.parse(patient.get("fechaEscalamiento").asText())).isEqualTo(escalatedAt);
		assertThat(patient.get("estado").asText()).isEqualTo("REGISTRADO");

		// a later cycle does not escalate it again
		StepVerifier.create(escalateOverduePatients.escalateOverdue()).expectNextCount(1).verifyComplete();
		assertThat(auditTrail(id)).containsExactly(REGISTRATION, ESCALATION);
		assertThat(events(id, 2)).hasSize(2);
	}

	@Test
	void doesNotEscalateAPatientWithinItsSla() {
		UUID id = registerE1();

		StepVerifier.create(escalateOverduePatients.escalateOverdue()).expectNextCount(1).verifyComplete();

		assertThat(row(id)).containsEntry("escalated", false);
		assertThat(auditTrail(id)).containsExactly(REGISTRATION);
	}

	@Test
	void twoParallelEscalationsOfTheSamePatientProduceOneAuditAndOneEvent() {
		UUID id = registerE1();
		makeOverdue(id);
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

		Mono<Optional<Patient>> first = attempt(id, now);
		Mono<Optional<Patient>> second = attempt(id, now);

		StepVerifier.create(Mono.zip(first, second))
			.assertNext(results -> assertThat(List.of(results.getT1(), results.getT2()))
				.filteredOn(Optional::isPresent)
				.hasSize(1))
			.verifyComplete();

		assertThat(auditTrail(id)).containsExactly(REGISTRATION, ESCALATION);
		assertThat(events(id, 2)).extracting(event -> event.get("eventType").asText())
			.containsExactly("paciente_registrado", "paciente_escalado");
	}

	@Test
	void anEscalatedPatientCanEnterConsultationAndStaysEscalated() {
		UUID id = registerE1();
		makeOverdue(id);
		StepVerifier.create(escalateOverduePatients.escalateOverdue()).expectNextCount(1).verifyComplete();
		Instant escalatedAt = (Instant) row(id).get("escalated_at");

		JsonNode body = client.patch().uri(PATH + "/{id}/estado", id)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("estado", "EN_CONSULTA"))
			.exchange()
			.expectStatus().isOk()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).isNotNull();
		assertThat(body.get("estado").asText()).isEqualTo("EN_CONSULTA");
		assertThat(body.get("escalado").asBoolean()).isTrue();
		assertThat(Instant.parse(body.get("fechaEscalamiento").asText())).isEqualTo(escalatedAt);
		assertThat(auditTrail(id)).containsExactly(REGISTRATION, ESCALATION, TO_CONSULTATION);
	}

	@Test
	void overdueQueryIsStrictAtTheSlaDeadline() {
		// registered in the future so no other cycle in this context can pick it up
		Instant registeredAt = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
		VitalSigns vitalSigns = new VitalSigns(80, 120, 80, 98, new BigDecimal("36.5"), 15, 2);
		Patient patient = Patient.register(UUID.randomUUID(), "Luis Gómez", 30, vitalSigns, "Dolor de cabeza",
				TriageLevel.CRITICAL, 5, registeredAt);
		patientRepository.insert(patient).block(TIMEOUT);
		Instant deadline = registeredAt.plus(Duration.ofMinutes(5));

		assertThat(overdueIds(deadline)).doesNotContain(patient.id());
		assertThat(overdueIds(deadline.plusNanos(1_000))).contains(patient.id());
	}

	// --- helpers ----------------------------------------------------------------------------------------------

	/** One escalation attempt on its own thread; {@code Optional.empty()} if this attempt did not escalate. */
	private Mono<Optional<Patient>> attempt(UUID id, Instant now) {
		return escalateOverduePatients.escalate(id, now)
			.map(Optional::of)
			.defaultIfEmpty(Optional.empty())
			.subscribeOn(Schedulers.parallel());
	}

	/** Registers example E1 (CRITICO, SLA 5 minutes) and returns its id. */
	private UUID registerE1() {
		Map<String, Object> body = new HashMap<>();
		body.put("nombreCompleto", "Ana Pérez");
		body.put("edad", 45);
		body.put("frecuenciaCardiaca", 130);
		body.put("presionSistolica", 180);
		body.put("presionDiastolica", 110);
		body.put("spo2", 97);
		body.put("temperatura", new BigDecimal("36.8"));
		body.put("dolor", 8);
		body.put("gcs", 15);
		body.put("quejaPrincipal", "Dolor torácico");
		JsonNode created = client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
			.expectStatus().isCreated()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(created).isNotNull();
		assertThat(created.get("slaMinutos").asInt()).isEqualTo(5);
		return UUID.fromString(created.get("id").asText());
	}

	/** Moves the registration 6 minutes back: the 5-minute SLA is exceeded. */
	private void makeOverdue(UUID id) {
		db.sql("UPDATE patients SET registered_at = registered_at - interval '6 minutes' WHERE id = :id")
			.bind("id", id)
			.fetch()
			.rowsUpdated()
			.block(TIMEOUT);
	}

	private Map<String, Object> row(UUID id) {
		return db.sql("SELECT status, escalated, escalated_at, version FROM patients WHERE id = :id")
			.bind("id", id)
			.map(row -> {
				Map<String, Object> values = new HashMap<>();
				values.put("status", row.get("status", String.class));
				values.put("escalated", row.get("escalated", Boolean.class));
				values.put("escalated_at", row.get("escalated_at", Instant.class));
				values.put("version", row.get("version", Long.class));
				return values;
			})
			.one()
			.block(TIMEOUT);
	}

	private List<UUID> overdueIds(Instant now) {
		return patientRepository.findOverdueIds(now).collectList().block(TIMEOUT);
	}

	/** Audit trail of a patient as {@code previous->new:type}, in insertion order. */
	private List<String> auditTrail(UUID id) {
		return db.sql("SELECT previous_status, new_status, event_type FROM audit_entries WHERE patient_id = :id ORDER BY id")
			.bind("id", id)
			.map(row -> row.get("previous_status", String.class) + "->" + row.get("new_status", String.class) + ":"
					+ row.get("event_type", String.class))
			.all()
			.collectList()
			.block(TIMEOUT);
	}

	private JsonNode get(UUID id) {
		JsonNode body = client.get().uri(PATH + "/{id}", id).exchange()
			.expectStatus().isOk()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		return body;
	}

	/** Events published for a patient (key = patient id), waiting for {@code expected} and checking there are no more. */
	private List<JsonNode> events(UUID id, int expected) {
		return new PatientEventsConsumer(kafka.getBootstrapServers(), objectMapper).eventsFor(id, expected);
	}

}
