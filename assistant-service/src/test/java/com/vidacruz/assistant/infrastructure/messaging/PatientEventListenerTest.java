package com.vidacruz.assistant.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.application.ApplyPatientEvent;
import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.domain.TriageLevel;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Skip and retry behaviour of the listener, without Kafka (the Mono it returns is what Spring Kafka awaits). */
class PatientEventListenerTest {

	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Test
	void invalidMessageCompletesWithoutTouchingTheView() {
		FlakyView view = new FlakyView(0);
		PatientEventListener listener = new PatientEventListener(new ApplyPatientEvent(view), objectMapper);

		StepVerifier.create(listener.onEvent(record("{no json"))).verifyComplete();

		assertThat(view.attempts.get()).isZero();
	}

	@Test
	void transientRedisFailureIsRetried() {
		FlakyView view = new FlakyView(2);
		PatientEventListener listener = new PatientEventListener(new ApplyPatientEvent(view), objectMapper);

		StepVerifier.withVirtualTime(() -> listener.onEvent(record(registered())))
			.thenAwait(Duration.ofSeconds(10))
			.verifyComplete();

		assertThat(view.attempts.get()).isEqualTo(3);
	}

	@Test
	void persistentRedisFailureIsLoggedAndSkipped() {
		FlakyView view = new FlakyView(Integer.MAX_VALUE);
		PatientEventListener listener = new PatientEventListener(new ApplyPatientEvent(view), objectMapper);

		StepVerifier.withVirtualTime(() -> listener.onEvent(record(registered())))
			.thenAwait(Duration.ofMinutes(1))
			.verifyComplete();

		assertThat(view.attempts.get()).isEqualTo(1 + PatientEventListener.MAX_RETRIES);
	}

	private static String registered() {
		return """
				{"eventId":"%s","eventType":"paciente_registrado","occurredAt":"2026-11-13T10:00:00Z",\
				"patientId":"%s","level":"CRITICO","status":"REGISTRADO"}"""
			.formatted(UUID.randomUUID(), UUID.randomUUID());
	}

	private static ConsumerRecord<String, String> record(String value) {
		return new ConsumerRecord<>("triaje.eventos", 0, 42L, "key", value);
	}

	/** Fails the first {@code failures} writes, as Redis would while it is unreachable. */
	private static final class FlakyView implements EmergencyStatusView {

		private final int failures;
		private final AtomicInteger attempts = new AtomicInteger();

		private FlakyView(int failures) {
			this.failures = failures;
		}

		@Override
		public Mono<Void> markActive(UUID patientId, TriageLevel level) {
			return Mono.defer(() -> attempts.incrementAndGet() <= failures
					? Mono.error(new DataAccessResourceFailureException("Redis down"))
					: Mono.empty());
		}

		@Override
		public Mono<Void> markDischarged(UUID patientId) {
			return markActive(patientId, TriageLevel.CRITICAL);
		}

		@Override
		public Mono<Map<TriageLevel, Long>> countActiveByLevel() {
			return Mono.just(Map.of());
		}

	}

}
