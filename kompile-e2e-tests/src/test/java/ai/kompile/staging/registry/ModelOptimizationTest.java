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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for model optimization, re-optimization, and restore flows.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModelOptimizationTest {

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

    /**
     * Helper to create a model with model file for testing.
     */
    private void createTestModel(String modelId, boolean optimized) throws IOException {
        Path modelPath = modelDir.resolve(modelId);
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("model.sdz"), "model content for " + modelId);

        ModelMetadata metadata = ModelMetadata.builder()
                .optimized(optimized)
                .build();

        if (optimized) {
            Path backupPath = modelPath.resolve("model.sdz.bak");
            Files.writeString(backupPath, "unoptimized content");
            metadata.setUnoptimizedBackupFile(backupPath.toString());
        }

        ModelEntry entry = ModelEntry.builder()
                .modelId(modelId)
                .type(ModelType.DENSE_ENCODER)
                .status(ModelStatus.ACTIVE)
                .path(modelId)
                .modelFile("model.sdz")
                .metadata(metadata)
                .build();
        registryService.addModel(entry);
    }

    // ==================== Prepare for Optimization Tests ====================

    @Test
    @Order(1)
    @DisplayName("Prepare for optimization creates backup")
    void testPrepareForOptimizationCreatesBackup() throws IOException {
        createTestModel("prep-opt-model", false);

        Optional<Path> modelPath = registryService.prepareForOptimization("prep-opt-model", false);

        assertTrue(modelPath.isPresent());
        // Check backup was created
        Path backupFile = modelDir.resolve("prep-opt-model").resolve("model.sdz.bak");
        assertTrue(Files.exists(backupFile), "Backup file should be created");
    }

    @Test
    @Order(2)
    @DisplayName("Prepare for optimization fails for already optimized model (without force)")
    void testPrepareForOptimizationFailsIfAlreadyOptimized() throws IOException {
        createTestModel("already-opt-model", true);

        Optional<Path> modelPath = registryService.prepareForOptimization("already-opt-model", false);

        assertTrue(modelPath.isEmpty(), "Should fail for already optimized model");
    }

    @Test
    @Order(3)
    @DisplayName("Prepare for optimization succeeds with force flag")
    void testPrepareForOptimizationSucceedsWithForce() throws IOException {
        createTestModel("force-opt-model", true);

        Optional<Path> modelPath = registryService.prepareForOptimization("force-opt-model", true);

        assertTrue(modelPath.isPresent(), "Should succeed with force=true");
    }

    @Test
    @Order(5)
    @DisplayName("Prepare for optimization returns empty for non-existent model")
    void testPrepareForOptimizationNonExistent() {
        Optional<Path> modelPath = registryService.prepareForOptimization("non-existent", false);
        assertTrue(modelPath.isEmpty());
    }

    // ==================== Complete Optimization Tests ====================

    @Test
    @Order(10)
    @DisplayName("Complete optimization updates registry metadata")
    void testCompleteOptimization() throws IOException {
        createTestModel("complete-opt-model", false);
        registryService.prepareForOptimization("complete-opt-model", false);

        RegistryService.OptimizationResult result = registryService.completeOptimizationWithDetails(
                "complete-opt-model", 1500L, List.of("pass1"), null, null);

        assertTrue(result.isSuccess());

        // Verify registry was updated
        Optional<ModelEntry> model = registryService.getModel("complete-opt-model");
        assertTrue(model.isPresent());
        assertNotNull(model.get().getMetadata());
        assertTrue(model.get().getMetadata().getOptimized());
        assertEquals(1500L, model.get().getMetadata().getOptimizationTimeMs());
        assertNotNull(model.get().getMetadata().getOptimizedAt());
        assertNotNull(model.get().getMetadata().getUnoptimizedBackupFile());
    }

    @Test
    @Order(11)
    @DisplayName("Complete optimization returns failure for non-existent model")
    void testCompleteOptimizationNonExistent() {
        RegistryService.OptimizationResult result = registryService.completeOptimizationWithDetails(
                "non-existent", 1000L, null, null, null);

        assertFalse(result.isSuccess());
    }

    // ==================== Re-optimization Tests ====================

    @Test
    @Order(20)
    @DisplayName("Re-optimization flow: force prepares already optimized model")
    void testReoptimizationFlow() throws IOException {
        // Create and mark as optimized
        createTestModel("reopt-model", true);

        // Force re-optimization preparation
        Optional<Path> modelPath = registryService.prepareForOptimization("reopt-model", true);
        assertTrue(modelPath.isPresent(), "Force should allow re-optimization");

        // Complete re-optimization
        RegistryService.OptimizationResult result = registryService.completeOptimizationWithDetails(
                "reopt-model", 2000L, List.of("pass1"), null, null);

        assertTrue(result.isSuccess());
    }

    @Test
    @Order(21)
    @DisplayName("Re-optimization updates metadata")
    void testReoptimizationUpdatesMetadata() throws IOException {
        createTestModel("reopt-preserve-model", true);

        // Re-optimize
        registryService.prepareForOptimization("reopt-preserve-model", true);
        registryService.completeOptimizationWithDetails("reopt-preserve-model", 3000L, List.of("pass1"), null, null);

        // Verify new optimization time
        Optional<ModelEntry> model = registryService.getModel("reopt-preserve-model");
        assertTrue(model.isPresent());
        assertEquals(3000L, model.get().getMetadata().getOptimizationTimeMs());
    }

    // ==================== Restore Tests ====================

    @Test
    @Order(30)
    @DisplayName("Restore model replaces optimized with backup")
    void testRestoreModel() throws IOException {
        createTestModel("restore-model", true);

        // Write different content to model file to simulate optimization
        Path modelFile = modelDir.resolve("restore-model").resolve("model.sdz");
        Files.writeString(modelFile, "optimized content");

        // Restore
        boolean restored = registryService.restoreFromBackup("restore-model");

        assertTrue(restored);

        // Verify registry metadata is updated
        Optional<ModelEntry> model = registryService.getModel("restore-model");
        assertTrue(model.isPresent());
        assertFalse(model.get().getMetadata().getOptimized());
    }

    @Test
    @Order(31)
    @DisplayName("Restore fails for non-optimized model")
    void testRestoreFailsForNonOptimized() throws IOException {
        createTestModel("not-optimized-model", false);

        boolean restored = registryService.restoreFromBackup("not-optimized-model");

        assertFalse(restored);
    }

    @Test
    @Order(32)
    @DisplayName("Restore fails when no backup exists")
    void testRestoreFailsWithoutBackup() throws IOException {
        // Create optimized model but without actual backup file
        Path modelPath = modelDir.resolve("no-backup-model");
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("model.sdz"), "model content");

        ModelMetadata metadata = ModelMetadata.builder()
                .optimized(true)
                .unoptimizedBackupFile(modelPath.resolve("nonexistent.bak").toString())
                .build();

        ModelEntry entry = ModelEntry.builder()
                .modelId("no-backup-model")
                .type(ModelType.DENSE_ENCODER)
                .status(ModelStatus.ACTIVE)
                .path("no-backup-model")
                .modelFile("model.sdz")
                .metadata(metadata)
                .build();
        registryService.addModel(entry);

        // Don't create backup file - it should fail
        boolean restored = registryService.restoreFromBackup("no-backup-model");

        assertFalse(restored);
    }

    @Test
    @Order(34)
    @DisplayName("Restore returns false for non-existent model")
    void testRestoreNonExistent() {
        boolean restored = registryService.restoreFromBackup("non-existent");
        assertFalse(restored);
    }

    // ==================== Full Optimization Cycle Tests ====================

    @Test
    @Order(40)
    @DisplayName("Full optimization cycle: prepare -> complete -> restore -> re-optimize")
    void testFullOptimizationCycle() throws IOException {
        // Step 1: Create unoptimized model
        createTestModel("full-cycle-model", false);

        Optional<ModelEntry> initial = registryService.getModel("full-cycle-model");
        assertTrue(initial.isPresent());
        assertFalse(initial.get().getMetadata().getOptimized());

        // Step 2: Prepare for optimization (creates backup)
        Optional<Path> prepPath = registryService.prepareForOptimization("full-cycle-model", false);
        assertTrue(prepPath.isPresent());

        // Step 3: Complete optimization
        RegistryService.OptimizationResult optResult = registryService.completeOptimizationWithDetails(
                "full-cycle-model", 1000L, List.of("pass1"), null, null);
        assertTrue(optResult.isSuccess());

        Optional<ModelEntry> afterOpt = registryService.getModel("full-cycle-model");
        assertTrue(afterOpt.isPresent());
        assertTrue(afterOpt.get().getMetadata().getOptimized());
        assertEquals(1000L, afterOpt.get().getMetadata().getOptimizationTimeMs());

        // Step 4: Restore to unoptimized
        boolean restored = registryService.restoreFromBackup("full-cycle-model");
        assertTrue(restored);

        Optional<ModelEntry> afterRestore = registryService.getModel("full-cycle-model");
        assertTrue(afterRestore.isPresent());
        assertFalse(afterRestore.get().getMetadata().getOptimized());

        // Step 5: Re-optimize
        Optional<Path> reoptPath = registryService.prepareForOptimization("full-cycle-model", false);
        assertTrue(reoptPath.isPresent());

        RegistryService.OptimizationResult reoptResult = registryService.completeOptimizationWithDetails(
                "full-cycle-model", 1200L, List.of("pass2"), null, null);
        assertTrue(reoptResult.isSuccess());

        Optional<ModelEntry> afterReopt = registryService.getModel("full-cycle-model");
        assertTrue(afterReopt.isPresent());
        assertTrue(afterReopt.get().getMetadata().getOptimized());
        assertEquals(1200L, afterReopt.get().getMetadata().getOptimizationTimeMs());
    }

    @Test
    @Order(41)
    @DisplayName("Force re-optimization without restore")
    void testForceReoptimizationWithoutRestore() throws IOException {
        // Create and optimize model
        createTestModel("force-reopt-model", false);
        registryService.prepareForOptimization("force-reopt-model", false);
        registryService.completeOptimizationWithDetails("force-reopt-model", 1000L, List.of("pass1"), null, null);

        // Force re-optimize directly (without restore)
        Optional<Path> forcePath = registryService.prepareForOptimization("force-reopt-model", true);
        assertTrue(forcePath.isPresent(), "Force should allow direct re-optimization");

        RegistryService.OptimizationResult forceResult = registryService.completeOptimizationWithDetails(
                "force-reopt-model", 2000L, List.of("pass2"), null, null);
        assertTrue(forceResult.isSuccess());

        Optional<ModelEntry> model = registryService.getModel("force-reopt-model");
        assertTrue(model.isPresent());
        assertEquals(2000L, model.get().getMetadata().getOptimizationTimeMs());
    }
}
