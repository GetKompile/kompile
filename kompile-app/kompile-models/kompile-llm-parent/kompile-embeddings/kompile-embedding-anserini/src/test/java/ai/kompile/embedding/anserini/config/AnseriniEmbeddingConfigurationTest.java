/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.embedding.anserini.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnseriniEmbeddingConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void projectConfigTakesPrecedenceOverDataDirectoryConfig() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(tempDir.resolve("kompile.project.json"), "{}");
        Path projectConfig = Files.createDirectories(tempDir.resolve("config"))
                .resolve("embedding-anserini-config.json");
        Files.writeString(projectConfig, """
                {"enabled":true,"modelIdentifier":"multilingual-e5-small",
                 "baseOptimalBatchSize":4,"baseMaxBatchSize":8,"absoluteMaxBatchSize":8}
                """);
        Path legacyDataConfig = Files.createDirectories(dataDir.resolve("config"))
                .resolve("embedding-anserini-config.json");
        Files.writeString(legacyDataConfig,
                "{\"enabled\":true,\"modelIdentifier\":\"bge-base-en-v1.5\"}");

        var properties = new AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties(
                dataDir.toString());
        properties.init();

        assertEquals(projectConfig,
                AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties
                        .resolveConfigFilePath(dataDir.toString()));
        assertEquals("multilingual-e5-small", properties.getModelIdentifier());
        assertTrue(properties.isEnabled());
        assertEquals(4, properties.getBaseOptimalBatchSize());
        assertEquals(8, properties.getBaseMaxBatchSize());
        assertEquals(8, properties.getAbsoluteMaxBatchSize());
    }
}
