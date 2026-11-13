package com.vidacruz.triage.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Test-only blocking consumer of {@code triaje.eventos}. Events of one patient share the key, so they land in the
 * same partition and come back in publication order.
 */
public final class PatientEventsConsumer {

	public static final String TOPIC = "triaje.eventos";

	private static final Duration TIMEOUT = Duration.ofSeconds(20);
	/** Extra wait after the expected events arrived, to detect events that should not exist. */
	private static final Duration SETTLE = Duration.ofMillis(1500);

	private final String bootstrapServers;
	private final ObjectMapper objectMapper;

	public PatientEventsConsumer(String bootstrapServers, ObjectMapper objectMapper) {
		this.bootstrapServers = bootstrapServers;
		this.objectMapper = objectMapper;
	}

	/**
	 * Reads the topic from the beginning and returns every event keyed by {@code patientId}, in order. Waits until at
	 * least {@code expected} events arrived (or the timeout), then a short extra period for unexpected ones.
	 */
	public List<JsonNode> eventsFor(UUID patientId, int expected) {
		return recordsFor(patientId, expected).stream().map(record -> parse(record.value())).toList();
	}

	/** Same as {@link #eventsFor}, but returns the raw records (with their headers, e.g. {@code traceparent}). */
	public List<ConsumerRecord<String, String>> recordsFor(UUID patientId, int expected) {
		String key = patientId.toString();
		Map<String, Object> config = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
				ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		List<ConsumerRecord<String, String>> events = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
			consumer.subscribe(List.of(TOPIC));
			Instant deadline = Instant.now().plus(TIMEOUT);
			Instant settleUntil = null;
			while (Instant.now().isBefore(settleUntil != null ? settleUntil : deadline)) {
				for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
					if (key.equals(record.key())) {
						events.add(record);
					}
				}
				if (settleUntil == null && events.size() >= expected) {
					settleUntil = Instant.now().plus(SETTLE);
				}
			}
		}
		return events;
	}

	private JsonNode parse(String json) {
		try {
			return objectMapper.readTree(json);
		}
		catch (JsonProcessingException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
