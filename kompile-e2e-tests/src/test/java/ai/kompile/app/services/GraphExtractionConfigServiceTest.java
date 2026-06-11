/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services;

import ai.kompile.app.services.GraphExtractionConfigService.GraphExtractionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GraphExtractionConfigService} — defaults, update/merge with
 * clamping, reset, persistence, isEnabled, getBatchSize, and GraphExtractionConfig
 * inner class (copy, copyWithPassword, getExtractionModelDisplayName).
 */
class GraphExtractionConfigServiceTest {

    @TempDir
    Path tempDir;

    private GraphExtractionConfigService service;

    @BeforeEach
    void setUp() {
        service = new GraphExtractionConfigService(tempDir.toString());
        service.init();
    }

    // ─── Default state ──────────────────────────────────────────────

    @Test
    void getConfig_returnsDefaults() {
        GraphExtractionConfig config = service.getConfig();
        assertNotNull(config);
        assertFalse(config.enabled);
        assertEquals(10, config.batchSize);
        assertEquals("LENIENT", config.schemaEnforcement);
        assertEquals(List.of(), config.entityTypes);
        assertEquals(List.of(), config.relationshipTypes);
        assertEquals(20, config.maxEntitiesPerChunk);
        assertEquals(30, config.maxRelationshipsPerChunk);
    }

    @Test
    void defaults_modelSettings() {
        GraphExtractionConfig config = service.getConfig();
        assertEquals("default", config.extractionModelProvider);
        assertNull(config.extractionModelName);
        assertEquals(0.0, config.extractionTemperature);
        assertEquals(4096, config.extractionMaxTokens);
        assertNull(config.customExtractionPrompt);
    }

    @Test
    void defaults_autoAcceptAndDedup() {
        GraphExtractionConfig config = service.getConfig();
        assertTrue(config.deduplicationEnabled);
        assertEquals(0.85, config.similarityThreshold);
    }

    @Test
    void defaults_neo4j() {
        GraphExtractionConfig config = service.getConfig();
        assertFalse(config.neo4jEnabled);
        assertEquals("bolt://localhost:7687", config.neo4jUri);
        assertEquals("neo4j", config.neo4jUsername);
        assertEquals("neo4j", config.neo4jDatabase);
    }

    @Test
    void isEnabled_defaultsFalse() {
        assertFalse(service.isEnabled());
    }

    @Test
    void getBatchSize_defaults10() {
        assertEquals(10, service.getBatchSize());
    }

    // ─── Update configuration ───────────────────────────────────────

    @Test
    void updateConfig_mergesNonNullFields() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.enabled = true;
        update.batchSize = 20;

        GraphExtractionConfig result = service.updateConfig(update);

