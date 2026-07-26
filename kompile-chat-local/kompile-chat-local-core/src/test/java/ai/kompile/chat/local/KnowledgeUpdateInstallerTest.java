package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.project.knowledge.KnowledgeUpdateManifest;
import ai.kompile.project.knowledge.PortableKnowledge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeUpdateInstallerTest {
    @TempDir
    Path temp;

    @Test
    void reconstructsAndVerifiesNewKnowledgeRevision() throws Exception {
        Path current = createCurrent();
        PortableKnowledge.Entry descriptor = entry(current, PortableKnowledge.PROJECT_DESCRIPTOR);
        PortableKnowledge.Entry keep = entry(current, "data/markdown/keep.md");
        PortableKnowledge.Entry graph = entry("data/graph/project.kgraph", "graph-v2");
        PortableKnowledge.Entry added = entry("data/markdown/mobile/new.md", "# New\nCaptured");
        List<PortableKnowledge.Entry> inventory = List.of(descriptor, keep, graph, added);
        String baseRevision = revision(current);
        String revision = PortableKnowledge.revision(
                "mobile-project", "Mobile Project", graph.path(), inventory);
        KnowledgeUpdateManifest manifest = new KnowledgeUpdateManifest(
                KnowledgeUpdateManifest.FORMAT,
                PortableKnowledge.FORMAT_VERSION,
                "mobile-project",
                "Mobile Project",
                baseRevision,
                revision,
                graph.path(),
                "2026-07-19T00:00:00Z",
                "test",
                inventory,
                List.of(graph, added),
                List.of("data/markdown/removed.md"));

        Path archive = writeUpdate(manifest, Map.of(
                graph.path(), "graph-v2".getBytes(StandardCharsets.UTF_8),
                added.path(), "# New\nCaptured".getBytes(StandardCharsets.UTF_8)));

        KnowledgeUpdateInstaller.InstalledKnowledgeUpdate installed =
                KnowledgeUpdateInstaller.install(
                        archive,
                        "mobile-project",
                        baseRevision,
                        current,
                        temp.resolve("updates"));

        assertEquals(revision, installed.revision());
        assertEquals("graph-v2", Files.readString(installed.graphPath()));
        assertEquals("# Keep", Files.readString(installed.sourcesRoot().resolve("keep.md")));
        assertEquals("# New\nCaptured", Files.readString(installed.sourcesRoot().resolve("mobile/new.md")));
        assertFalse(Files.exists(installed.sourcesRoot().resolve("removed.md")));
        assertEquals("graph-v1", Files.readString(current.resolve("data/graph/project.kgraph")),
                "The prior revision must remain immutable");
    }

    @Test
    void rejectsWrongBaseAndTamperedChangedPayloadWithoutPublishing() throws Exception {
        Path current = createCurrent();
        PortableKnowledge.Entry descriptor = entry(current, PortableKnowledge.PROJECT_DESCRIPTOR);
        PortableKnowledge.Entry keep = entry(current, "data/markdown/keep.md");
        PortableKnowledge.Entry graph = entry("data/graph/project.kgraph", "graph-v2");
        List<PortableKnowledge.Entry> inventory = List.of(descriptor, keep, graph);
        String baseRevision = revision(current);
        String revision = PortableKnowledge.revision(
                "mobile-project", "Mobile Project", graph.path(), inventory);
        KnowledgeUpdateManifest manifest = new KnowledgeUpdateManifest(
                KnowledgeUpdateManifest.FORMAT,
                PortableKnowledge.FORMAT_VERSION,
                "mobile-project",
                "Mobile Project",
                baseRevision,
                revision,
                graph.path(),
                "2026-07-19T00:00:00Z",
                "test",
                inventory,
                List.of(graph),
                List.of("data/markdown/removed.md"));
        Path archive = writeUpdate(manifest, Map.of(
                graph.path(), "tampered".getBytes(StandardCharsets.UTF_8)));
        Path output = temp.resolve("updates");

        assertThrows(Exception.class, () -> KnowledgeUpdateInstaller.install(
                archive, "mobile-project", "f".repeat(64), current, output));
        Exception tampered = assertThrows(Exception.class, () -> KnowledgeUpdateInstaller.install(
                archive, "mobile-project", baseRevision, current, output));
        assertTrue(tampered.getMessage().toLowerCase().contains("mismatch"));
        if (Files.exists(output)) {
            try (var children = Files.list(output)) {
                assertTrue(children.findAny().isEmpty());
            }
        }
    }

    private Path createCurrent() throws Exception {
        Path root = Files.createDirectory(temp.resolve("current"));
        write(root, PortableKnowledge.PROJECT_DESCRIPTOR,
                "{\"schemaVersion\":1,\"projectId\":\"mobile-project\",\"name\":\"Mobile Project\"}");
        write(root, "data/graph/project.kgraph", "graph-v1");
        write(root, "data/markdown/keep.md", "# Keep");
        write(root, "data/markdown/removed.md", "# Removed");
        return root;
    }

    private String revision(Path root) throws Exception {
        List<PortableKnowledge.Entry> entries = List.of(
                entry(root, PortableKnowledge.PROJECT_DESCRIPTOR),
                entry(root, "data/graph/project.kgraph"),
                entry(root, "data/markdown/keep.md"),
                entry(root, "data/markdown/removed.md"));
        return PortableKnowledge.revision(
                "mobile-project", "Mobile Project", "data/graph/project.kgraph", entries);
    }

    private Path writeUpdate(
            KnowledgeUpdateManifest manifest,
            Map<String, byte[]> payloads) throws Exception {
        Path archive = temp.resolve("update-" + System.nanoTime() + ".kupdate");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("format", manifest.format());
        json.put("formatVersion", manifest.formatVersion());
        json.put("projectId", manifest.projectId());
        json.put("projectName", manifest.projectName());
        json.put("baseRevision", manifest.baseRevision());
        json.put("revision", manifest.revision());
        json.put("defaultGraph", manifest.defaultGraph());
        json.put("createdAt", manifest.createdAt());
        json.put("generator", manifest.generator());
        json.put("inventory", manifest.inventory().stream().map(this::entryJson).toList());
        json.put("changed", manifest.changed().stream().map(this::entryJson).toList());
        json.put("deleted", manifest.deleted());

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, KnowledgeUpdateInstaller.MANIFEST_ENTRY,
                    MiniJson.write(json).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> payload : payloads.entrySet()) {
                put(zip, KnowledgeUpdateInstaller.PAYLOAD_ROOT + payload.getKey(), payload.getValue());
            }
        }
        return archive;
    }

    private Map<String, Object> entryJson(PortableKnowledge.Entry entry) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("path", entry.path());
        json.put("size", entry.size());
        json.put("sha256", entry.sha256());
        return json;
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static PortableKnowledge.Entry entry(Path root, String relative) throws Exception {
        byte[] bytes = Files.readAllBytes(root.resolve(relative));
        return new PortableKnowledge.Entry(relative, bytes.length, PortableKnowledge.sha256(bytes));
    }

    private static PortableKnowledge.Entry entry(String relative, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new PortableKnowledge.Entry(relative, bytes.length, PortableKnowledge.sha256(bytes));
    }

    private static void write(Path root, String relative, String value) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }
}
