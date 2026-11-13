package com.vidacruz.assistant.application.port;

import com.vidacruz.assistant.application.GeneratedAnswer;
import com.vidacruz.assistant.domain.ProtocolFragment;
import java.util.List;
import reactor.core.publisher.Mono;

/** RF-04 A: asks the language model to answer only from the given fragments. */
public interface AnswerGenerator {

	/**
	 * The model's answer and the ids of the fragments it says it used. Nothing is trusted yet: the caller validates
	 * every id. Errors with {@code AssistantUnavailableException} if the provider fails or times out.
	 */
	Mono<GeneratedAnswer> generate(String question, List<ProtocolFragment> context);

}
