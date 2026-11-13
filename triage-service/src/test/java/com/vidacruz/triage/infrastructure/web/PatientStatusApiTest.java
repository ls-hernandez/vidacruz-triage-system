package com.vidacruz.triage.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.TestcontainersConfiguration;
import com.vidacruz.triage.application.ChangePatientStatus;
import com.vidacruz.triage.application.ConcurrentPatientUpdateException;
import com.vidacruz.triage.application.port.AuditRepository;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.application.port.PatientRepository;
import com.vidacruz.triage.application.port.TransactionRunner;
import com.vidacruz.triage.domain.Patient;
import com.vidacruz.triage.domain.PatientStatus;
import com.vidacruz.triage.support.PatientEventsConsumer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * RF-02 end to end: status changes (PATCH) and patient details (GET) over HTTP → PostgreSQL → Kafka.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "triage.escalation.enabled=false")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PatientStatusApiTest {

	private static final String PATH = "/triage/pacientes";
	private static final String REGISTRATION = "null->REGISTERED:PATIENT_REGISTERED";
	private static final String TO_CONSULTATION = "REGISTERED->IN_CONSULTATION:PATIENT_IN_CONSULTATION";
	private static final String TO_DISCHARGE_FROM_REGISTERED = "REGISTERED->DISCHARGED:PATIENT_DISCHARGED";
	private static final String TO_DISCHARGE_FROM_CONSULTATION = "IN_CONSULTATION->DISCHARGED:PATIENT_DISCHARGED";

	@Autowired
	private WebTestClient client;

	@Autowired
	private DatabaseClient db;

	@Autowired
	private KafkaContainer kafka;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PatientRepository patientRepository;

	@Autowired
	private AuditRepository auditRepository;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private PatientEventPublisher eventPublisher;

	// --- helpers ----------------------------------------------------------------------------------------------

	/** Registers example E1 (CRITICO) and returns its id. */
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
		return UUID.fromString(created.get("id").asText());
	}

	private WebTestClient.ResponseSpec patch(Object id, Object body) {
		return client.patch().uri(PATH + "/{id}/estado", id)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange();
	}

	private JsonNode changeStatus(UUID id, String estado) {
		JsonNode body = patch(id, Map.of("estado", estado)).expectStatus().isOk()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		return body;
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

	/** Audit trail of a patient as {@code previous->new:type}, in insertion order. */
	private List<String> auditTrail(UUID id) {
		return db.sql("SELECT previous_status, new_status, event_type FROM audit_entries WHERE patient_id = :id ORDER BY id")
			.bind("id", id)
			.map(row -> row.get("previous_status", String.class) + "->" + row.get("new_status", String.class) + ":"
					+ row.get("event_type", String.class))
			.all()
			.collectList()
			.block(Duration.ofSeconds(10));
	}

	/** Event types published for a patient, waiting for {@code expected} of them and checking there are no more. */
	private List<JsonNode> events(UUID id, int expected) {
		return new PatientEventsConsumer(kafka.getBootstrapServers(), objectMapper).eventsFor(id, expected);
	}

	private static List<String> types(List<JsonNode> events) {
		return events.stream().map(event -> event.get("eventType").asText()).toList();
	}

	private Instant escalate(UUID id) {
		Instant escalatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		db.sql("UPDATE patients SET escalated = true, escalated_at = :at WHERE id = :id")
			.bind("at", escalatedAt)
			.bind("id", id)
			.fetch()
			.rowsUpdated()
			.block(Duration.ofSeconds(10));
		return escalatedAt;
	}

	/**
	 * Use case wired with the real database and Kafka, but whose {@code findById} always returns {@code snapshot}:
	 * reproduces deterministically a request that read the patient before another change committed.
	 */
	private ChangePatientStatus withStaleRead(Patient snapshot) {
		PatientRepository staleReads = new PatientRepository() {
			@Override
			public Mono<Void> insert(Patient patient) {
				return patientRepository.insert(patient);
			}

			@Override
			public Mono<Patient> findById(UUID id) {
				return Mono.just(snapshot);
			}

			@Override
			public Mono<Patient> updateStatus(UUID id, PatientStatus expectedStatus, long expectedVersion,
					PatientStatus newStatus, Instant changedAt) {
				return patientRepository.updateStatus(id, expectedStatus, expectedVersion, newStatus, changedAt);
			}

			@Override
			public Flux<UUID> findOverdueIds(Instant now) {
				return patientRepository.findOverdueIds(now);
			}

			@Override
			public Mono<Patient> markEscalated(UUID id, Instant escalatedAt) {
				return patientRepository.markEscalated(id, escalatedAt);
			}
		};
		return new ChangePatientStatus(staleReads, auditRepository, transactions, eventPublisher, Clock.systemUTC());
	}

	// --- valid transitions ------------------------------------------------------------------------------------

	@Test
	void registeredToInConsultation() {
		UUID id = registerE1();

		JsonNode body = changeStatus(id, "EN_CONSULTA");

		assertThat(body.get("id").asText()).isEqualTo(id.toString());
		assertThat(body.get("estado").asText()).isEqualTo("EN_CONSULTA");
		assertThat(body.get("nivel").asText()).isEqualTo("CRITICO");
		assertThat(body.get("tiempoEnEstadoActual").asText()).isEqualTo("PT0S");
		assertThat(auditTrail(id)).containsExactly(REGISTRATION, TO_CONSULTATION);

		List<JsonNode> events = events(id, 2);
		assertThat(types(events)).containsExactly("paciente_registrado", "paciente_en_consulta");
		JsonNode event = events.get(1);
		assertThat(event.get("patientId").asText()).isEqualTo(id.toString());
		assertThat(event.get("level").asText()).isEqualTo("CRITICO");
		assertThat(event.get("status").asText()).isEqualTo("EN_CONSULTA");
		assertThat(Instant.parse(event.get("occurredAt").asText()))
			.isEqualTo(Instant.parse(body.get("fechaUltimoCambioEstado").asText()));
	}

	@Test
	void registeredToDischargedDirectly() {
		UUID id = registerE1();

		JsonNode body = changeStatus(id, "ALTA");

		assertThat(body.get("estado").asText()).isEqualTo("ALTA");
		assertThat(auditTrail(id)).containsExactly(REGISTRATION, TO_DISCHARGE_FROM_REGISTERED);
		List<JsonNode> events = events(id, 2);
		assertThat(types(events)).containsExactly("paciente_registrado", "paciente_alta");
		assertThat(events.get(1).get("level").asText()).isEqualTo("CRITICO");
		assertThat(events.get(1).get("status").asText()).isEqualTo("ALTA");
	}

	@Test
	void inConsultationToDischarged() {
		UUID id = registerE1();
		changeStatus(id, "EN_CONSULTA");

		JsonNode body = changeStatus(id, "ALTA");

		assertThat(body.get("estado").asText()).isEqualTo("ALTA");
		assertThat(auditTrail(id)).containsExactly(REGISTRATION, TO_CONSULTATION, TO_DISCHARGE_FROM_CONSULTATION);
		assertThat(types(events(id, 3)))
			.containsExactly("paciente_registrado", "paciente_en_consulta", "paciente_alta");
	}

	@Test
	void escalatedPatientCanChangeStatusAndKeepsTheFlag() {
		UUID id = registerE1();
		Instant escalatedAt = escalate(id);

		JsonNode body = changeStatus(id, "EN_CONSULTA");

		assertThat(body.get("estado").asText()).isEqualTo("EN_CONSULTA");
		assertThat(body.get("escalado").asBoolean()).isTrue();
		assertThat(Instant.parse(body.get("fechaEscalamiento").asText())).isEqualTo(escalatedAt);
	}

	// --- invalid transitions ----------------------------------------------------------------------------------

	static Stream<Arguments> invalidTransitions() {
		return Stream.of(
				Arguments.of(List.of(), "REGISTRADO",
						"No se puede pasar de REGISTRADO a REGISTRADO: el paciente ya está en ese estado."),
				Arguments.of(List.of("EN_CONSULTA"), "EN_CONSULTA",
						"No se puede pasar de EN_CONSULTA a EN_CONSULTA: el paciente ya está en ese estado."),
				Arguments.of(List.of("EN_CONSULTA"), "REGISTRADO", "No se puede pasar de EN_CONSULTA a REGISTRADO."),
				Arguments.of(List.of("ALTA"), "EN_CONSULTA", "No se puede pasar de ALTA a EN_CONSULTA."),
				Arguments.of(List.of("ALTA"), "REGISTRADO", "No se puede pasar de ALTA a REGISTRADO."),
				Arguments.of(List.of("ALTA"), "ALTA",
						"No se puede pasar de ALTA a ALTA: el paciente ya está en ese estado."));
	}

	@ParameterizedTest(name = "after {0}: {1} -> 409")
	@MethodSource("invalidTransitions")
	void rejectsInvalidTransitionWithoutAuditOrEvent(List<String> previousChanges, String target, String detail) {
		UUID id = registerE1();
		previousChanges.forEach(estado -> changeStatus(id, estado));
		List<String> auditBefore = auditTrail(id);

		patch(id, Map.of("estado", target)).expectStatus().isEqualTo(409)
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.status").isEqualTo(409)
			.jsonPath("$.title").isEqualTo("Transición de estado no permitida")
			.jsonPath("$.detail").isEqualTo(detail);

		assertThat(auditTrail(id)).isEqualTo(auditBefore);
		assertThat(events(id, 1 + previousChanges.size())).hasSize(1 + previousChanges.size());
	}

	// --- 404 / 400 --------------------------------------------------------------------------------------------

	@Test
	void unknownPatientIsNotFound() {
		UUID unknown = UUID.randomUUID();

		client.get().uri(PATH + "/{id}", unknown).exchange()
			.expectStatus().isNotFound()
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.title").isEqualTo("Paciente no encontrado")
			.jsonPath("$.detail").isEqualTo("No existe un paciente con id " + unknown + ".");

		patch(unknown, Map.of("estado", "ALTA")).expectStatus().isNotFound()
			.expectBody()
			.jsonPath("$.title").isEqualTo("Paciente no encontrado");
	}

	@ParameterizedTest
	@ValueSource(strings = { "INVALIDO", "alta", "DISCHARGED", "" })
	void rejectsUnknownStatus(String estado) {
		UUID id = registerE1();

		patch(id, Map.of("estado", estado)).expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.title").isEqualTo("Datos inválidos")
			.jsonPath("$.errors.length()").isEqualTo(1)
			.jsonPath("$.errors[0].campo").isEqualTo("estado")
			.jsonPath("$.errors[0].mensaje").isEqualTo("debe ser uno de: REGISTRADO, EN_CONSULTA, ALTA");
	}

	@Test
	void rejectsMissingStatus() {
		UUID id = registerE1();

		patch(id, Map.of()).expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.errors[0].campo").isEqualTo("estado")
			.jsonPath("$.errors[0].mensaje").isEqualTo("es obligatorio");
	}

	@Test
	void rejectsMissingBody() {
		UUID id = registerE1();

		client.patch().uri(PATH + "/{id}/estado", id).contentType(MediaType.APPLICATION_JSON).exchange()
			.expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.title").isEqualTo("Datos inválidos");
	}

	@Test
	void rejectsIdThatIsNotAUuid() {
		client.get().uri(PATH + "/{id}", "abc").exchange()
			.expectStatus().isBadRequest()
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.title").isEqualTo("Datos inválidos")
			.jsonPath("$.errors[0].campo").isEqualTo("id")
			.jsonPath("$.errors[0].mensaje").isEqualTo("debe ser un UUID válido");

		patch("abc", Map.of("estado", "ALTA")).expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.errors[0].campo").isEqualTo("id")
			.jsonPath("$.errors[0].mensaje").isEqualTo("debe ser un UUID válido");
	}

	// --- GET --------------------------------------------------------------------------------------------------

	@Test
	void returnsAllPatientFields() {
		UUID id = registerE1();

		JsonNode body = get(id);

		assertThat(body.get("id").asText()).isEqualTo(id.toString());
		assertThat(body.get("nombreCompleto").asText()).isEqualTo("Ana Pérez");
		assertThat(body.get("edad").asInt()).isEqualTo(45);
		assertThat(body.get("frecuenciaCardiaca").asInt()).isEqualTo(130);
		assertThat(body.get("presionSistolica").asInt()).isEqualTo(180);
		assertThat(body.get("presionDiastolica").asInt()).isEqualTo(110);
		assertThat(body.get("spo2").asInt()).isEqualTo(97);
		assertThat(body.get("temperatura").decimalValue()).isEqualByComparingTo("36.8");
		assertThat(body.get("dolor").asInt()).isEqualTo(8);
		assertThat(body.get("gcs").asInt()).isEqualTo(15);
		assertThat(body.get("quejaPrincipal").asText()).isEqualTo("Dolor torácico");
		assertThat(body.get("nivel").asText()).isEqualTo("CRITICO");
		assertThat(body.get("slaMinutos").asInt()).isEqualTo(5);
		assertThat(body.get("estado").asText()).isEqualTo("REGISTRADO");
		assertThat(body.get("fechaRegistro").asText()).endsWith("Z");
		assertThat(body.get("fechaUltimoCambioEstado").asText()).isEqualTo(body.get("fechaRegistro").asText());
		assertThat(body.get("escalado").asBoolean()).isFalse();
		assertThat(body.has("fechaEscalamiento")).isTrue();
		assertThat(body.get("fechaEscalamiento").isNull()).isTrue();

		String timeInStatus = body.get("tiempoEnEstadoActual").asText();
		assertThat(timeInStatus).matches("PT(\\d+H)?(\\d+M)?\\d+S");
		assertThat(Duration.parse(timeInStatus).isNegative()).isFalse();
	}

	// --- concurrency ------------------------------------------------------------------------------------------

	@Test
	void concurrentRequestsToTheSameStatusSucceedOnlyOnce() throws Exception {
		UUID id = registerE1();
		int requests = 10;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		List<Future<Integer>> results = new ArrayList<>();
		try {
			for (int i = 0; i < requests; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return patch(id, Map.of("estado", "EN_CONSULTA")).expectBody().returnResult().getStatus().value();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}

			assertThat(statuses).containsOnly(200, 409);
			assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(auditTrail(id)).containsExactly(REGISTRATION, TO_CONSULTATION);
		assertThat(types(events(id, 2))).containsExactly("paciente_registrado", "paciente_en_consulta");
	}

	@Test
	void staleReadIsRejectedAsConcurrentModification() {
		UUID id = registerE1();
		Patient snapshot = patientRepository.findById(id).block(Duration.ofSeconds(10));
		ChangePatientStatus staleUseCase = withStaleRead(snapshot);

		// both requests read REGISTERED v0; the first one wins
		StepVerifier.create(staleUseCase.change(id, PatientStatus.IN_CONSULTATION))
			.assertNext(details -> assertThat(details.patient().status()).isEqualTo(PatientStatus.IN_CONSULTATION))
			.verifyComplete();
		StepVerifier.create(staleUseCase.change(id, PatientStatus.DISCHARGED))
			.expectError(ConcurrentPatientUpdateException.class)
			.verify();

		assertThat(get(id).get("estado").asText()).isEqualTo("EN_CONSULTA");
		assertThat(auditTrail(id)).containsExactly(REGISTRATION, TO_CONSULTATION);
		assertThat(types(events(id, 2))).containsExactly("paciente_registrado", "paciente_en_consulta");
	}

	@Test
	void concurrentEscalationIsNotLostByAStatusChange() {
		UUID id = registerE1();
		Patient snapshot = patientRepository.findById(id).block(Duration.ofSeconds(10));
		assertThat(snapshot.escalated()).isFalse();
		// RF-03 escalates the patient after the status change read it
		Instant escalatedAt = escalate(id);

		StepVerifier.create(withStaleRead(snapshot).change(id, PatientStatus.IN_CONSULTATION))
			.assertNext(details -> {
				assertThat(details.patient().status()).isEqualTo(PatientStatus.IN_CONSULTATION);
				assertThat(details.patient().escalated()).isTrue();
				assertThat(details.patient().escalatedAt()).isEqualTo(escalatedAt);
			})
			.verifyComplete();

		JsonNode stored = get(id);
		assertThat(stored.get("escalado").asBoolean()).isTrue();
		assertThat(Instant.parse(stored.get("fechaEscalamiento").asText())).isEqualTo(escalatedAt);
	}

}
