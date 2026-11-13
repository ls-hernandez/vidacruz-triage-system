package com.vidacruz.assistant.application;

/**
 * The corpus is not indexed yet (ingestion pending or failing). Answered as 503, never as "no protocol": an empty
 * index says nothing about whether a protocol exists.
 */
public class KnowledgeBaseNotReadyException extends RuntimeException {

	public KnowledgeBaseNotReadyException() {
		super("The protocol corpus is not indexed yet");
	}

}
