package ai.kompile.project.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortableKnowledgeTest {

    @Test
    void revisionIsStableAcrossInventoryOrder() {
        PortableKnowledge.Entry markdown = entry("data/markdown/note.md", "note");
        PortableKnowledge.Entry graph = entry("data/graph/project.kgraph", "graph");

        String first = PortableKnowledge.revision(
                "project", "Project", graph.path(), List.of(markdown, graph));
        String second = PortableKnowledge.revision(
                "project", "Project", graph.path(), List.of(graph, markdown));

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void rejectsModelsTraversalAndCaseCollisions() {
        assertFalse(PortableKnowledge.isPortablePath("models/model.sdz"));
        assertFalse(PortableKnowledge.isPortablePath("../data/markdown/note.md"));
        assertThrows(IllegalArgumentException.class, () -> PortableKnowledge.normalizeInventory(List.of(
                entry("data/markdown/Note.md", "one"),
                entry("data/markdown/note.md", "two"))));
    }

    @Test
    void updateManifestVerifiesRevisionAndDeltaShape() {
        PortableKnowledge.Entry graph = entry("data/graph/project.kgraph", "graph");
        String revision = PortableKnowledge.revision("p", "P", graph.path(), List.of(graph));

        KnowledgeUpdateManifest manifest = new KnowledgeUpdateManifest(
                KnowledgeUpdateManifest.FORMAT,
                PortableKnowledge.FORMAT_VERSION,
                "p",
                "P",
                "0".repeat(64),
                revision,
                graph.path(),
                "2026-07-19T00:00:00Z",
                "test",
                List.of(graph),
                List.of(graph),
                List.of("data/markdown/removed.md"));

        assertEquals(revision, manifest.revision());
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeUpdateManifest(
                KnowledgeUpdateManifest.FORMAT,
                PortableKnowledge.FORMAT_VERSION,
                "p",
                "P",
                "0".repeat(64),
                "f".repeat(64),
                graph.path(),
                "2026-07-19T00:00:00Z",
                "test",
                List.of(graph),
                List.of(graph),
                List.of()));
    }

    private static PortableKnowledge.Entry entry(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new PortableKnowledge.Entry(path, bytes.length, PortableKnowledge.sha256(bytes));
    }
}
