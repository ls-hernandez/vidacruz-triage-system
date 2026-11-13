package com.vidacruz.triage.infrastructure.metrics;

import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.domain.PatientEvent;
import com.vidacruz.triage.domain.TriageLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Decorator of the {@link PatientEventPublisher} port that counts classifications and escalations, keeping
 * Micrometer out of the application layer.
 * <p>
 * The use cases hand exactly one event to this port after each committed registration or escalation (an escalation
 * that matched no row publishes nothing, RN-03), so counting here counts committed facts. The counter is incremented
 * before delegating, so a Kafka failure (best-effort publication) does not hide a registration that does exist.
 * <ul>
 * <li>{@value #CLASSIFICATIONS} ({@code level}): patients classified at registration.</li>
 * <li>{@value #ESCALATIONS} ({@code level}): patients escalated for exceeding their SLA.</li>
 * </ul>
 * The {@code level} tag uses the internal value ({@code CRITICAL}…); every level is registered at 0 on startup so all
 * series exist in Prometheus before the first patient.
 */
public class MeteredPatientEventPublisher implements PatientEventPublisher {

	public static final String CLASSIFICATIONS = "triage.classifications";
	public static final String ESCALATIONS = "triage.escalations";
	public static final String LEVEL_TAG = "level";

	private final PatientEventPublisher delegate;
	private final Map<TriageLevel, Counter> classifications = new EnumMap<>(TriageLevel.class);
	private final Map<TriageLevel, Counter> escalations = new EnumMap<>(TriageLevel.class);

	public MeteredPatientEventPublisher(PatientEventPublisher delegate, MeterRegistry registry) {
		this.delegate = delegate;
		for (TriageLevel level : TriageLevel.values()) {
			classifications.put(level, Counter.builder(CLASSIFICATIONS)
				.description("Patients classified at registration, by triage level")
				.tag(LEVEL_TAG, level.name())
				.register(registry));
			escalations.put(level, Counter.builder(ESCALATIONS)
				.description("Patients escalated for exceeding their SLA, by triage level")
				.tag(LEVEL_TAG, level.name())
				.register(registry));
		}
	}

	@Override
	public Mono<Void> publish(PatientEvent event) {
		return Mono.defer(() -> {
			switch (event.type()) {
				case PATIENT_REGISTERED -> classifications.get(event.level()).increment();
				case PATIENT_ESCALATED -> escalations.get(event.level()).increment();
				case PATIENT_IN_CONSULTATION, PATIENT_DISCHARGED -> {
					// status changes are not part of the required metrics
				}
			}
			return delegate.publish(event);
		});
	}

}
