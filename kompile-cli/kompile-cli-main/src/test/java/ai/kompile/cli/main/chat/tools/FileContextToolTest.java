/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.main.codeindex.LocalCodeKGraphPublisher;
import ai.kompile.cli.main.codeindex.IndexDatabase;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("user.home")
class FileContextToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private String previousHome;
    private Path projectRoot;
    private Path source;
    private String projectId;
    private ToolContext context;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        projectRoot = tempDir.resolve("project");
        source = projectRoot.resolve("src/main/java/demo/ContextService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, sourceText("one"), StandardCharsets.UTF_8);
        projectId = "file-context-" + Math.abs(System.nanoTime());
        indexAndPublish();

        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("file-context-session", null, permissions,
                projectRoot, new ToolRegistry(mapper));
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void getReturnsExactIndexAndProjectedGraphContext() throws Exception {
        ObjectNode params = mapper.createObjectNode().put("file_path",
                projectRoot.relativize(source).toString());

        ToolResult result = new FileContextTool().execute(params, context);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("Identity: " + projectId + ":src/main/java/demo/ContextService.java"),
                result.getOutput());
        assertTrue(result.getOutput().contains("ContextService"), result.getOutput());
        assertTrue(result.getOutput().contains("-EXTENDS->"), result.getOutput());
        assertTrue(result.getOutput().contains("KGraph: AVAILABLE"), result.getOutput());
        assertEquals("AVAILABLE", result.getMetadata().get("indexStatus"));
        assertEquals("AVAILABLE", result.getMetadata().get("graphStatus"));
        assertEquals(true, result.getMetadata().get("graphFresh"));
    }

    @Test
    void notesSurviveReindexAndCanBeDeletedByExactId() throws Exception {
        FileNoteTool notes = new FileNoteTool();
        ObjectNode add = mapper.createObjectNode()
                .put("action", "add")
                .put("file_path", projectRoot.relativize(source).toString())
                .put("content", "Keep the retry boundary aligned with the caller contract.");
        ToolResult added = notes.execute(add, context);
        assertFalse(added.isError(), added.getOutput());
        String noteId = String.valueOf(added.getMetadata().get("noteId"));
        assertFalse(noteId.isBlank());

        Files.writeString(source, sourceText("two"), StandardCharsets.UTF_8);
        indexAndPublish();

        ToolResult afterReindex = new FileContextTool().execute(
                mapper.createObjectNode().put("file_path", projectRoot.relativize(source).toString()),
                context);
        assertTrue(afterReindex.getOutput().contains(noteId), afterReindex.getOutput());
        assertTrue(afterReindex.getOutput().contains("Keep the retry boundary"), afterReindex.getOutput());

        ObjectNode delete = mapper.createObjectNode()
                .put("action", "delete")
                .put("file_path", projectRoot.relativize(source).toString())
                .put("note_id", noteId);
        ToolResult deleted = notes.execute(delete, context);
        assertFalse(deleted.isError(), deleted.getOutput());
        assertFalse(deleted.getOutput().contains("Keep the retry boundary"), deleted.getOutput());
    }

    @Test
    void changedIndexReportsProjectedGraphAsStaleUntilRepublished() throws Exception {
        Files.writeString(source, sourceText("changed-without-projection"), StandardCharsets.UTF_8);
        indexOnly();

        ToolResult result = new FileContextTool().execute(
                mapper.createObjectNode().put("file_path", projectRoot.relativize(source).toString()),
                context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("STALE", result.getMetadata().get("graphStatus"));
        assertEquals(false, result.getMetadata().get("graphFresh"));
        assertTrue(result.getOutput().contains("KGraph: STALE"), result.getOutput());
        assertTrue(result.getOutput().contains("Projected KGraph is stale"), result.getOutput());
    }

    @Test
    void missingTransactionalGenerationNeverMergesProjectedGraphEdges() throws Exception {
        try (IndexDatabase database = IndexDatabase.open(LocalCodeIndexer.getIndexDir(projectId))) {
            database.setIndexGeneration(null);
        }

        ToolResult result = new FileContextTool().execute(
                mapper.createObjectNode().put("file_path", projectRoot.relativize(source).toString()),
                context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("GENERATION_UNVERIFIED", result.getMetadata().get("graphStatus"));
        assertEquals(false, result.getMetadata().get("graphFresh"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relations =
                (List<Map<String, Object>>) result.getMetadata().get("relations");
        assertTrue(relations.stream().noneMatch(relation ->
                String.valueOf(relation.get("origin")).contains("kgraph")));
    }

    @Test
    void concurrentAddsDoNotLoseEitherAgentsNote() throws Exception {
        FileNoteTool tool = new FileNoteTool();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ToolResult>> futures = new ArrayList<>();
            for (String content : List.of("first concurrent note", "second concurrent note")) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    ObjectNode params = mapper.createObjectNode()
                            .put("action", "add")
                            .put("file_path", projectRoot.relativize(source).toString())
                            .put("content", content);
                    return tool.execute(params, context);
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "concurrent note writers did not reach the start barrier");
            start.countDown();
            for (Future<ToolResult> future : futures) {
                ToolResult result = future.get();
                assertFalse(result.isError(), result.getOutput());
            }
        } finally {
            pool.shutdownNow();
        }

        ToolResult contextResult = new FileContextTool().execute(
                mapper.createObjectNode().put("file_path", projectRoot.relativize(source).toString()),
                context);
        assertTrue(contextResult.getOutput().contains("first concurrent note"), contextResult.getOutput());
        assertTrue(contextResult.getOutput().contains("second concurrent note"), contextResult.getOutput());
        assertEquals(2, ((List<?>) contextResult.getMetadata().get("notes")).size());
    }

    @Test
    void malformedStoreIsReportedAndMutationFailsClosed() throws Exception {
        Path store = projectRoot.resolve("data/code-projects").resolve(projectId)
                .resolve("metadata/file-notes.json");
        Files.writeString(store, "{broken", StandardCharsets.UTF_8);

        ToolResult lookup = new FileContextTool().execute(
                mapper.createObjectNode().put("file_path", projectRoot.relativize(source).toString()),
                context);
        assertFalse(lookup.isError(), lookup.getOutput());
        assertTrue(lookup.getOutput().contains("File notes unavailable"), lookup.getOutput());

        ToolResult add = new FileNoteTool().execute(mapper.createObjectNode()
                .put("action", "add")
                .put("file_path", projectRoot.relativize(source).toString())
                .put("content", "must not replace corrupt state"), context);
        assertTrue(add.isError(), add.getOutput());
        assertEquals("{broken", Files.readString(store));
    }

    @Test
    void unindexedFileReturnsExplicitStatusAndRejectsNotes() throws Exception {
        Path other = tempDir.resolve("outside-index.txt");
        Files.writeString(other, "not indexed\n");

        ToolResult lookup = new FileContextTool().execute(
                mapper.createObjectNode().put("file_path", other.toString()), context);
        assertFalse(lookup.isError(), lookup.getOutput());
        assertEquals(false, lookup.getMetadata().get("available"));
        assertTrue(lookup.getOutput().contains("No local code index contains this file"), lookup.getOutput());

        ToolResult add = new FileNoteTool().execute(mapper.createObjectNode()
                .put("action", "add").put("file_path", other.toString()).put("content", "no"), context);
        assertTrue(add.isError());
    }

    @Test
    void rejectsOversizedNotesWithoutChangingStore() throws Exception {
        ToolResult result = new FileNoteTool().execute(mapper.createObjectNode()
                .put("action", "add")
                .put("file_path", projectRoot.relativize(source).toString())
                .put("content", "x".repeat(4_001)), context);

        assertTrue(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("max 4000"), result.getOutput());
        Path store = projectRoot.resolve("data/code-projects").resolve(projectId)
                .resolve("metadata/file-notes.json");
        assertFalse(Files.exists(store));
    }

    @Test
    void everyAllowedNoteRemainsDiscoverableAndTheThirteenthIsRejected() throws Exception {
        FileNoteTool tool = new FileNoteTool();
        String relative = projectRoot.relativize(source).toString();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String content = i == 0 ? "z".repeat(1_500) : "note-" + i;
            ToolResult result = tool.execute(mapper.createObjectNode()
                    .put("action", "add").put("file_path", relative)
                    .put("content", content), context);
            assertFalse(result.isError(), result.getOutput());
            ids.add(String.valueOf(result.getMetadata().get("noteId")));
        }

        ToolResult listed = new FileContextTool().execute(
                mapper.createObjectNode().put("file_path", relative), context);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notes =
                (List<Map<String, Object>>) listed.getMetadata().get("notes");
        assertEquals(12, notes.size());
        assertTrue(notes.stream().map(note -> String.valueOf(note.get("id")))
                .toList().containsAll(ids));
        assertTrue(ids.stream().allMatch(id -> listed.getOutput().contains(id)),
                "every retained note id must be visible before body truncation");
        Map<String, Object> longNote = notes.stream()
                .filter(note -> ids.get(0).equals(note.get("id"))).findFirst().orElseThrow();
        assertEquals(1_000, String.valueOf(longNote.get("content")).length());
        assertEquals(true, longNote.get("contentTruncated"));

        ToolResult overflow = tool.execute(mapper.createObjectNode()
                .put("action", "add").put("file_path", relative)
                .put("content", "note-12"), context);
        assertTrue(overflow.isError());
        assertTrue(overflow.getOutput().contains("maximum 12 notes"), overflow.getOutput());
    }

    @Test
    void symbolicNoteStoreIsNeverReadOrReplaced() throws Exception {
        Path store = projectRoot.resolve("data/code-projects").resolve(projectId)
                .resolve("metadata/file-notes.json");
        Path outside = tempDir.resolve("outside-file-notes.json");
        String original = "{\"schema\":\"kompile-file-notes/v1\",\"files\":{}}";
        Files.writeString(outside, original, StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(store, outside);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + unsupported);
        }

        String relative = projectRoot.relativize(source).toString();
        ToolResult lookup = new FileContextTool().execute(
                mapper.createObjectNode().put("file_path", relative), context);
        assertFalse(lookup.isError(), lookup.getOutput());
        assertTrue(lookup.getOutput().contains("File notes unavailable"), lookup.getOutput());

        ToolResult add = new FileNoteTool().execute(mapper.createObjectNode()
                .put("action", "add").put("file_path", relative)
                .put("content", "must stay outside"), context);
        assertTrue(add.isError(), add.getOutput());
        assertEquals(original, Files.readString(outside));
    }

    @Test
    void symbolicMetadataDirectoryCannotRedirectNotesOutsideTheProject() throws Exception {
        Path metadata = projectRoot.resolve("data/code-projects").resolve(projectId)
                .resolve("metadata");
        Path outside = tempDir.resolve("outside-metadata");
        Files.createDirectories(outside);
        try {
            try (var entries = Files.list(metadata)) {
                for (Path entry : entries.toList()) Files.delete(entry);
            }
            Files.delete(metadata);
            Files.createSymbolicLink(metadata, outside);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable: " + unsupported);
        }

        ToolResult add = new FileNoteTool().execute(mapper.createObjectNode()
                .put("action", "add")
                .put("file_path", projectRoot.relativize(source).toString())
                .put("content", "must remain inside owner"), context);

        assertTrue(add.isError(), add.getOutput());
        assertFalse(Files.exists(outside.resolve("file-notes.json")));
    }

    @Test
    void mutationCannotPushTheAggregateStorePastItsReadLimit() throws Exception {
        int maxBytes = 2 * 1024 * 1024;
        Path store = projectRoot.resolve("data/code-projects").resolve(projectId)
                .resolve("metadata/file-notes.json");
        ObjectNode root = mapper.createObjectNode();
        root.put("schema", "kompile-file-notes/v1");
        root.put("updatedAt", "2026-09-04T00:00:00Z");
        root.putObject("files").putArray("other.java").addObject()
                .put("id", "existing")
                .put("content", "x".repeat(maxBytes - 2_500))
                .put("author", "test")
                .put("sessionId", "test")
                .put("createdAt", "2026-09-04T00:00:00Z");
        byte[] before = (mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(before.length < maxBytes && maxBytes - before.length < 4_000,
                "fixture must leave less than one note of headroom");
        Files.write(store, before);

        ToolResult add = new FileNoteTool().execute(mapper.createObjectNode()
                .put("action", "add")
                .put("file_path", projectRoot.relativize(source).toString())
                .put("content", "y".repeat(4_000)), context);

        assertTrue(add.isError(), add.getOutput());
        assertTrue(add.getOutput().contains("would exceed"), add.getOutput());
        assertArrayEquals(before, Files.readAllBytes(store));
    }

    @Test
    void externalCodeRootStoresNotesInTheActiveOwningProject() throws Exception {
        Path owner = tempDir.resolve("owner-project");
        Path externalRoot = tempDir.resolve("external-code");
        Path externalSource = externalRoot.resolve("src/main/java/ext/ExternalService.java");
        Files.createDirectories(externalSource.getParent());
        Files.writeString(externalSource, """
                package ext;
                final class ExternalService { String value() { return "external"; } }
                """, StandardCharsets.UTF_8);
        String externalId = "external-context-" + Math.abs(System.nanoTime());
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            new LocalCodeIndexer().index(externalRoot, externalId, null, null, true, quiet);
        }

        Files.createDirectories(owner);
        KompileCodingProject codeProject = new KompileCodingProject();
        codeProject.setId(externalId);
        codeProject.setCodeProjectId(externalId);
        codeProject.setName("External code");
        codeProject.setRootPath(externalRoot.toString());
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        request.setName("Owner project");
        request.setIncludeStandardComponents(false);
        request.getCodingProjects().add(codeProject);
        new KompileProjectStore().init(owner, request);

        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext ownerContext = new ToolContext("external-owner-session", null, permissions,
                owner, new ToolRegistry(mapper));
        ToolResult added = new FileNoteTool().execute(mapper.createObjectNode()
                .put("action", "add")
                .put("file_path", externalSource.toString())
                .put("content", "This checkout is owned by the active project."), ownerContext);

        assertFalse(added.isError(), added.getOutput());
        Path ownerStore = owner.resolve("data/code-projects").resolve(externalId)
                .resolve("metadata/file-notes.json");
        assertTrue(Files.isRegularFile(ownerStore), ownerStore.toString());
        assertFalse(Files.exists(externalRoot.resolve("data/code-projects").resolve(externalId)
                .resolve("metadata/file-notes.json")));
        assertEquals(owner.toRealPath().toString(), added.getMetadata().get("projectRoot"));
        assertEquals("NOT_PROJECTED", added.getMetadata().get("graphStatus"));
    }

    private void indexAndPublish() throws Exception {
        indexOnly();
        LocalCodeKGraphPublisher.ProjectionResult projection =
                LocalCodeKGraphPublisher.publish(projectRoot, projectId, null, null);
        assertNotNull(projection.graphPath());
        assertTrue(Files.isRegularFile(projection.graphPath()));
    }

    private void indexOnly() throws Exception {
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            new LocalCodeIndexer().index(projectRoot, projectId, null, null, true, quiet);
        }
    }

    private static String sourceText(String value) {
        return """
                package demo;
                class BaseContext {}
                final class ContextService extends BaseContext {
                    String answer() { return helper(); }
                    String helper() { return "%s"; }
                }
                """.formatted(value);
    }
}
