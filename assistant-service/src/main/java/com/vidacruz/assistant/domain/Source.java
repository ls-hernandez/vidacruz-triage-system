package com.vidacruz.assistant.domain;

import java.util.Objects;

/** A cited source: protocol id and section name, exactly as in the corpus. */
public record Source(String protocol, String section) {

	public Source {
		Objects.requireNonNull(protocol, "protocol");
		Objects.requireNonNull(section, "section");
	}

}
