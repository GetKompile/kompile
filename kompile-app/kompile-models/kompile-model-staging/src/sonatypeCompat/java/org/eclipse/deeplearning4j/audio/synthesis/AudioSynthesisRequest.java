/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package org.eclipse.deeplearning4j.audio.synthesis;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable request shape retained when the configured snapshot lacks synthesis support. */
public final class AudioSynthesisRequest {
    private final UUID runId;
    private final String text;
    private final String voice;
    private final String language;
    private final Map<String, Object> configuration;

    public AudioSynthesisRequest(
            UUID runId,
            String text,
            String voice,
            String language,
            Map<String, Object> configuration) {
        this.runId = Objects.requireNonNull(runId, "runId");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        this.text = text;
        this.voice = voice == null ? "" : voice;
        this.language = language == null ? "" : language;
        this.configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }

    public UUID getRunId() {
        return runId;
    }

    public String getText() {
        return text;
    }

    public String getVoice() {
        return voice;
    }

    public String getLanguage() {
        return language;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }
}
