package ai.kompile.staging.staging;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.*;
import ai.kompile.staging.conversion.ConversionResult;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.DownloadService;
import ai.kompile.staging.optimization.OptimizationService;
import ai.kompile.staging.web.dto.TrainingArtifactStageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.ggml.format.GGUFReader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    private Path writeTrainingArtifactManifest(Path outputDir, String modelId, String taskId) throws IOException {
        Files.createDirectories(outputDir);
        Path modelFile = outputDir.resolve("model.fb");
        Files.write(modelFile, new byte[]{1, 2, 3, 4});
        Files.writeString(outputDir.resolve("tokenizer.json"), "{\"type\":\"BPE\"}");

        Path manifest = outputDir.resolve("training-artifact.json");
        Files.writeString(manifest, "{\n"
                + "  \"schema\": \"kompile.training-artifact.v1\",\n"
                + "  \"taskId\": \"" + jsonEscape(taskId) + "\",\n"
                + "  \"trainingType\": \"LORA\",\n"
                + "  \"baseModelId\": \"base-qwen\",\n"
                + "  \"trainedModelId\": \"" + jsonEscape(modelId) + "\",\n"
                + "  \"outputDir\": \"" + jsonEscape(outputDir.toString()) + "\",\n"
                + "  \"modelPath\": \"" + jsonEscape(modelFile.toString()) + "\",\n"
                + "  \"modelFile\": \"model.fb\",\n"
                + "  \"modelFormat\": \"SAMEDIFF_FLATBUFFERS\",\n"
                + "  \"deployable\": true,\n"
                + "  \"dataset\": {\n"
                + "    \"datasetId\": \"dataset-1\",\n"
                + "    \"sourcePath\": \"" + jsonEscape(outputDir.resolve("dataset.jsonl").toString()) + "\"\n"
                + "  }\n"
                + "}\n");
        return manifest;
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== training artifact staging ====================

    @Test
    void stageTrainingArtifact_copiesDeployableFilesToVerifiedStaging() throws Exception {
        String modelId = "qwen-lora-staged";
        Path outputDir = tempDir.resolve("training-output");
        Path manifest = writeTrainingArtifactManifest(outputDir, modelId, "task-stage-1");

        StagingModelInfo info = stagingService.stageTrainingArtifact(
                TrainingArtifactStageRequest.builder()
                        .manifestPath(manifest.toString())
                        .build());

        assertEquals(modelId, info.getModelId());
        assertEquals(StagingStatus.READY, info.getStatus());
        assertTrue(registryService.getModel(modelId).isEmpty(), "Manual staging should not register the model");

        Path verifiedDir = tempDir.resolve(".staging/verified").resolve(modelId);
        assertTrue(Files.exists(verifiedDir.resolve("model.fb")));
        assertTrue(Files.exists(verifiedDir.resolve("training-artifact.json")));
        assertTrue(Files.exists(verifiedDir.resolve("tokenizer.json")));
        assertTrue(Files.exists(outputDir.resolve("model.fb")), "Training output should be copied, not moved");
    }

    @Test
    void stageTrainingArtifact_autoPromoteRegistersTrainedModel() throws Exception {
        String modelId = "qwen-lora-promoted";
        Path outputDir = tempDir.resolve("training-output-promote");
        Path manifest = writeTrainingArtifactManifest(outputDir, modelId, "task-promote-1");

        StagingModelInfo info = stagingService.stageTrainingArtifact(
                TrainingArtifactStageRequest.builder()
                        .manifestPath(manifest.toString())
                        .autoPromote(true)
                        .description("Promoted LoRA training artifact")
                        .build());

        assertEquals(modelId, info.getModelId());
        assertEquals(StagingStatus.COMPLETED, info.getStatus());

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.DENSE_ENCODER, model.getType());
        assertEquals("model.fb", model.getModelFile());
        assertEquals("tokenizer.json", model.getVocabFile());
        assertEquals("base-qwen", model.getMetadata().getSourceRepository());
        assertEquals("training-artifact", model.getMetadata().getSourceOrigin());
        assertEquals("lora", model.getMetadata().getModelType());
        assertEquals("dataset-1", model.getMetadata().getTrainingData());
        assertEquals("SAMEDIFF_FLATBUFFERS", model.getMetadata().getOriginalFormat());
        assertEquals("Promoted LoRA training artifact", model.getMetadata().getDescription());

        Path productionDir = tempDir.resolve(model.getPath());
        assertTrue(Files.exists(productionDir.resolve("model.fb")));
        assertTrue(Files.exists(productionDir.resolve("training-artifact.json")));
        assertTrue(Files.exists(outputDir.resolve("model.fb")), "Auto-promotion should not consume training output");
        assertFalse(Files.exists(tempDir.resolve(".staging/verified").resolve(modelId)),
                "Promoted model should move out of verified staging");
    }

    // ==================== tokenizer metadata migration ====================

    @Test
    void tokenizerMetadataMigrationBackfillsMissingRolesWithoutOverwritingExistingFields() {
        ObjectMapper mapper = new ObjectMapper();
        var config = mapper.createObjectNode();
        config.put("chat_template", "existing-template");

        boolean changed = StagingService.applyTokenizerMetadata(
                config,
                "source-template",
                List.of("<pad>", "<bos>", "<eos>"),
                1,
                2,
                0);

        assertTrue(changed);
        assertEquals("existing-template", config.path("chat_template").asText());
        assertEquals("<bos>", config.path("bos_token").asText());
        assertEquals("<eos>", config.path("eos_token").asText());
        assertEquals("<pad>", config.path("pad_token").asText());

        assertFalse(StagingService.applyTokenizerMetadata(
                config,
                "replacement-template",
                List.of("<new-pad>", "<new-bos>", "<new-eos>"),
                1,
                2,
                0));
        assertEquals("existing-template", config.path("chat_template").asText());
        assertEquals("<bos>", config.path("bos_token").asText());
        assertEquals("<eos>", config.path("eos_token").asText());
        assertEquals("<pad>", config.path("pad_token").asText());
    }

    @Test
    void installedGgufMetadataMigrationMatchesSourceContainer() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("kompile.staging.metadata.migration.integration"),
                "opt-in migration of an installed model");

        String modelId = System.getProperty(
                "kompile.staging.metadata.migration.model", "lfm2.5-1.2b-instruct");
        RegistryService installedRegistry = new RegistryService();
        ModelEntry model = installedRegistry.getModel(modelId).orElseThrow(
                () -> new IllegalStateException("Model is not registered: " + modelId));
        Path modelDir = installedRegistry.getModelsDir().resolve(model.getPath());
        Path sourceGguf;
        try (var files = Files.list(modelDir)) {
            sourceGguf = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No source GGUF beside installed model " + modelId));
        }

        List<String> sourceTokens;
        int sourceBos;
        int sourceEos;
        int sourcePad;
        try (GGUFReader reader = new GGUFReader(sourceGguf.toFile())) {
            sourceTokens = List.copyOf(reader.getHeader().getTokens());
            sourceBos = reader.getHeader().getBosTokenId();
            sourceEos = reader.getHeader().getEosTokenId();
            sourcePad = reader.getHeader().getPadTokenId();
        }

        StagingService installedService = new StagingService(
                installedRegistry, conversionService, List.of(), optimizationService);
        assertTrue(installedService.repairTokenizerMetadata(modelId));

        Path configPath = modelDir.resolve("tokenizer_config.json");
        var config = new ObjectMapper().readTree(configPath.toFile());
        assertTokenRoleMatchesSource(config.path("bos_token").asText(null), sourceTokens, sourceBos);
        assertTokenRoleMatchesSource(config.path("eos_token").asText(null), sourceTokens, sourceEos);
        assertTokenRoleMatchesSource(config.path("pad_token").asText(null), sourceTokens, sourcePad);

        try (HuggingFaceTokenizer tokenizer =
                     HuggingFaceTokenizer.fromFile(modelDir.resolve(model.getVocabFile()).toFile())) {
            assertEquals(sourceBos, tokenizer.getBosTokenId());
            assertEquals(sourceEos, tokenizer.getEosTokenId());
            assertEquals(sourcePad, tokenizer.getPadTokenId());
            if (sourcePad >= 0) {
                assertTrue(tokenizer.getSpecialTokenIds().contains(sourcePad),
                        "the migrated PAD role must participate in generic control-token gating");
            }
        }
    }

    private static void assertTokenRoleMatchesSource(
            String configuredToken, List<String> sourceTokens, int sourceId) {
        if (sourceId >= 0 && sourceId < sourceTokens.size()) {
            assertEquals(sourceTokens.get(sourceId), configuredToken);
        } else {
            assertNull(configuredToken);
        }
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
        assertEquals(
                "sha256:039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                model.getChecksum());
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
    void promoteModel_resumesFromProductionAfterPostMoveFailure() throws Exception {
        String modelId = "lfm-resume";
        Path productionDir = tempDir.resolve(ModelType.LLM_GGML.getDirectoryName()).resolve(modelId);
        Files.createDirectories(productionDir);
        Files.write(productionDir.resolve("model.sdz"), new byte[]{7, 8, 9});
        Files.writeString(productionDir.resolve("tokenizer.json"),
                "{\"type\":\"BPE\",\"model\":{}}" + "x".repeat(100));

        // Simulate a process restart after the verified directory was moved but before
        // the registry entry was committed: no in-memory staging status remains.
        assertTrue(stagingService.promoteModel(modelId, null));
        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals("model.sdz", model.getModelFile());
        assertEquals(ModelStatus.ACTIVE, model.getStatus());
        assertTrue(Files.exists(productionDir.resolve("model.sdz")));
    }

    @Test
    void promoteVlmOnnxComponents_setsCorrectRegistryEntry() throws Exception {
        String modelId = "smoldocling-256m";

        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        Files.write(stagingDir.resolve("decoder_model_merged.onnx"), new byte[]{1, 2, 3});
        Files.write(stagingDir.resolve("vision_encoder.onnx"), new byte[]{4, 5, 6});
        Files.write(stagingDir.resolve("embed_tokens.onnx"), new byte[]{7, 8, 9});
        Files.writeString(stagingDir.resolve("tokenizer.json"),
                "{\"type\":\"BPE\",\"model\":{}}" + "x".repeat(100));

        StagingModelInfo info = StagingModelInfo.create(
                modelId,
                "huggingface:docling-project/SmolDocling-256M-preview",
                ModelType.VLM_PIPELINE);
        info.withStatus(StagingStatus.PROMOTING, 95, "Auto-promoting");
        injectStagingModel(modelId, info);

        boolean result = stagingService.promoteModel(modelId, null);
        assertTrue(result);

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.VLM_PIPELINE, model.getType());
        assertEquals("decoder_model_merged.onnx", model.getModelFile());
        assertEquals("tokenizer.json", model.getVocabFile());
        assertTrue(model.getPath().contains("vlm-pipelines"));

        Path productionDir = tempDir.resolve(model.getPath());
        assertTrue(Files.exists(productionDir.resolve("decoder_model_merged.onnx")));
        assertTrue(Files.exists(productionDir.resolve("vision_encoder.onnx")));
        assertTrue(Files.exists(productionDir.resolve("embed_tokens.onnx")));
        assertTrue(Files.exists(productionDir.resolve("tokenizer.json")));
    }

    @Test
    void promoteAudioSynthesis_preservesServingAbiAcrossRestartSafeSidecar() throws Exception {
        String modelId = "audio-synthesis-test";
        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        Files.write(stagingDir.resolve("model.sdz"), new byte[]{1, 2, 3, 4});

        AudioSynthesisConfig config = AudioSynthesisConfig.builder()
                .tokenizerType(AudioSynthesisConfig.UTF8_BYTES_TOKENIZER)
                .tokenIdsInput("text_tokens")
                .waveformOutput("waveform")
                .tokenDataType("int32")
                .sampleRateHz(24_000)
                .voice("standard")
                .language("en")
                .build();
        new ObjectMapper().writeValue(stagingDir.resolve(".audio-synthesis.json").toFile(), config);

        StagingModelInfo info = StagingModelInfo.create(
                modelId, "huggingface:example/audio", ModelType.AUDIO_SYNTHESIS);
        info.withStatus(StagingStatus.READY, 100, "Ready");
        injectStagingModel(modelId, info);

        assertTrue(stagingService.promoteModel(modelId, null));

        ModelEntry model = registryService.getModel(modelId).orElseThrow();
        assertEquals(ModelType.AUDIO_SYNTHESIS, model.getType());
        assertEquals("audio-synthesis/" + modelId, model.getPath());
        assertEquals("model.sdz", model.getModelFile());
        assertEquals(config, model.getAudioSynthesis());
        assertTrue(Files.isRegularFile(tempDir.resolve(model.getPath()).resolve(".audio-synthesis.json")));
    }

    @Test
    void promoteAudioSynthesis_rejectsMissingServingAbiWithoutMovingArtifact() throws Exception {
        String modelId = "audio-synthesis-missing-config";
        Path stagingDir = tempDir.resolve(".staging/verified").resolve(modelId);
        Files.createDirectories(stagingDir);
        Files.write(stagingDir.resolve("model.sdz"), new byte[]{1, 2, 3, 4});

        StagingModelInfo info = StagingModelInfo.create(
                modelId, "huggingface:example/audio", ModelType.AUDIO_SYNTHESIS);
        info.withStatus(StagingStatus.READY, 100, "Ready");
        injectStagingModel(modelId, info);

        assertFalse(stagingService.promoteModel(modelId, null));
        assertTrue(registryService.getModel(modelId).isEmpty());
        assertTrue(Files.isRegularFile(stagingDir.resolve("model.sdz")));
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
