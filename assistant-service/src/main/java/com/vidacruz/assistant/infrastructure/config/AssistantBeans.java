package com.vidacruz.assistant.infrastructure.config;

import com.vidacruz.assistant.application.ApplyPatientEvent;
import com.vidacruz.assistant.application.GetEmergencyStatus;
import com.vidacruz.assistant.application.port.EmergencyStatusView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free application classes. */
@Configuration(proxyBeanMethods = false)
public class AssistantBeans {

	@Bean
	ApplyPatientEvent applyPatientEvent(EmergencyStatusView view) {
		return new ApplyPatientEvent(view);
	}

	@Bean
	GetEmergencyStatus getEmergencyStatus(EmergencyStatusView view) {
		return new GetEmergencyStatus(view);
	}

}
