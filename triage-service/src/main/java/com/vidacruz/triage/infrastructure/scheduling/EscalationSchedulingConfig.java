package com.vidacruz.triage.infrastructure.scheduling;

import com.vidacruz.triage.application.EscalateOverduePatients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the RF-03 scheduler only when {@code triage.escalation.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "triage.escalation", name = "enabled", havingValue = "true")
public class EscalationSchedulingConfig {

	@Bean
	EscalationScheduler escalationScheduler(EscalateOverduePatients escalateOverduePatients) {
		return new EscalationScheduler(escalateOverduePatients);
	}

}
