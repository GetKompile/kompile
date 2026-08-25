/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.cli.main.project;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectModelAcquisitionTest {

    @TempDir
    Path tempDir;

    @Test
    void dryRunResolvesPinnedComponentsWithoutStagingOrProjectMutation() throws Exception {
        LocalProjectModelAcquisition.Result result = LocalProjectModelAcquisition.acquire(
                tempDir,
                "multilingual-e5-small",
                Map.of(
                        "source", "HUGGINGFACE",
                        "repository", "intfloat/multilingual-e5-small",
                        "revision", "614241f622f53c4eeff9890bdc4f31cfecc418b3"),
                true,
                true);

        assertEquals("preview", result.disposition());
        assertTrue(result.dryRun());
        assertFalse(result.downloaded());
        assertTrue(result.modelPath().endsWith("data/models/multilingual-e5-small/model.onnx"));
        assertTrue(result.tokenizerPath().endsWith("data/models/multilingual-e5-small/tokenizer.json"));
        assertFalse(Files.exists(tempDir.resolve("data/models/multilingual-e5-small")));
        assertFalse(Files.exists(tempDir.resolve("kompile.project.json")));
    }

    @Test
    void convertedEncoderIsRegisteredWithItsExactRuntimeContract() throws Exception {
        Path modelDirectory = Files.createDirectories(
                tempDir.resolve("data/models/multilingual-e5-small"));
        Path converted = Files.write(modelDirectory.resolve("model.sdz"), new byte[2_048]);
        Files.writeString(modelDirectory.resolve("tokenizer.json"), "{}");

        ModelEntry registered = LocalProjectModelAcquisition.registerConverted(
                tempDir, "multilingual-e5-small", converted);

        assertEquals("model.sdz", registered.getModelFile());
        assertEquals("tokenizer.json", registered.getVocabFile());
        assertEquals(384, registered.getMetadata().getEmbeddingDim());
        assertEquals("MEAN", registered.getMetadata().getPoolingStrategy());
        assertEquals("query: ", registered.getMetadata().getInputPrefix());
        assertTrue(registered.getMetadata().getNormalizeOutput());
        assertEquals(64, registered.getChecksum().length());
        assertEquals(registered.getModelId(),
                new RegistryService(tempDir.resolve("data/models"))
                        .getModel("multilingual-e5-small").orElseThrow().getModelId());
        assertTrue(Files.isRegularFile(tempDir.resolve("data/models/registry.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve("kompile.project.json")));
    }

    @Test
    void localSourceImportIsDiscoverableButNotReportedRuntimeReady() throws Exception {
        Path source = Files.write(tempDir.resolve("source.onnx"), new byte[]{1, 2, 3});

        LocalProjectModelAcquisition.acquire(
                tempDir, "local-source", Map.of("localPath", source.toString()),
                false, false);

        Map<String, Object> inventory = LocalProjectModelBootstrap.inventory(tempDir).stream()
                .filter(item -> "local-source".equals(item.get("modelId")))
                .findFirst().orElseThrow();
        assertEquals("SOURCE", inventory.get("artifactStage"));
        assertEquals("CONVERSION_REQUIRED", inventory.get("runtimeStatus"));
        assertEquals(false, inventory.get("ready"));
    }
}
