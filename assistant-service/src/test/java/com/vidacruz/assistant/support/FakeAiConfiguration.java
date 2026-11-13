package com.vidacruz.assistant.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces the OpenAI models with deterministic doubles in every test context: tests never touch the network. The
 * OpenAI chat and embedding auto-configurations are turned off with {@code spring.ai.model.chat=none} and
 * {@code spring.ai.model.embedding=none} ({@code src/test/resources/application.properties}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAiConfiguration {

	@Bean
	KeywordEmbeddingModel keywordEmbeddingModel(@Value("${test.ai.embedding.fail-at-start:false}") boolean failAtStart) {
		return new KeywordEmbeddingModel(failAtStart);
	}

	@Bean
	ScriptedChatModel scriptedChatModel() {
		return new ScriptedChatModel();
	}

}
