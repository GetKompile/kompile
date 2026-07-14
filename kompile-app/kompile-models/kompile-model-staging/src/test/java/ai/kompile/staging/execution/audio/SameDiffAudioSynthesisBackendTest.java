package ai.kompile.staging.execution.audio;

import ai.kompile.modelmanager.registry.AudioSynthesisConfig;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.audio.synthesis.AudioFileGenerator;
import org.eclipse.deeplearning4j.audio.synthesis.AudioSynthesisRequest;
import org.eclipse.deeplearning4j.audio.synthesis.GeneratedAudioFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SameDiffAudioSynthesisBackendTest {

    @TempDir
    Path temporary;

    private final SameDiffAudioSynthesisBackend backend =
            new SameDiffAudioSynthesisBackend(new ObjectMapper());

    @Test
    void loadsRegisteredGraphAndMaterializesCompletedWaveformFile() throws Exception {
        Path modelDirectory = temporary.resolve("working-model");
        Path modelFile = saveWaveformGraph(modelDirectory);
        ModelEntry model = model(modelFile, config().build());

        assertTrue(backend.supports(model));
        try (AudioFileGenerator generator = backend.load(model, modelDirectory)) {
            UUID runId = UUID.randomUUID();
            Path outputDirectory = temporary.resolve("output");
            Files.createDirectories(outputDirectory);
            GeneratedAudioFile generated = generator.generate(
                    new AudioSynthesisRequest(runId, "Hi", "standard", "en", Map.of()),
                    outputDirectory);

            assertTrue(Files.isRegularFile(generated.getCompletedFile()));
            assertEquals(44 + "Hi".getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 2,
                    Files.size(generated.getCompletedFile()));
            assertEquals("audio/wav", generated.getMediaType());
            assertEquals("tts-waveform", generated.getModelId());
            assertEquals("v1", generated.getModelVersion());
            assertTrue(generated.getConfigurationVersion().startsWith("sha256:"));
            assertEquals(0.8d, generated.getConfidence(), 0.0d);
            assertEquals("samediff_waveform",
                    generated.getConfigurationEvidence().get("backend"));
            assertEquals(2, generated.getConfigurationEvidence().get("inputTokens"));
            assertEquals(2L, generated.getConfigurationEvidence().get("outputSamples"));
        }
    }

    @Test
    void rejectsUnregisteredVoiceLanguageAndArbitraryInferenceOptions() throws Exception {
        Path modelDirectory = temporary.resolve("request-validation");
        Path modelFile = saveWaveformGraph(modelDirectory);
        try (AudioFileGenerator generator =
                     backend.load(model(modelFile, config().build()), modelDirectory)) {
            Path output = temporary.resolve("request-output");
            Files.createDirectories(output);

            assertThrows(IllegalArgumentException.class, () -> generator.generate(
                    new AudioSynthesisRequest(UUID.randomUUID(), "hello",
                            "filesystem/voice", "en", Map.of()), output));
            assertThrows(IllegalArgumentException.class, () -> generator.generate(
                    new AudioSynthesisRequest(UUID.randomUUID(), "hello",
                            "standard", "fr", Map.of()), output));
            assertThrows(IllegalArgumentException.class, () -> generator.generate(
                    new AudioSynthesisRequest(UUID.randomUUID(), "hello",
                            "standard", "en", Map.of("temperature", 0.2)), output));
        }
    }

    @Test
    void rejectsMissingGraphVariablesAndTokenizerPathEscape() throws Exception {
        Path modelDirectory = temporary.resolve("invalid-config");
        Path modelFile = saveWaveformGraph(modelDirectory);

        ModelEntry wrongOutput = model(modelFile,
                config().waveformOutput("missing_samples").build());
        IllegalArgumentException outputError = assertThrows(IllegalArgumentException.class,
                () -> backend.load(wrongOutput, modelDirectory));
        assertTrue(outputError.getMessage().contains("waveform"));

        Path outsideTokenizer = temporary.resolve("outside-tokenizer.json");
        Files.writeString(outsideTokenizer, "{}");
        ModelEntry escapedTokenizer = model(modelFile, config()
                .tokenizerType(AudioSynthesisConfig.HUGGING_FACE_TOKENIZER)
                .tokenizerFile("../outside-tokenizer.json")
                .build());
        IllegalArgumentException pathError = assertThrows(IllegalArgumentException.class,
                () -> backend.load(escapedTokenizer, modelDirectory));
        assertTrue(pathError.getMessage().contains("tokenizer file"));
    }

    @Test
    void rejectsWaveformsThatExceedRegisteredOutputLimit() throws Exception {
        Path modelDirectory = temporary.resolve("limited-model");
        Path modelFile = saveWaveformGraph(modelDirectory);
        ModelEntry model = model(modelFile, config().maxOutputSamples(1).build());

        try (AudioFileGenerator generator = backend.load(model, modelDirectory)) {
            Path outputDirectory = temporary.resolve("limited-output");
            Files.createDirectories(outputDirectory);
            assertThrows(IllegalArgumentException.class, () -> generator.generate(
                    new AudioSynthesisRequest(UUID.randomUUID(), "two",
                            "standard", "en", Map.of()),
                    outputDirectory));
        }
    }

    private Path saveWaveformGraph(Path modelDirectory) throws Exception {
        Files.createDirectories(modelDirectory);
        Path modelFile = modelDirectory.resolve("model.sdz");
        try (SameDiff sameDiff = SameDiff.create()) {
            SDVariable tokens = sameDiff.placeHolder("tokens", DataType.INT32, -1, -1);
            tokens.castTo(DataType.FLOAT)
                    .div(255.0d)
                    .mul(2.0d)
                    .sub(1.0d)
                    .rename("samples");
            sameDiff.save(modelFile.toFile(), true);
        }
        return modelFile;
    }

    private ModelEntry model(Path modelFile, AudioSynthesisConfig config) throws Exception {
        String checksum = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(modelFile)));
        return ModelEntry.builder()
                .modelId("tts-waveform")
                .type(ModelType.AUDIO_SYNTHESIS)
                .path(modelFile.getParent().getFileName().toString())
                .modelFile(modelFile.getFileName().toString())
                .checksum(checksum)
                .metadata(ModelMetadata.builder()
                        .version("v1")
                        .framework("samediff")
                        .modelType("text_to_waveform")
                        .build())
                .audioSynthesis(config)
                .status(ModelStatus.ACTIVE)
                .build();
    }

    private AudioSynthesisConfig.AudioSynthesisConfigBuilder config() {
        return AudioSynthesisConfig.builder()
                .tokenizerType(AudioSynthesisConfig.UTF8_BYTES_TOKENIZER)
                .tokenIdsInput("tokens")
                .waveformOutput("samples")
                .tokenDataType("int32")
                .sampleRateHz(16_000)
                .maxInputTokens(64)
                .maxOutputSamples(64)
                .voice("standard")
                .language("en")
                .defaultConfidence(0.8d);
    }
}
