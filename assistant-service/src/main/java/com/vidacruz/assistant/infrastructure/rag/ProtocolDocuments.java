package com.vidacruz.assistant.infrastructure.rag;

import com.vidacruz.assistant.domain.Corpus;
import com.vidacruz.assistant.domain.ProtocolFragment;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Converts corpus fragments to vector store documents and back. The embedded text carries the protocol, its title and
 * the section name, so a question like "medidas iniciales ante una crisis hipertensiva" matches the right section.
 */
public final class ProtocolDocuments {

	static final String PROTOCOL = "protocolo";
	static final String SECTION = "seccion";
	static final String TITLE = "titulo";

	private ProtocolDocuments() {
	}

	public static List<Document> toDocuments(Corpus corpus) {
		return corpus.fragments().stream().map(ProtocolDocuments::toDocument).toList();
	}

	static Document toDocument(ProtocolFragment fragment) {
		return Document.builder()
			.id(fragment.id())
			.text(fragment.protocol() + " — " + fragment.title() + ". " + fragment.section() + ": " + fragment.text())
			.metadata(Map.of(PROTOCOL, fragment.protocol(), SECTION, fragment.section(), TITLE, fragment.title()))
			.build();
	}

}
