package com.vidacruz.assistant.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Sends the test API key ({@code assistant.security.api-key} in {@code src/test/resources/application.properties}) on
 * every request of the auto-configured {@code WebTestClient}. Tests of the unauthenticated case remove it with
 * {@code client.mutate().defaultHeaders(headers -> headers.remove(HEADER))}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApiKeyTestConfiguration {

	public static final String HEADER = "X-API-Key";
	public static final String API_KEY = "test-assistant-key";

	@Bean
	WebTestClientBuilderCustomizer apiKeyHeader() {
		return builder -> builder.defaultHeader(HEADER, API_KEY);
	}

}
