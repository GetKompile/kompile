/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalModelPipelineRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void documentModelSubprocessResolvesFromInstalledDedicatedNativeWorker() throws Exception {
        Path bin = tempDir.resolve("bin");
        Path lib = tempDir.resolve("lib");
        Files.createDirectories(bin);
        Files.createDirectories(lib);
        Path worker = bin.resolve("kompile-vlm-test");
        Files.writeString(worker, "#!/bin/sh\nexit 0\n");
        assertTrue(worker.toFile().setExecutable(true));

        String previous = System.getProperty("kompile.install.dir");
        try {
            System.setProperty("kompile.install.dir", tempDir.toString());
            LocalModelPipelineRunner.DocumentModelWorkerStatus status =
                    LocalModelPipelineRunner.documentModelWorkerStatus(tempDir, null);

            assertTrue(status.available());
            assertFalse(status.unifiedExecutable());
            assertEquals(worker.toAbsolutePath().normalize().toString(), status.executable());
            assertEquals("component-registry:kompile-vlm-test", status.source());
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.install.dir");
            } else {
                System.setProperty("kompile.install.dir", previous);
            }
        }
    }

    @Test
    void requestConfiguresFolderRelativeWorkerAfterMcpStartup() throws Exception {
        Path worker = tempDir.resolve("workers/document-model");
        Files.createDirectories(worker.getParent());
        Files.writeString(worker, "#!/bin/sh\nexit 0\n");
        assertTrue(worker.toFile().setExecutable(true));
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.putObject("runtimeConfig")
                .put("documentModelExecutable", "workers/document-model")
                .put("documentModelExecutableMode", "UNIFIED");

        assertNull(LocalModelPipelineRunner.validateWorkerConfiguration(tempDir, request));
        LocalModelPipelineRunner.DocumentModelWorkerStatus status =
                LocalModelPipelineRunner.documentModelWorkerStatus(tempDir, request);

        assertTrue(status.available());
        assertTrue(status.unifiedExecutable());
        assertEquals(worker.toAbsolutePath().normalize().toString(), status.executable());
        assertEquals("runtimeConfig.documentModelExecutable", status.source());
    }

    @Test
    void invalidRequestScopedWorkerIsRejectedBeforeExecution() {
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.putObject("runtimeConfig")
                .put("documentModelExecutable", "missing/document-model");

        String error = LocalModelPipelineRunner.validateWorkerConfiguration(tempDir, request);

        assertTrue(error.contains("does not exist"), error);
    }

    @Test
    void nativeParentRejectsRequestScopedExecutableJarWorker() throws Exception {
        Path jar = tempDir.resolve("document-model-exec.jar");
        Files.writeString(jar, "jar");
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.putObject("runtimeConfig")
                .put("documentModelExecutable", jar.toString());

        assertNull(LocalModelPipelineRunner.validateWorkerConfiguration(
                tempDir, request, false));
        String error = LocalModelPipelineRunner.validateWorkerConfiguration(
                tempDir, request, true);

        assertTrue(error.contains("native child executable"), error);
    }

    @Test
    void requestScopedModelRuntimeStagesAndInjectsTheSelectedVlmArtifact() throws Exception {
        Path document = tempDir.resolve("input.pdf");
        Files.writeString(document, "fixture");
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

        Path capturedArgs = tempDir.resolve("captured-vlm-args.json");
        Path worker = tempDir.resolve("capturing-vlm-worker");
        Files.writeString(worker, """
                #!/bin/sh
                cp "$1" "%s"
                echo 'INGEST_MSG:{"type":"COMPLETED","message":"VLM_RESULTS:{\\"pages\\":[{\\"text\\":\\"model runtime text\\"}]}"}'
                """.formatted(capturedArgs));
        assertTrue(worker.toFile().setExecutable(true));

        Map<String, Object> modelRuntime = new LinkedHashMap<>();
        modelRuntime.put("autoBootstrap", true);
        modelRuntime.put("localPath", descriptor.toString());
        modelRuntime.put("format", "vlm");
        modelRuntime.put("type", "vlm_pipeline");
        modelRuntime.put("stagingExecutable", staging.toString());
        Map<String, Object> processor = new LinkedHashMap<>();
        processor.put("adapter", "vlm-test");
        processor.put("documentModelExecutable", worker.toString());
        processor.put("modelRuntime", modelRuntime);
        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                new LocalCrawlCapabilities.ResolvedPipeline(
                        "staged-vlm", "VLM", "pdf", "sentence", 0, 0,
                        Map.of("modelId", "custom-vlm"), processor);

        String extracted = LocalModelPipelineRunner.extract(
                tempDir, document, pipeline, "");

        assertEquals("model runtime text", extracted);
        ObjectNode captured = (ObjectNode) new ObjectMapper().readTree(capturedArgs.toFile());
        assertEquals(document.toAbsolutePath().normalize().toString(),
                captured.path("filePath").asText());
        assertEquals("custom-vlm", captured.path("modelId").asText());
        assertEquals("LOCAL", captured.path("modelSourceType").asText());
        assertEquals(descriptor.toAbsolutePath().normalize().toString(),
                captured.path("modelIdentifier").asText());
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
    void documentModelFailureIncludesProtocolFailureAndStderrDetail() throws Exception {
        Path document = tempDir.resolve("input.pdf");
        Files.writeString(document, "fixture");
        Path worker = tempDir.resolve("failing-vlm-worker");
        Files.writeString(worker, """
                #!/bin/sh
                echo 'INGEST_MSG:{"type":"FAILED","message":"failed","errorMessage":"Document model pipeline failed"}'
                echo 'specific native worker diagnostic' >&2
                exit 7
                """);
        assertTrue(worker.toFile().setExecutable(true));

        LocalCrawlCapabilities.ResolvedPipeline pipeline =
                new LocalCrawlCapabilities.ResolvedPipeline(
                        "failing-vlm", "VLM", "pdf", "sentence", 0, 0,
                        Map.of("documentModelExecutable", worker.toString()),
                        Map.of("adapter", "vlm-test"));

        IOException failure = assertThrows(IOException.class, () ->
                LocalModelPipelineRunner.extract(tempDir, document, pipeline, ""));

        assertTrue(failure.getMessage().contains("Document model pipeline failed"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("specific native worker diagnostic"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("exit code: 7"), failure.getMessage());
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

        String previousJar = System.getProperty("kompile.pipeline.serving.jar");
        String result;
        try {
            System.setProperty("kompile.pipeline.serving.jar", pipelineServingExecJar().toString());
            result = LocalModelPipelineRunner.extract(
                    tempDir, document, pipeline, "loaded body");
        } finally {
            if (previousJar == null) {
                System.clearProperty("kompile.pipeline.serving.jar");
            } else {
                System.setProperty("kompile.pipeline.serving.jar", previousJar);
            }
        }

        assertEquals("loaded body", result);
    }

    private Path pipelineServingExecJar() throws IOException {
        Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path target = cursor.resolve("kompile-app")
                    .resolve("kompile-data")
                    .resolve("kompile-pipelines")
                    .resolve("kompile-pipeline-serving")
                    .resolve("target");
            if (!Files.isDirectory(target)) continue;
            try (var files = Files.list(target)) {
                Path jar = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                        .findFirst().orElse(null);
                if (jar != null) return jar.toAbsolutePath().normalize();
            }
        }
        throw new IOException("Build kompile-pipeline-serving before running the unified subprocess test.");
    }
}
