package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.project.archive.ProjectArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectArchiveCompatibilityTest {
    @TempDir
    Path temp;

    @Test
    void exporterChatResolverAndImporterShareOneCanonicalContract() throws Exception {
        Path project = Files.createDirectory(temp.resolve("source-project"));
        Files.writeString(project.resolve("kompile.project.json"),
                "{\"schemaVersion\":1,\"projectId\":\"integration-project\","
                        + "\"name\":\"Integration Project\"}");
        write(project, "README.md", "portable");
        write(project, "data/db/project.db", "database");
        write(project, "data/indices/vector.idx", "index");
        write(project, "data/models/model.bin", "model");
        write(project, "data/documents/document.json", "document");
        write(project, "data/chat/session.json", "chat");

        Path graphFile = project.resolve("data/graph/project.kgraph");
        Files.createDirectories(graphFile.getParent());
        new UnifiedGraph().graphId("project")
                .addEntity("entity-1", "DOCUMENT", "Portable document")
                .save(graphFile);

        Path archive = temp.resolve("integration-project.kproject");
        ProjectArchiveService archiveService = new ProjectArchiveService();
        archiveService.exportProject(project, archive);

        Path extractedGraph;
        try (ProjectBundleResolver.ResolvedProject resolved =
                     ProjectBundleResolver.resolve(archive, null)) {
            extractedGraph = resolved.graphPath();
            assertEquals("integration-project", resolved.projectId());
            assertEquals("Integration Project", resolved.projectName());
            UnifiedGraph loaded = UnifiedGraph.load(extractedGraph);
            assertTrue(loaded.containsEntity("entity-1"));
            assertEquals("project", loaded.graphId());
        }
        assertFalse(Files.exists(extractedGraph));

        Path restored = temp.resolve("restored-project");
        archiveService.importProject(archive, restored);
        assertEquals("portable", Files.readString(restored.resolve("README.md")));
        assertEquals("database", Files.readString(restored.resolve("data/db/project.db")));
        assertEquals("index", Files.readString(restored.resolve("data/indices/vector.idx")));
        assertEquals("model", Files.readString(restored.resolve("data/models/model.bin")));
        assertEquals("document", Files.readString(restored.resolve("data/documents/document.json")));
        assertEquals("chat", Files.readString(restored.resolve("data/chat/session.json")));
        assertTrue(UnifiedGraph.load(restored.resolve("data/graph/project.kgraph"))
                .containsEntity("entity-1"));
    }

    private static void write(Path root, String relative, String value) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }
}
