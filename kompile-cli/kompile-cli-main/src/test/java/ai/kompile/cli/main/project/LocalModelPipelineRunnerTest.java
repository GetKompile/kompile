/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelPipelineRunnerTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearRuntimePool() {
        PipelineRuntimeSupervisor.clearForTests();
    }

    @Test
    void requestScopedModelRuntimeStagesAndInjectsTheSelectedVlmArtifact() throws Exception {
        Path modelDirectory = tempDir.resolve("data/models/vlm-pipelines/custom-vlm");
        Files.createDirectories(modelDirectory);
        Path descriptor = modelDirectory.resolve("pipeline.json");
        Files.writeString(descriptor, "{}");

        Path staging = tempDir.resolve("fake-model-staging");
        Files.writeString(staging, """
                #!/bin/sh
                echo 'MODEL_BOOTSTRAP_RESULT:{"modelPath":"%s","modelType":"vlm_pipeline","disposition":"staged"}'
                """.formatted(descriptor));
        assertTrue(staging.toFile().setExecutable(true));

        Map<String, Object> modelRuntime = new LinkedHashMap<>();
        modelRuntime.put("autoBootstrap", true);
        modelRuntime.put("localPath", descriptor.toString());
        modelRuntime.put("format", "vlm");
        modelRuntime.put("type", "vlm_pipeline");
        modelRuntime.put("stagingExecutable", staging.toString());
        Map<String, Object> processor = new LinkedHashMap<>();
        processor.put("modelRuntime", modelRuntime);
        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                new LocalCrawlCapabilities.ResolvedPipeline(
                        "staged-vlm", "VLM", "pdf", "sentence", 0, 0,
                        Map.of("modelId", "custom-vlm"), processor);

        LocalModelPipelineRunner.ResolvedModelContext resolved =
                LocalModelPipelineRunner.resolveBoundModels(tempDir, pipeline, null);

        assertEquals(descriptor.toAbsolutePath().normalize().toString(),
                resolved.resolvedModels().get("default").get("modelPath"));
        assertEquals("custom-vlm",
                resolved.resolvedModels().get("default").get("modelId"));
        Map<String, Object> inventory = LocalProjectModelBootstrap.inventory(tempDir).stream()
                .filter(model -> "custom-vlm".equals(model.get("id")))
                .findFirst().orElseThrow();
        assertEquals(true, inventory.get("ready"));
        assertEquals("VLM", inventory.get("role"));
    }

    @Test
    void roleBindingsProvisionAndReuseMultipleProjectModels() throws Exception {
        Path models = tempDir.resolve("data/models/llm");
        Files.createDirectories(models);
        Path generator = models.resolve("generator.gguf");
        Path embedding = models.resolve("embedding.gguf");
        Files.writeString(generator, "generator");
        Files.writeString(embedding, "embedding");
        Path staging = tempDir.resolve("fake-model-registry-staging");
        Files.writeString(staging, """
                #!/bin/sh
                case "$*" in
                  *generator-model*) model='%s' ;;
                  *embedding-model*) model='%s' ;;
                  *) exit 3 ;;
                esac
                printf 'MODEL_BOOTSTRAP_RESULT:{"modelPath":"%%s","modelType":"llm_ggml","disposition":"staged"}\\n' "$model"
                """.formatted(generator, embedding));
        assertTrue(staging.toFile().setExecutable(true));

        Map<String, Object> registeredModels = new LinkedHashMap<>();
        registeredModels.put("generator-config", Map.of(
                "id", "generator-config",
                "modelId", "generator-model",
                "role", "generator",
                "source", "catalog",
                "runtime", Map.of("stagingExecutable", staging.toString())));
        registeredModels.put("embedding-config", Map.of(
                "id", "embedding-config",
                "modelId", "embedding-model",
                "role", "embedding",
                "source", "catalog",
                "runtime", Map.of("stagingExecutable", staging.toString())));
        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                new LocalCrawlCapabilities.ResolvedPipeline(
                        "model-bound", "CUSTOM", "text", "no-op", 0, 0,
                        Map.of("modelBindings", Map.of(
                                "generator", "generator-config",
                                "embedding", "embedding-config")),
                        Map.of("registeredModelDefinitions", registeredModels));

        LocalModelPipelineRunner.ResolvedModelContext first =
                LocalModelPipelineRunner.resolveBoundModels(tempDir, pipeline, null);
        LocalModelPipelineRunner.ResolvedModelContext second =
                LocalModelPipelineRunner.resolveBoundModels(tempDir, pipeline, null);

        assertEquals(generator.toString(),
                first.resolvedModels().get("generator").get("modelPath"));
        assertEquals(embedding.toString(),
                first.resolvedModels().get("embedding").get("modelPath"));
        assertEquals(false, second.resolvedModels().get("generator").get("bootstrapped"));
        assertEquals(false, second.resolvedModels().get("embedding").get("bootstrapped"));
    }

    @Test
    void definitionModelSetIdUsesTheSameProjectBootstrapResolver() {
        UnifiedPipelineDefinition definition = UnifiedPipelineDefinition.builder()
                .pipelineId("missing-model")
                .modelSetId("missing-model")
                .modelDefinitions(Map.of("missing-model", Map.of(
                        "id", "missing-model",
                        "runtime", Map.of("autoBootstrap", false))))
                .build();
        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                new LocalCrawlCapabilities.ResolvedPipeline(
                        "missing-model", "CUSTOM", "text", "no-op", 0, 0, Map.of());

        IOException failure = assertThrows(IOException.class, () ->
                LocalModelPipelineRunner.resolveBoundModels(tempDir, pipeline, definition));

        assertTrue(failure.getMessage().contains("autoBootstrap is false"), failure.getMessage());
    }

    @Test
    void callerSuppliedUnifiedPipelineExecutesInTheManagedRuntime() throws Exception {
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
