package com.vidacruz.assistant.infrastructure.rag;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RF-04 A settings ({@code assistant.rag.*}).
 *
 * @param corpus              location of the protocol corpus (JSON)
 * @param topK                maximum fragments sent to the model
 * @param similarityThreshold minimum cosine similarity for a fragment to count as relevant
 * @param ingestion           background indexing of the corpus
 * @param http                timeouts of the HTTP client used by Spring AI (bounds the threads blocked on the provider)
 */
@ConfigurationProperties("assistant.rag")
public record RagProperties(String corpus, int topK, double similarityThreshold, Ingestion ingestion, Http http) {

	/** @param retryInterval delay between indexing attempts while the provider is unavailable */
	public record Ingestion(Duration retryInterval) {
	}

	public record Http(Duration connectTimeout, Duration readTimeout) {
	}

}
