package com.vidacruz.assistant.application.port;

import com.vidacruz.assistant.domain.TriageLevel;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * RF-04 B: read model of the active patients per level, rebuilt only from triage events.
 * <p>
 * Contract: both writes are idempotent and commute with each other, so the final state does not depend on the order
 * in which events are applied, on duplicates or on concurrent application. A patient is active if and only if
 * {@link #markActive} was called for it and {@link #markDischarged} never was.
 */
public interface EmergencyStatusView {

	/** Adds the patient to the active patients of {@code level}, unless it was already discharged. */
	Mono<Void> markActive(UUID patientId, TriageLevel level);

	/** Removes the patient from every level and remembers the discharge, so later events cannot re-activate it. */
	Mono<Void> markDischarged(UUID patientId);

	/** Active patients per level; always contains the 5 levels. */
	Mono<Map<TriageLevel, Long>> countActiveByLevel();

}
