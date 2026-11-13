package com.vidacruz.triage.domain;

import java.util.Objects;

/**
 * Clinical status of a patient (RF-02).
 * Names are stored as-is in the database; the Spanish contract values are mapped at the boundary.
 * <p>
 * Allowed transitions: REGISTERED → IN_CONSULTATION, REGISTERED → DISCHARGED (direct discharge) and
 * IN_CONSULTATION → DISCHARGED. Anything else, including staying in the same status, is invalid.
 */
public enum PatientStatus {

	REGISTERED,
	IN_CONSULTATION,
	DISCHARGED;

	public boolean canTransitionTo(PatientStatus target) {
		Objects.requireNonNull(target, "target must not be null");
		return switch (this) {
			case REGISTERED -> target == IN_CONSULTATION || target == DISCHARGED;
			case IN_CONSULTATION -> target == DISCHARGED;
			case DISCHARGED -> false;
		};
	}

}
