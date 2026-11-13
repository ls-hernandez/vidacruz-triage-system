package com.vidacruz.triage.infrastructure.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Kafka topic for patient events ({@code triage.events.*}).
 *
 * @param partitions events are keyed by patient id, so ordering is kept per patient across partitions
 */
@Validated
@ConfigurationProperties("triage.events")
public record EventsProperties(@NotBlank String topic, @Positive int partitions, @Positive short replicas) {
}
