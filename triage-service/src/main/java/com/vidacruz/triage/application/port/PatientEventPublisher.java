package com.vidacruz.triage.application.port;

import com.vidacruz.triage.domain.PatientEvent;
import reactor.core.publisher.Mono;

/**
 * Publishes patient events to other services. Called after the commit; the level 1 implementation sends directly
 * to Kafka and can be replaced by an Outbox (level 2) without touching the use cases.
 */
public interface PatientEventPublisher {

	/** Completes when the broker acknowledges the event; errors if it could not be published. */
	Mono<Void> publish(PatientEvent event);

}
