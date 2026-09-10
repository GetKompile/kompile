/* Copyright 2025 Kompile Inc. Licensed under Apache-2.0. */
package ai.kompile.cli.main.project;

import ai.kompile.modelmanager.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void convertedManagedEncoderRequiresItsPinnedSourceBundle() throws Exception {
        Path modelDirectory = Files.createDirectories(
                tempDir.resolve("data/models/multilingual-e5-small"));
        Path converted = Files.write(modelDirectory.resolve("model.sdz"), new byte[2_048]);
        Files.writeString(modelDirectory.resolve("tokenizer.json"), "{}");

        assertThrows(java.io.IOException.class, () ->
                LocalProjectModelAcquisition.registerConverted(
                        tempDir, "multilingual-e5-small", converted));
        assertTrue(new RegistryService(tempDir.resolve("data/models"))
                .getModel("multilingual-e5-small").isEmpty());
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

    @Test
    void localGgufDirectoryImportIsReportedAsRuntimeReady() throws Exception {
        Path bundle = Files.createDirectories(tempDir.resolve("qwen-bundle"));
        Path gguf = Files.write(bundle.resolve("model.gguf"), new byte[]{1, 2, 3});
        Path tokenizer = Files.writeString(bundle.resolve("tokenizer.json"), "{}");

        LocalProjectModelAcquisition.Result result = LocalProjectModelAcquisition.acquire(
                tempDir, "qwen-local", Map.of("localPath", bundle.toString()),
                false, false);

        assertEquals(gguf.toAbsolutePath().normalize(), result.modelPath());
        assertEquals(tokenizer.toAbsolutePath().normalize(), result.tokenizerPath());
        Map<String, Object> inventory = LocalProjectModelBootstrap.inventory(tempDir).stream()
                .filter(item -> "qwen-local".equals(item.get("modelId")))
                .findFirst().orElseThrow();
        assertEquals("RUNTIME", inventory.get("artifactStage"));
        assertEquals("NOT_PROBED", inventory.get("runtimeStatus"));
        assertEquals(true, inventory.get("ready"));
    }
}
