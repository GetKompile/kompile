package ai.kompile.cli.main.chat;

import ai.kompile.modelmanager.KompileModelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void installedInventoryListsEachServableModelOnceAndEveryIdResolvesBackToIt()
            throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("user-home"));
        Path kompileHome = home.resolve(".kompile");
        Path models = kompileHome.resolve("models");
        Path vlm = Files.createDirectories(models.resolve("vlm").resolve("smoldocling-256m"));
        for (String part : List.of("vision_encoder", "decoder_model_merged", "embed_tokens")) {
            Files.writeString(vlm.resolve(part + ".sdz"), "weights");
            Files.writeString(vlm.resolve(part + ".opt.sdz"), "weights");
        }
        Files.writeString(vlm.resolve("tokenizer.json"), "{}");
        Path llm = Files.createDirectories(models.resolve("llm-ggmls").resolve("supra-50m-instruct"));
        Files.writeString(llm.resolve("Supra-50M-f16.gguf"), "weights");
        Files.writeString(llm.resolve("model.sdz"), "weights");
        Files.writeString(llm.resolve("tokenizer.json"), "{}");
        Files.createDirectories(llm.resolve(".cache"));
        Files.writeString(llm.resolve(".cache").resolve("shard.sdz"), "weights");
        Path encoder = Files.createDirectories(models.resolve("encoders").resolve("bge-base-en-v1.5"));
        Files.writeString(encoder.resolve("model.sdz"), "weights");
        Files.writeString(encoder.resolve("vocab.txt"), "[CLS]");
        Path staging = Files.createDirectories(models.resolve(".staging").resolve("partial"));
        Files.writeString(staging.resolve("model.gguf"), "weights");
        Files.writeString(staging.resolve("tokenizer.json"), "{}");
        Path chat = Files.createDirectories(models.resolve("chat"));
        Files.writeString(chat.resolve("qwen2.5-0.5b-instruct-q4_k_m.gguf"), "weights");
        Files.writeString(chat.resolve("qwen2.5-0.5b-instruct-q4_k_m.sdz"), "weights");
        Files.writeString(chat.resolve("qwen2.5-0.5b-instruct-fp16.gguf"), "weights");
        Files.writeString(chat.resolve("qwen2.5-1.5b-instruct-fp16.gguf"), "weights");
        Path tokenizers = Files.createDirectories(models.resolve("tokenizers").resolve("qwen2.5-0.5b"));
        Files.writeString(tokenizers.resolve("tokenizer.json"), "{}");
        Path huggingFace = Files.createDirectories(models.resolve("pipelines").resolve("Owner_model"));
        Files.writeString(huggingFace.resolve("model.gguf"), "weights");
        Files.writeString(huggingFace.resolve("tokenizer.json"), "{}");

        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            List<String> ids = KompileLocalServingBootstrap.installedModelIds(
                    null, kompileHome, models.resolve("pipelines"), Map.of());

            // Components, precision variants, encoders without a chat tokenizer, hidden
            // scratch, and the repo-id-listed HuggingFace cache are not models of their own.
            assertEquals(Set.of("qwen2.5-0.5b-instruct-fp16", "qwen2.5-0.5b-instruct-q4_k_m",
                            "smoldocling-256m", "supra-50m-instruct"),
                    Set.copyOf(ids), ids.toString());
            assertEquals(ids.size(), Set.copyOf(ids).size(), ids.toString());

            assertEquals(vlm, resolve("smoldocling-256m", kompileHome).modelPath());
            assertEquals(llm.resolve("Supra-50M-f16.gguf"),
                    resolve("supra-50m-instruct", kompileHome).modelPath());
            assertEquals(chat.resolve("qwen2.5-0.5b-instruct-fp16.gguf"),
                    resolve("qwen2.5-0.5b-instruct-fp16", kompileHome).modelPath());
            assertEquals(chat.resolve("qwen2.5-0.5b-instruct-q4_k_m.gguf"),
                    resolve("qwen2.5-0.5b-instruct-q4_k_m", kompileHome).modelPath());
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    private static KompileLocalServingBootstrap.ResolvedModel resolve(String id, Path kompileHome)
            throws Exception {
        return KompileLocalServingBootstrap.resolveModel(id, null, kompileHome, Map.of());
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
