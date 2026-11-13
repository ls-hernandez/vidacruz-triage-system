package com.vidacruz.assistant.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Chat model double, no network. By default it answers citing every fragment id present in the prompt context
 * ({@code [PROTO-001#medidas-iniciales] ...}); tests can script the JSON answer, make it fail or make it slow. Counts
 * the invocations so tests can assert the model was (not) called.
 */
public class ScriptedChatModel implements ChatModel {

	private static final Pattern FRAGMENT_ID = Pattern.compile("\\[(PROTO-\\d{3}#[a-z0-9-]+)]");
	private static final ObjectMapper JSON = new ObjectMapper();

	private final AtomicInteger invocations = new AtomicInteger();
	private final AtomicReference<Function<Prompt, String>> responder = new AtomicReference<>();
	private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
	private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
	private final AtomicReference<String> lastPrompt = new AtomicReference<>();

	public ScriptedChatModel() {
		reset();
	}

	public void reset() {
		invocations.set(0);
		responder.set(ScriptedChatModel::citeEveryFragment);
		failure.set(null);
		delay.set(Duration.ZERO);
		lastPrompt.set(null);
	}

	/** Answers with this text and these fragment ids. */
	public void answer(String text, List<String> fragmentIds) {
		String json = toJson(text, fragmentIds);
		responder.set(prompt -> json);
	}

	/** Answers with this raw text, e.g. something that is not JSON. */
	public void answerRaw(String raw) {
		responder.set(prompt -> raw);
	}

	public void fail(RuntimeException ex) {
		failure.set(ex);
	}

	public void delay(Duration value) {
		delay.set(value);
	}

	public int invocations() {
		return invocations.get();
	}

	public String lastPrompt() {
		return lastPrompt.get();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		invocations.incrementAndGet();
		lastPrompt.set(prompt.getContents());
		Duration wait = delay.get();
		if (!wait.isZero()) {
			try {
				Thread.sleep(wait.toMillis()); // test double only: simulates a slow provider
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted", ex);
			}
		}
		RuntimeException ex = failure.get();
		if (ex != null) {
			throw ex;
		}
		return new ChatResponse(List.of(new Generation(new AssistantMessage(responder.get().apply(prompt)))));
	}

	/** Fragment ids present in the context, in order of appearance. */
	public static List<String> fragmentIds(String promptText) {
		Set<String> ids = new LinkedHashSet<>();
		Matcher matcher = FRAGMENT_ID.matcher(promptText);
		while (matcher.find()) {
			ids.add(matcher.group(1));
		}
		return List.copyOf(ids);
	}

	private static String citeEveryFragment(Prompt prompt) {
		return toJson("Respuesta simulada a partir del contexto.", fragmentIds(prompt.getContents()));
	}

	private static String toJson(String text, List<String> fragmentIds) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("respuesta", text);
		body.put("fragmentos", fragmentIds);
		try {
			return JSON.writeValueAsString(body);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

}
