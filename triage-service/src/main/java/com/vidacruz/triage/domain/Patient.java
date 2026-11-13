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
 * @param version     incremented on every status change; used for optimistic concurrency (RF-02)
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
		Instant statusChangedAt,
		long version) {

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

	/** New patient in {@link PatientStatus#REGISTERED}, not escalated, version 0. */
	public static Patient register(UUID id, String fullName, int age, VitalSigns vitalSigns, String chiefComplaint,
			TriageLevel level, int slaMinutes, Instant now) {
		return new Patient(id, fullName, age, vitalSigns, chiefComplaint, level, slaMinutes,
				PatientStatus.REGISTERED, false, null, now, now, 0);
	}

	/**
	 * Checks that this patient may move to {@code target} (RF-02). The change itself is applied by the repository
	 * with a conditional update, so concurrent changes of other columns (e.g. escalation) are never overwritten.
	 *
	 * @throws InvalidStatusTransitionException if the transition is not allowed, including staying in the same status
	 */
	public void requireCanChangeStatusTo(PatientStatus target) {
		Objects.requireNonNull(target, "target must not be null");
		if (!status.canTransitionTo(target)) {
			throw new InvalidStatusTransitionException(status, target);
		}
	}

}
