/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services;

import ai.kompile.app.services.GraphExtractionConfigService.GraphExtractionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphExtractionConfigServiceTest {

    @TempDir
    Path tempDir;

    private GraphExtractionConfigService service;

    @BeforeEach
    void setUp() {
        service = new GraphExtractionConfigService(tempDir.toString());
        service.init();
    }

    // --- defaults ---

    @Test
    void initCreatesDefaultConfig() {
        GraphExtractionConfig config = service.getConfig();
        assertNotNull(config);
        assertFalse(config.enabled);
        assertEquals(10, config.batchSize);
        assertEquals("LENIENT", config.schemaEnforcement);
        assertEquals(20, config.maxEntitiesPerChunk);
        assertEquals(30, config.maxRelationshipsPerChunk);
    }

    @Test
    void defaultConfigHasModelSettings() {
        GraphExtractionConfig config = service.getConfig();
        assertEquals("default", config.extractionModelProvider);
        assertNull(config.extractionModelName);
        assertEquals(0.0, config.extractionTemperature);
        assertEquals(4096, config.extractionMaxTokens);
        assertNull(config.customExtractionPrompt);
    }

    @Test
    void defaultConfigHasDeduplicationSettings() {
        GraphExtractionConfig config = service.getConfig();
        assertTrue(config.deduplicationEnabled);
        assertEquals(0.85, config.similarityThreshold);
    }

    @Test
    void defaultConfigHasNeo4jSettings() {
        GraphExtractionConfig config = service.getConfig();
        assertFalse(config.neo4jEnabled);
        assertEquals("bolt://localhost:7687", config.neo4jUri);
        assertEquals("neo4j", config.neo4jUsername);
        assertEquals("neo4j", config.neo4jDatabase);
    }

    @Test
    void defaultConfigWritesJsonFile() {
        Path configFile = tempDir.resolve("config").resolve("graph-extraction-config.json");
        assertTrue(Files.exists(configFile));
    }

    // --- isEnabled / getBatchSize ---

    @Test
    void isEnabledReturnsFalseByDefault() {
        assertFalse(service.isEnabled());
    }

    @Test
    void getBatchSizeReturnsDefaultOf10() {
        assertEquals(10, service.getBatchSize());
    }

    // --- updateConfig ---

    @Test
    void updateConfigMergesEnabledField() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.enabled = true;
        service.updateConfig(update);

        assertTrue(service.isEnabled());
        // Other fields unchanged
        assertEquals(10, service.getBatchSize());
    }

    @Test
    void updateConfigClampsBatchSizeMin() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.batchSize = -5;
        service.updateConfig(update);

        assertEquals(1, service.getBatchSize());
    }

    @Test
    void updateConfigClampsBatchSizeMax() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.batchSize = 100;
        service.updateConfig(update);

        assertEquals(50, service.getBatchSize());
    }

    @Test
    void updateConfigClampsMaxEntitiesPerChunk() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.maxEntitiesPerChunk = 0;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(1, result.maxEntitiesPerChunk);

        update.maxEntitiesPerChunk = 500;
        result = service.updateConfig(update);
        assertEquals(100, result.maxEntitiesPerChunk);
    }

    @Test
    void updateConfigClampsMaxRelationshipsPerChunk() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.maxRelationshipsPerChunk = 0;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(1, result.maxRelationshipsPerChunk);

        update.maxRelationshipsPerChunk = 500;
        result = service.updateConfig(update);
        assertEquals(200, result.maxRelationshipsPerChunk);
    }

    @Test
    void updateConfigSetsSchemaEnforcement() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.schemaEnforcement = "STRICT";
        service.updateConfig(update);

        assertEquals("STRICT", service.getConfig().schemaEnforcement);
    }

    @Test
    void updateConfigSetsEntityTypes() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.entityTypes = List.of("PERSON", "ORGANIZATION", "LOCATION");
        service.updateConfig(update);

        assertEquals(3, service.getConfig().entityTypes.size());
        assertTrue(service.getConfig().entityTypes.contains("PERSON"));
    }

    @Test
    void updateConfigSetsRelationshipTypes() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.relationshipTypes = List.of("WORKS_AT", "AUTHORED_BY");
        service.updateConfig(update);

        assertEquals(2, service.getConfig().relationshipTypes.size());
    }

    @Test
    void updateConfigClampsExtractionTemperature() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.extractionTemperature = -1.0;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(0.0, result.extractionTemperature);

        update.extractionTemperature = 5.0;
        result = service.updateConfig(update);
        assertEquals(2.0, result.extractionTemperature);
    }

    @Test
    void updateConfigClampsExtractionMaxTokens() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.extractionMaxTokens = 10;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(100, result.extractionMaxTokens);

        update.extractionMaxTokens = 100000;
        result = service.updateConfig(update);
        assertEquals(32000, result.extractionMaxTokens);
    }

    @Test
    void updateConfigClearEmptyCustomPrompt() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.customExtractionPrompt = "Custom prompt";
        service.updateConfig(update);
        assertEquals("Custom prompt", service.getConfig().customExtractionPrompt);

        update.customExtractionPrompt = "";
        service.updateConfig(update);
        assertNull(service.getConfig().customExtractionPrompt);
    }

    @Test
    void updateConfigClampsSimilarityThreshold() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.similarityThreshold = -0.5;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(0.0, result.similarityThreshold);

        update.similarityThreshold = 1.5;
        result = service.updateConfig(update);
        assertEquals(1.0, result.similarityThreshold);
    }

    @Test
    void updateConfigSetsNeo4jSettings() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.neo4jEnabled = true;
        update.neo4jUri = "bolt://graph-db:7687";
        update.neo4jUsername = "admin";
        update.neo4jPassword = "secret";
        update.neo4jDatabase = "mydb";
        service.updateConfig(update);

        GraphExtractionConfig config = service.getConfig();
        assertTrue(config.neo4jEnabled);
        assertEquals("bolt://graph-db:7687", config.neo4jUri);
        assertEquals("admin", config.neo4jUsername);
        assertEquals("mydb", config.neo4jDatabase);
    }

    @Test
    void updateConfigIgnoresNullFields() {
        // First set everything
        GraphExtractionConfig fullUpdate = new GraphExtractionConfig();
        fullUpdate.enabled = true;
        fullUpdate.batchSize = 20;
        fullUpdate.schemaEnforcement = "STRICT";
        service.updateConfig(fullUpdate);

        // Now update with only batchSize, rest null
        GraphExtractionConfig partialUpdate = new GraphExtractionConfig();
        partialUpdate.batchSize = 5;
        service.updateConfig(partialUpdate);

        GraphExtractionConfig config = service.getConfig();
        assertTrue(config.enabled); // unchanged
        assertEquals(5, config.batchSize); // updated
        assertEquals("STRICT", config.schemaEnforcement); // unchanged
    }

    // --- resetToDefaults ---

    @Test
    void resetToDefaultsRestoresAllFields() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.enabled = true;
        update.batchSize = 50;
        update.schemaEnforcement = "STRICT";
        update.extractionTemperature = 1.5;
        service.updateConfig(update);

        GraphExtractionConfig reset = service.resetToDefaults();
        assertFalse(reset.enabled);
        assertEquals(10, reset.batchSize);
        assertEquals("LENIENT", reset.schemaEnforcement);
        assertEquals(0.0, reset.extractionTemperature);
    }

    // --- persistence across instances ---

    @Test
    void configPersistsAcrossServiceInstances() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.enabled = true;
        update.batchSize = 25;
        update.entityTypes = List.of("PERSON", "DOCUMENT");
        service.updateConfig(update);

        // Create new service instance pointing to same directory
        GraphExtractionConfigService service2 = new GraphExtractionConfigService(tempDir.toString());
        service2.init();

        GraphExtractionConfig loaded = service2.getConfig();
        assertTrue(loaded.enabled);
        assertEquals(25, loaded.batchSize);
        assertEquals(2, loaded.entityTypes.size());
    }

    // --- copy behavior ---

    @Test
    void getConfigReturnsCopyNotReference() {
        GraphExtractionConfig config1 = service.getConfig();
        config1.enabled = true;
        config1.batchSize = 99;

        GraphExtractionConfig config2 = service.getConfig();
        assertFalse(config2.enabled);
        assertEquals(10, config2.batchSize);
    }

    @Test
    void copyMasksNeo4jPassword() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.neo4jPassword = "my-secret-password";
        service.updateConfig(update);

        GraphExtractionConfig config = service.getConfig();
        assertEquals("********", config.neo4jPassword);
    }

    @Test
    void copyWithPasswordPreservesActualPassword() {
        GraphExtractionConfig defaults = GraphExtractionConfig.defaults();
        defaults.neo4jPassword = "real-password";

        GraphExtractionConfig copy = defaults.copy();
        assertEquals("********", copy.neo4jPassword);

        GraphExtractionConfig copyWithPw = defaults.copyWithPassword();
        assertEquals("real-password", copyWithPw.neo4jPassword);
    }

    // --- getExtractionModelDisplayName ---

    @Test
    void displayNameForDefaultProvider() {
        GraphExtractionConfig config = GraphExtractionConfig.defaults();
        assertEquals("Default (System LLM)", config.getExtractionModelDisplayName());
    }

    @Test
    void displayNameForNullProvider() {
        GraphExtractionConfig config = new GraphExtractionConfig();
        config.extractionModelProvider = null;
        assertEquals("Default (System LLM)", config.getExtractionModelDisplayName());
    }

    @Test
    void displayNameForCustomProvider() {
        GraphExtractionConfig config = new GraphExtractionConfig();
        config.extractionModelProvider = "openai";
        config.extractionModelName = "gpt-4o";
        assertEquals("openai/gpt-4o", config.getExtractionModelDisplayName());
    }

    @Test
    void displayNameForProviderWithoutModel() {
        GraphExtractionConfig config = new GraphExtractionConfig();
        config.extractionModelProvider = "anthropic";
        config.extractionModelName = null;
        assertEquals("anthropic/default", config.getExtractionModelDisplayName());
    }

    // --- corrupt file recovery ---

    @Test
    void initRecoverFromCorruptConfigFile() throws IOException {
        // Write corrupt JSON
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("graph-extraction-config.json"), "{ invalid json }}}");

        // New service should recover to defaults
        GraphExtractionConfigService corruptService = new GraphExtractionConfigService(tempDir.toString());
        corruptService.init();

        GraphExtractionConfig config = corruptService.getConfig();
        assertNotNull(config);
        assertFalse(config.enabled);
        assertEquals(10, config.batchSize);
    }
}
