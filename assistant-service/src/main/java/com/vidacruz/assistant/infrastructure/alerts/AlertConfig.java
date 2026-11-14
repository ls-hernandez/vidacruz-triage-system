package com.vidacruz.assistant.infrastructure.alerts;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RF-06: the only alert channel today, Server-Sent Events (ADR-004). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfig {

	@Bean
	SseAlertBroadcaster sseAlertBroadcaster(AlertProperties properties) {
		return new SseAlertBroadcaster(properties.clientBuffer());
	}

}
