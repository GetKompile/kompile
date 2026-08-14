/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package org.eclipse.deeplearning4j.audio.synthesis;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Completed-file shape retained for repository compatibility. */
public final class GeneratedAudioFile {
    private final Path completedFile;
    private final String mediaType;
    private final String modelId;
    private final String modelVersion;
    private final String configurationVersion;
    private final double confidence;
    private final Map<String, Object> configurationEvidence;

    public GeneratedAudioFile(
            Path completedFile,
            String mediaType,
            String modelId,
            String modelVersion,
            String configurationVersion,
            double confidence,
            Map<String, Object> configurationEvidence) {
        this.completedFile = Objects.requireNonNull(completedFile, "completedFile");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        this.configurationVersion =
                Objects.requireNonNull(configurationVersion, "configurationVersion");
        this.confidence = confidence;
        this.configurationEvidence = configurationEvidence == null
                ? Map.of()
                : Map.copyOf(configurationEvidence);
    }

    public Path getCompletedFile() {
        return completedFile;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getModelId() {
        return modelId;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getConfigurationVersion() {
        return configurationVersion;
    }

    public double getConfidence() {
        return confidence;
    }

    public Map<String, Object> getConfigurationEvidence() {
        return configurationEvidence;
    }
}
