package com.vidacruz.triage.infrastructure.web.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * API key expected in the {@code X-API-Key} header ({@code triage.security.api-key}, from {@code TRIAGE_API_KEY}).
 * There is no default value: the service does not start without a key.
 */
@Validated
@ConfigurationProperties("triage.security")
public record ApiKeyProperties(@NotBlank String apiKey) {

	@Override
	public String toString() {
		// never print the key (e.g. in a failed-binding report)
		return "ApiKeyProperties[apiKey=****]";
	}

}
