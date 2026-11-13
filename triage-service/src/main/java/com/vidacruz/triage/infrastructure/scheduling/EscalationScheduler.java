package com.vidacruz.triage.infrastructure.scheduling;

import com.vidacruz.triage.application.EscalateOverduePatients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

/**
 * RF-03 background process. Spring subscribes to the returned {@code Mono} on every run; with {@code fixedDelay}
 * the next run is scheduled only after the previous one completes, so cycles never overlap within a replica.
 * Across replicas, the conditional update of the use case guarantees a single escalation (RN-03).
 * <p>
 * A failing cycle is logged and completes empty, so the next cycles keep running.
 */
public class EscalationScheduler {

	private static final Logger log = LoggerFactory.getLogger(EscalationScheduler.class);

	private final EscalateOverduePatients escalateOverduePatients;

	public EscalationScheduler(EscalateOverduePatients escalateOverduePatients) {
		this.escalateOverduePatients = escalateOverduePatients;
	}

	@Scheduled(fixedDelayString = "${triage.escalation.interval}")
	public Mono<Void> run() {
		return escalateOverduePatients.escalateOverdue()
			.doOnNext(escalated -> {
				if (escalated > 0) {
					log.info("Escalated {} overdue patient(s)", escalated);
				}
			})
			.onErrorResume(error -> {
				log.error("SLA escalation cycle failed; retrying in the next cycle", error);
				return Mono.empty();
			})
			.then();
	}

}
