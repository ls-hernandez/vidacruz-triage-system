package com.vidacruz.triage.domain;

import static com.vidacruz.triage.domain.PatientStatus.DISCHARGED;
import static com.vidacruz.triage.domain.PatientStatus.IN_CONSULTATION;
import static com.vidacruz.triage.domain.PatientStatus.REGISTERED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RF-02 transition rules: the 3 valid transitions and the 6 invalid ones (all 9 pairs). */
class PatientStatusTransitionTest {

	private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

	@ParameterizedTest(name = "{0} -> {1} is allowed")
	@CsvSource({
			"REGISTERED, IN_CONSULTATION",
			"REGISTERED, DISCHARGED",
			"IN_CONSULTATION, DISCHARGED" })
	void allowsValidTransitions(PatientStatus from, PatientStatus to) {
		assertThat(from.canTransitionTo(to)).isTrue();
		assertThatCode(() -> patientIn(from, false).requireCanChangeStatusTo(to)).doesNotThrowAnyException();
	}

	@ParameterizedTest(name = "{0} -> {1} is rejected")
	@CsvSource({
			"DISCHARGED, IN_CONSULTATION",
			"DISCHARGED, REGISTERED",
			"IN_CONSULTATION, REGISTERED",
			"REGISTERED, REGISTERED",
			"IN_CONSULTATION, IN_CONSULTATION",
			"DISCHARGED, DISCHARGED" })
	void rejectsInvalidTransitions(PatientStatus from, PatientStatus to) {
		assertThat(from.canTransitionTo(to)).isFalse();
		assertThatThrownBy(() -> patientIn(from, false).requireCanChangeStatusTo(to))
			.isInstanceOfSatisfying(InvalidStatusTransitionException.class, ex -> {
				assertThat(ex.from()).isEqualTo(from);
				assertThat(ex.to()).isEqualTo(to);
			});
	}

	@ParameterizedTest(name = "escalated patient: {0} -> {1} is allowed")
	@CsvSource({
			"REGISTERED, IN_CONSULTATION",
			"REGISTERED, DISCHARGED" })
	void escalatedPatientCanChangeStatus(PatientStatus from, PatientStatus to) {
		assertThatCode(() -> patientIn(from, true).requireCanChangeStatusTo(to)).doesNotThrowAnyException();
	}

	@ParameterizedTest(name = "{0} produces {1}")
	@CsvSource({
			"IN_CONSULTATION, PATIENT_IN_CONSULTATION",
			"DISCHARGED, PATIENT_DISCHARGED" })
	void mapsTargetStatusToEventType(PatientStatus to, PatientEventType expected) {
		assertThat(PatientEventType.forStatusChange(to)).isEqualTo(expected);
	}

	private static Patient patientIn(PatientStatus status, boolean escalated) {
		VitalSigns vitalSigns = new VitalSigns(130, 180, 110, 97, new BigDecimal("36.8"), 15, 8);
		return new Patient(UUID.randomUUID(), "Ana Perez", 45, vitalSigns, "Dolor torácico", TriageLevel.CRITICAL, 5,
				status, escalated, escalated ? NOW : null, NOW, NOW, 0);
	}

}
