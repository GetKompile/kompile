package ai.kompile.project.archive;

import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectArchiveServiceTest {
    @TempDir Path temp;
    private final ProjectArchiveService service = new ProjectArchiveService();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void roundTripIncludesDurableStateAndExcludesSecretsAndRuntime() throws Exception {
        Path root = project("demo");
        write(root, "README.md", "hello");
        write(root, "data/db/state.db", "db");
        write(root, "data/index/segments", "index");
        write(root, "data/models/model.bin", "model");
        write(root, "data/documents/doc.json", "doc");
        write(root, "data/chat/session.json", "chat");
        write(root, "data/graph/project.kgraph", "graph");
        write(root, "data/graph/factsheet-1.kgraph", "facts");
        write(root, "config/secrets/token.json", "secret");
        write(root, "config/x.secret.json", "secret");
        write(root, "config/oauth-encryption.key", "secret");
        write(root, "oauth-settings.json", "secret");
        write(root, "credentials.json", "secret");
        write(root, "config/service-account-prod.json", "secret");
        write(root, "certs/private.pem", "secret");
        write(root, ".aws/credentials", "secret");
        write(root, ".env", "TOKEN=secret");
        write(root, ".env.example", "TOKEN=replace-me");
        write(root, ".kompile/cache/cache.bin", "runtime");
        write(root, ".kompile/project/open.json", "runtime");
        write(root, "data/logs/app.log", "runtime");
        write(root, "target/output.bin", "build");
        write(root, ".git/config", "git");

        Path archive = temp.resolve("demo.kproject");
        ProjectArchiveResult exported = service.exportProject(root, archive);
        assertEquals(ProjectArchiveService.FORMAT_VERSION, exported.manifest().formatVersion());
        assertTrue(exported.manifest().semantic().portableAssets().contains("KNOWLEDGE_GRAPH"));
        assertEquals(List.copyOf(exported.manifest().entries().stream()
                .map(ProjectArchiveManifest.Entry::path).sorted().toList()),
                exported.manifest().entries().stream().map(ProjectArchiveManifest.Entry::path).toList());

        List<String> paths = exported.manifest().entries().stream().map(ProjectArchiveManifest.Entry::path).toList();
        assertTrue(paths.containsAll(List.of("data/db/state.db", "data/index/segments",
                "data/models/model.bin", "data/documents/doc.json", "data/chat/session.json",
                "data/graph/project.kgraph", "data/graph/factsheet-1.kgraph", ".env.example")));
        assertEquals("data/graph/project.kgraph", exported.manifest().defaultGraph());
        assertFalse(paths.stream().anyMatch(p -> p.contains("secret") || p.startsWith(".git/") ||
                p.startsWith("target/") || p.startsWith(".kompile/cache/") ||
                p.equals(".kompile/project/open.json") || p.equals(".env") ||
                p.startsWith("data/logs/") || p.endsWith("oauth-encryption.key") ||
                p.endsWith("credentials.json") || p.endsWith(".pem") ||
                p.equals(".aws/credentials") || p.contains("service-account")));

        Path target = temp.resolve("restored");
        service.importProject(archive, target);
        assertEquals("hello", Files.readString(target.resolve("README.md")));
        assertEquals("db", Files.readString(target.resolve("data/db/state.db")));
    }

    @Test
    void refusesRunningProjectUnlessAllowed() throws Exception {
        Path root = project("running");
        write(root, "data/pids/app.pid", "123");
        IOException failure = assertThrows(IOException.class,
                () -> service.exportProject(root, temp.resolve("no.kproject")));
        assertTrue(failure.getMessage().contains("running"));
        ProjectArchiveResult result = service.exportProject(root, temp.resolve("yes.kproject"),
                new ProjectArchiveExportOptions(true));
        assertTrue(Files.exists(result.path()));
        assertFalse(result.manifest().entries().stream().anyMatch(e -> e.path().startsWith("data/pids/")));
    }

    @Test
    void sensitiveFilesRequireExplicitOptIn() throws Exception {
        Path root = project("sensitive-opt-in");
        write(root, "credentials.json", "credential");
        write(root, "config/application.properties", "service.api-key=actual-secret");
        Path archive = temp.resolve("sensitive-opt-in.kproject");

        IOException refusal = assertThrows(IOException.class,
                () -> service.exportProject(root, temp.resolve("sensitive-default.kproject")));
        assertTrue(refusal.getMessage().contains("embedded credential"));

        ProjectArchiveResult result = service.exportProject(root, archive,
                new ProjectArchiveExportOptions(false, true));

        assertTrue(result.manifest().entries().stream()
                .anyMatch(entry -> entry.path().equals("credentials.json")));
        assertTrue(result.manifest().entries().stream()
                .anyMatch(entry -> entry.path().equals("config/application.properties")));
    }

    @Test
    void rejectsSymlinkAndExistingTarget() throws Exception {
        Path root = project("links");
        Path real = write(root, "real.txt", "x");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), real);
            assertThrows(IOException.class, () -> service.exportProject(root, temp.resolve("link.kproject")));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            return;
        }
        Files.delete(root.resolve("link.txt"));
        Path archive = temp.resolve("ok.kproject");
        service.exportProject(root, archive);
        assertThrows(IOException.class, () -> service.exportProject(root, archive));
        Path target = Files.createDirectory(temp.resolve("exists"));
        assertThrows(IOException.class, () -> service.importProject(archive, target));
    }

    @Test
    void refusesSymlinkSwapAfterSourceFileIsSecurelyOpened() throws Exception {
        Path root = project("swap");
        Path payload = write(root, "payload.txt", "project-data");
        Path outside = write(temp, "outside-secret.txt", "outside-secret");
        Path probe = root.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, outside);
            Files.delete(probe);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            return;
        }

        AtomicBoolean swapped = new AtomicBoolean();
        ProjectArchiveService guarded = new ProjectArchiveService(new KompileProjectStore(), relative -> {
            if (relative.equals("payload.txt") && swapped.compareAndSet(false, true)) {
                Files.delete(payload);
                Files.createSymbolicLink(payload, outside);
            }
        });
        Path archive = temp.resolve("swap.kproject");
        IOException failure = assertThrows(IOException.class,
                () -> guarded.exportProject(root, archive));

        assertTrue(swapped.get());
        assertTrue(failure.getMessage().contains("symbolic link")
                || failure.getMessage().contains("changed"), failure::getMessage);
        assertFalse(Files.exists(archive));
    }

    @Test
    void publicationNeverOverwritesDestinationCreatedAfterPreflight() throws Exception {
        Path root = project("publication-race");
        write(root, "payload.txt", "project-data");
        Path racedArchive = temp.resolve("publication-race.kproject");
        ProjectArchiveService racingExporter = new ProjectArchiveService(
                new KompileProjectStore(), relative -> { },
                destination -> Files.writeString(destination, "do-not-overwrite"));

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> racingExporter.exportProject(root, racedArchive));
        assertEquals("do-not-overwrite", Files.readString(racedArchive));
        try (var paths = Files.list(temp)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString()
                    .startsWith(".publication-race.kproject.tmp-")));
        }

        Files.delete(racedArchive);
        Path archive = temp.resolve("publication-source.kproject");
        service.exportProject(root, archive);
        Path target = temp.resolve("publication-target");
        ProjectArchiveService racingImporter = new ProjectArchiveService(
                new KompileProjectStore(), relative -> { }, destination -> {
                    Files.createDirectory(destination);
                    Files.writeString(destination.resolve("sentinel"), "do-not-overwrite");
                });

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> racingImporter.importProject(archive, target));
        assertEquals("do-not-overwrite", Files.readString(target.resolve("sentinel")));
        assertFalse(Files.exists(target.resolve("kompile.project.json")));
        try (var paths = Files.list(temp)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString()
                    .startsWith(".publication-target.import-")));
        }
    }

    @Test
    void detectsChecksumCorruptionAndLeavesNoTargetOrStaging() throws Exception {
        Path root = project("corrupt");
        write(root, "payload.txt", "original");
        Path good = temp.resolve("good.kproject");
        service.exportProject(root, good);
        Path bad = temp.resolve("bad.kproject");
        Map<String, byte[]> content = readZip(good);
        content.put("project/payload.txt", "tampered".getBytes(StandardCharsets.UTF_8));
        writeZip(bad, content);

        Path target = temp.resolve("not-created");
        assertThrows(IOException.class, () -> service.importProject(bad, target));
        assertFalse(Files.exists(target));
        try (var paths = Files.list(temp)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".not-created.import-")));
        }
    }

    @Test
    void rejectsTraversalDuplicatesCaseCollisionsUnexpectedAndMissingEntries() throws Exception {
        assertBadArchive(List.of(entry("../escape", 1, "00", false)),
                Map.of("project/../escape", new byte[]{1}), "Unsafe");
        assertBadArchive(List.of(entry("a", 0, sha(new byte[0]), false),
                        entry("a", 0, sha(new byte[0]), false)),
                Map.of("project/a", new byte[0]), "Duplicate");
        assertBadArchive(List.of(entry("A", 0, sha(new byte[0]), false),
                        entry("a", 0, sha(new byte[0]), false)),
                Map.of("project/A", new byte[0], "project/a", new byte[0]), "Case-colliding");
        assertBadArchive(List.of(entry("a", 0, sha(new byte[0]), false)),
                Map.of("project/a", new byte[0], "project/extra", new byte[0]), "unexpected");
        assertBadArchive(List.of(entry("missing", 0, sha(new byte[0]), false)),
                Map.of(), "missing");
        assertBadArchive(List.of(entry("bad-hash", 0, "not-a-hash", false)),
                Map.of("project/bad-hash", new byte[0]), "SHA-256");
    }

    @Test
    void enforcesImportLimitsBeforePublishing() throws Exception {
        Path root = project("limits");
        write(root, "large.bin", "12345");
        Path archive = temp.resolve("limits.kproject");
        service.exportProject(root, archive);
        Path target = temp.resolve("limited");
        ProjectArchiveImportOptions limits = new ProjectArchiveImportOptions(100, 4, 1000, 10000);
        assertThrows(IOException.class, () -> service.importProject(archive, target, limits));
        assertFalse(Files.exists(target));
    }

    @Test
    void rejectsArchiveMetadataThatDisagreesWithProjectManifest() throws Exception {
        Path root = project("identity");
        Path good = temp.resolve("identity.kproject");
        service.exportProject(root, good);

        Map<String, byte[]> content = readZip(good);
        ProjectArchiveManifest original =
                mapper.readValue(content.get("manifest.json"), ProjectArchiveManifest.class);
        ProjectArchiveManifest altered = new ProjectArchiveManifest(
                original.format(), original.formatVersion(), "different-project-id", original.name(),
                original.createdAt(), original.generator(), original.defaultGraph(), original.entries());
        content.put("manifest.json", mapper.writeValueAsBytes(altered));
        Path bad = temp.resolve("identity-mismatch.kproject");
        writeZip(bad, content);

        IOException failure = assertThrows(IOException.class,
                () -> service.importProject(bad, temp.resolve("identity-restored")));
        assertTrue(failure.getMessage().contains("projectId"));
        assertFalse(Files.exists(temp.resolve("identity-restored")));
    }

    @Test
    void exportIncludesSemanticKnowledgeBaseInventory() throws Exception {
        Path root = project("semantic");
        Files.writeString(root.resolve("kompile.project.json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "id-semantic",
                  "name": "semantic",
                  "description": "Portable research workspace",
                  "lifecycle": "ACTIVE",
                  "tags": ["research", "portable"],
                  "components": [
                    {"id":"sources","type":"SOURCE","name":"Sources","path":"data/sources",
                     "storageBackend":"GIT","tags":[]},
                    {"id":"graph","type":"GRAPH","name":"Graph","path":"data/graph",
                     "storageBackend":"GIT","tags":[]}
                  ],
                  "codingProjects": [{"id":"code","name":"Code","rootPath":"../external","tags":[]}],
                  "models": [{"id":"embed","role":"EMBEDDING","modelId":"external-model","required":true,"tags":[]}],
                  "metadata": {}
                }
                """);
        write(root, "data/sources/source.md", "source");
        write(root, "data/graph/project.kgraph", "graph");
        write(root, "data/note-sync/project-note-sync.json", "{}");

        ProjectArchiveManifest manifest =
                service.exportProject(root, temp.resolve("semantic.kproject")).manifest();

        assertEquals("Portable research workspace", manifest.semantic().description());
        assertEquals(List.of("GRAPH", "SOURCE"), manifest.semantic().componentTypes());
        assertTrue(manifest.semantic().portableAssets().containsAll(
                List.of("SOURCE_CORPUS", "KNOWLEDGE_GRAPH", "SOURCE_SYNC_CONFIGURATION")));
        assertTrue(manifest.semantic().externalRequirements().containsAll(
                List.of("EXTERNAL_CODE_REPOSITORIES", "MODEL_ARTIFACTS", "SOURCE_CREDENTIALS")));
    }

    @Test
    void importsVersionOneArchiveWithoutSemanticMetadata() throws Exception {
        Path root = project("legacy");
        write(root, "payload.txt", "legacy-data");
        Path generated = temp.resolve("generated.kproject");
        service.exportProject(root, generated);

        Map<String, byte[]> content = readZip(generated);
        var json = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(content.get(ProjectArchiveService.MANIFEST_ENTRY));
        json.put("formatVersion", 1);
        json.remove("semantic");
        content.put(ProjectArchiveService.MANIFEST_ENTRY, mapper.writeValueAsBytes(json));
        Path legacy = temp.resolve("legacy.kproject");
        writeZip(legacy, content);

        ProjectArchiveInspection inspection = service.inspectProject(legacy);
        assertEquals(1, inspection.manifest().formatVersion());
        assertTrue(inspection.manifest().semantic().isEmpty());
        assertTrue(inspection.warnings().stream().anyMatch(warning -> warning.contains("Legacy")));

        Path restored = temp.resolve("legacy-restored");
        service.importProject(legacy, restored);
        assertEquals("legacy-data", Files.readString(restored.resolve("payload.txt")));
    }

    @Test
    void inspectionAndImportPreflightDoNotMutateDestination() throws Exception {
        Path root = project("inspect");
        write(root, "payload.txt", "12345");
        Path archive = temp.resolve("inspect.kproject");
        ProjectArchiveResult exported = service.exportProject(root, archive);
        Path target = temp.resolve("preflight-target");

        ProjectArchiveInspection inspection = service.preflightImport(archive, target);

        assertEquals(exported.totalBytes(), inspection.declaredTotalBytes());
        assertEquals("inspect", inspection.manifest().name());
        assertFalse(Files.exists(target));
        try (var paths = Files.list(temp)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".preflight-target.import-")));
        }

        Files.createDirectory(target);
        assertThrows(IOException.class, () -> service.preflightImport(archive, target));
    }

    @Test
    void inspectionIsStructuralWhileImportVerifiesChecksums() throws Exception {
        Path root = project("inspect-checksum");
        write(root, "payload.txt", "original");
        Path good = temp.resolve("inspect-good.kproject");
        service.exportProject(root, good);
        Map<String, byte[]> content = readZip(good);
        content.put("project/payload.txt", "tampered".getBytes(StandardCharsets.UTF_8));
        Path bad = temp.resolve("inspect-bad.kproject");
        writeZip(bad, content);

        assertDoesNotThrow(() -> service.inspectProject(bad));
        assertThrows(IOException.class,
                () -> service.importProject(bad, temp.resolve("inspect-checksum-target")));
    }

    @Test
    void restoresExecutableBitWhereSupported() throws Exception {
        Path root = project("exec");
        Path script = write(root, "bin/run.sh", "#!/bin/sh\n");
        if (!script.toFile().setExecutable(true, false) || !Files.isExecutable(script)) return;
        Path archive = temp.resolve("exec.kproject");
        service.exportProject(root, archive);
        Path target = temp.resolve("exec-out");
        service.importProject(archive, target);
        assertTrue(Files.isExecutable(target.resolve("bin/run.sh")));
    }

    private void assertBadArchive(List<ProjectArchiveManifest.Entry> entries,
                                  Map<String, byte[]> payload, String message) throws Exception {
        ProjectArchiveManifest manifest = new ProjectArchiveManifest("kompile-project", 1,
                "id", "bad", Instant.now(), "test", null, entries);
        Map<String, byte[]> zip = new LinkedHashMap<>();
        zip.put("manifest.json", mapper.writeValueAsBytes(manifest));
        zip.putAll(payload);
        Path archive = temp.resolve("bad-" + Files.list(temp).count() + ".kproject");
        writeZip(archive, zip);
        IOException failure = assertThrows(IOException.class,
                () -> service.importProject(archive, temp.resolve("target-" + archive.getFileName())));
        assertTrue(failure.getMessage().toLowerCase().contains(message.toLowerCase()), failure::getMessage);
    }

    private Path project(String name) throws IOException {
        Path root = Files.createDirectory(temp.resolve(name));
        String json = "{\"schemaVersion\":1,\"projectId\":\"id-" + name +
                "\",\"name\":\"" + name + "\"}";
        Files.writeString(root.resolve("kompile.project.json"), json);
        return root;
    }

    private static Path write(Path root, String relative, String value) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
        return path;
    }

    private static ProjectArchiveManifest.Entry entry(String path, long size, String sha, boolean executable) {
        return new ProjectArchiveManifest.Entry(path, size, sha, executable);
    }

    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, byte[]> readZip(Path path) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (var in = zip.getInputStream(entry)) {
                    result.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return result;
    }

    private static void writeZip(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
    }
}
