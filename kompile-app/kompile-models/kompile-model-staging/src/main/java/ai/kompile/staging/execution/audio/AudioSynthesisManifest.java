/*
 *   Copyright 2026 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.staging.execution.audio;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable public envelope for one completed generated audio file.
 */
public record AudioSynthesisManifest(
        UUID runId,
        String artifactReference,
        String mediaType,
        String contentHash,
        long byteLength,
        double confidence,
        String modelId,
        String modelVersion,
        String configurationVersion,
        Map<String, Object> configurationEvidence
) {
    public AudioSynthesisManifest {
        Objects.requireNonNull(runId, "runId");
        artifactReference = requireNonBlank(artifactReference, "artifactReference");
        if (!artifactReference.equals(runId.toString())) {
            throw new IllegalArgumentException("artifactReference must be the opaque run ID");
        }
        mediaType = requireNonBlank(mediaType, "mediaType");
        if (!mediaType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            throw new IllegalArgumentException("mediaType must be audio/*");
        }
        contentHash = requireNonBlank(contentHash, "contentHash");
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 value");
        }
        if (byteLength < 1) {
            throw new IllegalArgumentException("byteLength must be positive");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        modelId = requireNonBlank(modelId, "modelId");
        modelVersion = requireNonBlank(modelVersion, "modelVersion");
        configurationVersion = requireNonBlank(configurationVersion, "configurationVersion");
        configurationEvidence = configurationEvidence == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(configurationEvidence));
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
