package com.vidacruz.triage.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.triage.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * RF-05 end to end: data seeded by SQL with known registration and consultation times, report over HTTP.
 * <p>
 * The audit trail cannot be deleted (append-only trigger) and the database is shared with the other test classes,
 * so every test works on its own day, far from the dates the other tests use.
 * <p>
 * Expected p95 values follow {@code percentile_cont(0.95)}: with n sorted waits w[0..n-1], position
 * p = 0.95 * (n - 1), result = w[floor(p)] + (p - floor(p)) * (w[floor(p) + 1] - w[floor(p)]).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "triage.escalation.enabled=false")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class OperationReportApiTest {

	private static final String PATH = "/triage/reportes/operacion";
	private static final List<String> LEVELS = List.of("CRITICO", "URGENTE", "URGENTE_2", "NO_URGENTE", "NO_URGENTE_2");

	@Autowired
	private WebTestClient client;

	@Autowired
	private DatabaseClient db;

	// --- aggregates -------------------------------------------------------------------------------------------

	@Test
	void aggregatesEachHourWithAverageAndP95() {
		// 10:00 hour: 5 attended + 1 never attended
		consultAfter(patient("CRITICAL", "2030-01-10T10:00:00Z"), Duration.ofMinutes(10));
		consultAfter(patient("CRITICAL", "2030-01-10T10:05:00Z"), Duration.ofMinutes(20));
		consultAfter(patient("URGENT", "2030-01-10T10:10:00Z"), Duration.ofMinutes(30));
		consultAfter(patient("URGENT_2", "2030-01-10T10:15:00Z"), Duration.ofMinutes(40));
		// consultation at 13:50, after hasta (13:00): the wait is still measured
		consultAfter(patient("NON_URGENT", "2030-01-10T10:30:00Z"), Duration.ofMinutes(200));
		patient("NON_URGENT_2", "2030-01-10T10:45:00Z");
		// 11:00 hour: empty
		// 12:00 hour: waits of 5 min and 7 min 20 s (7.3333... min)
		consultAfter(patient("URGENT", "2030-01-10T12:00:00Z"), Duration.ofMinutes(5));
		consultAfter(patient("URGENT", "2030-01-10T12:59:59Z"), Duration.ofSeconds(7 * 60 + 20));

		JsonNode report = report("desde=2030-01-10T10:00:00Z&hasta=2030-01-10T13:00:00Z");

		assertThat(report.get("desde").asText()).isEqualTo("2030-01-10T10:00:00Z");
		assertThat(report.get("hasta").asText()).isEqualTo("2030-01-10T13:00:00Z");
		assertThat(report.get("zonaHoraria").asText()).isEqualTo("UTC");
		JsonNode hours = report.get("horas");
		assertThat(hours).hasSize(3);

		// waits 10, 20, 30, 40, 200 → average 300 / 5 = 60
		// p95: p = 0.95 * 4 = 3.8 → 40 + 0.8 * (200 - 40) = 168
		JsonNode ten = hours.get(0);
		assertThat(ten.get("hora").asText()).isEqualTo("2030-01-10T10:00:00Z");
		assertThat(ten.get("totalRegistrados").asLong()).isEqualTo(6);
		assertLevels(ten, 2, 1, 1, 1, 1);
		assertThat(ten.get("pacientesAtendidos").asLong()).isEqualTo(5);
		assertDecimal(ten.get("esperaPromedioMinutos"), "60.00");
		assertDecimal(ten.get("esperaP95Minutos"), "168.00");

		JsonNode eleven = hours.get(1);
		assertThat(eleven.get("hora").asText()).isEqualTo("2030-01-10T11:00:00Z");
		assertEmptyHour(eleven);

		// waits 5 and 7.3333 → average 6.1667 → 6.17
		// p95: p = 0.95 * 1 = 0.95 → 5 + 0.95 * 2.3333 = 7.2167 → 7.22
		JsonNode twelve = hours.get(2);
		assertThat(twelve.get("hora").asText()).isEqualTo("2030-01-10T12:00:00Z");
		assertThat(twelve.get("totalRegistrados").asLong()).isEqualTo(2);
		assertLevels(twelve, 0, 2, 0, 0, 0);
		assertThat(twelve.get("pacientesAtendidos").asLong()).isEqualTo(2);
		assertDecimal(twelve.get("esperaPromedioMinutos"), "6.17");
		assertDecimal(twelve.get("esperaP95Minutos"), "7.22");
	}

	@Test
	void patientsWithoutConsultationCountOnlyInTotals() {
		patient("NON_URGENT", "2030-01-11T08:10:00Z"); // before desde (08:15): excluded
		patient("NON_URGENT", "2030-01-11T08:20:00Z");
		patient("CRITICAL", "2030-01-11T08:40:00Z");

		JsonNode hours = report("desde=2030-01-11T08:15:00Z&hasta=2030-01-11T09:00:00Z").get("horas");

		// the series starts at the hour that contains desde
		assertThat(hours).hasSize(1);
		JsonNode eight = hours.get(0);
		assertThat(eight.get("hora").asText()).isEqualTo("2030-01-11T08:00:00Z");
		assertThat(eight.get("totalRegistrados").asLong()).isEqualTo(2);
		assertLevels(eight, 1, 0, 0, 1, 0);
		assertThat(eight.get("pacientesAtendidos").asLong()).isZero();
		assertThat(eight.get("esperaPromedioMinutos").isNull()).isTrue();
		assertThat(eight.get("esperaP95Minutos").isNull()).isTrue();
	}

	@Test
	void rangeIsHalfOpen() {
		patient("URGENT", "2030-01-12T00:00:00Z"); // exactly desde: included
		patient("URGENT", "2030-01-12T01:59:59.999999Z"); // last microsecond: included
		patient("URGENT", "2030-01-12T02:00:00Z"); // exactly hasta: excluded

		JsonNode hours = report("desde=2030-01-12T00:00:00Z&hasta=2030-01-12T02:00:00Z").get("horas");

		assertThat(hours).hasSize(2);
		assertThat(hours.get(0).get("hora").asText()).isEqualTo("2030-01-12T00:00:00Z");
		assertThat(hours.get(0).get("totalRegistrados").asLong()).isEqualTo(1);
		assertThat(hours.get(1).get("hora").asText()).isEqualTo("2030-01-12T01:00:00Z");
		assertThat(hours.get(1).get("totalRegistrados").asLong()).isEqualTo(1);
	}

	// --- time zones -------------------------------------------------------------------------------------------

	@Test
	void groupsByLocalHourOfTheRequestedZone() {
		consultAfter(patient("CRITICAL", "2030-01-13T05:30:00Z"), Duration.ofMinutes(3)); // 00:30 in Bogotá

		JsonNode bogota = report(
				"desde=2030-01-13T00:00:00-05:00&hasta=2030-01-13T02:00:00-05:00&zonaHoraria=America/Bogota");

		assertThat(bogota.get("desde").asText()).isEqualTo("2030-01-13T05:00:00Z");
		assertThat(bogota.get("zonaHoraria").asText()).isEqualTo("America/Bogota");
		JsonNode hours = bogota.get("horas");
		assertThat(hours).hasSize(2);
		assertThat(hours.get(0).get("hora").asText()).isEqualTo("2030-01-13T00:00:00-05:00");
		assertThat(hours.get(0).get("totalRegistrados").asLong()).isEqualTo(1);
		assertDecimal(hours.get(0).get("esperaPromedioMinutos"), "3.00");
		assertThat(hours.get(1).get("hora").asText()).isEqualTo("2030-01-13T01:00:00-05:00");
		assertEmptyHour(hours.get(1));

		// same patient without zonaHoraria: grouped by UTC hour
		JsonNode utc = report("desde=2030-01-13T05:00:00Z&hasta=2030-01-13T06:00:00Z");
		assertThat(utc.get("horas")).hasSize(1);
		assertThat(utc.get("horas").get(0).get("hora").asText()).isEqualTo("2030-01-13T05:00:00Z");
		assertThat(utc.get("horas").get(0).get("totalRegistrados").asLong()).isEqualTo(1);
	}

	@Test
	void repeatedLocalHourAtDaylightSavingEndIsTwoBuckets() {
		// New York falls back on 2030-11-03 at 02:00 EDT → 01:00 EST: the local hour 01:00 happens twice
		patient("URGENT", "2030-11-03T05:30:00Z"); // 01:30 EDT (-04:00)
		patient("URGENT", "2030-11-03T06:30:00Z"); // 01:30 EST (-05:00)

		JsonNode hours = report(
				"desde=2030-11-03T00:00:00-04:00&hasta=2030-11-03T04:00:00-05:00&zonaHoraria=America/New_York")
			.get("horas");

		List<String> starts = new ArrayList<>();
		hours.forEach(hour -> starts.add(hour.get("hora").asText()));
		assertThat(starts).containsExactly(
				"2030-11-03T00:00:00-04:00",
				"2030-11-03T01:00:00-04:00",
				"2030-11-03T01:00:00-05:00",
				"2030-11-03T02:00:00-05:00",
				"2030-11-03T03:00:00-05:00");
		assertThat(hours.get(1).get("totalRegistrados").asLong()).isEqualTo(1);
		assertThat(hours.get(2).get("totalRegistrados").asLong()).isEqualTo(1);
	}

	// --- parameters -------------------------------------------------------------------------------------------

	@Test
	void acceptsExactly31Days() {
		JsonNode hours = report("desde=2031-03-01T00:00:00Z&hasta=2031-04-01T00:00:00Z").get("horas");

		assertThat(hours).hasSize(31 * 24);
		assertThat(hours.get(0).get("hora").asText()).isEqualTo("2031-03-01T00:00:00Z");
		assertThat(hours.get(31 * 24 - 1).get("hora").asText()).isEqualTo("2031-03-31T23:00:00Z");
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			missing desde       | hasta=2030-01-10T01:00:00Z                                          | desde       | es obligatorio
			blank desde         | desde=&hasta=2030-01-10T01:00:00Z                                   | desde       | es obligatorio
			missing hasta       | desde=2030-01-10T00:00:00Z                                          | hasta       | es obligatorio
			date only           | desde=2030-01-10&hasta=2030-01-10T01:00:00Z                         | desde       | debe ser una fecha ISO 8601 con zona u offset, por ejemplo 2026-11-13T00:00:00Z
			no offset           | desde=2030-01-10T00:00:00Z&hasta=2030-01-10T01:00:00                | hasta       | debe ser una fecha ISO 8601 con zona u offset, por ejemplo 2026-11-13T00:00:00Z
			not a date          | desde=ayer&hasta=2030-01-10T01:00:00Z                               | desde       | debe ser una fecha ISO 8601 con zona u offset, por ejemplo 2026-11-13T00:00:00Z
			desde equals hasta  | desde=2030-01-10T00:00:00Z&hasta=2030-01-10T00:00:00Z               | hasta       | debe ser posterior a desde
			desde after hasta   | desde=2030-01-10T02:00:00Z&hasta=2030-01-10T01:00:00Z               | hasta       | debe ser posterior a desde
			same instant offset | desde=2030-01-10T05:00:00Z&hasta=2030-01-10T00:00:00-05:00          | hasta       | debe ser posterior a desde
			more than 31 days   | desde=2031-03-01T00:00:00Z&hasta=2031-04-01T00:00:01Z               | hasta       | el rango entre desde y hasta no puede superar 31 días
			unknown zone        | desde=2030-01-10T00:00:00Z&hasta=2030-01-10T01:00:00Z&zonaHoraria=Mars/Base | zonaHoraria | debe ser una zona horaria IANA válida, por ejemplo America/Bogota
			offset as zone      | desde=2030-01-10T00:00:00Z&hasta=2030-01-10T01:00:00Z&zonaHoraria=-05:00    | zonaHoraria | debe ser una zona horaria IANA válida, por ejemplo America/Bogota
			posix-like zone     | desde=2030-01-10T00:00:00Z&hasta=2030-01-10T01:00:00Z&zonaHoraria=GMT-5     | zonaHoraria | debe ser una zona horaria IANA válida, por ejemplo America/Bogota
			""")
	void rejectsInvalidParameters(String name, String query, String field, String message) {
		JsonNode problem = badRequest(query);

		assertThat(problem.get("errors")).hasSize(1);
		assertThat(problem.get("errors").get(0).get("campo").asText()).isEqualTo(field);
		assertThat(problem.get("errors").get(0).get("mensaje").asText()).isEqualTo(message);
	}

	@Test
	void reportsEveryInvalidParameterAtOnce() {
		JsonNode problem = badRequest("hasta=mañana&zonaHoraria=Nowhere");

		List<String> fields = new ArrayList<>();
		problem.get("errors").forEach(error -> fields.add(error.get("campo").asText()));
		assertThat(fields).containsExactly("desde", "hasta", "zonaHoraria");
		assertThat(problem.get("title").asText()).isEqualTo("Datos inválidos");
	}

	// --- helpers ----------------------------------------------------------------------------------------------

	private JsonNode report(String query) {
		JsonNode body = client.get().uri(PATH + "?" + query).exchange()
			.expectStatus().isOk()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		return body;
	}

	private JsonNode badRequest(String query) {
		JsonNode body = client.get().uri(PATH + "?" + query).exchange()
			.expectStatus().isBadRequest()
			.expectHeader().contentType("application/problem+json")
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		return body;
	}

	/** Inserts a patient registered at {@code registeredAt} (vital signs are irrelevant for the report). */
	private UUID patient(String level, String registeredAt) {
		UUID id = UUID.randomUUID();
		db.sql("""
				INSERT INTO patients (id, full_name, age, heart_rate, systolic_bp, diastolic_bp, spo2, temperature,
				                      pain_level, gcs, chief_complaint, level, sla_minutes, status,
				                      registered_at, status_changed_at)
				VALUES (:id, 'Paciente Reporte', 40, 80, 120, 80, 98, 36.8,
				        2, 15, 'Control', :level, 60, 'REGISTERED', :registeredAt, :registeredAt)
				""")
			.bind("id", id)
			.bind("level", level)
			.bind("registeredAt", Instant.parse(registeredAt))
			.then()
			.block();
		return id;
	}

	/** Records the first entry to consultation {@code wait} after the patient's registration. */
	private void consultAfter(UUID patientId, Duration wait) {
		db.sql("""
				INSERT INTO audit_entries (patient_id, previous_status, new_status, event_type, occurred_at)
				SELECT id, 'REGISTERED', 'IN_CONSULTATION', 'PATIENT_IN_CONSULTATION',
				       registered_at + make_interval(secs => :seconds)
				FROM patients WHERE id = :id
				""")
			.bind("id", patientId)
			.bind("seconds", wait.toSeconds())
			.then()
			.block();
	}

	private static void assertLevels(JsonNode hour, long... expected) {
		JsonNode counts = hour.get("conteoPorNivel");
		List<String> names = new ArrayList<>();
		counts.fieldNames().forEachRemaining(names::add);
		assertThat(names).containsExactlyElementsOf(LEVELS);
		for (int i = 0; i < LEVELS.size(); i++) {
			assertThat(counts.get(LEVELS.get(i)).asLong()).as(LEVELS.get(i)).isEqualTo(expected[i]);
		}
	}

	private static void assertEmptyHour(JsonNode hour) {
		assertThat(hour.get("totalRegistrados").asLong()).isZero();
		assertLevels(hour, 0, 0, 0, 0, 0);
		assertThat(hour.get("pacientesAtendidos").asLong()).isZero();
		assertThat(hour.get("esperaPromedioMinutos").isNull()).isTrue();
		assertThat(hour.get("esperaP95Minutos").isNull()).isTrue();
	}

	private static void assertDecimal(JsonNode value, String expected) {
		assertThat(value.isNumber()).as("%s is a number", value).isTrue();
		assertThat(value.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
	}

}
