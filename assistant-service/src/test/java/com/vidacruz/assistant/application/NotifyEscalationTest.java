package com.vidacruz.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.EscalationAlert;
import com.vidacruz.assistant.domain.PatientEvent;
import com.vidacruz.assistant.domain.PatientEventType;
import com.vidacruz.assistant.domain.TriageLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NotifyEscalationTest {

	private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
	private static final Duration MAX_AGE = Duration.ofMinutes(15);

	private final List<EscalationAlert> alerts = new ArrayList<>();
	private final NotifyEscalation notifyEscalation = new NotifyEscalation(
			alert -> Mono.fromRunnable(() -> alerts.add(alert)), Clock.fixed(NOW, ZoneOffset.UTC), MAX_AGE);

	@Test
	void escalationProducesOneAlertWithTheEventData() {
		UUID eventId = UUID.randomUUID();
		UUID patientId = UUID.randomUUID();
		Instant escalatedAt = NOW.minusSeconds(2);

		StepVerifier.create(notifyEscalation.handle(
				new PatientEvent(eventId, PatientEventType.PATIENT_ESCALATED, escalatedAt, patientId, TriageLevel.URGENT)))
			.verifyComplete();

		assertThat(alerts).containsExactly(new EscalationAlert(eventId, patientId, TriageLevel.URGENT, escalatedAt));
	}

	@ParameterizedTest
	@EnumSource(value = PatientEventType.class, names = "PATIENT_ESCALATED", mode = EnumSource.Mode.EXCLUDE)
	void otherEventTypesDoNotAlert(PatientEventType type) {
		StepVerifier.create(notifyEscalation.handle(event(type, NOW))).verifyComplete();

		assertThat(alerts).isEmpty();
	}

	@Test
	void missingOccurredAtStillAlertsDatedNow() {
		StepVerifier.create(notifyEscalation.handle(event(PatientEventType.PATIENT_ESCALATED, null))).verifyComplete();

		assertThat(alerts).singleElement().satisfies(alert -> assertThat(alert.escalatedAt()).isEqualTo(NOW));
	}

	@Test
	void escalationExactlyMaxAgeOldIsStillAlerted() {
		StepVerifier.create(notifyEscalation.handle(event(PatientEventType.PATIENT_ESCALATED, NOW.minus(MAX_AGE))))
			.verifyComplete();

		assertThat(alerts).hasSize(1);
	}

	@Test
	void escalationOlderThanMaxAgeIsNotAlerted() {
		Instant stale = NOW.minus(MAX_AGE).minusMillis(1);

		StepVerifier.create(notifyEscalation.handle(event(PatientEventType.PATIENT_ESCALATED, stale))).verifyComplete();

		assertThat(alerts).isEmpty();
	}

	private static PatientEvent event(PatientEventType type, Instant occurredAt) {
		return new PatientEvent(UUID.randomUUID(), type, occurredAt, UUID.randomUUID(), TriageLevel.CRITICAL);
	}

}
