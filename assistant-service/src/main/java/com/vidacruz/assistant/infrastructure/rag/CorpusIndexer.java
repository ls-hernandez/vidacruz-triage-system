package com.vidacruz.assistant.infrastructure.rag;

import com.vidacruz.assistant.domain.Corpus;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Indexes the corpus in the background at startup (assumption 32). Embedding the fragments calls the provider and
 * blocks, so it runs on {@code boundedElastic}; startup never waits for it. A fresh store is built on every attempt
 * and published only when all fragments are embedded, so a partial index is never served. If the provider is down or
 * there is no API key the attempt is logged and retried after {@code assistant.rag.ingestion.retry-interval}, forever.
 * <p>
 * This lifecycle bean owns the subscription (it is not a request handler): it subscribes on {@link #start()} and
 * disposes on {@link #stop()}.
 */
@Component
class CorpusIndexer implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(CorpusIndexer.class);

	private final EmbeddingModel embeddingModel;
	private final Corpus corpus;
	private final ProtocolIndex index;
	private final RagProperties properties;

	private volatile Disposable ingestion;

	CorpusIndexer(EmbeddingModel embeddingModel, Corpus corpus, ProtocolIndex index, RagProperties properties) {
		this.embeddingModel = embeddingModel;
		this.corpus = corpus;
		this.index = index;
		this.properties = properties;
	}

	@Override
	public void start() {
		List<Document> documents = ProtocolDocuments.toDocuments(corpus);
		ingestion = Mono.fromRunnable(() -> {
			SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
			store.add(documents);
			index.publish(store);
		})
			.subscribeOn(Schedulers.boundedElastic())
			.doOnError(ex -> log.warn("Protocol corpus indexing failed ({}); retrying in {}", ex.toString(),
					properties.ingestion().retryInterval()))
			.retryWhen(Retry.fixedDelay(Long.MAX_VALUE, properties.ingestion().retryInterval()))
			.subscribe(ignored -> {
			}, ex -> log.error("Protocol corpus indexing stopped", ex),
					() -> log.info("Protocol corpus indexed: {} fragments", documents.size()));
	}

	@Override
	public void stop() {
		Disposable current = ingestion;
		if (current != null) {
			current.dispose();
		}
	}

	@Override
	public boolean isRunning() {
		Disposable current = ingestion;
		return current != null && !current.isDisposed();
	}

}
