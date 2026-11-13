package com.vidacruz.assistant.application;

import java.util.List;

/**
 * Raw output of the language model: the answer text and the fragment ids it claims to have used. Untrusted until
 * {@link AnswerClinicalQuestion} validates it.
 */
public record GeneratedAnswer(String text, List<String> fragmentIds) {

	public GeneratedAnswer {
		fragmentIds = fragmentIds == null ? List.of() : List.copyOf(fragmentIds.stream()
			.map(id -> id == null ? "" : id)
			.toList());
	}

}
