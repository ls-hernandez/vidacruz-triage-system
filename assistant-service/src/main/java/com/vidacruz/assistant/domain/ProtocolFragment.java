package com.vidacruz.assistant.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * One section of a clinical protocol: the unit that is indexed, retrieved and cited (RF-04 A, RN-05).
 *
 * @param id       stable id, e.g. {@code PROTO-001#medidas-iniciales}
 * @param protocol protocol id, e.g. {@code PROTO-001}
 * @param title    protocol title, e.g. {@code Crisis Hipertensiva, manejo inicial}
 * @param section  section name as written in the corpus, e.g. {@code Medidas iniciales}
 * @param text     exact section text from the corpus
 */
public record ProtocolFragment(String id, String protocol, String title, String section, String text) {

	public ProtocolFragment {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(protocol, "protocol");
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(section, "section");
		Objects.requireNonNull(text, "text");
	}

	public static ProtocolFragment of(String protocol, String title, String section, String text) {
		return new ProtocolFragment(idOf(protocol, section), protocol, title, section, text);
	}

	/** {@code PROTO-001} + {@code Medidas iniciales} → {@code PROTO-001#medidas-iniciales}. */
	public static String idOf(String protocol, String section) {
		String slug = Normalizer.normalize(section, Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.toLowerCase(Locale.ROOT)
			.trim()
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		return protocol + "#" + slug;
	}

	/** The source cited for this fragment. */
	public Source source() {
		return new Source(protocol, section);
	}

}
