package com.vidacruz.triage.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.TestcontainersConfiguration;
import com.vidacruz.triage.support.PatientEventsConsumer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;
import reactor.test.StepVerifier;

/**
 * RF-01 end to end: HTTP → PostgreSQL → Kafka, with real containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "triage.escalation.enabled=false")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class RegisterPatientApiTest {

	private static final String PATH = "/triage/pacientes";

	private static final List<String> FIELDS = List.of("nombreCompleto", "edad", "frecuenciaCardiaca",
			"presionSistolica", "presionDiastolica", "spo2", "temperatura", "dolor", "gcs", "quejaPrincipal");

	@Autowired
	private WebTestClient client;

	@Autowired
	private DatabaseClient db;

	@Autowired
	private KafkaContainer kafka;

	@Autowired
	private ObjectMapper objectMapper;

	/** Example E1 of the challenge (expected level CRITICO). */
	private static Map<String, Object> e1(Consumer<Map<String, Object>> overrides) {
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
		overrides.accept(body);
		return body;
	}

	private WebTestClient.ResponseSpec post(Object body) {
		return client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
	}

	private RegisteredPatient register(Map<String, Object> body) {
		RegisteredPatient patient = post(body).expectStatus().isCreated()
			.expectBody(RegisteredPatient.class)
			.returnResult()
			.getResponseBody();
		assertThat(patient).isNotNull();
		return patient;
	}

	record RegisteredPatient(UUID id, String nivel, int slaMinutos, String estado, Instant fechaRegistro) {
	}

	/** Asserts a 400 whose errors all point to {@code field}. */
	private void expectInvalidField(Object body, String field) {
		post(body).expectStatus().isBadRequest()
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.status").isEqualTo(400)
			.jsonPath("$.title").isEqualTo("Datos inválidos")
			.jsonPath("$.errors").value(hasSize(1))
			.jsonPath("$.errors[*].campo").value(everyItem(equalTo(field)))
			.jsonPath("$.errors[0].mensaje").isNotEmpty();
	}

	// --- happy path -------------------------------------------------------------------------------------------

	@Test
	void registersE1AsCritical() {
		Instant before = Instant.now();

		EntityExchangeResult<JsonNode> result = post(e1(b -> { })).expectStatus().isCreated()
			.expectBody(JsonNode.class)
			.returnResult();
		JsonNode body = result.getResponseBody();

		assertThat(body).isNotNull();
		String id = body.get("id").asText();
		assertThat(UUID.fromString(id)).isNotNull();
		assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).endsWith(PATH + "/" + id);
		assertThat(body.get("nivel").asText()).isEqualTo("CRITICO");
		assertThat(body.get("slaMinutos").asInt()).isEqualTo(5);
		assertThat(body.get("estado").asText()).isEqualTo("REGISTRADO");
		String registeredAt = body.get("fechaRegistro").asText();
		assertThat(registeredAt).endsWith("Z"); // ISO 8601 in UTC
		assertThat(Instant.parse(registeredAt)).isBetween(before.minusSeconds(1), Instant.now());
	}

	@Test
	void acceptsValuesOnTheMinimumBoundaries() {
		RegisteredPatient patient = register(e1(b -> {
			b.put("nombreCompleto", "Ana");
			b.put("edad", 0);
			b.put("frecuenciaCardiaca", 20);
			b.put("presionSistolica", 60);
			b.put("presionDiastolica", 30);
			b.put("spo2", 70);
			b.put("temperatura", new BigDecimal("34.0"));
			b.put("dolor", 0);
			b.put("gcs", 3);
			b.put("quejaPrincipal", "x");
		}));

		assertThat(patient.nivel()).isEqualTo("CRITICO");
	}

	@Test
	void acceptsValuesOnTheMaximumBoundaries() {
		RegisteredPatient patient = register(e1(b -> {
			b.put("nombreCompleto", "a".repeat(100));
			b.put("edad", 120);
			b.put("frecuenciaCardiaca", 250);
			b.put("presionSistolica", 260);
			b.put("presionDiastolica", 160);
			b.put("spo2", 100);
			b.put("temperatura", new BigDecimal("42.0"));
			b.put("dolor", 10);
			b.put("gcs", 15);
			b.put("quejaPrincipal", "x".repeat(500));
		}));

		assertThat(patient.nivel()).isEqualTo("CRITICO");
	}

	// --- validation -------------------------------------------------------------------------------------------

	static Stream<Arguments> valuesJustOutsideTheRange() {
		return Stream.of(
				Arguments.of("frecuenciaCardiaca", 19),
				Arguments.of("frecuenciaCardiaca", 251),
				Arguments.of("presionSistolica", 59),
				Arguments.of("presionSistolica", 261),
				Arguments.of("presionDiastolica", 29),
				Arguments.of("presionDiastolica", 161),
				Arguments.of("spo2", 69),
				Arguments.of("spo2", 101),
				Arguments.of("temperatura", new BigDecimal("33.9")),
				Arguments.of("temperatura", new BigDecimal("42.1")),
				Arguments.of("dolor", -1),
				Arguments.of("dolor", 11),
				Arguments.of("gcs", 2),
				Arguments.of("gcs", 16),
				Arguments.of("edad", -1),
				Arguments.of("edad", 121),
				Arguments.of("nombreCompleto", "Al"),
				Arguments.of("nombreCompleto", "a".repeat(101)),
				Arguments.of("quejaPrincipal", "x".repeat(501)));
	}

	@ParameterizedTest(name = "{0} = {1}")
	@MethodSource("valuesJustOutsideTheRange")
	void rejectsValueJustOutsideTheRange(String field, Object value) {
		expectInvalidField(e1(b -> {
			b.put(field, value);
			if (field.equals("presionSistolica")) {
				b.put("presionDiastolica", 50); // keep the cross-field rule satisfied
			}
		}), field);
	}

	@Test
	void rejectsDiastolicEqualToSystolic() {
		expectInvalidField(e1(b -> {
			b.put("presionSistolica", 120);
			b.put("presionDiastolica", 120);
		}), "presionDiastolica");
	}

	@Test
	void rejectsTemperatureWithMoreThanOneDecimal() {
		expectInvalidField(e1(b -> b.put("temperatura", new BigDecimal("38.45"))), "temperatura");
	}

	@Test
	void acceptsTrailingZeroInTemperature() {
		// E4 vital signs with 38.50 °C: one significant decimal, classified as URGENTE (temperature ≥ 38.5)
		RegisteredPatient patient = register(e1(b -> {
			b.put("frecuenciaCardiaca", 72);
			b.put("presionSistolica", 120);
			b.put("presionDiastolica", 80);
			b.put("spo2", 99);
			b.put("temperatura", new BigDecimal("38.50"));
			b.put("dolor", 2);
		}));

		assertThat(patient.nivel()).isEqualTo("URGENTE");
	}

	@ParameterizedTest
	@MethodSource("fields")
	void rejectsMissingField(String field) {
		expectInvalidField(e1(b -> b.remove(field)), field);
	}

	@ParameterizedTest
	@MethodSource("fields")
	void rejectsNullField(String field) {
		expectInvalidField(e1(b -> b.put(field, null)), field);
	}

	static List<String> fields() {
		return FIELDS;
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void rejectsBlankChiefComplaint(String complaint) {
		expectInvalidField(e1(b -> b.put("quejaPrincipal", complaint)), "quejaPrincipal");
	}

	@Test
	void rejectsNameThatIsTooShortAfterStripping() {
		expectInvalidField(e1(b -> b.put("nombreCompleto", "  Al  ")), "nombreCompleto");
	}

	@Test
	void rejectsTextInANumericField() {
		expectInvalidField(e1(b -> b.put("frecuenciaCardiaca", "abc")), "frecuenciaCardiaca");
	}

	@Test
	void rejectsNumberAsTextInAnIntegerField() {
		expectInvalidField(e1(b -> b.put("edad", "45")), "edad");
	}

	@Test
	void rejectsDecimalInAnIntegerField() {
		expectInvalidField(e1(b -> b.put("dolor", 7.5)), "dolor");
	}

	@Test
	void rejectsMalformedJson() {
		post("{\"nombreCompleto\": \"Ana Pérez\", \"edad\": ").expectStatus().isBadRequest()
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody()
			.jsonPath("$.title").isEqualTo("Datos inválidos")
			.jsonPath("$.detail").isEqualTo("El cuerpo de la solicitud no es un JSON válido.")
			.jsonPath("$.errors").isEmpty();
	}

	@Test
	void rejectsMissingBody() {
		client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).exchange()
			.expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.title").isEqualTo("Datos inválidos");
	}

	// --- side effects -----------------------------------------------------------------------------------------

	@Test
	void auditsTheRegistration() {
		RegisteredPatient patient = register(e1(b -> { }));

		StepVerifier.create(db.sql("""
				SELECT previous_status, new_status, event_type, occurred_at
				FROM audit_entries WHERE patient_id = :id
				""")
				.bind("id", patient.id())
				.map(row -> new Object[] {
						row.get("previous_status", String.class),
						row.get("new_status", String.class),
						row.get("event_type", String.class),
						row.get("occurred_at", Instant.class) })
				.all()
				.collectList())
			.assertNext(rows -> {
				assertThat(rows).hasSize(1);
				Object[] row = rows.get(0);
				assertThat(row[0]).isNull();
				assertThat(row[1]).isEqualTo("REGISTERED");
				assertThat(row[2]).isEqualTo("PATIENT_REGISTERED");
				assertThat(row[3]).isEqualTo(patient.fechaRegistro());
			})
			.verifyComplete();
	}

	@Test
	void storesStrippedTexts() {
		RegisteredPatient patient = register(e1(b -> {
			b.put("nombreCompleto", "  Ana Pérez  ");
			b.put("quejaPrincipal", "  Dolor torácico ");
		}));

		StepVerifier.create(db.sql("SELECT full_name, chief_complaint FROM patients WHERE id = :id")
				.bind("id", patient.id())
				.map(row -> row.get("full_name", String.class) + "|" + row.get("chief_complaint", String.class))
				.one())
			.expectNext("Ana Pérez|Dolor torácico")
			.verifyComplete();
	}

	@Test
	void publishesPatientRegisteredKeyedByPatientId() {
		RegisteredPatient patient = register(e1(b -> { }));

		List<JsonNode> events = new PatientEventsConsumer(kafka.getBootstrapServers(), objectMapper)
			.eventsFor(patient.id(), 1);

		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.get("eventId").asText()).isNotBlank();
			assertThat(event.get("eventType").asText()).isEqualTo("paciente_registrado");
			assertThat(event.get("patientId").asText()).isEqualTo(patient.id().toString());
			assertThat(event.get("level").asText()).isEqualTo("CRITICO");
			assertThat(event.get("status").asText()).isEqualTo("REGISTRADO");
			assertThat(Instant.parse(event.get("occurredAt").asText())).isEqualTo(patient.fechaRegistro());
		});
	}

}
