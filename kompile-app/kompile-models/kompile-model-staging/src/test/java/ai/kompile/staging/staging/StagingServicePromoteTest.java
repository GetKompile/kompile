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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link StagingService#promoteModel} and related file discovery logic.
 * Verifies that GGUF/LLM models are promoted with correct type, model_file, and vocab_file.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StagingServicePromoteTest {

    @TempDir
    Path tempDir;

    @Mock
    private ConversionService conversionService;
    @Mock
    private OptimizationService optimizationService;

    private RegistryService registryService;
    private StagingService stagingService;

    @BeforeEach
    void setUp() {
        registryService = new RegistryService(tempDir);
        stagingService = new StagingService(
                registryService, conversionService, List.of(), optimizationService);
    }

    /**
     * Inject a StagingModelInfo into the private stagingModels map via reflection,
     * simulating what stageLocalModel() would do.
     */
    private void injectStagingModel(String modelId, StagingModelInfo info) throws Exception {
        Field field = StagingService.class.getDeclaredField("stagingModels");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, StagingModelInfo> map = (Map<String, StagingModelInfo>) field.get(stagingService);
        map.put(modelId, info);
    }

    // ==================== promoteModel with sharded GGUF ====================

    @Test
    void promoteShardedGgufModel_setsCorrectRegistryEntry() throws Exception {
        String modelId = "lfm-test";

        // Set up verified directory with sharded model files + tokenizer
        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        // Shard files
        Files.write(stagingDir.resolve("model.shard0-of-2.sdnb"), new byte[]{1, 2, 3});
        Files.write(stagingDir.resolve("model.shard1-of-2.sdnb"), new byte[]{4, 5, 6});
        // Tokenizer
        Files.writeString(stagingDir.resolve("tokenizer.json"),
                "{\"type\": \"BPE\", \"model\": {}}  " + "x".repeat(100));
        // GGUF source file (left over from conversion)
        Files.write(stagingDir.resolve("model.gguf"), new byte[]{0});

        // Inject staging model info as LLM_GGML
        StagingModelInfo info = StagingModelInfo.create(modelId, "local:/test", ModelType.LLM_GGML);
        info.withStatus(StagingStatus.PROMOTING, 95, "Auto-promoting");
        injectStagingModel(modelId, info);

        // Promote
        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result, "Promotion should succeed");

        // Verify registry entry
        Optional<ModelEntry> entry = registryService.getModel(modelId);
        assertTrue(entry.isPresent(), "Model should be in registry");

        ModelEntry model = entry.get();
        assertEquals(ModelType.LLM_GGML, model.getType());
        assertEquals("model.sdnb", model.getModelFile(),
                "Sharded GGUF model should use model.sdnb as model_file");
        assertEquals("tokenizer.json", model.getVocabFile(),
                "GGUF model should use tokenizer.json as vocab_file");
        assertTrue(model.getPath().contains(modelId));

        // Verify 0-byte marker file was created
        Path productionDir = tempDir.resolve(model.getPath());
        Path markerFile = productionDir.resolve("model.sdnb");
        assertTrue(Files.exists(markerFile), "0-byte marker file should exist");
        assertEquals(0, Files.size(markerFile), "Marker file should be 0 bytes");

        // Verify shard files were moved to production
        assertTrue(Files.exists(productionDir.resolve("model.shard0-of-2.sdnb")));
        assertTrue(Files.exists(productionDir.resolve("model.shard1-of-2.sdnb")));
        assertTrue(Files.exists(productionDir.resolve("tokenizer.json")));

        // Verify tokenizer config is LLM-style (not BERT defaults)
        assertNotNull(model.getTokenizer());
        assertFalse(model.getTokenizer().isDoLowerCase(),
                "LLM tokenizer should not lowercase");
    }

    @Test
    void promoteEncoderModel_setsCorrectRegistryEntry() throws Exception {
        String modelId = "bge-test";

        // Set up verified directory with single .sdz model + vocab.txt
        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        Files.write(stagingDir.resolve("model.sdz"), new byte[]{1, 2, 3, 4});
        Files.writeString(stagingDir.resolve("vocab.txt"), "[PAD]\n[UNK]\n[CLS]\n");

        StagingModelInfo info = StagingModelInfo.create(modelId, "local:/test", ModelType.DENSE_ENCODER);
        info.withStatus(StagingStatus.PROMOTING, 95, "Auto-promoting");
        injectStagingModel(modelId, info);

        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result);

        Optional<ModelEntry> entry = registryService.getModel(modelId);
        assertTrue(entry.isPresent());

        ModelEntry model = entry.get();
        assertEquals(ModelType.DENSE_ENCODER, model.getType());
        assertEquals("model.sdz", model.getModelFile());
        assertEquals("vocab.txt", model.getVocabFile());

        // Tokenizer should use BERT defaults
        assertNotNull(model.getTokenizer());
        assertTrue(model.getTokenizer().isDoLowerCase(),
                "Encoder tokenizer should lowercase by default");
    }

    @Test
    void promoteEncoderModel_prefersSdzOverStaleSdnbShards() throws Exception {
        String modelId = "bge-with-stale-shards";

        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        Files.write(stagingDir.resolve("model.sdz"), new byte[]{9, 8, 7, 6});
        Files.createFile(stagingDir.resolve("model.sdnb"));
        Files.write(stagingDir.resolve("model.shard0-of-2.sdnb"), new byte[]{1, 2, 3});
        Files.write(stagingDir.resolve("model.shard1-of-2.sdnb"), new byte[]{4, 5, 6});
        Files.writeString(stagingDir.resolve("tokenizer.json"),
                "{\"type\": \"BPE\"}" + "x".repeat(100));

        StagingModelInfo info = StagingModelInfo.create(modelId, "local:/test", ModelType.DENSE_ENCODER);
        info.withStatus(StagingStatus.PROMOTING, 95, "Auto-promoting");
        injectStagingModel(modelId, info);

        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.DENSE_ENCODER, model.getType());
        assertEquals("model.sdz", model.getModelFile(),
                "Dense encoder promotion must prefer the optimized SDZ container over stale SDNB shards");
        assertEquals("tokenizer.json", model.getVocabFile());
    }

    @Test
    void promoteSingleSdnbModel_findsSdnbFile() throws Exception {
        String modelId = "single-sdnb";

        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        // Single .sdnb with actual content (not sharded)
        Files.write(stagingDir.resolve("model.sdnb"), new byte[100]);
        Files.writeString(stagingDir.resolve("tokenizer.json"),
                "{\"type\": \"BPE\"}" + "x".repeat(100));

        StagingModelInfo info = StagingModelInfo.create(modelId, "local:/test", ModelType.LLM_GGML);
        info.withStatus(StagingStatus.PROMOTING, 95, "Promoting");
        injectStagingModel(modelId, info);

        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals("model.sdnb", model.getModelFile(),
                "Single .sdnb file should be detected as model file");
        assertEquals("tokenizer.json", model.getVocabFile());
    }

    // ==================== findStagedModel type inference ====================

    @Test
    void findStagedModel_infersLlmTypeFromGgufFile() throws Exception {
        String modelId = "infer-gguf";

        // Set up verified directory with a .gguf file — simulates post-restart discovery
        Path verifiedDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(verifiedDir);
        Files.write(verifiedDir.resolve("model.gguf"), new byte[]{1});
        Files.write(verifiedDir.resolve("model.shard0-of-1.sdnb"), new byte[]{2, 3, 4});

        // Don't inject into stagingModels — force findStagedModel path
        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result, "Promotion should succeed via findStagedModel path");

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.LLM_GGML, model.getType(),
                "Should infer LLM_GGML type from .gguf file in verified dir");
        assertEquals("model.sdnb", model.getModelFile());
    }

    @Test
    void findStagedModel_infersLlmTypeFromTokenizerJson() throws Exception {
        String modelId = "infer-tokenizer";

        Path verifiedDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(verifiedDir);
        Files.write(verifiedDir.resolve("model.shard0-of-1.sdnb"), new byte[]{1, 2});
        Files.writeString(verifiedDir.resolve("tokenizer.json"),
                "{\"type\": \"BPE\"}" + "x".repeat(100));

        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.LLM_GGML, model.getType(),
                "Should infer LLM_GGML type from tokenizer.json presence");
    }

    @Test
    void findStagedModel_defaultsToEncoderWithoutGgufOrTokenizer() throws Exception {
        String modelId = "infer-encoder";

        Path verifiedDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(verifiedDir);
        Files.write(verifiedDir.resolve("model.sdz"), new byte[]{1, 2, 3});
        Files.writeString(verifiedDir.resolve("vocab.txt"), "[PAD]\n[UNK]\n");

        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.DENSE_ENCODER, model.getType(),
                "Should default to DENSE_ENCODER when no GGUF/tokenizer.json signals");
        assertEquals("model.sdz", model.getModelFile());
        assertEquals("vocab.txt", model.getVocabFile());
    }

    // ==================== Marker file edge cases ====================

    @Test
    void promoteShardedModel_doesNotCreateDuplicateMarker() throws Exception {
        String modelId = "existing-marker";

        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        Files.write(stagingDir.resolve("model.shard0-of-1.sdnb"), new byte[]{1, 2, 3});
        // Pre-existing marker file (shouldn't cause error)
        Files.createFile(stagingDir.resolve("model.sdnb"));
        Files.writeString(stagingDir.resolve("tokenizer.json"),
                "{\"type\": \"BPE\"}" + "x".repeat(100));

        StagingModelInfo info = StagingModelInfo.create(modelId, "local:/test", ModelType.LLM_GGML);
        info.withStatus(StagingStatus.PROMOTING, 95, "Promoting");
        injectStagingModel(modelId, info);

        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result, "Should succeed even with pre-existing marker file");

        Path productionDir = tempDir.resolve(
                registryService.getModel(modelId).orElseThrow().getPath());
        Path marker = productionDir.resolve("model.sdnb");
        assertTrue(Files.exists(marker));
        assertEquals(0, Files.size(marker));
    }

    @Test
    void findModelFile_prefersShardOverZeroByteMarker() throws Exception {
        String modelId = "shard-vs-marker";

        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        // 0-byte marker
        Files.createFile(stagingDir.resolve("model.sdnb"));
        // Real shard file
        Files.write(stagingDir.resolve("model.shard0-of-2.sdnb"), new byte[]{1, 2, 3});
        Files.write(stagingDir.resolve("model.shard1-of-2.sdnb"), new byte[]{4, 5, 6});
        Files.writeString(stagingDir.resolve("tokenizer.json"),
                "{\"type\": \"BPE\"}" + "x".repeat(100));

        StagingModelInfo info = StagingModelInfo.create(modelId, "local:/test", ModelType.LLM_GGML);
        info.withStatus(StagingStatus.PROMOTING, 95, "Promoting");
        injectStagingModel(modelId, info);

        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result);

        // Checksum should be computed on shard0, not the 0-byte marker
        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertNotNull(model.getChecksum(), "Checksum should be computed from shard0 file");
        assertNotEquals("", model.getChecksum());
    }
}
