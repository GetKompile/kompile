/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.learning.subprocess;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Versioned whole-graph learning request consumed by {@link LearningSubprocessMain}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortableGraphLearningSubprocessArgs(
        int protocolVersion,
        String operation,
        String crawlJobId,
        Long factSheetId,
        String inputGraphPath,
        String outputGraphPath,
        boolean embeddingEnabled,
        String embeddingAlgorithm,
        int embeddingDim,
        int embeddingEpochs,
        double embeddingLearningRate,
        int embeddingWarmStartEpochs,
        long embeddingSeed,
        boolean reasoningEnabled,
        int pslSteps,
        int mebnEpochs,
        int consensusRounds,
        double consensusWeight,
        int maxRelationTypes
) {
    public static final int PROTOCOL_VERSION = 1;
    public static final String OPERATION = "PORTABLE_GRAPH_LEARNING";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Path writeToTempFile() throws IOException {
        Path file = Files.createTempFile("kompile-portable-graph-learning-", ".json");
        Files.writeString(file, MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
        return file;
    }

    public static PortableGraphLearningSubprocessArgs readFromFile(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), PortableGraphLearningSubprocessArgs.class);
    }
}
