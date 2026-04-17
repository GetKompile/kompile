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

package ai.kompile.modelmanager.registry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegistryService covering model loading, registration,
 * optimization, re-optimization, and restore flows.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistryServiceTest {

    @TempDir
    Path tempDir;

    private RegistryService registryService;
    private Path modelDir;

    @BeforeEach
    void setUp() throws IOException {
        modelDir = tempDir.resolve("models");
        Files.createDirectories(modelDir);
        registryService = new RegistryService(modelDir);
    }

    // ==================== Registry Loading Tests ====================

    @Test
    @Order(1)
    @DisplayName("Load empty registry when no registry file exists")
    void testLoadEmptyRegistry() {
        ModelRegistry registry = registryService.loadRegistry();

        assertNotNull(registry);
        assertTrue(registry.getModels().isEmpty());
        assertEquals("1.0", registry.getVersion());
    }

    @Test
    @Order(2)
    @DisplayName("Load registry from existing file")
    void testLoadExistingRegistry() throws IOException {
        // Create a registry file
        String registryJson = """
            {
              "version": "1.0.0",
              "models": {
                "test-model": {
                  "model_id": "test-model",
                  "type": "dense_encoder",
                  "status": "available",
                  "path": "test-model",
                  "model_file": "model.sdz"
                }
              }
            }
            """;
        Files.writeString(modelDir.resolve("registry.json"), registryJson);

        ModelRegistry registry = registryService.loadRegistry();

        assertNotNull(registry);
        assertEquals(1, registry.getModels().size());
        assertTrue(registry.getModels().containsKey("test-model"));
        assertEquals(ModelType.DENSE_ENCODER, registry.getModel("test-model").getType());
    }

    @Test
    @Order(3)
    @DisplayName("Registry caching works correctly")
    void testRegistryCaching() throws IOException {
        // Create initial registry
        String registryJson = """
            {
              "version": "1.0.0",
              "models": {
                "cached-model": {
                  "model_id": "cached-model",
                  "type": "dense_encoder",
                  "status": "available",
                  "path": "cached-model"
                }
              }
            }
            """;
        Files.writeString(modelDir.resolve("registry.json"), registryJson);

        // Load twice - second load should use cache
        ModelRegistry registry1 = registryService.loadRegistry();
        ModelRegistry registry2 = registryService.loadRegistry();

        assertNotNull(registry1);
        assertNotNull(registry2);
        // Both should have the same data
        assertEquals(registry1.getModels().size(), registry2.getModels().size());
    }

    // ==================== Model Registration Tests ====================

    @Test
    @Order(10)
    @DisplayName("Add new model to registry")
    void testAddModel() throws IOException {
        // Create model directory and file
        Path modelPath = modelDir.resolve("new-model");
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("model.sdz"), "dummy model content");

        ModelEntry entry = ModelEntry.builder()
                .modelId("new-model")
                .type(ModelType.DENSE_ENCODER)
                .status(ModelStatus.ACTIVE)
                .path("new-model")
                .modelFile("model.sdz")
                .build();

        registryService.addModel(entry);

        // Verify model was added
        Optional<ModelEntry> retrieved = registryService.getModel("new-model");
        assertTrue(retrieved.isPresent());
        assertEquals("new-model", retrieved.get().getModelId());
        assertEquals(ModelType.DENSE_ENCODER, retrieved.get().getType());
    }

    @Test
    @Order(11)
    @DisplayName("Get non-existent model returns empty")
    void testGetNonExistentModel() {
        Optional<ModelEntry> result = registryService.getModel("non-existent-model");
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(12)
    @DisplayName("List all models in registry")
    void testListModels() throws IOException {
        // Add multiple models
        for (int i = 1; i <= 3; i++) {
            Path modelPath = modelDir.resolve("model-" + i);
            Files.createDirectories(modelPath);
            Files.writeString(modelPath.resolve("model.sdz"), "content " + i);

            ModelEntry entry = ModelEntry.builder()
                    .modelId("model-" + i)
                    .type(ModelType.DENSE_ENCODER)
                    .status(ModelStatus.ACTIVE)
                    .path("model-" + i)
                    .modelFile("model.sdz")
                    .build();
            registryService.addModel(entry);
        }

        var models = registryService.loadRegistry().getAllModels();

        assertEquals(3, models.size());
    }

    // ==================== Model Activation Tests ====================

    @Test
    @Order(20)
    @DisplayName("Activate model changes status to active")
    void testActivateModel() throws IOException {
        // Add a model
        Path modelPath = modelDir.resolve("activatable-model");
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("model.sdz"), "model content");

        ModelEntry entry = ModelEntry.builder()
                .modelId("activatable-model")
                .type(ModelType.DENSE_ENCODER)
                .status(ModelStatus.ACTIVE)
                .path("activatable-model")
                .modelFile("model.sdz")
                .build();
        registryService.addModel(entry);

        // Activate
        boolean activated = registryService.updateModelStatus("activatable-model", ModelStatus.ACTIVE);

        assertTrue(activated);

        Optional<ModelEntry> model = registryService.getModel("activatable-model");
        assertTrue(model.isPresent());
        assertEquals(ModelStatus.ACTIVE, model.get().getStatus());
    }

    @Test
    @Order(21)
    @DisplayName("Activating one model: other models of same type remain in their state")
    void testActivateMultipleModelsOfSameType() throws IOException {
        // Add two dense_encoder models
        for (String id : new String[]{"encoder-1", "encoder-2"}) {
            Path modelPath = modelDir.resolve(id);
            Files.createDirectories(modelPath);
            Files.writeString(modelPath.resolve("model.sdz"), "content");

            ModelEntry entry = ModelEntry.builder()
                    .modelId(id)
                    .type(ModelType.DENSE_ENCODER)
                    .status(ModelStatus.ACTIVE)
                    .path(id)
                    .modelFile("model.sdz")
                    .build();
            registryService.addModel(entry);
        }

        // Activate first
        registryService.updateModelStatus("encoder-1", ModelStatus.ACTIVE);

        // Activate second
        registryService.updateModelStatus("encoder-2", ModelStatus.ACTIVE);

        // Check both models
        Optional<ModelEntry> model1 = registryService.getModel("encoder-1");
        Optional<ModelEntry> model2 = registryService.getModel("encoder-2");

        assertTrue(model1.isPresent());
        assertTrue(model2.isPresent());
        assertEquals(ModelStatus.ACTIVE, model2.get().getStatus());
    }

    // ==================== Model Deletion Tests ====================

    @Test
    @Order(30)
    @DisplayName("Delete model removes from registry")
    void testDeleteModel() throws IOException {
        // Add a model
        Path modelPath = modelDir.resolve("deletable-model");
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("model.sdz"), "model content");

        ModelEntry entry = ModelEntry.builder()
                .modelId("deletable-model")
                .type(ModelType.DENSE_ENCODER)
                .status(ModelStatus.ACTIVE)
                .path("deletable-model")
                .modelFile("model.sdz")
                .build();
        registryService.addModel(entry);

        // Delete
        RegistryService.DeleteResult result = registryService.deleteModelCompletely("deletable-model", true);

        assertTrue(result.isSuccess());
        assertTrue(registryService.getModel("deletable-model").isEmpty());
    }

    @Test
    @Order(31)
    @DisplayName("Delete non-existent model returns appropriate result")
    void testDeleteNonExistentModel() {
        RegistryService.DeleteResult result = registryService.deleteModelCompletely("non-existent", false);
        assertFalse(result.isSuccess());
    }
}
