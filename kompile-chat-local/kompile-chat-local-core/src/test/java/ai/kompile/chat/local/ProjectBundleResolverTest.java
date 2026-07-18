package ai.kompile.chat.local;

import ai.kompile.graph.reasoning.unified.MiniJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectBundleResolverTest {
    @TempDir
    Path temp;

    @Test
    void directoryPrefersProjectThenGlobalThenSingleRecursiveGraph() throws Exception {
        Path root = projectDirectory("selection");
        Path nested = write(root, "nested/only.kgraph", "single");
        Path copied;
        try (var resolved = ProjectBundleResolver.resolve(root, null)) {
            copied = resolved.graphPath();
            assertNotEquals(nested, copied);
            assertEquals("single", Files.readString(copied));
            assertEquals("selection-id", resolved.projectId());
        }
        assertFalse(Files.exists(copied));

        Path global = write(root, "data/graph/global.kgraph", "global");
        try (var resolved = ProjectBundleResolver.resolve(root, null)) {
            assertNotEquals(global, resolved.graphPath());
            assertEquals("global", Files.readString(resolved.graphPath()));
        }

        Path project = write(root, "data/graph/project.kgraph", "project");
        try (var resolved = ProjectBundleResolver.resolve(root, null)) {
            assertNotEquals(project, resolved.graphPath());
            assertEquals("project", Files.readString(resolved.graphPath()));
        }
    }

    @Test
    void directoryRejectsAmbiguousGraphs() throws Exception {
        Path root = projectDirectory("ambiguous");
        write(root, "one.kgraph", "one");
        write(root, "nested/two.kgraph", "two");
        IOException error = assertThrows(IOException.class,
                () -> ProjectBundleResolver.resolve(root, null));
        assertTrue(error.getMessage().contains("Ambiguous"));
    }

    @Test
    void directorySelectsRequestedFactSheet() throws Exception {
        Path root = projectDirectory("facts");
        Path fact = write(root, "data/graph/factsheet-17.kgraph", "facts");
        try (var resolved = ProjectBundleResolver.resolve(root, "17")) {
            assertNotEquals(fact, resolved.graphPath());
            assertEquals("facts", Files.readString(resolved.graphPath()));
        }
        IOException error = assertThrows(IOException.class,
                () -> ProjectBundleResolver.resolve(root, "missing"));
        assertTrue(error.getMessage().contains("fact-sheet graph not found"));
    }

    @Test
    void directoryRejectsSymlinkedGraphParent() throws Exception {
        Path root = projectDirectory("symlink-parent");
        Path outside = Files.createDirectory(temp.resolve("outside-graphs"));
        Files.writeString(outside.resolve("factsheet-17.kgraph"), "outside");
        Files.createDirectory(root.resolve("data"));
        try {
            Files.createSymbolicLink(root.resolve("data/graph"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            return;
        }

        IOException error = assertThrows(IOException.class,
                () -> ProjectBundleResolver.resolve(root, "17"));
        assertTrue(error.getMessage().contains("symbolic link"), error::getMessage);
    }

    @Test
    void directoryReturnsStableTemporaryGraphSnapshot() throws Exception {
        Path root = projectDirectory("stable-snapshot");
        Path source = write(root, "data/graph/project.kgraph", "original");
        Path copied;
        try (var resolved = ProjectBundleResolver.resolve(root, null)) {
            copied = resolved.graphPath();
            Files.writeString(source, "changed-after-resolution");
            assertEquals("original", Files.readString(copied));
            assertTrue(Files.exists(copied));
        }
        assertFalse(Files.exists(copied));
    }

    @Test
    void validArchiveVerifiesChecksumAndDeletesTemporaryGraph() throws Exception {
        byte[] graph = "verified graph".getBytes(StandardCharsets.UTF_8);
        byte[] metadata = "full project metadata".getBytes(StandardCharsets.UTF_8);
        Path archive = archive("valid.kproject", manifest(1, "data/graph/project.kgraph",
                List.of(entry("data/graph/project.kgraph", graph), entry("README.md", metadata))), Map.of(
                "project/data/graph/project.kgraph", graph, "project/README.md", metadata));
        Path extracted;
        try (var resolved = ProjectBundleResolver.resolve(archive, null)) {
            extracted = resolved.graphPath();
            assertArrayEquals(graph, Files.readAllBytes(extracted));
            assertEquals("archive-project", resolved.projectId());
            assertTrue(Files.exists(extracted));
        }
        assertFalse(Files.exists(extracted));
    }

    @Test
    void archiveRejectsCorruptHash() throws Exception {
        byte[] graph = "graph".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> bad = entry("data/graph/global.kgraph", graph);
        bad.put("sha256", "0".repeat(64));
        Path archive = archive("bad-hash.kproject", manifest(1, "data/graph/global.kgraph",
                List.of(bad)), Map.of("project/data/graph/global.kgraph", graph));
        assertMessage("SHA-256 mismatch", () -> ProjectBundleResolver.resolve(archive, null));
    }

    @Test
    void archiveRejectsMissingAndUnknownSelectedGraph() throws Exception {
        byte[] graph = "graph".getBytes(StandardCharsets.UTF_8);
        Path missingPayload = archive("missing-payload.kproject",
                manifest(1, "data/graph/global.kgraph",
                        List.of(entry("data/graph/global.kgraph", graph))), Map.of());
        assertMessage("payload is missing", () -> ProjectBundleResolver.resolve(missingPayload, null));

        Path unknown = archive("unknown.kproject",
                manifest(1, "data/graph/unknown.kgraph", List.of()), Map.of());
        assertMessage("defaultGraph is not present", () -> ProjectBundleResolver.resolve(unknown, null));
    }

    @Test
    void archiveRejectsTraversalDuplicateAndCaseCollision() throws Exception {
        byte[] graph = "x".getBytes(StandardCharsets.UTF_8);
        Path traversal = archive("traversal.kproject",
                manifest(1, "../escape.kgraph", List.of(entry("../escape.kgraph", graph))), Map.of());
        assertMessage("unsafe path", () -> ProjectBundleResolver.resolve(traversal, null));

        Map<String, Object> duplicateManifest = manifest(1, "same.kgraph",
                List.of(entry("same.kgraph", graph), entry("same.kgraph", graph)));
        Path duplicate = archive("duplicate.kproject", duplicateManifest,
                Map.of("project/same.kgraph", graph));
        assertMessage("Duplicate manifest entry", () -> ProjectBundleResolver.resolve(duplicate, null));

        Map<String, Object> collisionManifest = manifest(1, "Graph.kgraph",
                List.of(entry("Graph.kgraph", graph), entry("graph.kgraph", graph)));
        Path collision = archive("collision.kproject", collisionManifest, Map.of(
                "project/Graph.kgraph", graph, "project/graph.kgraph", graph));
        assertMessage("Case-colliding", () -> ProjectBundleResolver.resolve(collision, null));

        Path extra = archive("extra.kproject", manifest(1, "graph.kgraph",
                List.of(entry("graph.kgraph", graph))), Map.of(
                "project/graph.kgraph", graph, "project/undeclared.txt", graph));
        assertMessage("not declared by manifest", () -> ProjectBundleResolver.resolve(extra, null));
    }

    @Test
    void archiveRejectsLimitsAndUnsupportedVersion() throws Exception {
        byte[] graph = "x".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> oversized = entry("large.kgraph", graph);
        oversized.put("size", ProjectBundleResolver.MAX_ENTRY_BYTES + 1);
        Path limit = archive("limit.kproject", manifest(1, "large.kgraph",
                List.of(oversized)), Map.of("project/large.kgraph", graph));
        assertMessage("size out of bounds", () -> ProjectBundleResolver.resolve(limit, null));

        Path version = archive("version.kproject", manifest(2, "graph.kgraph",
                List.of(entry("graph.kgraph", graph))), Map.of("project/graph.kgraph", graph));
        assertMessage("formatVersion", () -> ProjectBundleResolver.resolve(version, null));
    }

    @Test
    void archiveFactSheetMustBeInventoried() throws Exception {
        byte[] graph = "global".getBytes(StandardCharsets.UTF_8);
        Path archive = archive("fact.kproject", manifest(1, "data/graph/global.kgraph",
                List.of(entry("data/graph/global.kgraph", graph))),
                Map.of("project/data/graph/global.kgraph", graph));
        assertMessage("not present in manifest inventory",
                () -> ProjectBundleResolver.resolve(archive, "42"));
    }

    @Test
    void archiveRequiresMatchingProjectDescriptorIdentity() throws Exception {
        byte[] graph = "graph".getBytes(StandardCharsets.UTF_8);
        byte[] mismatchedDescriptor = ("{\"schemaVersion\":1,"
                + "\"projectId\":\"another-project\",\"name\":\"Archive Project\"}")
                .getBytes(StandardCharsets.UTF_8);
        Path archive = archive("identity.kproject",
                manifest(1, "data/graph/project.kgraph",
                        List.of(entry("data/graph/project.kgraph", graph))),
                Map.of("project/data/graph/project.kgraph", graph,
                        "project/kompile.project.json", mismatchedDescriptor));

        assertMessage("projectId does not match",
                () -> ProjectBundleResolver.resolve(archive, null));
    }

    private Path projectDirectory(String name) throws IOException {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.writeString(root.resolve("kompile.project.json"),
                "{\"schemaVersion\":1,\"projectId\":\"" + name
                        + "-id\",\"name\":\"" + name + "\"}");
        return root;
    }

    private static Path write(Path root, String relative, String value) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
        return path;
    }

    private Path archive(String name, Map<String, Object> manifest, Map<String, byte[]> payloads)
            throws IOException {
        Map<String, byte[]> completePayloads = new LinkedHashMap<>(payloads);
        byte[] descriptor = completePayloads.getOrDefault("project/kompile.project.json",
                ("{\"schemaVersion\":1,\"projectId\":\"archive-project\","
                        + "\"name\":\"Archive Project\"}").getBytes(StandardCharsets.UTF_8));
        completePayloads.put("project/kompile.project.json", descriptor);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inventory =
                (List<Map<String, Object>>) manifest.get("entries");
        if (inventory.stream().noneMatch(item -> "kompile.project.json".equals(item.get("path")))) {
            try {
                inventory.add(entry("kompile.project.json", descriptor));
            } catch (Exception e) {
                throw new IOException("Could not construct project descriptor inventory", e);
            }
        }
        Path archive = temp.resolve(name);
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            put(zip, "manifest.json", MiniJson.write(manifest).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> payload : completePayloads.entrySet()) {
                put(zip, payload.getKey(), payload.getValue());
            }
        }
        return archive;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static Map<String, Object> manifest(int version, String defaultGraph,
                                                 List<Map<String, Object>> entries) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "kompile-project");
        manifest.put("formatVersion", version);
        manifest.put("projectId", "archive-project");
        manifest.put("name", "Archive Project");
        if (defaultGraph != null) {
            manifest.put("defaultGraph", defaultGraph);
        }
        manifest.put("entries", new ArrayList<>(entries));
        return manifest;
    }

    private static Map<String, Object> entry(String path, byte[] content) throws Exception {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("size", content.length);
        entry.put("sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)));
        entry.put("executable", false);
        return entry;
    }

    private static void assertMessage(String expected, ThrowingCall call) {
        IOException error = assertThrows(IOException.class, call::run);
        assertTrue(error.getMessage().contains(expected),
                "Expected message containing '" + expected + "', got: " + error.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
