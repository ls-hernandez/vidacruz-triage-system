package com.vidacruz.assistant.domain;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** The clinical protocols the assistant may answer from: the only valid sources (RN-05). */
public final class Corpus {

	private static final Pattern PROTOCOL_ID = Pattern.compile("PROTO-\\d{3}");

	private final List<ProtocolFragment> ordered;
	private final Map<String, ProtocolFragment> fragmentsById;
	private final Set<Source> sources;

	/** @throws IllegalArgumentException if the corpus is empty or malformed (duplicate ids, blank texts…) */
	public Corpus(List<ProtocolFragment> fragments) {
		if (fragments.isEmpty()) {
			throw new IllegalArgumentException("The corpus has no fragments");
		}
		Map<String, ProtocolFragment> byId = new LinkedHashMap<>();
		Set<Source> allSources = new HashSet<>();
		for (ProtocolFragment fragment : fragments) {
			if (!PROTOCOL_ID.matcher(fragment.protocol()).matches()) {
				throw new IllegalArgumentException("Invalid protocol id: " + fragment.protocol());
			}
			if (fragment.title().isBlank() || fragment.section().isBlank() || fragment.text().isBlank()) {
				throw new IllegalArgumentException("Blank title, section or text in " + fragment.id());
			}
			if (byId.putIfAbsent(fragment.id(), fragment) != null) {
				throw new IllegalArgumentException("Duplicate fragment id: " + fragment.id());
			}
			allSources.add(fragment.source());
		}
		this.fragmentsById = Map.copyOf(byId);
		this.sources = Set.copyOf(allSources);
		this.ordered = List.copyOf(byId.values());
	}

	/** Fragments in corpus order. */
	public List<ProtocolFragment> fragments() {
		return ordered;
	}

	public Optional<ProtocolFragment> findById(String id) {
		return Optional.ofNullable(fragmentsById.get(id));
	}

	/** Whether {@code protocol} + {@code section} is a real section of the corpus. */
	public boolean contains(Source source) {
		return sources.contains(source);
	}

}