        assertTrue(result.enabled);
        assertEquals(20, result.batchSize);
        // Unchanged fields keep defaults
        assertEquals("LENIENT", result.schemaEnforcement);
    }

    @Test
    void updateConfig_nullFieldsIgnored() {
        // First set enabled=true
        GraphExtractionConfig update1 = new GraphExtractionConfig();
        update1.enabled = true;
        service.updateConfig(update1);

        // Now update only batchSize (enabled is null, should not change)
        GraphExtractionConfig update2 = new GraphExtractionConfig();
        update2.batchSize = 5;
        GraphExtractionConfig result = service.updateConfig(update2);

        assertTrue(result.enabled); // still true
        assertEquals(5, result.batchSize);
    }

    @Test
    void updateConfig_clampsBatchSize_min() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.batchSize = -5;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(1, result.batchSize);
    }

    @Test
    void updateConfig_clampsBatchSize_max() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.batchSize = 999;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(50, result.batchSize);
    }

    @Test
    void updateConfig_clampsMaxEntitiesPerChunk() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.maxEntitiesPerChunk = 0;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(1, result.maxEntitiesPerChunk);

        update.maxEntitiesPerChunk = 500;
        result = service.updateConfig(update);
        assertEquals(100, result.maxEntitiesPerChunk);
    }

    @Test
    void updateConfig_clampsMaxRelationshipsPerChunk() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.maxRelationshipsPerChunk = 0;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(1, result.maxRelationshipsPerChunk);

        update.maxRelationshipsPerChunk = 999;
        result = service.updateConfig(update);
        assertEquals(200, result.maxRelationshipsPerChunk);
    }

    @Test
    void updateConfig_clampsTemperature() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.extractionTemperature = -1.0;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(0.0, result.extractionTemperature);

        update.extractionTemperature = 5.0;
        result = service.updateConfig(update);
        assertEquals(2.0, result.extractionTemperature);
    }

    @Test
    void updateConfig_clampsMaxTokens() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.extractionMaxTokens = 10;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(100, result.extractionMaxTokens);

        update.extractionMaxTokens = 100000;
        result = service.updateConfig(update);
        assertEquals(32000, result.extractionMaxTokens);
    }

    @Test
    void updateConfig_clampsSimilarityThreshold() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.similarityThreshold = -0.1;
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(0.0, result.similarityThreshold);

        update.similarityThreshold = 2.0;
        result = service.updateConfig(update);
        assertEquals(1.0, result.similarityThreshold);
    }

    @Test
    void updateConfig_emptyPromptClearedToNull() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.customExtractionPrompt = "";
        GraphExtractionConfig result = service.updateConfig(update);
        assertNull(result.customExtractionPrompt);
    }

    @Test
    void updateConfig_nonEmptyPromptKept() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.customExtractionPrompt = "Extract entities from: {text}";
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals("Extract entities from: {text}", result.customExtractionPrompt);
    }

    @Test
    void updateConfig_emptyPresetIdClearedToNull() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.activeSchemaPresetId = "";
        GraphExtractionConfig result = service.updateConfig(update);
        assertNull(result.activeSchemaPresetId);
    }

    @Test
    void updateConfig_nonEmptyPresetIdKept() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.activeSchemaPresetId = "business-ontology";
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals("business-ontology", result.activeSchemaPresetId);
    }

    @Test
    void updateConfig_setsEntityTypes() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.entityTypes = List.of("PERSON", "ORG");
        GraphExtractionConfig result = service.updateConfig(update);
        assertEquals(List.of("PERSON", "ORG"), result.entityTypes);
    }

    @Test
    void updateConfig_setsNeo4jSettings() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.neo4jEnabled = true;
        update.neo4jUri = "bolt://remote:7687";
        update.neo4jUsername = "admin";
        update.neo4jPassword = "secret";
        update.neo4jDatabase = "mydb";
        GraphExtractionConfig result = service.updateConfig(update);

        assertTrue(result.neo4jEnabled);
        assertEquals("bolt://remote:7687", result.neo4jUri);
        assertEquals("admin", result.neo4jUsername);
        assertEquals("mydb", result.neo4jDatabase);
    }

    // ─── isEnabled/getBatchSize after update ─────────────────────────

    @Test
    void isEnabled_afterUpdate_returnsTrue() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.enabled = true;
        service.updateConfig(update);
        assertTrue(service.isEnabled());
    }

    @Test
    void getBatchSize_afterUpdate_returnsNewValue() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.batchSize = 25;
        service.updateConfig(update);
        assertEquals(25, service.getBatchSize());
    }

    // ─── Reset ──────────────────────────────────────────────────────

    @Test
    void resetToDefaults_restoresDefaults() {
        // Change some values
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.enabled = true;
        update.batchSize = 50;
        update.extractionModelProvider = "openai";
        service.updateConfig(update);

        // Reset
        GraphExtractionConfig result = service.resetToDefaults();
        assertFalse(result.enabled);
        assertEquals(10, result.batchSize);
        assertEquals("default", result.extractionModelProvider);
    }

    // ─── Persistence ────────────────────────────────────────────────

    @Test
    void updateConfig_persistsToNewInstance() {
        GraphExtractionConfig update = new GraphExtractionConfig();
        update.enabled = true;
        update.batchSize = 30;
        service.updateConfig(update);

        // Create new service pointing at same dir
        GraphExtractionConfigService service2 = new GraphExtractionConfigService(tempDir.toString());
        service2.init();

        GraphExtractionConfig loaded = service2.getConfig();
        assertTrue(loaded.enabled);
        assertEquals(30, loaded.batchSize);
    }

    // ─── GraphExtractionConfig inner class ──────────────────────────

    @Test
    void copy_returnsDeepCopy() {
        GraphExtractionConfig original = GraphExtractionConfig.defaults();
        original.entityTypes = List.of("PERSON");
        original.neo4jPassword = "secret";

        GraphExtractionConfig copy = original.copy();

        assertEquals(original.enabled, copy.enabled);
        assertEquals(original.batchSize, copy.batchSize);
        assertEquals(List.of("PERSON"), copy.entityTypes);
        // Password is masked in copy
        assertEquals("********", copy.neo4jPassword);
    }

    @Test
    void copy_masksEmptyPassword() {
        GraphExtractionConfig original = GraphExtractionConfig.defaults();
        original.neo4jPassword = "";

        GraphExtractionConfig copy = original.copy();
        assertEquals("", copy.neo4jPassword);
    }

    @Test
    void copyWithPassword_keepsActualPassword() {
        GraphExtractionConfig original = GraphExtractionConfig.defaults();
        original.neo4jPassword = "secret";

        GraphExtractionConfig copy = original.copyWithPassword();
        assertEquals("secret", copy.neo4jPassword);
    }

    @Test
    void getExtractionModelDisplayName_defaultProvider() {
        GraphExtractionConfig config = GraphExtractionConfig.defaults();
        assertEquals("Default (System LLM)", config.getExtractionModelDisplayName());
    }

    @Test
    void getExtractionModelDisplayName_nullProvider() {
        GraphExtractionConfig config = GraphExtractionConfig.defaults();
        config.extractionModelProvider = null;
        assertEquals("Default (System LLM)", config.getExtractionModelDisplayName());
    }

    @Test
    void getExtractionModelDisplayName_specificModel() {
        GraphExtractionConfig config = GraphExtractionConfig.defaults();
        config.extractionModelProvider = "openai";
        config.extractionModelName = "gpt-4o";
        assertEquals("openai/gpt-4o", config.getExtractionModelDisplayName());
    }

    @Test
    void getExtractionModelDisplayName_providerWithoutModel() {
        GraphExtractionConfig config = GraphExtractionConfig.defaults();
        config.extractionModelProvider = "anthropic";
        config.extractionModelName = null;
        assertEquals("anthropic/default", config.getExtractionModelDisplayName());
    }

    @Test
    void getConfig_returnsCopy_notOriginal() {
        GraphExtractionConfig config1 = service.getConfig();
        GraphExtractionConfig config2 = service.getConfig();
        assertNotSame(config1, config2);
    }
}
