package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.application.port.SlaPolicy;
import com.vidacruz.triage.domain.TriageLevel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SLA per triage level in minutes (RN-02), configurable as {@code triage.sla.*}.
 */
@Validated
@ConfigurationProperties("triage.sla")
public record SlaProperties(
		@NotNull @Positive Integer critical,
		@NotNull @Positive Integer urgent,
		@NotNull @Positive Integer urgent2,
		@NotNull @Positive Integer nonUrgent,
		@NotNull @Positive Integer nonUrgent2) implements SlaPolicy {

	@Override
	public int slaMinutesFor(TriageLevel level) {
		return switch (level) {
			case CRITICAL -> critical;
			case URGENT -> urgent;
			case URGENT_2 -> urgent2;
			case NON_URGENT -> nonUrgent;
			case NON_URGENT_2 -> nonUrgent2;
		};
	}

}
