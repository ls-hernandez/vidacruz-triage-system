package com.vidacruz.assistant.application;

import com.vidacruz.assistant.application.port.AlertNotifier;
import com.vidacruz.assistant.domain.EscalationAlert;
import com.vidacruz.assistant.domain.PatientEvent;
import com.vidacruz.assistant.domain.PatientEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * RF-06: turns each {@code paciente_escalado} event into one alert for the medical team. Other event types are ignored.
 * <p>
 * Escalations older than {@code maxAge} are not alerted: they only reach the assistant when the topic is replayed (a
 * new consumer group rebuilding the Redis view, assumption 26), and a burst of stale alerts on a live board would hide
 * the current ones. A missing {@code occurredAt} still alerts, dated at reception: alerting matters more than the
 * exact time (the triage service always sends it).
 */
public class NotifyEscalation {

	private static final Logger log = LoggerFactory.getLogger(NotifyEscalation.class);

	private final AlertNotifier notifier;
	private final Clock clock;
	private final Duration maxAge;

	public NotifyEscalation(AlertNotifier notifier, Clock clock, Duration maxAge) {
		this.notifier = Objects.requireNonNull(notifier, "notifier");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
	}

	public Mono<Void> handle(PatientEvent event) {
		if (event.type() != PatientEventType.PATIENT_ESCALATED) {
			return Mono.empty();
		}
		Instant now = clock.instant();
		Instant escalatedAt = event.occurredAt() != null ? event.occurredAt() : now;
		if (escalatedAt.isBefore(now.minus(maxAge))) {
			log.debug("Escalation of patient {} at {} is older than {}; no alert (topic replay)", event.patientId(),
					escalatedAt, maxAge);
			return Mono.empty();
		}
		return notifier.notify(new EscalationAlert(event.eventId(), event.patientId(), event.level(), escalatedAt));
	}

}
