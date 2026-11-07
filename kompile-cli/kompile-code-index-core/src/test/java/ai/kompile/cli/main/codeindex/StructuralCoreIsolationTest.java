package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** This test runs on the core module's own classpath, not the CLI host's dependency closure. */
class StructuralCoreIsolationTest {
    @TempDir Path temporary;

    @Test
    void structuralCoreDoesNotContainHostProjectionLearningOrTools() {
        ClassLoader loader = LocalCodeIndexer.class.getClassLoader();
        for (String hostClass : new String[]{
                "ai.kompile.cli.main.MainCommand",
                "ai.kompile.cli.main.codeindex.BackgroundIndexService",
                "ai.kompile.cli.main.codeindex.LocalCodeKGraphPublisher",
                "ai.kompile.cli.main.codeindex.CodeSearchEngine",
                "ai.kompile.cli.main.chat.tools.grounding.CodeGraphLearningRunner",
                "ai.kompile.cli.main.chat.tools.ToolRegistryFactory"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(hostClass, false, loader), hostClass);
        }
    }

    @Test
    void moduleOwnsReflectionHintsRequiredForNativePersistenceAndConfiguration() throws Exception {
        String resource = "META-INF/native-image/ai.kompile/kompile-code-index-core/reflect-config.json";
        try (var input = LocalCodeIndexer.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Core native metadata must travel with its artifact");
            var entries = JsonUtils.standardMapper().readTree(input);
            var names = new java.util.HashSet<String>();
            entries.forEach(entry -> names.add(entry.path("name").asText()));
            assertTrue(names.contains(IndexFileStore.FileShard.class.getName()));
            assertTrue(names.contains(IndexFileStore.FileFingerprint.class.getName()));
            assertTrue(names.contains(CodeGraphReasoningConfig.class.getName()));
        }
    }

    @Test
    void configuredLearningCanBePersistedWithoutInstantiatingItsRuntime() throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        var config = CodeGraphReasoningConfig.updateProject(project,
                "{\"enabled\":true,\"kgeTraining\":true,\"triggers\":[\"manual\"]}");
        var loaded = CodeGraphReasoningConfig.loadProject(project);
        assertNotNull(loaded);
        assertTrue(config.isEnabled() && loaded.isEnabled() && loaded.isKgeTraining());
        assertTrue(loaded.triggersOn("manual"));
        assertFalse(loaded.triggersOn("build"));
        assertFalse(Files.exists(project.resolve("data")));
    }
}
