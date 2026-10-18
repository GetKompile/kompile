/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.incidents.IncidentStore;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncidentToolTest {
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final IncidentTool tool = new IncidentTool();
    @TempDir Path project;

    private ToolContext context() {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        return new ToolContext("incident-session", null, permissions, project, new ToolRegistry(mapper));
    }
    private ToolResult run(String json) throws Exception { return tool.execute(mapper.readTree(json), context()); }
    private ObjectNode draft(String title) {
        ObjectNode record = mapper.createObjectNode().put("title", title)
                .put("symptom", "Fallback blocks work").put("invariant", "Infrastructure failures fail open");
        ObjectNode scope = record.putObject("scope");
        scope.putArray("paths").add("src/**/*.java");
        scope.putArray("symbols").add("Guard.evaluate");
        scope.putArray("tags").add("judge");
        return record;
    }
    private String add(String title) throws Exception {
        ObjectNode params = mapper.createObjectNode().put("action", "add");
        params.set("record", draft(title));
        ToolResult result = tool.execute(params, context());
        assertFalse(result.isError(), result.getOutput());
        return result.getMetadata().get("id").toString();
    }
    private ObjectNode mutation(String action, String id, int revision) {
        return mapper.createObjectNode().put("action", action).put("id", id).put("expected_revision", revision);
    }

    @Test void emptyReadsDoNotCreateStorage() throws Exception {
        assertFalse(run("{\"action\":\"list\"}").isError());
        assertFalse(Files.exists(project.resolve(".kompile")));
        assertTrue(run("{\"action\":\"get\",\"id\":\"../outside\"}").isError());
    }

    @Test void lifecyclePreservesEvidenceAndRevisionsAndRejectsStaleUpdates() throws Exception {
        String id = add("Judge fail-open");
        ObjectNode update = mutation("update", id, 1);
        ObjectNode changes = update.putObject("record").put("verification", "confirmed");
        changes.putArray("evidence").addObject().put("type", "memory").put("ref", "memory/fix.md");
        changes.putArray("checks").addObject().put("type", "test").put("selector", "GuardTest");
        ToolResult confirmed = tool.execute(update, context());
        assertFalse(confirmed.isError(), confirmed.getOutput());
        assertTrue(tool.execute(update, context()).isError());
        ObjectNode resolved = mutation("resolve", id, 2);
        resolved.putObject("record").putArray("evidence").addObject()
                .put("type", "validation").put("ref", "test-run/123");
        ToolResult fixed = tool.execute(resolved, context());
        assertFalse(fixed.isError(), fixed.getOutput());
        JsonNode record = mapper.readTree(fixed.getOutput());
        assertEquals("fixed", record.path("status").asText());
        assertEquals(2, record.path("evidence").size());
        assertEquals("reported-not-runner-verified", record.path("validationBasis").asText());
        assertEquals("incident-session", record.path("recordedBySession").asText());
        IncidentStore store = new IncidentStore(project);
        assertEquals("suspected", store.get(id, 1).path("verification").asText());
        assertEquals("open", store.get(id, 2).path("status").asText());
        assertEquals(3, store.get(id, 3).path("revision").asInt());
        assertEquals(1, store.search("judge", null, null, 10, false).matches().size(), "fixed stays active");
        ObjectNode invalidated = mutation("update", id, 3);
        invalidated.putObject("record").put("status", "invalidated");
        assertFalse(tool.execute(invalidated, context()).isError());
        assertTrue(store.search("judge", null, null, 10, false).matches().isEmpty());
        assertEquals(1, store.search(null, null, null, 10, true).matches().size());
    }

    @Test void requiresEvidenceAndExplicitTransitionsButNeverSupportsBlocking() throws Exception {
        String id = add("Judge issue");
        assertTrue(tool.execute(mutation("resolve", id, 1), context()).isError());
        for (String field : List.of("verification", "status", "guardrail", "revision", "schemaVersion")) {
            ObjectNode update = mutation("update", id, 1);
            ObjectNode record = update.putObject("record");
            switch (field) {
                case "verification" -> record.put(field, "confirmed");
                case "status" -> record.put(field, "fixed");
                case "guardrail" -> record.putObject(field).put("mode", "block");
                default -> record.put(field, 999);
            }
            assertTrue(tool.execute(update, context()).isError(), field);
        }
        assertTrue(run("{\"action\":\"update\",\"id\":\"" + id + "\"}").isError());
        assertEquals(1, new IncidentStore(project).get(id, null).path("revision").asInt());
    }

    @Test void supersedesWithoutDeletingOriginalAndRejectsBadReplacement() throws Exception {
        String id = add("Original judge incident"), replacement = add("Replacement judge incident");
        ObjectNode params = mutation("supersede", id, 1).put("replacement_id", id);
        assertTrue(tool.execute(params, context()).isError());
        params.put("replacement_id", replacement);
        assertFalse(tool.execute(params, context()).isError());
        IncidentStore store = new IncidentStore(project);
        assertEquals(replacement, store.get(id, null).path("supersededBy").asText());
        assertEquals("open", store.get(id, 1).path("status").asText());
        assertEquals(1, store.search("judge", null, null, 20, false).matches().size());
    }

    @Test void deterministicMatchingSupportsDirectChildGlobAndProjectIsolation() throws Exception {
        String id = add("Judge fallback");
        IncidentStore store = new IncidentStore(project);
        for (String path : List.of("src/Guard.java", "src/deep/Guard.java", project.resolve("src/Guard.java").toString())) {
            var match = store.search(null, path, null, 10, false).matches();
            assertEquals(id, match.get(0).incident().path("id").asText());
            assertTrue(match.get(0).reasons().get(0).startsWith("path:"));
        }
        assertEquals(1, store.search(null, null, "Guard.evaluate", 10, false).matches().size());
        assertTrue(store.search("unrelated", null, null, 10, false).matches().isEmpty());
        assertTrue(store.search(null, "../src/Guard.java", null, 10, false).matches().isEmpty());
        Path other = Files.createDirectory(project.resolve("other"));
        assertTrue(new IncidentStore(other).search("judge", null, null, 10, false).matches().isEmpty());
    }

    @Test void malformedAndOversizeRecordsAreReportedWithoutBreakingGoodMatches() throws Exception {
        String id = add("Judge fallback");
        Files.writeString(project.resolve(".kompile/incidents/INC-broken.json"), "{bad");
        Files.writeString(project.resolve(".kompile/incidents/INC-large.json"), "x".repeat(65537));
        var result = new IncidentStore(project).search("judge", null, null, 10, false);
        assertEquals(id, result.matches().get(0).incident().path("id").asText());
        assertEquals(2, result.warnings().size());
        assertTrue(new IncidentStore(project).context("judge", null, 500).length() <= 500);
    }

    @Test void symlinksAndTraversalCannotReadOrOverwriteExternalFiles() throws Exception {
        String id = add("Judge fallback");
        Path outside = Files.createTempDirectory(project, "outside-");
        Path external = Files.writeString(outside.resolve("external.json"), "untouched");
        Path file = project.resolve(".kompile/incidents/" + id + ".json");
        Files.delete(file);
        Files.createSymbolicLink(file, external);
        assertTrue(run("{\"action\":\"get\",\"id\":\"" + id + "\"}").isError());
        assertTrue(tool.execute(mutation("update", id, 1), context()).isError());
        assertEquals("untouched", Files.readString(external));
        for (String unsafe : List.of("../outside", "/tmp/outside", "INC-../outside")) {
            assertTrue(run("{\"action\":\"get\",\"id\":\"" + unsafe + "\"}").isError());
        }
        Path other = Files.createDirectory(project.resolve("other"));
        Files.createSymbolicLink(other.resolve(".kompile"), outside);
        assertThrows(java.io.IOException.class, () -> new IncidentStore(other).search(null, null, null, 10, true));
    }

    @Test void readAndWritePermissionsAreActionSpecific() throws Exception {
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("memory", PermissionService.PermissionLevel.DENY);
        permissions.setUserOverride("write", PermissionService.PermissionLevel.DENY);
        ToolContext denied = new ToolContext("restricted", null, permissions, project, new ToolRegistry(mapper));
        assertFalse(tool.execute(mapper.createObjectNode().put("action", "list"), denied).isError());
        ObjectNode params = mapper.createObjectNode().put("action", "add");
        params.set("record", draft("denied"));
        assertThrows(ToolExecutionException.class, () -> tool.execute(params, denied));
        permissions.setUserOverride("memory", PermissionService.PermissionLevel.ALLOW);
        assertThrows(ToolExecutionException.class, () -> tool.execute(params, denied), "write capability still required");
        permissions.setUserOverride("read", PermissionService.PermissionLevel.DENY);
        assertThrows(ToolExecutionException.class, () -> tool.execute(mapper.createObjectNode().put("action", "list"), denied));
        assertFalse(Files.exists(project.resolve(".kompile")));
    }

    @Test void malformedEvidenceUpdatesAreRejectedBeforeChangingHistory() throws Exception {
        String id = add("Judge fallback");
        for (String value : List.of("\"invalid\"", "null", "{\"x\":{\"type\":\"memory\",\"ref\":\"a\"}}")) {
            ObjectNode params = mutation("update", id, 1);
            params.putObject("record").set("evidence", mapper.readTree(value));
            assertTrue(tool.execute(params, context()).isError(), value);
            assertEquals(1, new IncidentStore(project).get(id, null).path("revision").asInt());
            assertFalse(Files.exists(project.resolve(".kompile/incidents/history")));
        }
    }

    @Test void adversarialWildcardsHaveNoRegexBacktracking() throws Exception {
        ObjectNode params = mapper.createObjectNode().put("action", "add");
        ObjectNode draft = draft("Wildcard regression");
        draft.putObject("scope").putArray("paths").add("*a".repeat(100) + "Z");
        params.set("record", draft);
        assertFalse(tool.execute(params, context()).isError());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
            assertTrue(new IncidentStore(project).search(null, "a".repeat(200), null, 10, false).matches().isEmpty());
        });
    }

    @Test void competingWritersCannotCommitTheSameExpectedRevision() throws Exception {
        String id = add("Judge fallback");
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> writer = () -> {
                start.await();
                return !tool.execute(mutation("update", id, 1), context()).isError();
            };
            var first = executor.submit(writer);
            var second = executor.submit(writer);
            start.countDown();
            int successes = (first.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(2, new IncidentStore(project).get(id, null).path("revision").asInt());
            assertEquals(1, new IncidentStore(project).get(id, 1).path("revision").asInt());
        } finally { executor.shutdownNow(); }
    }

    @Test void busyStoreRefusesUpdatesWithoutLosingTheCurrentRevision() throws Exception {
        String id = add("Judge fallback");
        Path lockFile = project.resolve(".kompile/incidents/.lock");
        try (var channel = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            ToolResult result = tool.execute(mutation("update", id, 1), context());
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("busy"));
        }
        assertEquals(1, new IncidentStore(project).get(id, null).path("revision").asInt());
    }

    @Test void explicitOffDisablesAdviceButKeepsTheRecordAndHistory() throws Exception {
        String id = add("Judge fallback");
        ObjectNode update = mutation("update", id, 1);
        update.putObject("record").putObject("guardrail").put("mode", "off");
        assertFalse(tool.execute(update, context()).isError());
        IncidentStore store = new IncidentStore(project);
        assertEquals("", store.context("judge", null, 2000));
        assertEquals(1, store.search("judge", null, null, 10, true).matches().size());
        assertEquals("warn", store.get(id, 1).path("guardrail").path("mode").asText());
        assertEquals("", store.context(null, null, 2000));
    }

    @Test void discoverableThroughBothMemoryAndWorkflowGroups() {
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(tool);
        for (String group : List.of("memory", "workflow")) {
            DynamicToolManager manager = new DynamicToolManager();
            manager.register(tool.id(), tool.description(), tool.parameterSchema());
            manager.activateGroup(group);
            assertTrue(manager.getActiveToolIds().contains("incident"));
        }
        assertFalse(tool.mcpAnnotations().readOnlyHint(), "mixed-action tool is not globally read-only");
    }
}
