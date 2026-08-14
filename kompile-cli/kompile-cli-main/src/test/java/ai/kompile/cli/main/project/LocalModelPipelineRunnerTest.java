/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
