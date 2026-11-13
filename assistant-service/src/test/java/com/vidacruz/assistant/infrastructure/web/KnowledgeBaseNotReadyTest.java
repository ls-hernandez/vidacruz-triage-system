package com.vidacruz.assistant.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.assistant.TestcontainersConfiguration;
import com.vidacruz.assistant.support.KeywordEmbeddingModel;
import com.vidacruz.assistant.support.ScriptedChatModel;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The embedding provider is down at startup (no key, outage): the service starts anyway, health does not depend on
 * the LLM, and questions are answered 503 — never "no protocol" — until a background retry indexes the corpus.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "test.ai.embedding.fail-at-start=true")
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class KnowledgeBaseNotReadyTest {

	@Autowired
	private WebTestClient client;

	@Autowired
	private KeywordEmbeddingModel embeddings;

	@Autowired
	private ScriptedChatModel chat;

	@Test
	void answers503UntilTheCorpusIsIndexedThenRecovers() {
		client.get().uri("/actuator/health").exchange().expectStatus().isOk();

		JsonNode problem = post(ClinicalQueryApiTest.HYPERTENSION).expectStatus().isEqualTo(503)
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(problem.get("title").asText()).isEqualTo("Asistente clínico no disponible");
		// an empty index is not "no protocol"
		post(ClinicalQueryApiTest.DIABETES).expectStatus().isEqualTo(503);
		assertThat(chat.invocations()).isZero();
		// indexing keeps being retried in the background
		await().atMost(Duration.ofSeconds(5)).until(() -> embeddings.calls() >= 2);

		embeddings.setFailing(false);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> post(ClinicalQueryApiTest.HYPERTENSION)
			.expectStatus().isOk());
		JsonNode body = post(ClinicalQueryApiTest.HYPERTENSION).expectStatus().isOk()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody();
		assertThat(ClinicalQueryApiTest.sources(body)).contains("PROTO-001|Medidas iniciales");
		ClinicalQueryApiTest.assertNoProtocol(post(ClinicalQueryApiTest.DIABETES).expectStatus().isOk()
			.expectBody(JsonNode.class)
			.returnResult()
			.getResponseBody());
	}

	private WebTestClient.ResponseSpec post(String question) {
		return client.post().uri(ClinicalQueryApiTest.PATH).contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("pregunta", question))
			.exchange();
	}

}
