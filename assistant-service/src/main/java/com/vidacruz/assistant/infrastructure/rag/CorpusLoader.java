package com.vidacruz.assistant.infrastructure.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.domain.Corpus;
import com.vidacruz.assistant.domain.ProtocolFragment;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the protocol corpus: a JSON array of protocols, each with its sections (assumption 27). One fragment per
 * section, with the exact text of the challenge. A malformed corpus fails startup: it is a packaging error, not a
 * provider outage.
 */
public final class CorpusLoader {

	record ProtocolJson(String protocolo, String titulo, List<SectionJson> secciones) {
	}

	record SectionJson(String seccion, String texto) {
	}

	private CorpusLoader() {
	}

	public static Corpus load(InputStream json, ObjectMapper objectMapper) {
		List<ProtocolJson> protocols;
		try {
			protocols = objectMapper.readValue(json, new TypeReference<>() {
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read the protocol corpus", ex);
		}
		List<ProtocolFragment> fragments = new ArrayList<>();
		for (ProtocolJson protocol : protocols) {
			if (protocol.protocolo() == null || protocol.titulo() == null || protocol.secciones() == null) {
				throw new IllegalArgumentException("Incomplete protocol in corpus: " + protocol);
			}
			for (SectionJson section : protocol.secciones()) {
				if (section.seccion() == null || section.texto() == null) {
					throw new IllegalArgumentException("Incomplete section in " + protocol.protocolo());
				}
				fragments.add(ProtocolFragment.of(protocol.protocolo(), protocol.titulo(), section.seccion(),
						section.texto()));
			}
		}
		return new Corpus(fragments);
	}

}
