package com.vidacruz.assistant.infrastructure.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.domain.Corpus;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
class RagConfig {

	/** Loaded once at startup; a malformed corpus fails fast. */
	@Bean
	Corpus corpus(ResourceLoader resourceLoader, ObjectMapper objectMapper, RagProperties properties) {
		try (InputStream json = resourceLoader.getResource(properties.corpus()).getInputStream()) {
			return CorpusLoader.load(json, objectMapper);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot open the protocol corpus " + properties.corpus(), ex);
		}
	}

	/**
	 * {@code RestClient.Builder} picked up by the Spring AI OpenAI auto-configuration. In a WebFlux application Spring
	 * Boot does not auto-configure one, so without it the provider calls would have no explicit timeouts: the Reactor
	 * time limit cancels the {@code Mono}, but only an HTTP read timeout frees the blocked {@code boundedElastic}
	 * thread.
	 */
	@Bean
	RestClient.Builder aiRestClientBuilder(RagProperties properties) {
		ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
			.withConnectTimeout(properties.http().connectTimeout())
			.withReadTimeout(properties.http().readTimeout());
		return RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
	}

}
