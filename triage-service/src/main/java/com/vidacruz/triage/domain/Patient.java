package com.vidacruz.triage.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Patient registered at triage. Range validation of the input belongs to the API boundary (RF-01);
 * the database CHECK constraints are the last line of defence.
 *
 * @param slaMinutes  SLA captured at registration (RN-02), so later configuration changes do not affect it
 * @param escalatedAt {@code null} unless {@code escalated}
 */
public record Patient(
		UUID id,
		String fullName,
		int age,
		VitalSigns vitalSigns,
		String chiefComplaint,
		TriageLevel level,
		int slaMinutes,
		PatientStatus status,
		boolean escalated,
		Instant escalatedAt,
		Instant registeredAt,
		Instant statusChangedAt) {

	public Patient {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(fullName, "fullName must not be null");
		Objects.requireNonNull(vitalSigns, "vitalSigns must not be null");
		Objects.requireNonNull(chiefComplaint, "chiefComplaint must not be null");
		Objects.requireNonNull(level, "level must not be null");
		Objects.requireNonNull(status, "status must not be null");
		Objects.requireNonNull(registeredAt, "registeredAt must not be null");
		Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
		if (escalated != (escalatedAt != null)) {
			throw new IllegalArgumentException("escalatedAt must be set if and only if the patient is escalated");
		}
	}

	/** New patient in {@link PatientStatus#REGISTERED}, not escalated. */
	public static Patient register(UUID id, String fullName, int age, VitalSigns vitalSigns, String chiefComplaint,
			TriageLevel level, int slaMinutes, Instant now) {
		return new Patient(id, fullName, age, vitalSigns, chiefComplaint, level, slaMinutes,
				PatientStatus.REGISTERED, false, null, now, now);
	}

}
