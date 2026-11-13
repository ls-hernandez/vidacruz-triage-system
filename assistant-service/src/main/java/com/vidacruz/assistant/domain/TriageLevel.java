package com.vidacruz.assistant.domain;

/**
 * Triage priority levels, from most to least severe. The assistant keeps its own copy (no code is shared with the
 * triage service); the Spanish contract values are mapped at the boundary.
 */
public enum TriageLevel {

	CRITICAL,
	URGENT,
	URGENT_2,
	NON_URGENT,
	NON_URGENT_2

}
