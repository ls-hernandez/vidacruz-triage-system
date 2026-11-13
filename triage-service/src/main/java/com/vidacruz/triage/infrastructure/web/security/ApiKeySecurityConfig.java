package com.vidacruz.triage.infrastructure.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API key authentication (header {@code X-API-Key}); {@link ApiKeyProperties} is bound by the properties scan. */
@Configuration(proxyBeanMethods = false)
class ApiKeySecurityConfig {

	@Bean
	ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyProperties properties, ObjectMapper objectMapper) {
		return new ApiKeyAuthenticationFilter(new ApiKey(properties.apiKey()), objectMapper);
	}

}
