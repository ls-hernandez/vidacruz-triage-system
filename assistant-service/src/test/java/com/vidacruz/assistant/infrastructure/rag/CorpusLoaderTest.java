package com.vidacruz.assistant.infrastructure.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.domain.Corpus;
import com.vidacruz.assistant.domain.ProtocolFragment;
import com.vidacruz.assistant.domain.Source;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** The packaged corpus holds the 3 protocols of the challenge, one fragment per section, with the exact text. */
class CorpusLoaderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void loadsOneFragmentPerSectionOfTheThreeProtocols() throws IOException {
		Corpus corpus = packagedCorpus();

		assertThat(corpus.fragments()).extracting(f -> f.protocol() + "|" + f.section()).containsExactly(
				"PROTO-001|Definición", "PROTO-001|Medidas iniciales", "PROTO-001|Contraindicaciones",
				"PROTO-002|Criterios", "PROTO-002|Acciones iniciales",
				"PROTO-003|Clasificación", "PROTO-003|Rehidratación");
		assertThat(corpus.fragments()).extracting(ProtocolFragment::id).doesNotHaveDuplicates()
			.contains("PROTO-001#medidas-iniciales", "PROTO-002#acciones-iniciales", "PROTO-003#rehidratacion");
	}

	@Test
	void keepsTheExactTextOfTheChallenge() throws IOException {
		Corpus corpus = packagedCorpus();

		assertThat(corpus.findById("PROTO-001#medidas-iniciales")).get().extracting(ProtocolFragment::text)
			.isEqualTo("Reconfirmar la presión tras 5 minutos de reposo. No reducir la presión más de un 25% en la "
					+ "primera hora. Vigilar signos de encefalopatía.");
		assertThat(corpus.findById("PROTO-002#acciones-iniciales")).get().extracting(ProtocolFragment::text)
			.isEqualTo("Lactato y hemocultivos antes del antibiótico. Antibiótico de amplio espectro en la primera "
					+ "hora. Fluidoterapia con 30 ml/kg de cristaloides si hay hipotensión o lactato ≥ 4.");
		assertThat(corpus.findById("PROTO-003#rehidratacion")).get().extracting(ProtocolFragment::title)
			.isEqualTo("Deshidratación pediátrica");
		assertThat(corpus.contains(new Source("PROTO-002", "Criterios"))).isTrue();
		assertThat(corpus.contains(new Source("PROTO-002", "Medidas iniciales"))).isFalse();
	}

	@Test
	void documentsCarryProtocolAndSectionMetadata() throws IOException {
		List<Document> documents = ProtocolDocuments.toDocuments(packagedCorpus());

		Document measures = documents.get(1);
		assertThat(measures.getId()).isEqualTo("PROTO-001#medidas-iniciales");
		assertThat(measures.getMetadata()).containsEntry("protocolo", "PROTO-001")
			.containsEntry("seccion", "Medidas iniciales");
		assertThat(measures.getText()).startsWith("PROTO-001 — Crisis Hipertensiva, manejo inicial. Medidas iniciales: ");
	}

	@Test
	void rejectsDuplicateSections() {
		String json = """
				[{"protocolo":"PROTO-001","titulo":"T","secciones":[
				  {"seccion":"Definición","texto":"a"},{"seccion":"Definicion","texto":"b"}]}]""";

		assertThatThrownBy(() -> load(json)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Duplicate fragment id");
	}

	@Test
	void rejectsInvalidProtocolIdsAndBlankTexts() {
		assertThatThrownBy(() -> load("""
				[{"protocolo":"P-1","titulo":"T","secciones":[{"seccion":"S","texto":"a"}]}]"""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> load("""
				[{"protocolo":"PROTO-001","titulo":"T","secciones":[{"seccion":"S","texto":"  "}]}]"""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> load("[]")).isInstanceOf(IllegalArgumentException.class);
	}

	static Corpus packagedCorpus() throws IOException {
		try (InputStream json = CorpusLoaderTest.class.getResourceAsStream("/corpus/protocols.json")) {
			return CorpusLoader.load(json, new ObjectMapper());
		}
	}

	private Corpus load(String json) {
		return CorpusLoader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), objectMapper);
	}

}
