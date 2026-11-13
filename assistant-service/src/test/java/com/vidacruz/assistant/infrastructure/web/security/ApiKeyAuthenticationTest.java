package com.vidacruz.assistant.infrastructure.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.TestcontainersConfiguration;
import com.vidacruz.assistant.support.ApiKeyTestConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * X-API-Key on the business endpoints, public health probes, protected Prometheus endpoint and readiness membership
 * (Redis only, never the LLM nor the RAG index). {@code @AutoConfigureObservability} enables the Prometheus registry,
 * which {@code @SpringBootTest} disables by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@AutoConfigureObservability
@Import({ TestcontainersConfiguration.class, ApiKeyTestConfiguration.class })
class ApiKeyAuthenticationTest {

	private static final String STATUS_PATH = "/asistente/estado-urgencias";
	private static final String QUERY_PATH = "/asistente/consulta";

	@Autowired
	private WebTestClient client;

	@Autowired
	private HealthEndpointGroups healthGroups;

	private WebTestClient withoutKey() {
		return client.mutate().defaultHeaders(headers -> headers.remove(ApiKeyTestConfiguration.HEADER)).build();
	}

	@Test
	void requestWithoutKeyIs401ProblemDetail() {
		withoutKey().get().uri(STATUS_PATH).exchange()
			.expectStatus().isUnauthorized()
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"X-API-Key\"")
			.expectBody()
			.jsonPath("$.status").isEqualTo(401)
			.jsonPath("$.title").isEqualTo("No autenticado")
			.jsonPath("$.detail").isEqualTo("Falta el header X-API-Key.")
			.jsonPath("$.instance").isEqualTo(STATUS_PATH);
	}

	@ParameterizedTest
	// same length as the real key, shorter, longer, the key of the triage service
	@ValueSource(strings = { "test-assistant-kez", "test", "test-assistant-key-2", "test-triage-key" })
	void wrongKeyIs401(String wrongKey) {
		withoutKey().post().uri(QUERY_PATH).header(ApiKeyTestConfiguration.HEADER, wrongKey)
			.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("pregunta", "¿Sepsis?")).exchange()
			.expectStatus().isUnauthorized()
			.expectBody()
			.jsonPath("$.title").isEqualTo("No autenticado")
			.jsonPath("$.detail").isEqualTo("La clave del header X-API-Key no es válida.");
	}

	@Test
	void correctKeyReachesTheApi() {
		client.get().uri(STATUS_PATH).exchange()
			.expectStatus().isOk()
			.expectBody().jsonPath("$.totalActivos").isNumber();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
	void healthProbesArePublicAndUp(String path) {
		withoutKey().get().uri(path).exchange()
			.expectStatus().isOk()
			.expectBody().jsonPath("$.status").isEqualTo("UP");
	}

	@Test
	void prometheusRequiresTheKey() {
		withoutKey().get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void prometheusExposesTheLlmLatencyWithTheKey() {
		String body = client.get().uri("/actuator/prometheus").exchange()
			.expectStatus().isOk()
			.expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains("assistant_llm_latency_seconds_count{", "operation=\"chat\"",
				"operation=\"embedding\"", "outcome=\"error\"", "outcome=\"success\"")
			.contains("assistant_llm_latency_seconds_bucket{");
	}

	@Test
	void readinessDependsOnRedisOnlyAndLivenessOnNothingExternal() {
		HealthEndpointGroup readiness = healthGroups.get("readiness");
		assertThat(readiness.isMember("readinessState")).isTrue();
		assertThat(readiness.isMember("redis")).isTrue();
		assertThat(readiness.isMember("kafka")).isFalse();
		assertThat(readiness.isMember("ping")).isFalse();

		HealthEndpointGroup liveness = healthGroups.get("liveness");
		assertThat(liveness.isMember("livenessState")).isTrue();
		assertThat(liveness.isMember("redis")).isFalse();
	}

}
