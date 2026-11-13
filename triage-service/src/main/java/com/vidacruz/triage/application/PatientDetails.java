package com.vidacruz.triage.application;

import com.vidacruz.triage.domain.Patient;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * A patient plus the time spent in its current status, computed when read (never stored).
 *
 * @param timeInCurrentStatus truncated to seconds; never negative, even with clock skew between replicas
 */
public record PatientDetails(Patient patient, Duration timeInCurrentStatus) {

	public static PatientDetails of(Patient patient, Clock clock) {
		Duration elapsed = Duration.between(patient.statusChangedAt(), clock.instant()).truncatedTo(ChronoUnit.SECONDS);
		return new PatientDetails(patient, elapsed.isNegative() ? Duration.ZERO : elapsed);
	}

}
