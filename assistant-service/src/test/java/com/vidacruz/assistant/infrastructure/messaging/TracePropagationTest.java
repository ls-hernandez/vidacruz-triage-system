package com.vidacruz.assistant.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.awaitility.Awaitility.await;

import com.vidacruz.assistant.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The traceId written by the triage service in the {@code traceparent} header of an event reaches the assistant's
 * logs, also after the hop to the Redis client thread (the "Applied" line is written there).
 * <p>
 * The check does not depend on the log format: Spring Boot keeps the console format of the first context started in
 * the JVM (a system property), and the test properties use plain text.
 */
@SpringBootTest(properties = "logging.level.com.vidacruz.assistant.infrastructure.messaging=DEBUG")
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class TracePropagationTest {

	private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
	private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@Test
	void processingOfAnEventLogsTheTraceIdOfItsTraceparentHeader(CapturedOutput output) throws Exception {
		UUID patientId = UUID.randomUUID();
		String payload = """
				{"eventId":"%s","eventType":"paciente_registrado","occurredAt":"%s","patientId":"%s",\
				"level":"CRITICO","status":"REGISTRADO"}""".formatted(UUID.randomUUID(), Instant.now(), patientId);
		ProducerRecord<String, String> record = new ProducerRecord<>(TestcontainersConfiguration.EVENTS_TOPIC,
				patientId.toString(), payload);
		record.headers()
			.add("traceparent", ("00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01").getBytes(StandardCharsets.UTF_8));
		// the test's template is not observed: the header above is the only trace context
		kafkaTemplate.send(record).get();

		await().atMost(Duration.ofSeconds(30))
			.untilAsserted(() -> assertThat(appliedLine(output, patientId)).as("log line of the applied event")
				.isPresent());

		assertThat(appliedLine(output, patientId)).get(STRING)
			.contains(TRACE_ID)
			// the listener opens its own child span
			.doesNotContain(PARENT_SPAN_ID);
	}

	private static Optional<String> appliedLine(CapturedOutput output, UUID patientId) {
		return output.getOut()
			.lines()
			.filter(text -> text.contains("Applied patient event") && text.contains(patientId.toString()))
			.findFirst();
	}

}
