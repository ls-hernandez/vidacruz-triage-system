package com.vidacruz.triage.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.TestcontainersConfiguration;
import com.vidacruz.triage.support.ApiKeyTestConfiguration;
import com.vidacruz.triage.support.PatientEventsConsumer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The trace of an HTTP request survives the hop to {@code boundedElastic} and reaches Kafka: the event carries a W3C
 * {@code traceparent} header with the traceId of the request, so the assistant's processing shares it. Same context
 * configuration as {@code ApiKeyAuthenticationTest} (shared Spring context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "triage.escalation.enabled=false")
@AutoConfigureWebTestClient
@AutoConfigureObservability
@Import({ TestcontainersConfiguration.class, ApiKeyTestConfiguration.class })
class TracePropagationTest {

	private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

	@Autowired
	private WebTestClient client;

	@Autowired
	private KafkaContainer kafka;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void registrationEventCarriesTheTraceIdOfTheHttpRequest() {
		Map<String, Object> e1 = Map.of("nombreCompleto", "Ana Pérez", "edad", 45, "frecuenciaCardiaca", 130,
				"presionSistolica", 180, "presionDiastolica", 110, "spo2", 97, "temperatura", new BigDecimal("36.8"),
				"dolor", 8, "gcs", 15, "quejaPrincipal", "Dolor torácico");

		JsonNode created = client.post().uri("/triage/pacientes")
			.header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
			.contentType(MediaType.APPLICATION_JSON).bodyValue(e1).exchange()
			.expectStatus().isCreated()
			.expectBody(JsonNode.class).returnResult().getResponseBody();
		assertThat(created).isNotNull();
		UUID id = UUID.fromString(created.get("id").asText());

		List<ConsumerRecord<String, String>> records = new PatientEventsConsumer(kafka.getBootstrapServers(),
				objectMapper).recordsFor(id, 1);

		assertThat(records).hasSize(1);
		Header traceparent = records.get(0).headers().lastHeader("traceparent");
		assertThat(traceparent).as("traceparent header of the event").isNotNull();
		String value = new String(traceparent.value(), StandardCharsets.UTF_8);
		// 00-<traceId>-<spanId of the producer span>-<flags>
		assertThat(value).startsWith("00-" + TRACE_ID + "-").doesNotContain("00f067aa0ba902b7");
	}

}
