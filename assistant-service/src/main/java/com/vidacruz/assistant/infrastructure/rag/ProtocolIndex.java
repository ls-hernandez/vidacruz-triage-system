package com.vidacruz.assistant.infrastructure.rag;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

/**
 * Holds the in-memory vector store once the whole corpus is indexed. Empty until then: readers must treat that as
 * "not ready", never as "no protocol".
 */
@Component
public class ProtocolIndex {

	private final AtomicReference<VectorStore> store = new AtomicReference<>();

	Optional<VectorStore> current() {
		return Optional.ofNullable(store.get());
	}

	void publish(VectorStore fullyIndexed) {
		store.set(fullyIndexed);
	}

	public boolean isReady() {
		return store.get() != null;
	}

}
