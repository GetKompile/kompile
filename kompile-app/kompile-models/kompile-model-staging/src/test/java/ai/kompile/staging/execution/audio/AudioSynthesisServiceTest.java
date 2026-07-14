package ai.kompile.staging.execution.audio;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.audio.synthesis.AudioFileGenerator;
import org.eclipse.deeplearning4j.audio.synthesis.AudioSynthesisRequest;
import org.eclipse.deeplearning4j.audio.synthesis.GeneratedAudioFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AudioSynthesisServiceTest {

    @TempDir
    Path temporary;

    @Test
    void createsCompleteDurableManifestAndRetriesTheSameRunIdempotently() throws Exception {
        AtomicInteger generations = new AtomicInteger();
        AudioSynthesisService service = service((request, outputDirectory) -> {
            generations.incrementAndGet();
            Path output = outputDirectory.resolve("generated.wav");
            Files.write(output, "completed-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return generated(output);
        });

        UUID runId = UUID.randomUUID();
        AudioSynthesisService.SynthesisCommand command = command(runId, "xin chào");
        AudioSynthesisManifest first = service.synthesize(command);
        AudioSynthesisManifest retry = service.synthesize(command);

        assertEquals(first, retry);
        assertEquals(1, generations.get());
        assertEquals(runId, first.runId());
        assertEquals(runId.toString(), first.artifactReference());
        assertEquals("audio/wav", first.mediaType());
        assertEquals(64, first.contentHash().length());
        assertEquals("completed-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                first.byteLength());
        assertEquals("speech-model", first.modelId());
        assertEquals("v7", first.modelVersion());
        assertTrue(first.configurationVersion().startsWith("sha256:"));
        assertEquals("vi-standard", first.configurationEvidence().get("voice"));
        assertEquals("voice-config-v3",
                first.configurationEvidence().get("generatorConfigurationVersion"));
        assertEquals(first.configurationVersion(),
                first.configurationEvidence().get("stagingConfigurationVersion"));

        AudioSynthesisService.ArtifactContent opened = service.openArtifact(runId);
        assertArrayEquals("completed-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(opened.path()));
    }

    @Test
    void coordinatesIdempotentRetriesAcrossServiceInstances() throws Exception {
        Path root = temporary.resolve("multi-instance");
        RegistryService registry = registry(root.resolve("models"));
        AtomicInteger generations = new AtomicInteger();
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        AudioFileGenerator generator = (request, outputDirectory) -> {
            generations.incrementAndGet();
            generationEntered.countDown();
            assertTrue(releaseGeneration.await(5, TimeUnit.SECONDS));
            Path output = outputDirectory.resolve("generated.wav");
            Files.write(output, new byte[]{1, 2, 3, 4});
            return generated(output);
        };
        AudioSynthesisService firstService = serviceFor(
                registry, root.resolve("artifacts"), generator);
        AudioSynthesisService secondService = serviceFor(
                registry, root.resolve("artifacts"), generator);
        AudioSynthesisService.SynthesisCommand command =
                command(UUID.randomUUID(), "same request");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<AudioSynthesisManifest> first = executor.submit(() -> {
                start.await();
                return firstService.synthesize(command);
            });
            Future<AudioSynthesisManifest> second = executor.submit(() -> {
                start.await();
                return secondService.synthesize(command);
            });
            start.countDown();
            assertTrue(generationEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
            releaseGeneration.countDown();

            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, generations.get());
        } finally {
            releaseGeneration.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void modelContractsDefensivelyFreezeNestedConfiguration() {
        List<Object> voices = new ArrayList<>(List.of("standard"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("voices", voices);
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("synthesis", nested);

        AudioSynthesisRequest request = new AudioSynthesisRequest(
                UUID.randomUUID(), "hello", "standard", "en", configuration);
        voices.add("mutated");

        Map<?, ?> frozenNested = (Map<?, ?>) request.getConfiguration().get("synthesis");
        List<?> frozenVoices = (List<?>) frozenNested.get("voices");
        assertEquals(List.of("standard"), frozenVoices);
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) frozenVoices).add("forbidden"));
    }

    @Test
    void rejectsConflictingRetryForTheSameRun() throws Exception {
        AudioSynthesisService service = service((request, outputDirectory) -> {
            Path output = outputDirectory.resolve("generated.wav");
            Files.write(output, new byte[]{1, 2, 3});
            return generated(output);
        });
        UUID runId = UUID.randomUUID();
        service.synthesize(command(runId, "first"));

        assertThrows(AudioSynthesisService.IdempotencyConflictException.class,
                () -> service.synthesize(command(runId, "different")));
    }

    @Test
    void rejectsMissingOrEscapedCompletedFiles() throws Exception {
        AudioSynthesisService missing = service((request, outputDirectory) ->
                generated(outputDirectory.resolve("missing.wav")));
        assertThrows(AudioSynthesisService.InvalidGeneratedFileException.class,
                () -> missing.synthesize(command(UUID.randomUUID(), "missing")));

        Path outside = temporary.resolve("outside.wav");
        Files.write(outside, new byte[]{1});
        AudioSynthesisService escaped = serviceIn(temporary.resolve("escaped"),
                (request, outputDirectory) -> generated(outside));
        assertThrows(AudioSynthesisService.InvalidGeneratedFileException.class,
                () -> escaped.synthesize(command(UUID.randomUUID(), "escaped")));
    }

    @Test
    void reportsAStoredManifestWhoseGeneratedFileHasDisappearedAsGone() throws Exception {
        AudioSynthesisService service = service((request, outputDirectory) -> {
            Path output = outputDirectory.resolve("generated.wav");
            Files.write(output, new byte[]{1, 2, 3, 4});
            return generated(output);
        });
        UUID runId = UUID.randomUUID();
        service.synthesize(command(runId, "hello"));
        AudioSynthesisService.ArtifactContent content = service.openArtifact(runId);
        Files.delete(content.path());

        assertThrows(AudioSynthesisService.ArtifactGoneException.class,
                () -> service.openArtifact(runId));
    }

    @Test
    void rejectsAnActiveModelWhoseBytesDoNotMatchTheRegisteredChecksum() throws Exception {
        Path root = temporary.resolve("checksum-mismatch");
        RegistryService registry = registry(root.resolve("models"));
        Files.write(root.resolve("models/audio-synthesis/speech-model/model.sdz"),
                new byte[]{9, 9, 9});
        AudioSynthesisService service = serviceFor(
                registry, root.resolve("artifacts"),
                (request, outputDirectory) -> {
                    fail("generator must not run for an unverified model");
                    return null;
                });

        AudioSynthesisService.ModelUnavailableException error = assertThrows(
                AudioSynthesisService.ModelUnavailableException.class,
                () -> service.synthesize(command(UUID.randomUUID(), "hello")));
        assertTrue(error.getMessage().contains("SHA-256"));
    }

    @Test
    void failsClosedWhenNoActiveSupportedAudioModelCanBeLoaded() throws Exception {
        RegistryService registry = registry(temporary.resolve("unavailable-models"));
        AudioSynthesisService service = new AudioSynthesisService(
                registry, List.of(), new ObjectMapper(), temporary.resolve("unavailable-artifacts"));

        assertThrows(AudioSynthesisService.ModelUnavailableException.class,
                () -> service.synthesize(command(UUID.randomUUID(), "hello")));
    }

    private AudioSynthesisService service(AudioFileGenerator generator) throws Exception {
        return serviceIn(temporary.resolve("default"), generator);
    }

    private AudioSynthesisService serviceIn(Path root, AudioFileGenerator generator) throws Exception {
        RegistryService registry = registry(root.resolve("models"));
        return serviceFor(registry, root.resolve("artifacts"), generator);
    }

    private AudioSynthesisService serviceFor(RegistryService registry, Path artifacts,
                                              AudioFileGenerator generator) {
        AudioSynthesisBackend backend = new AudioSynthesisBackend() {
            @Override
            public boolean supports(ModelEntry model) {
                return model.getType() == ModelType.AUDIO_SYNTHESIS;
            }

            @Override
            public AudioFileGenerator load(ModelEntry model, Path modelDirectory) {
                return generator;
            }
        };
        return new AudioSynthesisService(registry, List.of(backend), new ObjectMapper(), artifacts);
    }

    private RegistryService registry(Path models) throws Exception {
        RegistryService registry = new RegistryService(models);
        Path modelDirectory = models.resolve("audio-synthesis/speech-model");
        Files.createDirectories(modelDirectory);
        Path modelFile = modelDirectory.resolve("model.sdz");
        Files.write(modelFile, new byte[]{7, 7, 7});
        registry.addModel(ModelEntry.builder()
                .modelId("speech-model")
                .type(ModelType.AUDIO_SYNTHESIS)
                .path("audio-synthesis/speech-model")
                .modelFile("model.sdz")
                .checksum("sha256:" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(modelFile))))
                .metadata(ModelMetadata.builder().version("v7").framework("samediff").build())
                .status(ModelStatus.ACTIVE)
                .build());
        return registry;
    }

    private static AudioSynthesisService.SynthesisCommand command(UUID runId, String text) {
        return new AudioSynthesisService.SynthesisCommand(
                runId, text, "vi-standard", "vi", Map.of("temperature", 0.2));
    }

    private static GeneratedAudioFile generated(Path output) {
        return new GeneratedAudioFile(output, "audio/wav", "speech-model", "v7",
                "voice-config-v3", 0.98, Map.of("voice", "vi-standard"));
    }
}
