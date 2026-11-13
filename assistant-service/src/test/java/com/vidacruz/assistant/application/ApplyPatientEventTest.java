package com.vidacruz.assistant.application;

import static com.vidacruz.assistant.domain.PatientEventType.PATIENT_DISCHARGED;
import static com.vidacruz.assistant.domain.PatientEventType.PATIENT_ESCALATED;
import static com.vidacruz.assistant.domain.PatientEventType.PATIENT_IN_CONSULTATION;
import static com.vidacruz.assistant.domain.PatientEventType.PATIENT_REGISTERED;
import static com.vidacruz.assistant.domain.TriageLevel.CRITICAL;
import static com.vidacruz.assistant.domain.TriageLevel.NON_URGENT;
import static com.vidacruz.assistant.domain.TriageLevel.NON_URGENT_2;
import static com.vidacruz.assistant.domain.TriageLevel.URGENT;
import static com.vidacruz.assistant.domain.TriageLevel.URGENT_2;
import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.PatientEvent;
import com.vidacruz.assistant.domain.PatientEventType;
import com.vidacruz.assistant.domain.TriageLevel;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.test.StepVerifier;

/** RF-04 B rules applied through the port, with an in-memory view that honours its contract. */
class ApplyPatientEventTest {

	private final InMemoryEmergencyStatusView view = new InMemoryEmergencyStatusView();
	private final ApplyPatientEvent applyPatientEvent = new ApplyPatientEvent(view);
	private final GetEmergencyStatus getEmergencyStatus = new GetEmergencyStatus(view);

	@ParameterizedTest
	@EnumSource(value = PatientEventType.class, names = { "PATIENT_REGISTERED", "PATIENT_IN_CONSULTATION",
			"PATIENT_ESCALATED" })
	void everyEventButDischargeMakesThePatientActiveInItsLevel(PatientEventType type) {
		apply(type, UUID.randomUUID(), URGENT_2);

		assertThat(counts()).isEqualTo(counts(0, 0, 1, 0, 0));
	}

	@Test
	void registrationCountsInItsLevel() {
		apply(PATIENT_REGISTERED, UUID.randomUUID(), CRITICAL);
		apply(PATIENT_REGISTERED, UUID.randomUUID(), CRITICAL);
		apply(PATIENT_REGISTERED, UUID.randomUUID(), NON_URGENT);

		assertThat(counts()).isEqualTo(counts(2, 0, 0, 1, 0));
	}

	@Test
	void duplicatedEventsDoNotChangeTheCounts() {
		UUID id = UUID.randomUUID();
		PatientEvent registered = event(PATIENT_REGISTERED, id, URGENT);

		apply(registered);
		apply(registered);
		apply(registered);

		assertThat(counts()).isEqualTo(counts(0, 1, 0, 0, 0));
	}

	@Test
	void patientInConsultationIsStillActive() {
		UUID id = UUID.randomUUID();
		apply(PATIENT_REGISTERED, id, NON_URGENT_2);
		apply(PATIENT_IN_CONSULTATION, id, NON_URGENT_2);

		assertThat(counts()).isEqualTo(counts(0, 0, 0, 0, 1));
	}

	@Test
	void escalationDoesNotChangeTheLevelNorCountTwice() {
		UUID id = UUID.randomUUID();
		apply(PATIENT_REGISTERED, id, URGENT_2);
		apply(PATIENT_ESCALATED, id, URGENT_2);

		assertThat(counts()).isEqualTo(counts(0, 0, 1, 0, 0));
	}

	@Test
	void dischargeRemovesThePatient() {
		UUID id = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		apply(PATIENT_REGISTERED, id, CRITICAL);
		apply(PATIENT_IN_CONSULTATION, id, CRITICAL);
		apply(PATIENT_REGISTERED, other, CRITICAL);

		apply(PATIENT_DISCHARGED, id, CRITICAL);

		assertThat(counts()).isEqualTo(counts(1, 0, 0, 0, 0));
		assertThat(view.isDischarged(id)).isTrue();
		assertThat(view.isDischarged(other)).isFalse();
	}

	@Test
	void lateEventAfterDischargeIsIgnored() {
		UUID id = UUID.randomUUID();
		apply(PATIENT_REGISTERED, id, URGENT);
		apply(PATIENT_DISCHARGED, id, URGENT);

		apply(PATIENT_ESCALATED, id, URGENT);

		assertThat(counts()).isEqualTo(counts(0, 0, 0, 0, 0));
	}

	@Test
	void dischargeAppliedBeforeRegistrationWins() {
		UUID id = UUID.randomUUID();
		apply(PATIENT_DISCHARGED, id, CRITICAL);
		apply(PATIENT_REGISTERED, id, CRITICAL);
		apply(PATIENT_IN_CONSULTATION, id, CRITICAL);

		assertThat(counts()).isEqualTo(counts(0, 0, 0, 0, 0));
	}

	@Test
	void statusAlwaysHasTheFiveLevels() {
		assertThat(counts()).containsOnlyKeys(TriageLevel.values()).isEqualTo(counts(0, 0, 0, 0, 0));
	}

	// --- helpers ----------------------------------------------------------------------------------------------

	private void apply(PatientEventType type, UUID patientId, TriageLevel level) {
		apply(event(type, patientId, level));
	}

	private void apply(PatientEvent event) {
		StepVerifier.create(applyPatientEvent.apply(event)).verifyComplete();
	}

	private static PatientEvent event(PatientEventType type, UUID patientId, TriageLevel level) {
		return new PatientEvent(UUID.randomUUID(), type, Instant.parse("2026-11-13T10:00:00Z"), patientId, level);
	}

	private Map<TriageLevel, Long> counts() {
		return getEmergencyStatus.activeByLevel().block();
	}

	private static Map<TriageLevel, Long> counts(long critical, long urgent, long urgent2, long nonUrgent,
			long nonUrgent2) {
		return Map.of(CRITICAL, critical, URGENT, urgent, URGENT_2, urgent2, NON_URGENT, nonUrgent, NON_URGENT_2,
				nonUrgent2);
	}

}
