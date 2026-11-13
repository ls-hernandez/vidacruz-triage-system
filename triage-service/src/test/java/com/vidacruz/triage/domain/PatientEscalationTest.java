package com.vidacruz.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** RF-03 / RN-03 rules on the patient: when it is overdue and how the escalation is audited and published. */
class PatientEscalationTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-11-13T10:00:00Z");
	/** registeredAt + SLA of 5 minutes. */
	private static final Instant SLA_DEADLINE = Instant.parse("2026-11-13T10:05:00Z");
	private static final UUID ID = UUID.randomUUID();

	@Test
	void isNotOverdueBeforeTheSla() {
		assertThat(patient(PatientStatus.REGISTERED, false).isOverdueAt(SLA_DEADLINE.minusSeconds(1))).isFalse();
	}

	@Test
	void isNotOverdueExactlyAtTheSlaDeadline() {
		assertThat(patient(PatientStatus.REGISTERED, false).isOverdueAt(SLA_DEADLINE)).isFalse();
	}

	@Test
	void isOverdueOneMicrosecondAfterTheSlaDeadline() {
		assertThat(patient(PatientStatus.REGISTERED, false).isOverdueAt(SLA_DEADLINE.plusNanos(1_000))).isTrue();
	}

	@ParameterizedTest
	@EnumSource(value = PatientStatus.class, names = { "IN_CONSULTATION", "DISCHARGED" })
	void isNeverOverdueOutsideRegistered(PatientStatus status) {
		assertThat(patient(status, false).isOverdueAt(SLA_DEADLINE.plusSeconds(3600))).isFalse();
	}

	@Test
	void isNotOverdueOnceEscalated() {
		assertThat(patient(PatientStatus.REGISTERED, true).isOverdueAt(SLA_DEADLINE.plusSeconds(3600))).isFalse();
	}

	@Test
	void escalationAuditKeepsTheStatusAndUsesTheEscalationTime() {
		Patient escalated = patient(PatientStatus.REGISTERED, true);

		assertThat(AuditEntry.escalation(escalated)).isEqualTo(new AuditEntry(ID, PatientStatus.REGISTERED,
				PatientStatus.REGISTERED, PatientEventType.PATIENT_ESCALATED, escalated.escalatedAt()));
	}

	@Test
	void escalationEventCarriesLevelAndStatus() {
		Patient escalated = patient(PatientStatus.REGISTERED, true);
		UUID eventId = UUID.randomUUID();

		assertThat(PatientEvent.escalated(eventId, escalated)).isEqualTo(new PatientEvent(eventId,
				PatientEventType.PATIENT_ESCALATED, escalated.escalatedAt(), ID, TriageLevel.CRITICAL,
				PatientStatus.REGISTERED));
	}

	@Test
	void escalationAuditAndEventRequireAnEscalatedPatient() {
		Patient notEscalated = patient(PatientStatus.REGISTERED, false);

		assertThatIllegalArgumentException().isThrownBy(() -> AuditEntry.escalation(notEscalated));
		assertThatIllegalArgumentException().isThrownBy(() -> PatientEvent.escalated(UUID.randomUUID(), notEscalated));
	}

	private static Patient patient(PatientStatus status, boolean escalated) {
		VitalSigns vitalSigns = new VitalSigns(130, 180, 110, 97, new BigDecimal("36.8"), 15, 8);
		return new Patient(ID, "Ana Perez", 45, vitalSigns, "Dolor torácico", TriageLevel.CRITICAL, 5, status,
				escalated, escalated ? SLA_DEADLINE.plusSeconds(30) : null, REGISTERED_AT, REGISTERED_AT, 0);
	}

}
