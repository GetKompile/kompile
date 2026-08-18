package ai.kompile.modelmanager.vlm.registry;

import ai.kompile.modelmanager.vlm.dynamic.VlmCustomModelSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VlmModelDefinitionRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void createsUpdatesAndPersistsProviderNeutralDefinitions() {
        VlmPipelineRegistry registry = VlmPipelineRegistry.create(tempDir);
        VlmCustomModelSet definition = VlmCustomModelSet.builder()
                .setId("custom-provider-vlm")
                .displayName("Custom Provider VLM")
                .description("Provider-neutral test definition")
                .provider("test-provider")
                .source(VlmCustomModelSet.ModelSource.CUSTOM_URL)
                .repository("registry://models/custom-provider-vlm")
                .revision("2026-08")
                .format("safetensors")
                .modelType("custom-vlm")
                .runtime(Map.of("type", "custom-vlm", "autoBootstrap", true))
                .metadata(Map.of("tenant", "test"))
                .addComponent(VlmCustomModelSet.VlmModelComponentConfig.builder()
                        .componentKey("vision")
                        .fileName("vision.bin")
                        .downloadUrl("https://models.example.test/vision.bin")
                        .pipelineStage("VISION_ENCODING")
                        .build())
                .build();

        assertTrue(definition.validate().isEmpty());
        assertTrue(registry.registerModelSet(definition).isEmpty());

        VlmCustomModelSet stored = registry.getModelSet("custom-provider-vlm").orElseThrow();
        assertEquals("test-provider", stored.getProvider());
        assertEquals("registry://models/custom-provider-vlm", stored.getRepository());
        assertEquals("test-provider", stored.toDefinitionMap().get("provider"));
        assertEquals("custom-vlm", stored.toDefinitionMap().get("type"));
        assertEquals("VLM", stored.toDefinitionMap().get("role"));
        assertEquals("VISION_ENCODING", stored.getComponents().get(0).getPipelineStage());

        VlmCustomModelSet update = VlmCustomModelSet.builder()
                .setId("ignored-by-path")
                .displayName("Updated Custom Provider VLM")
                .provider("another-provider")
                .localPath("data/models/custom-provider-vlm")
                .modelType("another-vlm")
                .build();
        assertTrue(registry.updateModelSet("custom-provider-vlm", update).isEmpty());
        assertEquals("custom-provider-vlm", update.getSetId());
        assertEquals("another-provider",
                registry.getModelSet("custom-provider-vlm").orElseThrow().getProvider());

        VlmPipelineRegistry reloaded = VlmPipelineRegistry.create(tempDir);
        assertEquals("Updated Custom Provider VLM",
                reloaded.getModelSet("custom-provider-vlm").orElseThrow().getDisplayName());
        assertTrue(reloaded.deleteModelSet("custom-provider-vlm"));
        assertTrue(reloaded.getModelSet("custom-provider-vlm").isEmpty());
    }

    @Test
    void rejectsDefinitionsWithoutAProviderLocator() {
        VlmPipelineRegistry registry = VlmPipelineRegistry.create(tempDir);
        VlmCustomModelSet invalid = new VlmCustomModelSet();
        invalid.setSetId("missing-locator");
        invalid.setDisplayName("Missing Locator");

        List<String> errors = registry.registerModelSet(invalid);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("provider")));
    }
}
