/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.modelmanager;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedModelArtifactDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void multilingualE5ManifestIsPinnedAndDryRunDoesNotWrite() throws Exception {
        ManagedModelArtifactCatalog.Definition definition =
                ManagedModelArtifactCatalog.find("multilingual-e5-small").orElseThrow();

        assertEquals("intfloat/multilingual-e5-small", definition.repository());
        assertEquals("614241f622f53c4eeff9890bdc4f31cfecc418b3", definition.revision());
        assertEquals("MEAN", definition.metadata().get("pooling_strategy"));
        assertEquals("query: ", definition.metadata().get("input_prefix"));
        assertEquals("https://huggingface.co/intfloat/multilingual-e5-small/resolve/"
                        + "614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx/model.onnx",
                ManagedModelArtifactDownloader.componentUrl(
                        definition, definition.component("model").orElseThrow()).toString());

        ManagedModelArtifactDownloader.Acquisition acquisition =
                new ManagedModelArtifactDownloader().acquire(
                        definition, tempDir.resolve("model"), false, true);
        assertFalse(acquisition.downloaded());
        assertFalse(Files.exists(acquisition.directory()));
        assertTrue(acquisition.primaryModel().endsWith("model.onnx"));
        assertTrue(acquisition.tokenizer().endsWith("tokenizer.json"));
    }

    @Test
    void relativeRedirectIsResolvedAgainstPinnedSourceUrl() throws Exception {
        assertEquals(
                "https://huggingface.co/intfloat/cdn/model.onnx",
                ManagedModelArtifactDownloader.resolveRedirect(
                        URI.create("https://huggingface.co/intfloat/model/resolve/rev/model.onnx").toURL(),
                        "../../../cdn/model.onnx").toString());
    }

    @Test
    void concurrentRegistryInstancesDoNotLoseModelRegistrations() throws Exception {
        RegistryService first = new RegistryService(tempDir);
        RegistryService second = new RegistryService(tempDir);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWrite = writers.submit(() -> {
                await(start);
                first.addModel(entry("encoder-a"));
            });
            Future<?> secondWrite = writers.submit(() -> {
                await(start);
                second.addModel(entry("encoder-b"));
            });
            firstWrite.get();
            secondWrite.get();
        } finally {
            writers.shutdownNow();
        }

        RegistryService reloaded = new RegistryService(tempDir);
        assertTrue(reloaded.getModel("encoder-a").isPresent());
        assertTrue(reloaded.getModel("encoder-b").isPresent());
    }

    @Test
    void boundedCopyStopsBeforeAnOversizedComponentCanFillDisk() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThrows(java.io.IOException.class, () ->
                ManagedModelArtifactDownloader.copyBounded(
                        new ByteArrayInputStream(new byte[9]), output, 8));
        assertTrue(output.size() <= 8);
    }

    @Test
    void runtimeRegistrationRequiresAndVerifiesEveryPinnedComponent() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("managed-test"));
        Path source = Files.write(bundle.resolve("source.onnx"), new byte[]{1, 2, 3, 4});
        Path tokenizer = Files.writeString(
                bundle.resolve("tokenizer.json"), "{}", StandardCharsets.UTF_8);
        Path config = Files.writeString(
                bundle.resolve("config.json"), "{\"type\":\"test\"}", StandardCharsets.UTF_8);
        Path converted = Files.write(bundle.resolve("model.sdz"), new byte[2_048]);
        ManagedModelArtifactCatalog.Definition definition = new ManagedModelArtifactCatalog.Definition(
                "managed-test", "TEST", "example/test", "revision", "ONNX",
                "dense_encoder", "ENCODER", "source", "tokenizer",
                List.of(component("source", source), component("tokenizer", tokenizer),
                        component("config", config)),
                Map.of("embedding_dim", "8", "max_sequence_length", "16"));

        ModelEntry registered = ManagedModelRuntimeRegistrar.register(
                tempDir, definition, converted, tokenizer);

        assertEquals(ManagedModelArtifactDownloader.sha256(converted), registered.getChecksum());
        assertEquals("revision", registered.getMetadata().getVersion());
        assertTrue(new RegistryService(tempDir).getModel("managed-test").isPresent());
    }

    private static ManagedModelArtifactCatalog.Component component(String key, Path path)
            throws Exception {
        return new ManagedModelArtifactCatalog.Component(
                key, path.getFileName().toString(), path.getFileName().toString(),
                ManagedModelArtifactDownloader.sha256(path), Files.size(path), true);
    }

    private static ModelEntry entry(String id) {
        return ModelEntry.builder()
                .modelId(id)
                .type(ModelType.DENSE_ENCODER)
                .path(id)
                .modelFile("model.sdz")
                .vocabFile("tokenizer.json")
                .status(ModelStatus.ACTIVE)
                .build();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
