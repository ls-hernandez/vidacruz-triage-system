package com.vidacruz.triage.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.application.port.PatientEventPublisher;
import com.vidacruz.triage.domain.PatientEvent;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes patient events to Kafka as JSON strings, keyed by patient id so events of one patient stay ordered.
 * <p>
 * The payload is serialized here instead of with Spring's {@code JsonSerializer} so no Java type headers are sent:
 * consumers depend only on the JSON contract. {@code KafkaTemplate.send} may block while it fetches metadata
 * ({@code max.block.ms}), so it runs on the bounded elastic scheduler; the result completes on broker acknowledgement
 * or fails within the producer timeouts configured in {@code application.yml}.
 * <p>
 * The template is observed ({@code spring.kafka.template.observation-enabled}): the W3C {@code traceparent} header of
 * the current trace is added to each record. Automatic context propagation restores that trace on the
 * {@code boundedElastic} thread that calls {@code send}. Wired by {@link PatientEventPublisherConfig}.
 */
class KafkaPatientEventPublisher implements PatientEventPublisher {

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final EventsProperties properties;

	KafkaPatientEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
			EventsProperties properties) {
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	@Override
	public Mono<Void> publish(PatientEvent event) {
		return Mono.fromCallable(() -> objectMapper.writeValueAsString(PatientEventMessage.from(event)))
			.flatMap(payload -> Mono.fromFuture(
					() -> kafkaTemplate.send(properties.topic(), event.patientId().toString(), payload)))
			.subscribeOn(Schedulers.boundedElastic())
			// the future completes on the producer's network thread: do not run downstream work there
			.publishOn(Schedulers.parallel())
			.then();
	}

}
