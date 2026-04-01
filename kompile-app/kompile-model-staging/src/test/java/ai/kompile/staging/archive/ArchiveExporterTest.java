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

package ai.kompile.staging.archive;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ArchiveExporter covering model export to .karch archives.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArchiveExporterTest {

    @TempDir
    Path tempDir;

    private RegistryService registryService;
    private ArchiveExporter archiveExporter;
    private Path modelDir;

    @BeforeEach
    void setUp() throws IOException {
        modelDir = tempDir.resolve("models");
        Files.createDirectories(modelDir);
        registryService = new RegistryService(modelDir);
        archiveExporter = new ArchiveExporter(registryService);
    }

    /**
     * Helper to create a test model with files.
     */
    private void createTestModel(String modelId, ModelType type) throws IOException {
        Path modelPath = modelDir.resolve(modelId);
        Files.createDirectories(modelPath);

        // Create model file
        Files.writeString(modelPath.resolve("model.sdz"), "model content for " + modelId);

        // Create vocab file
        Files.writeString(modelPath.resolve("vocab.txt"), "vocab content for " + modelId);

        // Create tokenizer config
        Files.writeString(modelPath.resolve("tokenizer_config.json"), "{\"model_id\": \"" + modelId + "\"}");

        ModelMetadata metadata = ModelMetadata.builder()
                .embeddingDim(768)
                .maxSequenceLength(512)
                .build();

        ModelEntry entry = ModelEntry.builder()
                .modelId(modelId)
                .type(type)
                .status(ModelStatus.ACTIVE)
                .path(modelId)
                .modelFile("model.sdz")
                .vocabFile("vocab.txt")
                .metadata(metadata)
                .build();

        registryService.addModel(entry);
    }

    /**
     * Helper to extract archive entries for verification.
     */
    private Set<String> extractArchiveEntries(Path archivePath) throws IOException {
        Set<String> entries = new HashSet<>();
        try (BufferedInputStream bi = new BufferedInputStream(Files.newInputStream(archivePath));
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }

    // ==================== Basic Export Tests ====================

    @Test
    @Order(1)
    @DisplayName("Export single model to archive")
    void testExportSingleModel() throws IOException {
        createTestModel("export-single-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("single-export.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("test-archive")
                .version("1.0.0")
                .description("Test archive with single model")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("export-single-model"), outputPath, options);

        assertTrue(result.isSuccess(), "Export should succeed");
        assertNotNull(result.getArchivePath());
        assertTrue(Files.exists(result.getArchivePath()), "Archive file should exist");
        assertNotNull(result.getManifest());
        assertEquals(1, result.getManifest().getModelCount());
        assertNotNull(result.getArchiveChecksum());
        assertTrue(result.getArchiveSize() > 0);
    }

    @Test
    @Order(2)
    @DisplayName("Export multiple models to archive")
    void testExportMultipleModels() throws IOException {
        createTestModel("export-model-1", ModelType.DENSE_ENCODER);
        createTestModel("export-model-2", ModelType.SPARSE_ENCODER);
        createTestModel("export-model-3", ModelType.CROSS_ENCODER);

        Path outputPath = tempDir.resolve("multi-export.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("multi-model-archive")
                .version("1.0.0")
                .description("Archive with multiple models")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("export-model-1", "export-model-2", "export-model-3"),
                outputPath, options);

        assertTrue(result.isSuccess());
        assertEquals(3, result.getManifest().getModelCount());
    }

    @Test
    @Order(3)
    @DisplayName("Export adds .karch extension if missing")
    void testExportAddsExtension() throws IOException {
        createTestModel("extension-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("no-extension");  // No .karch extension
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("ext-test")
                .version("1.0.0")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("extension-test-model"), outputPath, options);

        assertTrue(result.isSuccess());
        assertTrue(result.getArchivePath().toString().endsWith(".karch"),
                "Archive should have .karch extension");
    }

    // ==================== Archive Contents Tests ====================

    @Test
    @Order(10)
    @DisplayName("Archive contains model files")
    void testArchiveContainsModelFiles() throws IOException {
        createTestModel("content-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("content-test.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("content-test")
                .version("1.0.0")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("content-test-model"), outputPath, options);

        assertTrue(result.isSuccess());

        // Extract and verify contents
        Set<String> entries = extractArchiveEntries(result.getArchivePath());

        assertTrue(entries.stream().anyMatch(e -> e.contains("model.sdz")),
                "Archive should contain model file");
        assertTrue(entries.stream().anyMatch(e -> e.contains("vocab.txt")),
                "Archive should contain vocab file");
        assertTrue(entries.stream().anyMatch(e -> e.contains("tokenizer_config.json")),
                "Archive should contain tokenizer config");
    }

    @Test
    @Order(11)
    @DisplayName("Archive contains manifest file")
    void testArchiveContainsManifest() throws IOException {
        createTestModel("manifest-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("manifest-test.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("manifest-test")
                .version("1.0.0")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("manifest-test-model"), outputPath, options);

        assertTrue(result.isSuccess());

        Set<String> entries = extractArchiveEntries(result.getArchivePath());

        assertTrue(entries.contains(KompileArchive.MANIFEST_FILENAME),
                "Archive should contain manifest file");
    }

    @Test
    @Order(12)
    @DisplayName("Archive contains checksums file")
    void testArchiveContainsChecksums() throws IOException {
        createTestModel("checksum-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("checksum-test.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("checksum-test")
                .version("1.0.0")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("checksum-test-model"), outputPath, options);

        assertTrue(result.isSuccess());

        Set<String> entries = extractArchiveEntries(result.getArchivePath());

        assertTrue(entries.contains(KompileArchive.CHECKSUMS_FILENAME),
                "Archive should contain checksums file");
    }

    // ==================== Export Options Tests ====================

    @Test
    @Order(20)
    @DisplayName("Export with README adds README to archive")
    void testExportWithReadme() throws IOException {
        createTestModel("readme-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("readme-test.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("readme-test")
                .version("1.0.0")
                .readme("# Test Archive\n\nThis is a test archive.")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("readme-test-model"), outputPath, options);

        assertTrue(result.isSuccess());

        Set<String> entries = extractArchiveEntries(result.getArchivePath());

        assertTrue(entries.contains("README.md"), "Archive should contain README.md");
    }

    @Test
    @Order(21)
    @DisplayName("Export with changelog adds CHANGELOG to archive")
    void testExportWithChangelog() throws IOException {
        createTestModel("changelog-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("changelog-test.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("changelog-test")
                .version("1.0.0")
                .changelog("# Changelog\n\n## 1.0.0\n- Initial release")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("changelog-test-model"), outputPath, options);

        assertTrue(result.isSuccess());

        Set<String> entries = extractArchiveEntries(result.getArchivePath());

        assertTrue(entries.contains("CHANGELOG.md"), "Archive should contain CHANGELOG.md");
    }

    @Test
    @Order(22)
    @DisplayName("Export with publisher sets manifest publisher")
    void testExportWithPublisher() throws IOException {
        createTestModel("publisher-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("publisher-test.karch");
        ArchivePublisher testPublisher = ArchivePublisher.of("Test Publisher");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("publisher-test")
                .version("1.0.0")
                .publisher(testPublisher)
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("publisher-test-model"), outputPath, options);

        assertTrue(result.isSuccess());
        assertEquals("Test Publisher", result.getManifest().getPublisher().getName());
    }

    @Test
    @Order(23)
    @DisplayName("Export with tags sets manifest tags")
    void testExportWithTags() throws IOException {
        createTestModel("tags-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("tags-test.karch");
        List<String> tags = List.of("test", "embedding", "encoder");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("tags-test")
                .version("1.0.0")
                .tags(tags)
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("tags-test-model"), outputPath, options);

        assertTrue(result.isSuccess());
        assertEquals(tags, result.getManifest().getTags());
    }

    // ==================== Progress Callback Tests ====================

    @Test
    @Order(30)
    @DisplayName("Export calls progress callback")
    void testExportProgressCallback() throws IOException {
        createTestModel("progress-test-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("progress-test.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("progress-test")
                .version("1.0.0")
                .build();

        List<ArchiveExporter.ExportProgress> progressUpdates = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("progress-test-model"), outputPath, options,
                progress -> {
                    progressUpdates.add(progress);
                    callCount.incrementAndGet();
                });

        assertTrue(result.isSuccess());
        assertTrue(callCount.get() > 0, "Progress callback should be called");
        assertFalse(progressUpdates.isEmpty(), "Should have progress updates");
    }

    // ==================== Edge Cases Tests ====================

    @Test
    @Order(40)
    @DisplayName("Export skips non-existent models")
    void testExportSkipsNonExistent() throws IOException {
        createTestModel("existing-model", ModelType.DENSE_ENCODER);

        Path outputPath = tempDir.resolve("skip-test.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("skip-test")
                .version("1.0.0")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("existing-model", "non-existent-model"), outputPath, options);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getManifest().getModelCount(),
                "Should only export existing model");
    }

    @Test
    @Order(41)
    @DisplayName("Export all models in registry")
    void testExportAllModels() throws IOException {
        createTestModel("all-model-1", ModelType.DENSE_ENCODER);
        createTestModel("all-model-2", ModelType.SPARSE_ENCODER);

        Path outputPath = tempDir.resolve("all-export.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("all-export")
                .version("1.0.0")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.exportAll(outputPath, options);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getManifest().getModelCount());
    }

    @Test
    @Order(42)
    @DisplayName("Export empty model list creates valid archive")
    void testExportEmptyList() throws IOException {
        Path outputPath = tempDir.resolve("empty-export.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("empty-export")
                .version("1.0.0")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of(), outputPath, options);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getManifest().getModelCount());
        assertTrue(Files.exists(result.getArchivePath()));
    }

    // ==================== Filename Generation Tests ====================

    @Test
    @Order(50)
    @DisplayName("Generate filename with archive ID and version")
    void testGenerateFilename() {
        String filename = archiveExporter.generateFilename("my-archive", "1.2.3");

        assertTrue(filename.contains("my-archive"));
        assertTrue(filename.contains("1.2.3"));
        assertTrue(filename.endsWith(".karch"));
    }

    @Test
    @Order(51)
    @DisplayName("Generate default filename includes date")
    void testGenerateDefaultFilename() {
        String filename = archiveExporter.generateDefaultFilename();

        assertTrue(filename.startsWith("kompile-models-"));
        assertTrue(filename.endsWith(".karch"));
        // Should contain date in ISO format (YYYY-MM-DD)
        assertTrue(filename.matches(".*\\d{4}-\\d{2}-\\d{2}.*"));
    }

    // ==================== Optimized Model Export Tests ====================

    @Test
    @Order(60)
    @DisplayName("Export optimized model includes optimization metadata")
    void testExportOptimizedModel() throws IOException {
        // Create an optimized model
        Path modelPath = modelDir.resolve("optimized-export-model");
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("model.sdz"), "optimized model content");
        Files.writeString(modelPath.resolve("model-unoptimized.sdz"), "original model content");

        ModelMetadata metadata = ModelMetadata.builder()
                .optimized(true)
                .optimizationTimeMs(1500L)
                .unoptimizedBackupFile("model-unoptimized.sdz")
                .embeddingDim(768)
                .build();

        ModelEntry entry = ModelEntry.builder()
                .modelId("optimized-export-model")
                .type(ModelType.DENSE_ENCODER)
                .status(ModelStatus.ACTIVE)
                .path("optimized-export-model")
                .modelFile("model.sdz")
                .metadata(metadata)
                .build();

        registryService.addModel(entry);

        Path outputPath = tempDir.resolve("optimized-export.karch");
        ArchiveExporter.ExportOptions options = ArchiveExporter.ExportOptions.builder()
                .archiveId("optimized-export")
                .version("1.0.0")
                .build();

        ArchiveExporter.ExportResult result = archiveExporter.export(
                List.of("optimized-export-model"), outputPath, options);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getManifest().getModelCount());

        // Verify archive contains the optimized model
        Set<String> entries = extractArchiveEntries(result.getArchivePath());
        assertTrue(entries.stream().anyMatch(e -> e.contains("model.sdz")));
    }
}
