package ai.kompile.staging.staging;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.*;
import ai.kompile.staging.conversion.ConversionResult;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.DownloadService;
import ai.kompile.staging.optimization.OptimizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link StagingService#stageLocalModel} — verifies that GGUF models
 * go through conversion with the correct output path and format, and that
 * auto-promote produces a correct registry entry.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StagingServiceLocalModelTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConversionService conversionService;
    @Mock
    private OptimizationService optimizationService;

    private RegistryService registryService;
    private StagingService stagingService;

    // Source directory for "local" model files
    private Path sourceDir;

    @BeforeEach
    void setUp() throws Exception {
        registryService = new RegistryService(tempDir);
        stagingService = new StagingService(
                registryService, conversionService, List.of(), optimizationService);
        sourceDir = tempDir.resolve("source-models");
        Files.createDirectories(sourceDir);
    }

    @Test
    void stageLocalGgufModel_usesCorrectOutputExtension() throws Exception {
        String modelId = "gguf-ext-test";
        Path ggufFile = sourceDir.resolve("model.gguf");
        Files.write(ggufFile, new byte[]{1, 2, 3, 4, 5});

        // Mock conversion to write sharded output files in the pending dir
        when(conversionService.convert(any(), any(), eq("gguf")))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    // Verify the output path ends with .sdnb, not .sdz
                    assertTrue(outputPath.toString().endsWith("model.sdnb"),
                            "GGUF conversion output should use .sdnb extension, got: " + outputPath);
                    // Simulate saveAutoShard producing sharded files
                    Path dir = outputPath.getParent();
                    Files.write(dir.resolve("model.shard0-of-1.sdnb"), new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .outputModelPath(outputPath)
                            .checksum("sha256:fake")
                            .build();
                });

        // Mock validation to pass
        when(conversionService.validate(any()))
                .thenReturn(ConversionService.ValidationResult.success(10, 50));

        StagingModelInfo info = stagingService.stageLocalModel(
                modelId, ggufFile.toString(), "gguf", false);

        assertNotNull(info);
        assertEquals(ModelType.LLM_GGML, info.getType(),
                "GGUF model should be typed as LLM_GGML");

        // Wait for async staging
        awaitStatus(modelId, StagingStatus.READY, 10);
    }

    @Test
    void stageLocalGgufModel_autoPromote_createsCorrectRegistry() throws Exception {
        String modelId = "gguf-promote-test";
        Path ggufFile = sourceDir.resolve("test-model.gguf");
        Files.write(ggufFile, new byte[]{1, 2, 3, 4, 5});

        // Also put a tokenizer.json next to the GGUF file
        Files.writeString(sourceDir.resolve("tokenizer.json"),
                "{\"type\": \"BPE\", \"model\": {\"vocab\": {}}}" + "x".repeat(100));

        when(conversionService.convert(any(), any(), eq("gguf")))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    Path dir = outputPath.getParent();
                    Files.write(dir.resolve("model.shard0-of-2.sdnb"), new byte[100]);
                    Files.write(dir.resolve("model.shard1-of-2.sdnb"), new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .outputModelPath(outputPath)
                            .checksum("sha256:fake")
                            .build();
                });

        when(conversionService.validate(any()))
                .thenReturn(ConversionService.ValidationResult.success(10, 50));

        // Auto-promote = true
        stagingService.stageLocalModel(modelId, ggufFile.toString(), "gguf", true);

        // Wait for async staging + promote
        awaitStatus(modelId, StagingStatus.COMPLETED, 15);

        // Verify registry entry
        Optional<ModelEntry> entry = registryService.getModel(modelId);
        assertTrue(entry.isPresent(), "Model should be in registry after auto-promote");

        ModelEntry model = entry.get();
        assertEquals(ModelType.LLM_GGML, model.getType());
        assertEquals("model.sdnb", model.getModelFile(),
                "Sharded GGUF model should have model.sdnb in registry");
        assertEquals("tokenizer.json", model.getVocabFile(),
                "GGUF model should have tokenizer.json in registry");

        // Verify production directory
        Path productionDir = tempDir.resolve(model.getPath());
        assertTrue(Files.exists(productionDir), "Production directory should exist");
        assertTrue(Files.exists(productionDir.resolve("tokenizer.json")),
                "tokenizer.json should be copied to production dir");
        assertTrue(Files.exists(productionDir.resolve("model.shard0-of-2.sdnb")),
                "Shard files should be in production dir");
        assertTrue(Files.exists(productionDir.resolve("model.sdnb")),
                "0-byte marker file should be created in production dir");
    }

    @Test
    void stageLocalGgufModel_copiesTokenizerFromSourceDir() throws Exception {
        String modelId = "tokenizer-copy-test";
        Path ggufFile = sourceDir.resolve("model.gguf");
        Files.write(ggufFile, new byte[]{1, 2, 3});

        // tokenizer.json sitting next to the GGUF file
        String tokenizerContent = "{\"type\": \"BPE\", \"model\": {}}" + "x".repeat(100);
        Files.writeString(sourceDir.resolve("tokenizer.json"), tokenizerContent);

        when(conversionService.convert(any(), any(), eq("gguf")))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    Path dir = outputPath.getParent();
                    Files.write(dir.resolve("model.shard0-of-1.sdnb"), new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .outputModelPath(outputPath)
                            .build();
                });

        when(conversionService.validate(any()))
                .thenReturn(ConversionService.ValidationResult.success(5, 20));

        stagingService.stageLocalModel(modelId, ggufFile.toString(), "gguf", true);
        awaitStatus(modelId, StagingStatus.COMPLETED, 15);

        // Verify tokenizer was copied into production
        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        Path productionDir = tempDir.resolve(model.getPath());
        Path tokenizerInProd = productionDir.resolve("tokenizer.json");
        assertTrue(Files.exists(tokenizerInProd), "tokenizer.json should be in production dir");
        String content = Files.readString(tokenizerInProd);
        assertTrue(content.contains("BPE"), "tokenizer.json content should match source");
    }

    @Test
    void stageLocalOnnxModel_usesStandardSdzExtension() throws Exception {
        String modelId = "onnx-test";
        Path onnxFile = sourceDir.resolve("model.onnx");
        Files.write(onnxFile, new byte[]{1, 2, 3});

        when(conversionService.convert(any(), any(), eq("onnx")))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    assertTrue(outputPath.toString().endsWith("model.sdz"),
                            "ONNX conversion should use .sdz extension, got: " + outputPath);
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .outputModelPath(outputPath)
                            .build();
                });

        when(conversionService.validate(any()))
                .thenReturn(ConversionService.ValidationResult.success(5, 10));

        stagingService.stageLocalModel(modelId, onnxFile.toString(), "onnx", true);
        awaitStatus(modelId, StagingStatus.COMPLETED, 15);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.DENSE_ENCODER, model.getType(),
                "ONNX model should be typed as DENSE_ENCODER");
        assertEquals("model.sdz", model.getModelFile());
        assertEquals("vocab.txt", model.getVocabFile(),
                "Encoder model without vocab file should default to vocab.txt");
    }

    /**
     * Poll until the staging completes. For auto-promoted models, the staging info
     * is removed from the map upon successful promotion, so null means "done".
     * We verify success by checking the registry instead.
     */
    private void awaitStatus(String modelId, StagingStatus expected, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            StagingModelInfo info = stagingService.getStagingModel(modelId);
            if (info == null) {
                // Model removed from staging map — promotion completed and cleaned up.
                // For COMPLETED/READY expectations, this counts as success.
                if (expected == StagingStatus.COMPLETED || expected == StagingStatus.READY) {
                    return;
                }
            } else if (info.getStatus() == expected || info.getStatus().isTerminal()) {
                if (info.getStatus() == StagingStatus.FAILED) {
                    fail("Staging failed: " + info.getError());
                }
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for staging");
            }
        }
        StagingModelInfo info = stagingService.getStagingModel(modelId);
        fail("Timed out waiting for status " + expected + ". Current: "
                + (info != null ? info.getStatus() + " - " + info.getMessage() : "null"));
    }
}
