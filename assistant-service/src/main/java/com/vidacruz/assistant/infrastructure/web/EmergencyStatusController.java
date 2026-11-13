package com.vidacruz.assistant.infrastructure.web;

import com.vidacruz.assistant.application.GetEmergencyStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
class EmergencyStatusController {

	private final GetEmergencyStatus getEmergencyStatus;

	EmergencyStatusController(GetEmergencyStatus getEmergencyStatus) {
		this.getEmergencyStatus = getEmergencyStatus;
	}

	/** RF-04 B: live emergency-room status, read from the view built from {@code triaje.eventos}. */
	@GetMapping("/asistente/estado-urgencias")
	Mono<EmergencyStatusResponse> emergencyStatus() {
		return getEmergencyStatus.activeByLevel().map(EmergencyStatusResponse::from);
	}

}
