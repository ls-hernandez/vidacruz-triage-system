package com.vidacruz.triage.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Creates {@code triaje.eventos} at startup if it does not exist (Spring Kafka's {@code KafkaAdmin}). */
@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {

	@Bean
	NewTopic patientEventsTopic(EventsProperties properties) {
		return TopicBuilder.name(properties.topic())
			.partitions(properties.partitions())
			.replicas(properties.replicas())
			.build();
	}

}
