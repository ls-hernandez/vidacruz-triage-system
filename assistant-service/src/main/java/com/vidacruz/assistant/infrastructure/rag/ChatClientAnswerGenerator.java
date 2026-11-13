package com.vidacruz.assistant.infrastructure.rag;

import com.vidacruz.assistant.application.GeneratedAnswer;
import com.vidacruz.assistant.application.port.AnswerGenerator;
import com.vidacruz.assistant.domain.ProtocolFragment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Asks the chat model to answer only from the retrieved fragments (assumption 30). Model and temperature (0) come
 * from {@code spring.ai.openai.chat.options.*}. The output is structured ({@link ModelAnswer}); the call blocks, so it
 * runs through {@link ProviderGuard} with the {@code chat} circuit breaker and time limit.
 * <p>
 * Messages are passed as {@link SystemMessage} / {@link UserMessage} objects, not as template strings, so braces in a
 * question are never interpreted as template variables.
 */
@Component
class ChatClientAnswerGenerator implements AnswerGenerator {

	private final ChatClient chatClient;
	private final ProviderGuard guard;
	private final String systemPrompt;

	ChatClientAnswerGenerator(ChatClient.Builder chatClientBuilder, ProviderGuard guard,
			@Value("classpath:prompts/system-prompt.txt") Resource systemPrompt) {
		this.chatClient = chatClientBuilder.build();
		this.guard = guard;
		this.systemPrompt = read(systemPrompt);
	}

	@Override
	public Mono<GeneratedAnswer> generate(String question, List<ProtocolFragment> context) {
		String userMessage = "Contexto:\n" + formatContext(context) + "\n\nPregunta: " + question;
		return guard.call(ProviderGuard.CHAT, () -> chatClient.prompt()
			.messages(new SystemMessage(systemPrompt), new UserMessage(userMessage))
			.call()
			.entity(ModelAnswer.class))
			.map(answer -> new GeneratedAnswer(answer.answer(), answer.fragmentIds()));
	}

	/** {@code [PROTO-001#medidas-iniciales] PROTO-001 — Crisis Hipertensiva, manejo inicial / Medidas iniciales: …} */
	static String formatContext(List<ProtocolFragment> context) {
		return context.stream()
			.map(f -> "[" + f.id() + "] " + f.protocol() + " — " + f.title() + " / " + f.section() + ": " + f.text())
			.collect(Collectors.joining("\n"));
	}

	/** Read once at startup (bean creation), not on the event loop. */
	private static String read(Resource resource) {
		try {
			return resource.getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read the system prompt", ex);
		}
	}

}
