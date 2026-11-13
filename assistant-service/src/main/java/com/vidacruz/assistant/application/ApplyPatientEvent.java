package com.vidacruz.assistant.application;

import com.vidacruz.assistant.application.port.EmergencyStatusView;
import com.vidacruz.assistant.domain.PatientEvent;
import reactor.core.publisher.Mono;

/**
 * RF-04 B: applies one triage event to the emergency-room view. Every state but discharge counts as active
 * ({@code EN_CONSULTA} included), and escalation does not change the level.
 */
public class ApplyPatientEvent {

	private final EmergencyStatusView view;

	public ApplyPatientEvent(EmergencyStatusView view) {
		this.view = view;
	}

	public Mono<Void> apply(PatientEvent event) {
		return switch (event.type()) {
			case PATIENT_REGISTERED, PATIENT_IN_CONSULTATION, PATIENT_ESCALATED ->
				view.markActive(event.patientId(), event.level());
			case PATIENT_DISCHARGED -> view.markDischarged(event.patientId());
		};
	}

}
