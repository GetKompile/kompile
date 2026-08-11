/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelPipelineRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void documentModelSubprocessIsPackagedWithTheOfflineWorker() {
        assertTrue(LocalModelPipelineRunner.documentModelWorkerAvailable());
    }

    @Test
    void callerSuppliedUnifiedPipelineExecutesInTheServingSubprocess() throws Exception {
        Path document = tempDir.resolve("input.txt");
        Files.writeString(document, "source body");
        Map<String, Object> pipelineSpec = new LinkedHashMap<>();
        pipelineSpec.put("@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline");
        pipelineSpec.put("id", "offline-noop");
        pipelineSpec.put("steps", List.of());
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("pipelineId", "offline-noop");
        definition.put("displayName", "Offline no-op");
        definition.put("kind", "GENERIC");
        definition.put("topology", "SEQUENCE");
        definition.put("pipelineSpec", pipelineSpec);
        definition.put("enabled", true);

        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                new LocalCrawlCapabilities.ResolvedPipeline(
                        "custom-model", "CUSTOM", "text", "no-op", 0, 0,
                        Map.of("pipelineDefinition", definition));

        String result = LocalModelPipelineRunner.extract(
                tempDir, document, pipeline, "loaded body");

        assertEquals("loaded body", result);
    }
}
