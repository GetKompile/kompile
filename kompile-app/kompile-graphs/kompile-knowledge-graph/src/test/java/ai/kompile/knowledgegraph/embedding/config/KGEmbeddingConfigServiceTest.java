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

package ai.kompile.knowledgegraph.embedding.config;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.GraphBuildingConfig;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.GraphRAGConfig;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.KGEmbeddingConfig;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.Neo4jConfig;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.TrainConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KGEmbeddingConfigService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KGEmbeddingConfigServiceTest {

    @TempDir
    Path tempDir;

    private KGEmbeddingConfigService service;

    @BeforeEach
    void setUp() {
        // Use temp dir to avoid writing to ~/.kompile during tests
        service = new KGEmbeddingConfigService(tempDir.toString());
    }

    @Test
    void getConfig_returnsDefaults_whenNotPersisted() {
        KGEmbeddingConfig config = service.getConfig();
        assertNotNull(config);
        assertNotNull(config.transe());
        assertNotNull(config.rotate());
        assertNotNull(config.graphrag());
        assertNotNull(config.neo4j());
    }

    @Test
    void getTransEConfig_returnsDefaultTransEValues() {
        TrainConfig tc = service.getTransEConfig();
        assertEquals(100, tc.embeddingDim());
        assertEquals(100, tc.epochs());
        assertEquals(0.01, tc.learningRate(), 1e-9);
        assertEquals(1024, tc.batchSize());
        assertEquals(1.0, tc.margin(), 1e-9);
        assertEquals(10, tc.negativeSamples());
    }

    @Test
    void getRotatEConfig_returnsDefaultRotatEValues() {
        TrainConfig tc = service.getRotatEConfig();
        assertEquals(100, tc.embeddingDim());
        assertEquals(0.001, tc.learningRate(), 1e-9);
        assertEquals(512, tc.batchSize());
        assertEquals(6.0, tc.margin(), 1e-9);
        assertEquals(256, tc.negativeSamples());
    }

    @Test
    void getTrainConfig_forTransE_returnsTransEDefaults() {
        TrainConfig tc = service.getTrainConfig(KGEmbeddingAlgorithm.TRANSE);
        assertEquals(1024, tc.batchSize());
    }

    @Test
    void getTrainConfig_forRotatE_returnsRotatEDefaults() {
        TrainConfig tc = service.getTrainConfig(KGEmbeddingAlgorithm.ROTATE);
        assertEquals(512, tc.batchSize());
    }

    @Test
    void updateConfig_replacesFullConfig_andReturnsUpdated() {
        TrainConfig newTransE = new TrainConfig(64, 50, 0.005, 512, 2.0, 5);
        TrainConfig newRotatE = new TrainConfig(64, 50, 0.0005, 256, 3.0, 128);
        GraphRAGConfig newGraphRAG = new GraphRAGConfig(true, 0.5, 0.5, 2, 10, 2L);
        Neo4jConfig newNeo4j = new Neo4jConfig(true, "bolt://db:7687", "admin", "secret");

        KGEmbeddingConfig newCfg = new KGEmbeddingConfig(newTransE, newRotatE, newGraphRAG, newNeo4j);
        KGEmbeddingConfig result = service.updateConfig(newCfg);

        assertNotNull(result);
        assertEquals(64, result.transe().embeddingDim());
        assertEquals(true, result.graphrag().enabled());
        assertEquals("bolt://db:7687", result.neo4j().uri());
    }

    @Test
    void updateTransEConfig_onlyUpdatesTransE_preservesOtherConfigs() {
        GraphRAGConfig originalGraphRAG = service.getConfig().graphrag();
        TrainConfig newTransE = new TrainConfig(32, 10, 0.1, 128, 0.5, 2);

        KGEmbeddingConfig updated = service.updateTransEConfig(newTransE);

        assertEquals(32, updated.transe().embeddingDim());
        // Rotate should remain unchanged
        assertEquals(service.getConfig().rotate().embeddingDim(), updated.rotate().embeddingDim());
        // GraphRAG should remain unchanged
        assertEquals(originalGraphRAG.enabled(), updated.graphrag().enabled());
    }

    @Test
    void updateRotatEConfig_onlyUpdatesRotatE_preservesOtherConfigs() {
        TrainConfig newRotatE = new TrainConfig(128, 200, 0.002, 1024, 12.0, 512);
        KGEmbeddingConfig updated = service.updateRotatEConfig(newRotatE);

        assertEquals(128, updated.rotate().embeddingDim());
        assertEquals(200, updated.rotate().epochs());
        // TransE should be unchanged
        assertEquals(100, updated.transe().embeddingDim());
    }

    @Test
    void updateGraphRAGConfig_onlyUpdatesGraphRAG() {
        GraphRAGConfig newGraphRAG = new GraphRAGConfig(true, 0.4, 0.6, 3, 7, 42L);
        KGEmbeddingConfig updated = service.updateGraphRAGConfig(newGraphRAG);

        assertTrue(updated.graphrag().enabled());
        assertEquals(0.4, updated.graphrag().kgWeight(), 1e-9);
        assertEquals(3, updated.graphrag().expansionHops());
    }

    @Test
    void updateNeo4jConfig_onlyUpdatesNeo4j() {
        Neo4jConfig newNeo4j = new Neo4jConfig(true, "neo4j://remotehost:7687", "user", "pass");
        KGEmbeddingConfig updated = service.updateNeo4jConfig(newNeo4j);

        assertTrue(updated.neo4j().enabled());
        assertEquals("neo4j://remotehost:7687", updated.neo4j().uri());
    }

    @Test
    void resetToDefaults_restoresDefaultValues() {
        // Change something first
        service.updateTransEConfig(new TrainConfig(999, 999, 9.9, 999, 9.9, 999));

        KGEmbeddingConfig reset = service.resetToDefaults();
        assertEquals(100, reset.transe().embeddingDim());
        assertEquals(0.01, reset.transe().learningRate(), 1e-9);
    }

    @Test
    void getNeo4jConfig_returnsDefaults() {
        Neo4jConfig nc = service.getNeo4jConfig();
        assertFalse(nc.enabled());
        assertEquals("bolt://localhost:7687", nc.uri());
        assertEquals("neo4j", nc.username());
    }

    @Test
    void getAvailableLlmProviders_returnsExpectedProviders() {
        List<String> providers = service.getAvailableLlmProviders();
        assertNotNull(providers);
        assertTrue(providers.contains("openai"));
        assertTrue(providers.contains("anthropic"));
        assertTrue(providers.contains("google"));
    }

    @Test
    void getAvailableLlmModels_forOpenAi_returnsOpenAiModels() {
        List<String> models = service.getAvailableLlmModels("openai");
        assertNotNull(models);
        assertFalse(models.isEmpty());
        assertTrue(models.stream().anyMatch(m -> m.contains("gpt")));
    }

    @Test
    void getAvailableLlmModels_forAnthropic_returnsAnthropicModels() {
        List<String> models = service.getAvailableLlmModels("anthropic");
        assertTrue(models.stream().anyMatch(m -> m.contains("claude")));
    }

    @Test
    void getAvailableLlmModels_forGoogle_returnsGoogleModels() {
        List<String> models = service.getAvailableLlmModels("google");
        assertTrue(models.stream().anyMatch(m -> m.contains("gemini")));
    }

    @Test
    void getAvailableLlmModels_forUnknownProvider_returnsEmpty() {
        // Production returns an empty list for unknown providers (no fallback default)
        List<String> models = service.getAvailableLlmModels("unknown-provider");
        assertTrue(models.isEmpty());
    }

    @Test
    void kgEmbeddingConfig_defaults_hasExpectedValues() {
        KGEmbeddingConfig cfg = KGEmbeddingConfig.defaults();
        assertNotNull(cfg.transe());
        assertNotNull(cfg.rotate());
        assertFalse(cfg.graphrag().enabled());
        assertFalse(cfg.neo4j().enabled());
    }

    @Test
    void graphBuildingConfig_defaults_hasExpectedValues() {
        GraphBuildingConfig cfg = GraphBuildingConfig.defaults();
        assertFalse(cfg.enabled());
        assertEquals("llm", cfg.builderType());
        assertEquals("jpa", cfg.storageType());
        assertFalse(cfg.autoAccept());
        assertNotNull(cfg.entityTypes());
        assertFalse(cfg.entityTypes().isEmpty());
    }

    @Test
    void graphBuildingConfig_withDefaults_overridesTopLevelFields() {
        GraphBuildingConfig cfg = GraphBuildingConfig.withDefaults(true, "manual", "neo4j");
        assertTrue(cfg.enabled());
        assertEquals("manual", cfg.builderType());
        assertEquals("neo4j", cfg.storageType());
    }

    @Test
    void configIsPersisted_andReloadedOnNextInstance() throws Exception {
        // Update config with a distinctive value
        TrainConfig updatedTransE = new TrainConfig(77, 77, 0.077, 777, 7.7, 77);
        service.updateTransEConfig(updatedTransE);

        // Create a new service instance pointing to same dir
        KGEmbeddingConfigService service2 = new KGEmbeddingConfigService(tempDir.toString());
        service2.loadPersistedConfig();

        // Should have loaded the persisted value
        assertEquals(77, service2.getTransEConfig().embeddingDim());
    }
}
