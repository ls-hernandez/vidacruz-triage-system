package com.vidacruz.assistant.support;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic embedding double, no network. Each dimension counts the occurrences of one clinical stem in the text
 * (lowercase, without accents); a constant last dimension avoids zero vectors. Texts that share clinical terms are
 * similar, texts that share none (e.g. "diabetes tipo 2" vs the corpus) are close to 0.
 * <p>
 * With the corpus and the 3 example questions of the challenge (threshold 0.3 in tests):
 * <ul>
 * <li>"medidas iniciales ... crisis hipertensiva" → the 3 PROTO-001 sections (≈ 0.67–0.75), Medidas iniciales
 * included;</li>
 * <li>"primer antibiótico en sepsis" → PROTO-002 Acciones iniciales (≈ 0.57) and Criterios (≈ 0.32);</li>
 * <li>"diabetes tipo 2" → nothing above 0.01.</li>
 * </ul>
 */
public class KeywordEmbeddingModel implements EmbeddingModel {

	static final List<String> STEMS = List.of("hipertens", "presion", "medida", "inicial", "crisis", "reposo",
			"encefalopat", "contraindic", "nifedipino", "sepsis", "qsofa", "antibiot", "lactato", "hemocultivo",
			"fluidoterap", "criterio", "accion", "respirator", "deshidrat", "rehidrat", "pediatr", "clasific", "vomito",
			"diabetes");

	private static final float BIAS = 0.1f;

	private final AtomicBoolean failing;
	private final AtomicInteger calls = new AtomicInteger();

	public KeywordEmbeddingModel(boolean failing) {
		this.failing = new AtomicBoolean(failing);
	}

	/** While {@code true}, every embedding call fails as if the provider were down. */
	public void setFailing(boolean value) {
		failing.set(value);
	}

	public int calls() {
		return calls.get();
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<String> texts = request.getInstructions();
		return new EmbeddingResponse(
				IntStream.range(0, texts.size()).mapToObj(i -> new Embedding(vector(texts.get(i)), i)).toList());
	}

	@Override
	public float[] embed(Document document) {
		return vector(document.getText());
	}

	@Override
	public int dimensions() {
		return STEMS.size() + 1;
	}

	private float[] vector(String text) {
		calls.incrementAndGet();
		if (failing.get()) {
			throw new IllegalStateException("Simulated embedding provider outage");
		}
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.toLowerCase(Locale.ROOT);
		float[] vector = new float[STEMS.size() + 1];
		for (int i = 0; i < STEMS.size(); i++) {
			vector[i] = occurrences(normalized, STEMS.get(i));
		}
		vector[STEMS.size()] = BIAS;
		return vector;
	}

	private static int occurrences(String text, String stem) {
		int count = 0;
		for (int from = text.indexOf(stem); from >= 0; from = text.indexOf(stem, from + stem.length())) {
			count++;
		}
		return count;
	}

}
