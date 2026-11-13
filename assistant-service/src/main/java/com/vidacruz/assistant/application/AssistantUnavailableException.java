package com.vidacruz.assistant.application;

/** The language model provider cannot be used right now: open circuit, timeout or provider error (503). */
public class AssistantUnavailableException extends RuntimeException {

	public AssistantUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
