package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.project.LocalProjectModelBootstrap;
import ai.kompile.modelmanager.KompileModelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KompileLocalModelsTest {

    @TempDir
    Path tempDir;

    // == Project-registry-first resolution ==================================

    @Test
    void projectRegistryModelWinsOverInstalledScan() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"))
                .toAbsolutePath();
        ai.kompile.project.KompileProjectStore store =
                new ai.kompile.project.KompileProjectStore();
        ai.kompile.project.KompileProjectInitRequest init =
                new ai.kompile.project.KompileProjectInitRequest();
        init.setName("proj");
        init.setInitializeGit(false);
        store.init(projectRoot, init);

        ai.kompile.project.KompileProjectModel model =
                new ai.kompile.project.KompileProjectModel();
        model.setId("chat-model");
        model.setModelId("chat-model");
        model.setRegistryModelId("chat-model");
        model.setRole("LLM");
        model.setSource("LOCAL");
        model.setPath("data/models/chat/model.sdz");
        model.getMetadata().put("registry.type", "llm_ggml");
        model.getMetadata().put("artifact.stage", "RUNTIME");
        store.registerModel(projectRoot, model);

        Path modelDirectory = projectRoot.resolve("data/models/chat");
        Files.createDirectories(modelDirectory);
        Files.writeString(modelDirectory.resolve("model.sdz"), "weights");
        Files.writeString(modelDirectory.resolve("tokenizer.json"), "{}");

        String previousDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", projectRoot.toString());
            Path artifact = LocalProjectModelBootstrap.projectModelArtifact("chat-model");
            assertNotNull(artifact, "the registered project model must resolve");
            assertTrue(artifact.startsWith(projectRoot));
            assertEquals(modelDirectory.resolve("model.sdz").toAbsolutePath(), artifact);

            assertNull(LocalProjectModelBootstrap.projectModelArtifact("no-such-model"));
        } finally {
            System.setProperty("user.dir", previousDir);
        }
    }

    // == Picker inventory ====================================================

    @Test
    void discoveryInventoryIncludesCachedHuggingFaceAndInstalledModels() throws Exception {
        Path cache = tempDir.resolve("hf-cache");
        Path modelDirectory = cache.resolve("pipelines").resolve("Test_Model");
        Files.createDirectories(modelDirectory);
        Files.writeString(modelDirectory.resolve("model.gguf"), "weights");
        Files.writeString(modelDirectory.resolve("config.json"), "{}");
        KompileModelManager manager = new KompileModelManager(cache);

        // User models root: ~/.kompile/models
        String previousHome = System.getProperty("user.home");
        try {
            Path fakeHome = Files.createDirectories(tempDir.resolve("home"));
            System.setProperty("user.home", fakeHome.toString());
            Files.createDirectories(fakeHome.resolve(".kompile").resolve("models"));
            Files.writeString(fakeHome.resolve(".kompile").resolve("models")
                    .resolve("installed.gguf"), "weights");

            List<LiveModelDiscovery.Model> models =
                    KompileLocalModels.discover(manager).stream()
                            // Only entries contributed by the temp fixtures.
                            .filter(entry -> entry.id().equalsIgnoreCase("Test/Model")
                                    || entry.id().equalsIgnoreCase("installed"))
                            .toList();

            assertTrue(models.stream().anyMatch(entry -> "Test/Model".equals(entry.id())),
                    "cached HuggingFace repo id must appear in the picker: " + models);
            assertTrue(models.stream().anyMatch(entry -> "installed".equals(entry.id())),
                    "installed model must appear in the picker: " + models);
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void huggingFaceRepoIdValidation() {
        assertTrue(KompileLocalModels.isHuggingFaceRepoId("Qwen/Qwen2.5-0.5B-Instruct-GGUF"));
        assertTrue(KompileLocalModels.isHuggingFaceRepoId("org/name.v2"));
        assertFalse(KompileLocalModels.isHuggingFaceRepoId("just-a-name"));
        assertFalse(KompileLocalModels.isHuggingFaceRepoId("/absolute/path"));
        assertFalse(KompileLocalModels.isHuggingFaceRepoId("a/b/c"));
        assertFalse(KompileLocalModels.isHuggingFaceRepoId(null));
        assertFalse(KompileLocalModels.isHuggingFaceRepoId("  "));
    }

    @Test
    void downloadRejectsNonRepositoryIdsWithoutTouchingTheNetwork() {
        Exception error = assertThrowsMaybe();
        assertTrue(error.getMessage().contains("not a HuggingFace repository id"));
    }

    private static Exception assertThrowsMaybe() {
        try {
            KompileLocalModels.downloadFromHuggingFace("not-a-repo-id", null, null, null);
        } catch (Exception e) {
            return e;
        }
        throw new AssertionError("expected a validation failure");
    }
}
