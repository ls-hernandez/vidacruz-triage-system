package com.vidacruz.assistant.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.application.ApplyPatientEvent;
import com.vidacruz.assistant.domain.PatientEvent;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * RF-04 B: consumes {@code triaje.eventos} and applies each event to the Redis view.
 * <p>
 * Returning {@link Mono} makes Spring Kafka use async acknowledgements (manual ack mode with out-of-order commits):
 * the offset of a record is committed only once its {@code Mono} completes. The container does not wait for that
 * {@code Mono} before handing over the next record, so events of one patient may be applied concurrently; the view
 * is order-independent for that reason (assumption 25).
 * <p>
 * A message that cannot be understood is logged and skipped, never retried: it would fail the same way forever.
 * Redis failures are retried with backoff (the writes are idempotent); if they persist the event is logged and
 * skipped, and the view can be rebuilt by replaying the topic (assumption 26).
 * <p>
 * The listener is observed ({@code spring.kafka.listener.observation-enabled}): the {@code traceparent} header written
 * by the triage service opens a child span, so the logs of both services share the traceId.
 */
@Component
class PatientEventListener {

	private static final Logger log = LoggerFactory.getLogger(PatientEventListener.class);

	static final int MAX_RETRIES = 5;
	static final Duration FIRST_BACKOFF = Duration.ofMillis(200);

	private final ApplyPatientEvent applyPatientEvent;
	private final ObjectMapper objectMapper;

	PatientEventListener(ApplyPatientEvent applyPatientEvent, ObjectMapper objectMapper) {
		this.applyPatientEvent = applyPatientEvent;
		this.objectMapper = objectMapper;
	}

	// idIsGroup = false: the consumer group is spring.kafka.consumer.group-id (assistant-service), not the listener id
	@KafkaListener(id = "patient-events", idIsGroup = false, topics = "${assistant.events.topic}")
	Mono<Void> onEvent(ConsumerRecord<String, String> record) {
		PatientEvent event;
		try {
			event = PatientEventMessage.parse(objectMapper, record.value());
		}
		catch (InvalidPatientEventException ex) {
			log.warn("Skipping invalid patient event at {}-{}@{}: {}", record.topic(), record.partition(),
					record.offset(), ex.getMessage());
			return Mono.empty();
		}
		return applyPatientEvent.apply(event)
			.retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF))
			// runs after Redis answered, on another thread: the traceId of the event is still in the MDC
			.doOnSuccess(ignored -> log.debug("Applied patient event {} ({}) for patient {}", event.eventId(),
					event.type(), event.patientId()))
			.onErrorResume(ex -> {
				log.error("Could not apply patient event {} (patientId {}) at {}-{}@{} after {} retries; skipped",
						event.eventId(), event.patientId(), record.topic(), record.partition(), record.offset(),
						MAX_RETRIES, ex);
				return Mono.empty();
			})
			// Spring Kafka subscribes with toFuture() while the listener observation is open: capture it into the
			// Reactor context so the traceId is restored on the Redis client threads (automatic propagation)
			.contextCapture();
	}

}
