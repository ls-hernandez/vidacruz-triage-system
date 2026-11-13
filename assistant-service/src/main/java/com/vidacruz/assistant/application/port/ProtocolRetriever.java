package com.vidacruz.assistant.application.port;

import com.vidacruz.assistant.domain.ProtocolFragment;
import java.util.List;
import reactor.core.publisher.Mono;

/** RF-04 A: finds the corpus fragments relevant to a question. */
public interface ProtocolRetriever {

	/**
	 * Fragments whose similarity to the question reaches the configured threshold, most similar first; empty when
	 * nothing in the corpus is relevant.
	 * <p>
	 * Errors with {@code KnowledgeBaseNotReadyException} while the corpus is not indexed (an empty index must never
	 * be read as "no protocol"), and with {@code AssistantUnavailableException} if the provider fails.
	 */
	Mono<List<ProtocolFragment>> retrieve(String question);

}
