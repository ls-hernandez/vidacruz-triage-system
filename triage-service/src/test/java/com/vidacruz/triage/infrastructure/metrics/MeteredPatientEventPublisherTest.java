package com.vidacruz.triage.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.domain.PatientEvent;
import com.vidacruz.triage.domain.PatientEventType;
import com.vidacruz.triage.domain.PatientStatus;
import com.vidacruz.triage.domain.TriageLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class MeteredPatientEventPublisherTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final List<PatientEvent> delegated = new ArrayList<>();
	private RuntimeException delegateFailure;

	private final PatientEventPublisher delegate = event -> Mono.defer(() -> {
		delegated.add(event);
		return delegateFailure == null ? Mono.empty() : Mono.error(delegateFailure);
	});

	private final MeteredPatientEventPublisher publisher = new MeteredPatientEventPublisher(delegate, registry);

	@Test
	void registersEveryLevelAtZeroOnStartup() {
		for (TriageLevel level : TriageLevel.values()) {
			assertThat(classifications(level)).isZero();
			assertThat(escalations(level)).isZero();
		}
	}

	@Test
	void countsARegistrationByItsLevelAndDelegates() {
		PatientEvent event = event(PatientEventType.PATIENT_REGISTERED, TriageLevel.URGENT_2, PatientStatus.REGISTERED);

		StepVerifier.create(publisher.publish(event)).verifyComplete();

		assertThat(classifications(TriageLevel.URGENT_2)).isEqualTo(1);
		assertThat(classifications(TriageLevel.CRITICAL)).isZero();
		assertThat(escalations(TriageLevel.URGENT_2)).isZero();
		assertThat(delegated).containsExactly(event);
	}

	@Test
	void countsAnEscalationByItsLevel() {
		StepVerifier.create(publisher
			.publish(event(PatientEventType.PATIENT_ESCALATED, TriageLevel.CRITICAL, PatientStatus.REGISTERED)))
			.verifyComplete();

		assertThat(escalations(TriageLevel.CRITICAL)).isEqualTo(1);
		assertThat(classifications(TriageLevel.CRITICAL)).isZero();
	}

	@Test
	void statusChangesAreNotCounted() {
		StepVerifier.create(publisher.publish(
				event(PatientEventType.PATIENT_IN_CONSULTATION, TriageLevel.URGENT, PatientStatus.IN_CONSULTATION)))
			.verifyComplete();
		StepVerifier.create(publisher
			.publish(event(PatientEventType.PATIENT_DISCHARGED, TriageLevel.URGENT, PatientStatus.DISCHARGED)))
			.verifyComplete();

		assertThat(registry.get(MeteredPatientEventPublisher.CLASSIFICATIONS).counters())
			.allSatisfy(counter -> assertThat(counter.count()).isZero());
		assertThat(registry.get(MeteredPatientEventPublisher.ESCALATIONS).counters())
			.allSatisfy(counter -> assertThat(counter.count()).isZero());
		assertThat(delegated).hasSize(2);
	}

	@Test
	void aPublicationFailureStillCountsTheCommittedRegistration() {
		delegateFailure = new IllegalStateException("Kafka is down");

		StepVerifier.create(publisher
			.publish(event(PatientEventType.PATIENT_REGISTERED, TriageLevel.NON_URGENT, PatientStatus.REGISTERED)))
			.verifyErrorMessage("Kafka is down");

		assertThat(classifications(TriageLevel.NON_URGENT)).isEqualTo(1);
	}

	@Test
	void countsOnlyWhenSubscribed() {
		publisher.publish(event(PatientEventType.PATIENT_REGISTERED, TriageLevel.CRITICAL, PatientStatus.REGISTERED));

		assertThat(classifications(TriageLevel.CRITICAL)).isZero();
	}

	private double classifications(TriageLevel level) {
		return registry.get(MeteredPatientEventPublisher.CLASSIFICATIONS).tag("level", level.name()).counter().count();
	}

	private double escalations(TriageLevel level) {
		return registry.get(MeteredPatientEventPublisher.ESCALATIONS).tag("level", level.name()).counter().count();
	}

	private static PatientEvent event(PatientEventType type, TriageLevel level, PatientStatus status) {
		return new PatientEvent(UUID.randomUUID(), type, Instant.parse("2026-11-13T10:00:00Z"), UUID.randomUUID(),
				level, status);
	}

}
