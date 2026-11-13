package com.vidacruz.assistant.infrastructure.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API key authentication (header {@code X-API-Key}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiKeyProperties.class)
class ApiKeySecurityConfig {

	@Bean
	ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyProperties properties, ObjectMapper objectMapper) {
		return new ApiKeyAuthenticationFilter(new ApiKey(properties.apiKey()), objectMapper);
	}

}
