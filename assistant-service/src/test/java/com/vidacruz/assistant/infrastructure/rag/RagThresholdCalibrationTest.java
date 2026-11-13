package com.vidacruz.assistant.infrastructure.rag;

import com.vidacruz.assistant.domain.Corpus;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

/**
 * Optional calibration of {@code assistant.rag.similarity-threshold} against the REAL embedding model (network, costs
 * a few cents of a cent). Disabled by default; runs only with both the system property and the API key:
 *
 * <pre>
 * ./mvnw -pl assistant-service test -Dtest=RagThresholdCalibrationTest -Drag.calibration=true
 * </pre>
 *
 * with {@code OPENAI_API_KEY} (and optionally {@code OPENAI_EMBEDDING_MODEL}) set in the shell. Prints the similarity
 * of each example question against every corpus fragment, using the same documents and vector store as production.
 * Pick a threshold between the best score of the diabetes question and the relevant scores of the other two.
 */
@EnabledIfSystemProperty(named = "rag.calibration", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class RagThresholdCalibrationTest {

	private static final List<String> QUESTIONS = List.of(
			"¿Cuáles son las medidas iniciales ante una crisis hipertensiva?",
			"¿Cuándo se administra el primer antibiótico en sepsis?",
			"¿Cuál es el tratamiento de primera línea para la diabetes tipo 2?");

	@Test
	void printRealSimilarities() throws IOException {
		String model = System.getenv().getOrDefault("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small");
		OpenAiApi api = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build();
		OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(model).build());

		Corpus corpus = CorpusLoaderTest.packagedCorpus();
		SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
		store.add(ProtocolDocuments.toDocuments(corpus));

		System.out.println("\n=== RAG similarity calibration (" + model + ") ===");
		for (String question : QUESTIONS) {
			System.out.println("\n" + question);
			List<Document> results = store.similaritySearch(
					SearchRequest.builder().query(question).topK(corpus.fragments().size()).similarityThreshold(0).build());
			results.forEach(document -> System.out.printf("  %.4f  %s%n", document.getScore(), document.getId()));
		}
	}

}
