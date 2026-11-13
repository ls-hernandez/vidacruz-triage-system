package com.vidacruz.triage.infrastructure.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * RF-03 SLA escalation, configurable as {@code triage.escalation.*}.
 *
 * @param enabled     runs the background scheduler; disabled in tests that must not be affected by it
 * @param interval    delay between the end of one evaluation cycle and the start of the next (ISO 8601, e.g. PT30S)
 * @param concurrency patients escalated in parallel within a cycle; keep it below the R2DBC pool size
 */
@Validated
@ConfigurationProperties("triage.escalation")
public record EscalationProperties(
		boolean enabled,
		@NotNull Duration interval,
		@NotNull @Positive Integer concurrency) {
}
