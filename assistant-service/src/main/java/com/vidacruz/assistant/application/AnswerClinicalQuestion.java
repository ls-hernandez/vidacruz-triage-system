package com.vidacruz.assistant.application;

import com.vidacruz.assistant.application.port.AnswerGenerator;
import com.vidacruz.assistant.application.port.ProtocolRetriever;
import com.vidacruz.assistant.domain.ClinicalAnswer;
import com.vidacruz.assistant.domain.Corpus;
import com.vidacruz.assistant.domain.ProtocolFragment;
import com.vidacruz.assistant.domain.Source;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * RF-04 A / RN-05: answers a clinical question only from the protocol corpus.
 * <ol>
 * <li>No fragment reaches the similarity threshold → fixed "no protocol" notice, without calling the model.</li>
 * <li>Otherwise the model answers from the retrieved fragments and lists the ids it used.</li>
 * <li>The answer is rejected as a whole (→ "no protocol") if the model returns the no-protocol mark, no ids, or any
 * id that is not one of the retrieved fragments or whose protocol and section are not in the corpus.</li>
 * <li>Sources are built from the retrieved fragments, never from the model's text, without duplicates.</li>
 * </ol>
 */
public class AnswerClinicalQuestion {

	/** Fixed mark the model must return when the context does not answer the question. */
	public static final String NO_PROTOCOL_MARK = "SIN_PROTOCOLO";

	private static final Logger log = LoggerFactory.getLogger(AnswerClinicalQuestion.class);

	private final ProtocolRetriever retriever;
	private final AnswerGenerator generator;
	private final Corpus corpus;

	public AnswerClinicalQuestion(ProtocolRetriever retriever, AnswerGenerator generator, Corpus corpus) {
		this.retriever = retriever;
		this.generator = generator;
		this.corpus = corpus;
	}

	public Mono<ClinicalAnswer> answer(String question) {
		return retriever.retrieve(question).flatMap(fragments -> {
			if (fragments.isEmpty()) {
				return Mono.just(ClinicalAnswer.noProtocol());
			}
			return generator.generate(question, fragments).map(generated -> validate(generated, fragments));
		});
	}

	private ClinicalAnswer validate(GeneratedAnswer generated, List<ProtocolFragment> retrieved) {
		String text = generated.text() == null ? "" : generated.text().strip();
		if (text.isEmpty() || text.contains(NO_PROTOCOL_MARK)) {
			return ClinicalAnswer.noProtocol();
		}
		if (generated.fragmentIds().isEmpty()) {
			log.warn("Model answer rejected: no fragment cited");
			return ClinicalAnswer.noProtocol();
		}
		Map<String, ProtocolFragment> retrievedById = retrieved.stream()
			.collect(Collectors.toMap(ProtocolFragment::id, Function.identity(), (a, b) -> a));
		Set<Source> sources = new LinkedHashSet<>();
		for (String id : generated.fragmentIds()) {
			Optional<Source> source = Optional.ofNullable(retrievedById.get(id.strip()))
				.map(ProtocolFragment::source)
				.filter(corpus::contains);
			if (source.isEmpty()) {
				log.warn("Model answer rejected: cited fragment '{}' was not retrieved or is not in the corpus", id);
				return ClinicalAnswer.noProtocol();
			}
			sources.add(source.get());
		}
		return new ClinicalAnswer(text, new ArrayList<>(sources));
	}

}
