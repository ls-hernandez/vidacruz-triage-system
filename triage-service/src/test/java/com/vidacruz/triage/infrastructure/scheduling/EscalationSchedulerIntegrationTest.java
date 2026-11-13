package com.vidacruz.triage.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.triage.TestcontainersConfiguration;
import com.vidacruz.triage.support.ApiKeyTestConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * RF-03 background trigger: with the scheduler enabled, an overdue patient is escalated without any request. Each
 * cycle runs in its own trace (Spring observes reactive {@code @Scheduled} methods), so its logs carry a traceId.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "triage.escalation.enabled=true", "triage.escalation.interval=PT1S" })
@AutoConfigureWebTestClient
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
@Import({ TestcontainersConfiguration.class, ApiKeyTestConfiguration.class })
class EscalationSchedulerIntegrationTest {

	/** 32 hex characters (W3C traceId) in the correlation part of a plain line or in the ECS "traceId" field. */
	private static final Pattern TRACE_ID = Pattern.compile("\\b[0-9a-f]{32}\\b");

	@Autowired
	private WebTestClient client;

	@Autowired
	private DatabaseClient db;

	@Test
	void schedulerEscalatesAnOverduePatient(CapturedOutput output) {
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

		// logged at the end of the cycle, after the R2DBC update on a driver thread: the trace of the cycle is still there
		List<String> cycleLines = Mono
			.fromSupplier(() -> output.getOut()
				.lines()
				.filter(line -> line.contains("Escalated") && line.contains("overdue patient(s)"))
				.toList())
			.filter(lines -> !lines.isEmpty())
			.repeatWhenEmpty(repeat -> repeat.delayElements(Duration.ofMillis(200)))
			.timeout(Duration.ofSeconds(10))
			.block();
		assertThat(cycleLines).allMatch(line -> TRACE_ID.matcher(line).find(), "contains a traceId");
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
