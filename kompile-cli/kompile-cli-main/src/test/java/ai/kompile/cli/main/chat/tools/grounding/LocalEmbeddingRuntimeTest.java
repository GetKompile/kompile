/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LocalEmbeddingRuntimeTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @TempDir
    Path projectRoot;

    @Test
    void fingerprintChangesWhenSameSizeArtifactIsReplacedWithPreservedMtime() throws Exception {
        Path model = writeModelRegistry();
        String first = LocalEmbeddingRuntime.modelFingerprint(projectRoot, mapper);
        FileTime originalTime = Files.getLastModifiedTime(model);

        Files.writeString(model, "bbbb", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(model, originalTime);

        String second = LocalEmbeddingRuntime.modelFingerprint(projectRoot, mapper);
        assertNotEquals(first, second);
    }

    @Test
    void explicitLocalPathsBypassRegistryModelIdSymlink() throws Exception {
        Path model = writeModelRegistry();
        Path outside = Files.createTempDirectory(projectRoot.getParent(), "foreign-model-");
        Files.writeString(outside.resolve("model.sdz"), "aaaa", StandardCharsets.UTF_8);
        Files.writeString(outside.resolve("vocab.txt"), "token", StandardCharsets.UTF_8);
        Files.createSymbolicLink(projectRoot.resolve("data/models/test-encoder"), outside);

        Map<String, String> config = LocalEmbeddingRuntime.modelConfig(projectRoot, mapper);
        assertEquals(model.toRealPath().toString(), config.get("modelPath"));
        assertEquals("LOCAL_PROJECT", config.get("modelSource"));
    }

    @Test
    void fingerprintUsesTheChildLoadersTokenizerJsonFallback() throws Exception {
        Path model = writeModelRegistry();
        Path encoder = model.getParent();
        Files.delete(encoder.resolve("vocab.txt"));
        Files.writeString(encoder.resolve("tokenizer.json"), "{}", StandardCharsets.UTF_8);

        assertNotNull(LocalEmbeddingRuntime.modelFingerprint(projectRoot, mapper));
    }

    private Path writeModelRegistry() throws Exception {
        Path models = Files.createDirectories(projectRoot.resolve("data/models"));
        Path encoder = Files.createDirectories(models.resolve("encoders/test-encoder"));
        Path model = encoder.resolve("model.sdz");
        Files.writeString(model, "aaaa", StandardCharsets.UTF_8);
        Files.writeString(encoder.resolve("vocab.txt"), "token", StandardCharsets.UTF_8);
        Files.writeString(models.resolve("registry.json"), """
                {
                  "models": {
                    "test-encoder": {
                      "type": "dense_encoder",
                      "path": "encoders/test-encoder",
                      "model_file": "model.sdz",
                      "vocab_file": "vocab.txt",
                      "status": "staged"
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("kompile.project.json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "local-rag-test",
                  "name": "local-rag-test",
                  "models": [{
                    "id": "test-encoder",
                    "modelId": "test-encoder",
                    "registryModelId": "test-encoder",
                    "role": "ENCODER",
                    "lifecycle": "ACTIVE",
                    "metadata": {"registry.type": "dense_encoder"}
                  }]
                }
                """, StandardCharsets.UTF_8);
        return model;
    }
}
