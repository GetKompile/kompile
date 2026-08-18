package ai.kompile.staging.staging;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.*;
import ai.kompile.staging.conversion.ConversionArtifact;
import ai.kompile.staging.conversion.ConversionResult;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.DownloadService;
import ai.kompile.staging.download.LocalDownloader;
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
                registryService,
                conversionService,
                List.of(new LocalDownloader(registryService)),
                optimizationService);
        sourceDir = tempDir.resolve("source-models");
        Files.createDirectories(sourceDir);
    }

    @Test
    void stageLocalGgufModel_usesCorrectOutputExtension() throws Exception {
        String modelId = "gguf-ext-test";
        Path ggufFile = sourceDir.resolve("model.gguf");
        Files.write(ggufFile, new byte[]{1, 2, 3, 4, 5});

        // Mock conversion to write sharded output files in the pending dir
        when(conversionService.convert(any(), any(), eq("gguf"), any()))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    assertTrue(outputPath.toString().endsWith("model.sdz"),
                            "GGUF conversion output must be one canonical SDZ, got: " + outputPath);
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .artifact(ConversionArtifact.canonicalSdz(outputPath))
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

        when(conversionService.convert(any(), any(), eq("gguf"), any()))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .artifact(ConversionArtifact.canonicalSdz(outputPath))
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
        assertEquals("model.sdz", model.getModelFile(),
                "Converted GGUF model should retain the canonical SDZ in the registry");
        assertEquals("tokenizer.json", model.getVocabFile(),
                "GGUF model should have tokenizer.json in registry");

        // Verify production directory
        Path productionDir = tempDir.resolve(model.getPath());
        assertTrue(Files.exists(productionDir), "Production directory should exist");
        assertTrue(Files.exists(productionDir.resolve("tokenizer.json")),
                "tokenizer.json should be copied to production dir");
        assertTrue(Files.size(productionDir.resolve("model.sdz")) > 0L,
                "Canonical model.sdz should be in the production directory");
    }

    @Test
    void stageLocalGgufModel_copiesTokenizerFromSourceDir() throws Exception {
        String modelId = "tokenizer-copy-test";
        Path ggufFile = sourceDir.resolve("model.gguf");
        Files.write(ggufFile, new byte[]{1, 2, 3});

        // tokenizer.json sitting next to the GGUF file
        String tokenizerContent = "{\"type\": \"BPE\", \"model\": {}}" + "x".repeat(100);
        Files.writeString(sourceDir.resolve("tokenizer.json"), tokenizerContent);

        when(conversionService.convert(any(), any(), eq("gguf"), any()))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .artifact(ConversionArtifact.canonicalSdz(outputPath))
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

        when(conversionService.convert(any(), any(), eq("onnx"), any()))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    assertTrue(outputPath.toString().endsWith("model.sdz"),
                            "ONNX conversion should use .sdz extension, got: " + outputPath);
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .artifact(ConversionArtifact.canonicalSdz(outputPath))
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

    @Test
    void stageLocalSafeTensorsModel_routesThroughCanonicalConversion() throws Exception {
        String modelId = "safetensors-test";
        Path modelFile = sourceDir.resolve("model.safetensors");
        Files.write(modelFile, new byte[]{1, 2, 3});

        when(conversionService.convert(any(), any(), eq("safetensors"), any()))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    assertTrue(outputPath.toString().endsWith("model.sdz"),
                            "SafeTensors conversion should use .sdz output, got: " + outputPath);
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .artifact(ConversionArtifact.canonicalSdz(outputPath))
                            .build();
                });
        when(conversionService.validate(any()))
                .thenReturn(ConversionService.ValidationResult.success(5, 10));

        stagingService.stageLocalModel(modelId, modelFile.toString(), "safetensors", true);
        awaitStatus(modelId, StagingStatus.COMPLETED, 15);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.DENSE_ENCODER, model.getType());
        assertEquals("model.sdz", model.getModelFile());
        verify(conversionService).convert(any(), any(), eq("safetensors"), any());
    }

    @Test
    void stageLocalVlmPipeline_promotesPipelineManifestWithoutSameDiffValidation() throws Exception {
        String modelId = "vlm-pipeline-test";
        Path pipelineFile = sourceDir.resolve("pipeline.json");
        Files.writeString(pipelineFile, "{\"id\":\"vlm-pipeline-test\"}");

        stagingService.stageLocalModel(modelId, pipelineFile.toString(), "vlm", true);
        awaitStatus(modelId, StagingStatus.COMPLETED, 15);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.VLM_PIPELINE, model.getType());
        assertEquals("pipeline.json", model.getModelFile());
        verify(conversionService, never()).convert(any(), any(), any(), any());
        verify(conversionService, never()).convertVlmOnnx(any(), any(), any());
        verify(conversionService, never()).validate(any());
    }

    @Test
    void stageLocalVlmOnnx_convertsCompleteBundleBeforePromotion() throws Exception {
        String modelId = "vlm-onnx-test";
        Path decoderFile = sourceDir.resolve("decoder_model_merged.onnx");
        Files.write(decoderFile, new byte[]{1, 2, 3});
        Files.write(sourceDir.resolve("vision_encoder.onnx"), new byte[]{4, 5, 6});
        Files.write(sourceDir.resolve("embed_tokens.onnx"), new byte[]{7, 8, 9});
        Files.writeString(sourceDir.resolve("tokenizer.json"), "{\"model\":{\"vocab\":{}}}");

        when(conversionService.convertVlmOnnx(any(), any(), any()))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .artifact(ConversionArtifact.canonicalSdz(outputPath))
                            .build();
                });

        stagingService.stageLocalModel(modelId, decoderFile.toString(), "vlm", true);
        awaitStatus(modelId, StagingStatus.COMPLETED, 15);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.VLM_PIPELINE, model.getType());
        assertEquals("decoder.sdz", model.getModelFile());

        Path productionDir = tempDir.resolve(model.getPath());
        assertTrue(Files.isRegularFile(productionDir.resolve("vision_encoder.sdz")));
        assertTrue(Files.isRegularFile(productionDir.resolve("embed_tokens.sdz")));
        assertTrue(Files.isRegularFile(productionDir.resolve("decoder.sdz")));
        verify(conversionService, times(3)).convertVlmOnnx(any(), any(), any());
        verify(conversionService, never()).validate(any());
    }

    /**
     * Verify that stageLocalModel cleans up stale pending-dir artifacts left by a
     * prior failed/cancelled attempt before starting the new one.  Without the fix
     * the ONNX importer can encounter "duplicate variable" errors from leftover
     * graph artifacts written by the previous run.
     */
    @Test
    void stageLocalModel_retryAfterPartialFailure_cleansStalePendingWorkspace() throws Exception {
        String modelId = "stale-workspace-retry";
        Path onnxFile = sourceDir.resolve("model.onnx");
        Files.write(onnxFile, new byte[]{1, 2, 3, 4, 5});

        // Simulate stale artifacts from a prior failed attempt that was not fully
        // cleaned up by moveToFailed (e.g. the JVM was killed, or the move failed).
        Path stalePendingDir = tempDir.resolve(".staging/pending").resolve(modelId);
        Files.createDirectories(stalePendingDir);
        Path staleFile = stalePendingDir.resolve("stale-poison-artifact.bin");
        Files.write(staleFile, new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});

        // When conversion runs the pending directory must be clean — no stale file.
        when(conversionService.convert(any(), any(), eq("onnx"), any()))
                .thenAnswer(invocation -> {
                    Path outputPath = invocation.getArgument(1);
                    Path pendingDir = outputPath.getParent();
                    assertFalse(
                            Files.exists(pendingDir.resolve("stale-poison-artifact.bin")),
                            "Stale artifact from a prior failed attempt must not survive into the new run");
                    Files.write(outputPath, new byte[100]);
                    return ConversionResult.builder()
                            .success(true)
                            .artifact(ConversionArtifact.canonicalSdz(outputPath))
                            .build();
                });

        when(conversionService.validate(any()))
                .thenReturn(ConversionService.ValidationResult.success(5, 10));

        stagingService.stageLocalModel(modelId, onnxFile.toString(), "onnx", true);
        awaitStatus(modelId, StagingStatus.COMPLETED, 15);

        // Verify staging succeeded end-to-end
        assertTrue(registryService.getModel(modelId).isPresent(),
                "Model should be in registry after successful retry");
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
