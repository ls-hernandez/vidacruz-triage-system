package com.vidacruz.assistant;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String EVENTS_TOPIC = "triaje.eventos";

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
	}

	/**
	 * In production the triage service owns and creates the topic (the assistant never auto-creates it); in tests it
	 * is created here, with the same 3 partitions, before the listener starts.
	 */
	@Bean
	NewTopic patientEventsTopic() {
		return TopicBuilder.name(EVENTS_TOPIC).partitions(3).replicas(1).build();
	}

}
