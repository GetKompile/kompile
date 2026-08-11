package ai.kompile.cli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBundleLoaderTest {
    @TempDir
    Path temp;

    @Test
    void loadsAndMaterializesDirectoryBundle() throws Exception {
        Path bundle = createBundle();

        AgentBundleLoader.LoadedBundle loaded = new AgentBundleLoader().load(bundle);
        assertEquals("demo-agent", loaded.manifest().path("metadata").path("name").asText());
        assertTrue(loaded.entries().contains("skills/README.md"));

        Path workspace = loaded.materialize();
        assertTrue(Files.isRegularFile(workspace.resolve("AGENTS.md")));
        assertTrue(Files.readString(workspace.resolve("AGENTS.md")).contains("Use the graph"));
        assertTrue(Files.isRegularFile(workspace.resolve(".kompile/agent-graphs.json")));
        assertTrue(Files.isRegularFile(workspace.resolve(".mcp.json")));
        loaded.close();
        assertFalse(Files.exists(workspace));
    }

    @Test
    void packsAndLoadsArchiveBundle() throws Exception {
        Path source = createBundle();
        Path archive = temp.resolve("demo.kagent");

        AgentBundleLoader.pack(source, archive);
        AgentBundleLoader.LoadedBundle loaded = new AgentBundleLoader().load(archive);
        assertTrue(loaded.archive());
        assertNotNull(loaded.materialize());
        assertTrue(loaded.entries().contains("agent.yaml"));
        loaded.close();
    }

    @Test
    void rejectsUnsafeArchiveEntries() throws Exception {
        Path archive = temp.resolve("unsafe.kagent");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "agent.yaml", "schemaVersion: '1'\nmetadata:\n  name: unsafe\n");
            put(zip, "../escape.txt", "nope");
        }

        assertThrows(IllegalArgumentException.class, () -> new AgentBundleLoader().load(archive));
    }

    private Path createBundle() throws IOException {
        Path bundle = temp.resolve("bundle");
        Files.createDirectories(bundle.resolve("skills"));
        Files.writeString(bundle.resolve("agent.yaml"), """
                schemaVersion: '1'
                metadata:
                  name: demo-agent
                  description: Bundle test agent
                engine: cli-loop
                systemPrompt: Use the graph before answering.
                instructions:
                  - instructions.md
                skills:
                  - skills/README.md
                graphs:
                  - graph.kgraph
                mcp:
                  servers:
                    - id: local-tools
                      url: http://127.0.0.1:9/mcp
                """);
        Files.writeString(bundle.resolve("instructions.md"), "Follow the project rules.");
        Files.writeString(bundle.resolve("skills/README.md"), "Use the graph.");
        Files.writeString(bundle.resolve("graph.kgraph"), "graph-placeholder");
        Files.writeString(bundle.resolve("AGENTS.md"), "Local bundle instructions.");
        return bundle;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
