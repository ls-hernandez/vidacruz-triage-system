package com.vidacruz.triage.domain;

/**
 * Triage priority levels (RN-01), from most to least severe.
 * Names are stored as-is in the database; the Spanish contract values are mapped at the boundary.
 */
public enum TriageLevel {

	CRITICAL(1),
	URGENT(2),
	URGENT_2(3),
	NON_URGENT(4),
	NON_URGENT_2(5);

	private final int number;

	TriageLevel(int number) {
		this.number = number;
	}

	public int number() {
		return number;
	}

}
