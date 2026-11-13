package com.vidacruz.assistant.infrastructure.rag;

import com.vidacruz.assistant.application.KnowledgeBaseNotReadyException;
import com.vidacruz.assistant.application.port.ProtocolRetriever;
import com.vidacruz.assistant.domain.Corpus;
import com.vidacruz.assistant.domain.ProtocolFragment;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Similarity search over the in-memory index. {@code SimpleVectorStore.similaritySearch} embeds the question with
 * the provider and then compares in memory, so the whole call is guarded by the {@code embedding} circuit breaker
 * and time limit. Results are mapped back to corpus fragments by id; anything not in the corpus is dropped.
 */
@Component
class VectorStoreProtocolRetriever implements ProtocolRetriever {

	private static final Logger log = LoggerFactory.getLogger(VectorStoreProtocolRetriever.class);

	private final ProtocolIndex index;
	private final Corpus corpus;
	private final ProviderGuard guard;
	private final RagProperties properties;

	VectorStoreProtocolRetriever(ProtocolIndex index, Corpus corpus, ProviderGuard guard, RagProperties properties) {
		this.index = index;
		this.corpus = corpus;
		this.guard = guard;
		this.properties = properties;
	}

	@Override
	public Mono<List<ProtocolFragment>> retrieve(String question) {
		return Mono.defer(() -> {
			Optional<VectorStore> store = index.current();
			if (store.isEmpty()) {
				// checked before the circuit breaker: an index still loading is not a provider failure
				return Mono.error(new KnowledgeBaseNotReadyException());
			}
			SearchRequest request = SearchRequest.builder()
				.query(question)
				.topK(properties.topK())
				.similarityThreshold(properties.similarityThreshold())
				.build();
			return guard.call(ProviderGuard.EMBEDDING, () -> store.get().similaritySearch(request))
				.map(this::toFragments);
		});
	}

	private List<ProtocolFragment> toFragments(List<Document> documents) {
		if (log.isDebugEnabled()) {
			documents.forEach(document -> log.debug("Retrieved {} (score {})", document.getId(), document.getScore()));
		}
		return documents.stream()
			.map(document -> corpus.findById(document.getId()))
			.flatMap(Optional::stream)
			.toList();
	}

}
