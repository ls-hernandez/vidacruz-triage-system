package com.vidacruz.triage.infrastructure.web.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The expected API key, compared in constant time. Both values are hashed with SHA-256 before
 * {@link MessageDigest#isEqual}, so the comparison takes the same time whatever the content and the length of the
 * presented key: neither a matching prefix nor the length of the real key can be measured.
 */
final class ApiKey {

	private final byte[] expectedHash;

	ApiKey(String expected) {
		if (expected == null || expected.isBlank()) {
			throw new IllegalArgumentException("The API key must not be blank");
		}
		this.expectedHash = sha256(expected);
	}

	boolean matches(String presented) {
		if (presented == null) {
			return false;
		}
		return MessageDigest.isEqual(expectedHash, sha256(presented));
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandatory in every Java platform
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public String toString() {
		return "ApiKey[****]";
	}

}
