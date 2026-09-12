package ai.kompile.cli.main.chat;

import ai.kompile.modelmanager.KompileModelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KompileLocalServingModelResolutionTest {

    @TempDir
    Path tempDir;

    @Test
    void huggingFaceRepoIdsResolveFromTheSharedCacheWithoutANetwork() throws Exception {
        Path cache = tempDir.resolve("hf-cache");
        Path modelDirectory =
                cache.resolve("pipelines").resolve("Qwen_Qwen2.5-0.5B-Instruct-GGUF");
        Files.createDirectories(modelDirectory);
        Files.writeString(modelDirectory.resolve("model.gguf"), "weights");
        Files.writeString(modelDirectory.resolve("tokenizer.json"), "{}");
        KompileModelManager manager = new KompileModelManager(cache);

        KompileLocalServingBootstrap.ResolvedModel resolved =
                KompileLocalServingBootstrap.resolveHuggingFaceModel(
                        "Qwen/Qwen2.5-0.5B-Instruct-GGUF", null, null, Map.of(), manager);

        assertEquals("Qwen/Qwen2.5-0.5B-Instruct-GGUF", resolved.modelId());
        assertEquals(modelDirectory.resolve("model.gguf").toAbsolutePath(),
                resolved.modelPath());
        assertEquals(modelDirectory.resolve("tokenizer.json").toAbsolutePath(),
                resolved.tokenizerPath());
    }

    @Test
    void repoIdLookingLikeARelativePathResolvesFromHuggingFaceWhenNoDirectoryExists()
            throws Exception {
        Path cache = tempDir.resolve("hf-cache");
        Path modelDirectory = cache.resolve("pipelines").resolve("Owner_model");
        Files.createDirectories(modelDirectory);
        Files.writeString(modelDirectory.resolve("model.sdz"), "weights");
        Files.writeString(modelDirectory.resolve("tokenizer.json"), "{}");
        KompileModelManager manager = new KompileModelManager(cache);

        // Owner/model never exists as a local directory here, so the HF route wins.
        String previousDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            KompileLocalServingBootstrap.ResolvedModel resolved =
                    KompileLocalServingBootstrap.resolveHuggingFaceModel(
                            "Owner/model", null, null, Map.of(), manager);
            assertEquals(modelDirectory.resolve("model.sdz").toAbsolutePath(),
                    resolved.modelPath());
        } finally {
            System.setProperty("user.dir", previousDir);
        }
    }

    @Test
    void anExistingLocalPathWinsOverAHuggingFaceRepoId() throws Exception {
        Path existing = Files.createDirectories(tempDir.resolve("Owner").resolve("model"));
        Files.writeString(existing.resolve("model.gguf"), "weights");
        Files.writeString(existing.resolve("tokenizer.json"), "{}");

        // Absolute selection containing owner/model segments: the on-disk
        // directory wins and no download is attempted.
        KompileLocalServingBootstrap.ResolvedModel resolved =
                KompileLocalServingBootstrap.resolveModel(
                        existing.toString(), null, null, Map.of());
        assertEquals(existing.resolve("model.gguf").toAbsolutePath(),
                resolved.modelPath());
    }

    @Test
    void aLocalDirectoryWithOnlyConvertibleSourcesIsAccepted() throws Exception {
        Path directory = tempDir.resolve("converted");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("model.safetensors"), "weights");
        Files.writeString(directory.resolve("tokenizer.json"), "{}");

        KompileLocalServingBootstrap.ResolvedModel resolved =
                KompileLocalServingBootstrap.resolveLocalModel(directory, "converted");

        assertEquals(directory.resolve("model.safetensors").toAbsolutePath(),
                resolved.modelPath());
    }

    @Test
    void anExplicitSafetensorsFileIsAcceptedForStaging() throws Exception {
        Path model = Files.writeString(tempDir.resolve("weights.safetensors"), "weights");
        Files.writeString(tempDir.resolve("tokenizer.json"), "{}");

        KompileLocalServingBootstrap.ResolvedModel resolved =
                KompileLocalServingBootstrap.resolveLocalModel(model, "weights");

        assertEquals(model.toAbsolutePath(), resolved.modelPath());
        assertTrue(KompileLocalServingBootstrap.isConvertibleSourceFile(model));
    }
}
