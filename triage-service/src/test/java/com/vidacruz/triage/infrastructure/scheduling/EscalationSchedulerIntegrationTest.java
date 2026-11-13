package com.vidacruz.triage.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.triage.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** RF-03 background trigger: with the scheduler enabled, an overdue patient is escalated without any request. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "triage.escalation.enabled=true", "triage.escalation.interval=PT1S" })
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class EscalationSchedulerIntegrationTest {

	@Autowired
	private WebTestClient client;

	@Autowired
	private DatabaseClient db;

	@Test
	void schedulerEscalatesAnOverduePatient() {
		UUID id = registerE1();
		db.sql("UPDATE patients SET registered_at = registered_at - interval '6 minutes' WHERE id = :id")
			.bind("id", id)
			.fetch()
			.rowsUpdated()
			.block(Duration.ofSeconds(10));

		Boolean escalated = Mono
			.defer(() -> db.sql("SELECT escalated FROM patients WHERE id = :id")
				.bind("id", id)
				.map(row -> row.get("escalated", Boolean.class))
				.one())
			.filter(Boolean::booleanValue)
			.repeatWhenEmpty(repeat -> repeat.delayElements(Duration.ofMillis(200)))
			.timeout(Duration.ofSeconds(15))
			.block();

		assertThat(escalated).isTrue();
		Long escalationAudits = db
			.sql("SELECT count(*) AS n FROM audit_entries WHERE patient_id = :id AND event_type = 'PATIENT_ESCALATED'")
			.bind("id", id)
			.map(row -> row.get("n", Long.class))
			.one()
			.block(Duration.ofSeconds(10));
		assertThat(escalationAudits).isEqualTo(1L);
	}

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
		JsonNode created = client.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange()
			.expectStatus().isCreated()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(created).isNotNull();
		return UUID.fromString(created.get("id").asText());
	}

}
