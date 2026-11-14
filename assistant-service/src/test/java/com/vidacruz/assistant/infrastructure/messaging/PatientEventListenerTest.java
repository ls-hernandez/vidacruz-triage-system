package com.vidacruz.assistant.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.application.ApplyPatientEvent;
import com.vidacruz.assistant.application.NotifyEscalation;
import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.domain.EscalationAlert;
import com.vidacruz.assistant.domain.TriageLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Skip and retry behaviour of the listener, without Kafka (the Mono it returns is what Spring Kafka awaits). */
class PatientEventListenerTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T10:01:00Z"), ZoneOffset.UTC);

	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
	private final List<EscalationAlert> alerts = new CopyOnWriteArrayList<>();
	private final NotifyEscalation notifyEscalation = new NotifyEscalation(
			alert -> Mono.fromRunnable(() -> alerts.add(alert)), CLOCK, Duration.ofMinutes(15));

	@Test
	void invalidMessageCompletesWithoutTouchingTheView() {
		FlakyView view = new FlakyView(0);
		PatientEventListener listener = listener(view);

		StepVerifier.create(listener.onEvent(record("{no json"))).verifyComplete();

		assertThat(view.attempts.get()).isZero();
	}

	@Test
	void transientRedisFailureIsRetried() {
		FlakyView view = new FlakyView(2);
		PatientEventListener listener = listener(view);

		StepVerifier.withVirtualTime(() -> listener.onEvent(record(registered())))
			.thenAwait(Duration.ofSeconds(10))
			.verifyComplete();

		assertThat(view.attempts.get()).isEqualTo(3);
	}

	@Test
	void persistentRedisFailureIsLoggedAndSkipped() {
		FlakyView view = new FlakyView(Integer.MAX_VALUE);
		PatientEventListener listener = listener(view);

		StepVerifier.withVirtualTime(() -> listener.onEvent(record(registered())))
			.thenAwait(Duration.ofMinutes(1))
			.verifyComplete();

		assertThat(view.attempts.get()).isEqualTo(1 + PatientEventListener.MAX_RETRIES);
	}

	@Test
	void escalationIsAlertedOnceEvenWhenTheViewWriteIsRetried() {
		FlakyView view = new FlakyView(Integer.MAX_VALUE);
		PatientEventListener listener = listener(view);

		StepVerifier.withVirtualTime(() -> listener.onEvent(record(event("paciente_escalado"))))
			.thenAwait(Duration.ofMinutes(1))
			.verifyComplete();

		assertThat(alerts).singleElement().satisfies(alert -> assertThat(alert.level()).isEqualTo(TriageLevel.CRITICAL));
		assertThat(view.attempts.get()).isEqualTo(1 + PatientEventListener.MAX_RETRIES);
	}

	@Test
	void failingAlertDoesNotPreventTheViewUpdate() {
		FlakyView view = new FlakyView(0);
		NotifyEscalation failing = new NotifyEscalation(alert -> Mono.error(new IllegalStateException("channel down")),
				CLOCK, Duration.ofMinutes(15));
		PatientEventListener listener = new PatientEventListener(new ApplyPatientEvent(view), failing, objectMapper);

		StepVerifier.create(listener.onEvent(record(event("paciente_escalado")))).verifyComplete();

		assertThat(view.attempts.get()).isEqualTo(1);
	}

	@Test
	void otherEventsAreNotAlerted() {
		PatientEventListener listener = listener(new FlakyView(0));

		StepVerifier.create(listener.onEvent(record(registered()))).verifyComplete();

		assertThat(alerts).isEmpty();
	}

	private PatientEventListener listener(FlakyView view) {
		return new PatientEventListener(new ApplyPatientEvent(view), notifyEscalation, objectMapper);
	}

	private static String registered() {
		return event("paciente_registrado");
	}

	private static String event(String eventType) {
		return """
				{"eventId":"%s","eventType":"%s","occurredAt":"2026-11-13T10:00:00Z",\
				"patientId":"%s","level":"CRITICO","status":"REGISTRADO"}"""
			.formatted(UUID.randomUUID(), eventType, UUID.randomUUID());
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
