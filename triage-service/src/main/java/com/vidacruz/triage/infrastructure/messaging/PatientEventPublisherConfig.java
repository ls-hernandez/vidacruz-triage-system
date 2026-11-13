package com.vidacruz.triage.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.infrastructure.metrics.MeteredPatientEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The single {@link PatientEventPublisher} used by the use cases: the Kafka adapter wrapped by the metrics decorator.
 * Switching to an Outbox (level 2) only replaces the inner adapter.
 */
@Configuration(proxyBeanMethods = false)
class PatientEventPublisherConfig {

	@Bean
	PatientEventPublisher patientEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
			EventsProperties properties, MeterRegistry meterRegistry) {
		return new MeteredPatientEventPublisher(
				new KafkaPatientEventPublisher(kafkaTemplate, objectMapper, properties), meterRegistry);
	}

}
