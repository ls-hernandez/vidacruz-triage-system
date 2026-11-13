package com.vidacruz.assistant.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.assistant.TestcontainersConfiguration;
import com.vidacruz.assistant.domain.ClinicalAnswer;
import com.vidacruz.assistant.infrastructure.rag.ProtocolIndex;
import com.vidacruz.assistant.support.KeywordEmbeddingModel;
import com.vidacruz.assistant.support.ScriptedChatModel;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * RF-04 A over HTTP with the AI doubles (no network): retrieval, citation validation (RN-05), degraded mode (503)
 * and input validation. Uses the 3 example questions of the challenge.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class ClinicalQueryApiTest {

	static final String PATH = "/asistente/consulta";
	static final String HYPERTENSION = "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?";
	static final String SEPSIS = "¿Cuándo se administra el primer antibiótico en sepsis?";
	static final String DIABETES = "¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?";

	@Autowired
	private WebTestClient client;

	@Autowired
	private ScriptedChatModel chat;

	@Autowired
	private KeywordEmbeddingModel embeddings;

	@Autowired
	private CircuitBreakerRegistry circuitBreakers;

	@Autowired
	private ProtocolIndex index;

	@BeforeEach
	void setUp() {
		chat.reset();
		embeddings.setFailing(false);
		circuitBreakers.circuitBreaker("chat").reset();
		circuitBreakers.circuitBreaker("embedding").reset();
		await().atMost(Duration.ofSeconds(10)).until(index::isReady);
	}

	// --- the 3 example questions --------------------------------------------------------------------------------

	@Test
	void hypertensiveCrisisCitesProto001InitialMeasures() {
		JsonNode body = ok(HYPERTENSION);

		assertThat(body.get("respuesta").asText()).isNotBlank().isNotEqualTo(ClinicalAnswer.NO_PROTOCOL_MESSAGE);
		assertThat(sources(body)).contains("PROTO-001|Medidas iniciales");
		assertThat(chat.lastPrompt()).contains("[PROTO-001#medidas-iniciales]").doesNotContain("PROTO-003");
	}

	@Test
	void sepsisAntibioticCitesProto002InitialActions() {
		JsonNode body = ok(SEPSIS);

		assertThat(sources(body)).contains("PROTO-002|Acciones iniciales");
		assertThat(sources(body)).allMatch(source -> source.startsWith("PROTO-002|"));
	}

	@Test
	void diabetesHasNoProtocolAndNeverCallsTheModel() {
		JsonNode body = ok(DIABETES);

		assertNoProtocol(body);
		assertThat(chat.invocations()).isZero();
	}

	// --- citation validation (RN-05) ----------------------------------------------------------------------------

	@Test
	void unknownFragmentIdRejectsTheWholeAnswer() {
		chat.answer("Reposo de 5 minutos y control de la presión.",
				List.of("PROTO-001#medidas-iniciales", "PROTO-009#inventado"));

		assertNoProtocol(ok(HYPERTENSION));
		assertThat(chat.invocations()).isEqualTo(1);
	}

	@Test
	void fragmentOfTheCorpusThatWasNotRetrievedIsRejected() {
		// real corpus fragment, but not part of the context sent for this question
		chat.answer("Solución oral 50 ml/kg.", List.of("PROTO-003#rehidratacion"));

		assertNoProtocol(ok(HYPERTENSION));
	}

	@Test
	void noProtocolMarkGivesTheFixedMessage() {
		chat.answer("SIN_PROTOCOLO", List.of());

		assertNoProtocol(ok(HYPERTENSION));
	}

	@Test
	void answerWithoutCitedFragmentsIsRejected() {
		chat.answer("Reconfirmar la presión tras 5 minutos de reposo.", List.of());

		assertNoProtocol(ok(HYPERTENSION));
	}

	@Test
	void repeatedCitationsGiveEachSourceOnce() {
		chat.answer("Reconfirmar la presión tras 5 minutos de reposo.",
				List.of("PROTO-001#medidas-iniciales", "PROTO-001#medidas-iniciales", " PROTO-001#medidas-iniciales "));

		JsonNode body = ok(HYPERTENSION);

		assertThat(body.get("respuesta").asText()).isEqualTo("Reconfirmar la presión tras 5 minutos de reposo.");
		assertThat(sources(body)).containsExactly("PROTO-001|Medidas iniciales");
	}

	@Test
	void bracesInTheQuestionAreNotTemplateVariables() {
		JsonNode body = ok(HYPERTENSION + " {contexto} {{x}}");

		assertThat(sources(body)).contains("PROTO-001|Medidas iniciales");
	}

	// --- degraded mode (503) ------------------------------------------------------------------------------------

	@Test
	void chatFailureIsServiceUnavailable() {
		chat.fail(new IllegalStateException("Simulated chat provider outage"));

		assertUnavailable(HYPERTENSION);
	}

	@Test
	void unparseableModelOutputIsServiceUnavailable() {
		chat.answerRaw("esto no es JSON");

		assertUnavailable(HYPERTENSION);
	}

	@Test
	void chatTimeoutIsServiceUnavailable() {
		chat.delay(Duration.ofSeconds(3)); // time limit in tests: 500 ms

		long start = System.nanoTime();
		assertUnavailable(HYPERTENSION);

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void questionEmbeddingFailureIsServiceUnavailable() {
		embeddings.setFailing(true);

		assertUnavailable(HYPERTENSION);
		assertThat(chat.invocations()).isZero();
	}

	@Test
	void openCircuitAnswers503WithoutCallingTheModel() {
		chat.fail(new IllegalStateException("Simulated chat provider outage"));
		// test window: 4 calls, 50 % failure rate
		for (int i = 0; i < 4; i++) {
			assertUnavailable(HYPERTENSION);
		}
		assertThat(chat.invocations()).isEqualTo(4);
		assertThat(circuitBreakers.circuitBreaker("chat").getState()).isEqualTo(CircuitBreaker.State.OPEN);

		assertUnavailable(HYPERTENSION);
		assertUnavailable(SEPSIS);

		assertThat(chat.invocations()).isEqualTo(4);
	}

	@Test
	void noProtocolAnswersDoNotCountAsFailures() {
		for (int i = 0; i < 6; i++) {
			assertNoProtocol(ok(DIABETES));
		}
		chat.answer("SIN_PROTOCOLO", List.of());
		for (int i = 0; i < 6; i++) {
			assertNoProtocol(ok(HYPERTENSION));
		}

		assertThat(circuitBreakers.circuitBreaker("chat").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(circuitBreakers.circuitBreaker("chat").getMetrics().getNumberOfFailedCalls()).isZero();
	}

	// --- input validation ---------------------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"pregunta\":null}", "{\"pregunta\":\"\"}", "{\"pregunta\":\"   \"}" })
	void missingOrBlankQuestionIs400(String body) {
		assertInvalidQuestion(body, "es obligatoria y no puede estar vacía");
	}

	@Test
	void tooLongQuestionIs400() {
		assertInvalidQuestion("{\"pregunta\":\"" + "a".repeat(1001) + "\"}", "no puede superar 1000 caracteres");
	}

	@Test
	void questionOf1000CharactersIsAccepted() {
		client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("pregunta", "a".repeat(1000)))
			.exchange()
			.expectStatus().isOk();
	}

	@Test
	void questionThatIsNotTextIs400() {
		assertInvalidQuestion("{\"pregunta\":123}", "tiene un tipo de dato inválido");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"pregunta\":", "no es json" })
	void malformedJsonIs400(String body) {
		JsonNode problem = client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
			.exchange()
			.expectStatus().isBadRequest()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();

		assertThat(problem.get("title").asText()).isEqualTo("Datos inválidos");
		assertThat(problem.get("errors")).isEmpty();
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private WebTestClient.ResponseSpec post(String question) {
		return client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("pregunta", question))
			.exchange();
	}

	private JsonNode ok(String question) {
		JsonNode body = post(question).expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
		assertThat(body).isNotNull();
		return body;
	}

	private void assertUnavailable(String question) {
		JsonNode problem = post(question).expectStatus().isEqualTo(503)
			.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(problem.get("title").asText()).isEqualTo("Asistente clínico no disponible");
		assertThat(problem.get("detail").asText())
			.startsWith("El asistente clínico no está disponible en este momento; intente más tarde.");
	}

	private void assertInvalidQuestion(String body, String message) {
		JsonNode problem = client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
			.exchange()
			.expectStatus().isBadRequest()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();

		assertThat(problem.get("title").asText()).isEqualTo("Datos inválidos");
		assertThat(problem.get("errors")).hasSize(1);
		assertThat(problem.get("errors").get(0).get("campo").asText()).isEqualTo("pregunta");
		assertThat(problem.get("errors").get(0).get("mensaje").asText()).isEqualTo(message);
		assertThat(chat.invocations()).isZero();
	}

	static void assertNoProtocol(JsonNode body) {
		assertThat(body.get("respuesta").asText()).isEqualTo(ClinicalAnswer.NO_PROTOCOL_MESSAGE);
		assertThat(body.get("fuentes").isArray()).isTrue();
		assertThat(body.get("fuentes")).isEmpty();
	}

	static List<String> sources(JsonNode body) {
		List<String> sources = new ArrayList<>();
		body.get("fuentes").forEach(s -> sources.add(s.get("protocolo").asText() + "|" + s.get("seccion").asText()));
		return sources;
	}

}
