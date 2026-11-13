package com.vidacruz.triage.application;

import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.domain.PatientEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Publishes an event after the commit without failing the use case: if publishing fails, the change is kept and the
 * error is logged. The event is lost (level 1 limitation, solved by the Outbox pattern in level 2).
 */
final class BestEffortEventPublication {

	private static final Logger log = LoggerFactory.getLogger(BestEffortEventPublication.class);

	private final PatientEventPublisher publisher;

	BestEffortEventPublication(PatientEventPublisher publisher) {
		this.publisher = publisher;
	}

	Mono<Void> publish(PatientEvent event) {
		return publisher.publish(event)
			.onErrorResume(error -> {
				log.error("Patient {} was saved but event {} ({}) could not be published; the event is lost",
						event.patientId(), event.eventId(), event.type(), error);
				return Mono.empty();
			});
	}

}
