package com.vidacruz.triage.infrastructure.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.PathContainer;

class ApiKeyTest {

	private final ApiKey apiKey = new ApiKey("s3cret-key");

	@Test
	void matchesOnlyTheExactKey() {
		assertThat(apiKey.matches("s3cret-key")).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "s3cret-kez", "s3cret-key ", "S3CRET-KEY", "s3cret", "s3cret-key-and-more" })
	void rejectsAnyOtherValue(String presented) {
		assertThat(apiKey.matches(presented)).isFalse();
	}

	@Test
	void rejectsAMissingKey() {
		assertThat(apiKey.matches(null)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void aBlankExpectedKeyIsAConfigurationError(String expected) {
		assertThatIllegalArgumentException().isThrownBy(() -> new ApiKey(expected));
	}

	@Test
	void neverPrintsTheKey() {
		assertThat(apiKey.toString()).doesNotContain("s3cret");
		assertThat(new ApiKeyProperties("s3cret-key").toString()).doesNotContain("s3cret");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
	void healthProbesArePublic(String path) {
		assertThat(ApiKeyAuthenticationFilter.isPublic(PathContainer.parsePath(path))).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator/prometheus", "/actuator/info", "/actuator/healthz", "/actuator",
			"/triage/pacientes", "/", "/actuator/health/../prometheus", "/actuator/health/%2e%2e/prometheus",
			"/actuator/health/./liveness" })
	void everythingElseRequiresTheKey(String path) {
		assertThat(ApiKeyAuthenticationFilter.isPublic(PathContainer.parsePath(path))).isFalse();
	}

}
