package com.vidacruz.triage.infrastructure.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.TestcontainersConfiguration;
import com.vidacruz.triage.support.ApiKeyTestConfiguration;
import java.util.UUID;
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
 * X-API-Key on the business endpoints, public health probes, protected Prometheus endpoint and readiness membership.
 * {@code @AutoConfigureObservability} enables the Prometheus registry and tracing, which {@code @SpringBootTest}
 * disables by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "triage.escalation.enabled=false")
@AutoConfigureWebTestClient
@AutoConfigureObservability
@Import({ TestcontainersConfiguration.class, ApiKeyTestConfiguration.class })
class ApiKeyAuthenticationTest {

	@Autowired
	private WebTestClient client;

	@Autowired
	private HealthEndpointGroups healthGroups;

	private WebTestClient withoutKey() {
		return client.mutate().defaultHeaders(headers -> headers.remove(ApiKeyTestConfiguration.HEADER)).build();
	}

	private static String unknownPatientPath() {
		return "/triage/pacientes/" + UUID.randomUUID();
	}

	@Test
	void requestWithoutKeyIs401ProblemDetail() {
		String path = unknownPatientPath();
		withoutKey().get().uri(path).exchange()
			.expectStatus().isUnauthorized()
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"X-API-Key\"")
			.expectBody()
			.jsonPath("$.status").isEqualTo(401)
			.jsonPath("$.title").isEqualTo("No autenticado")
			.jsonPath("$.detail").isEqualTo("Falta el header X-API-Key.")
			.jsonPath("$.instance").isEqualTo(path);
	}

	@Test
	void emptyKeyIsTreatedAsMissing() {
		withoutKey().get().uri(unknownPatientPath()).header(ApiKeyTestConfiguration.HEADER, "").exchange()
			.expectStatus().isUnauthorized()
			.expectBody().jsonPath("$.detail").isEqualTo("Falta el header X-API-Key.");
	}

	@ParameterizedTest
	// same length as the real key, shorter, longer, other case
	@ValueSource(strings = { "test-triage-kez", "test", "test-triage-key-2", "TEST-TRIAGE-KEY" })
	void wrongKeyIs401(String wrongKey) {
		withoutKey().post().uri("/triage/pacientes").header(ApiKeyTestConfiguration.HEADER, wrongKey)
			.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isUnauthorized()
			.expectBody()
			.jsonPath("$.title").isEqualTo("No autenticado")
			.jsonPath("$.detail").isEqualTo("La clave del header X-API-Key no es válida.");
	}

	@Test
	void correctKeyReachesTheApi() {
		// passes the filter: the controller answers 404 for an unknown patient
		client.get().uri(unknownPatientPath()).exchange()
			.expectStatus().isNotFound()
			.expectBody().jsonPath("$.title").isEqualTo("Paciente no encontrado");
	}

	@Test
	void validationStillRunsAfterTheKey() {
		client.post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
			.expectStatus().isBadRequest();
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
	void prometheusExposesTheTriageMetricsWithTheKey() {
		String body = client.get().uri("/actuator/prometheus").exchange()
			.expectStatus().isOk()
			.expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains("triage_classifications_total{", "level=\"NON_URGENT_2\"")
			.contains("triage_escalations_total{");
	}

	@Test
	void readinessDependsOnPostgresOnlyAndLivenessOnNothingExternal() {
		HealthEndpointGroup readiness = healthGroups.get("readiness");
		assertThat(readiness.isMember("readinessState")).isTrue();
		assertThat(readiness.isMember("r2dbc")).isTrue();
		assertThat(readiness.isMember("kafka")).isFalse();

		HealthEndpointGroup liveness = healthGroups.get("liveness");
		assertThat(liveness.isMember("livenessState")).isTrue();
		assertThat(liveness.isMember("r2dbc")).isFalse();
	}

}
