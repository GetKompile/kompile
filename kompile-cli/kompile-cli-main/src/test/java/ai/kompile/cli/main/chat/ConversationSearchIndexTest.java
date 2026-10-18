package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ConversationSearchIndexTest {
    @TempDir Path directory;

    private Path transcript(String id, String agent, String project, String body) throws Exception {
        Path file = directory.resolve(id + ".txt");
        Files.writeString(file, "Started: 2026-10-01 12:00:00\nAgent: " + agent + "\nCWD: " + project + "\n> " + body + "\n");
        return file;
    }

    private ConversationSearchIndex.Page search(ConversationSearchIndex index, String text) throws Exception {
        return index.search(text, null, null, null, null, null, 0, 50);
    }

    @Test
    void transcriptSyncUpdateDeletionAndRebuildOnlyChangeProjection() throws Exception {
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        Path file = transcript("session", "codex", "/work/project", "originalword");
        assertEquals(1, search(index, "originalword").hits().size());
        assertEquals(1, search(index, "originalword").hits().size(), "Unchanged source is not duplicated");
        Files.writeString(file, "< appendedword\n", StandardOpenOption.APPEND);
        assertEquals(1, search(index, "appendedword").hits().size());
        transcript("session", "claude", "/work/other", "replacementword");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        assertTrue(search(index, "originalword").hits().isEmpty());
        assertEquals("claude", search(index, "replacementword").hits().get(0).agent());
        String source = Files.readString(file);
        index.rebuild();
        assertEquals(source, Files.readString(file));
        assertEquals(1, search(index, "replacementword").hits().size());
        Files.delete(file); // Test fixture only; index reconciliation must not delete history.
        assertTrue(search(index, "replacementword").hits().isEmpty());
    }

    @Test
    void missingProjectionRecreatesItselfFromHistory() throws Exception {
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        transcript("session", "codex", "/work/project", "rebuildword");
        search(index, "rebuildword");
        Files.delete(directory.resolve(".search-index.db")); // Disposable temporary projection only.
        assertEquals(1, search(new ConversationSearchIndex(directory), "rebuildword").hits().size());
    }

    @Test
    void fulltextUsesTokenAndPhraseSyntaxNotRegexOrSubstringMatching() throws Exception {
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        transcript("session", "codex", "/work/project", "alpha beta alphabetical");
        assertEquals(1, search(index, "\"alpha beta\"").hits().size());
        assertTrue(search(index, "alph").hits().isEmpty());
        assertEquals(1, search(index, "alph*").hits().size());
        assertThrows(SQLException.class, () -> search(index, "alpha.*"));
    }

    @Test
    void rankedLimitsAndTiesAreStableAcrossPagesAndInstances() throws Exception {
        for (String id : List.of("c", "a", "b")) transcript(id, "codex", "/work/project", "sharedtoken");
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        var first = index.search("sharedtoken", null, null, null, null, null, 0, 1);
        assertEquals("a", first.hits().get(0).sessionId());
        assertEquals(1, first.nextOffset());
        var second = new ConversationSearchIndex(directory).search("sharedtoken", null, null, null, null, null, 1, 1);
        assertEquals("b", second.hits().get(0).sessionId());
        assertEquals(2, second.nextOffset());
        var last = index.search("sharedtoken", null, null, null, null, null, 2, 1);
        assertEquals("c", last.hits().get(0).sessionId());
        assertNull(last.nextOffset());
        assertThrows(IllegalArgumentException.class, () -> index.search("sharedtoken", null, null, null, null, null, 0, 201));
        assertThrows(IllegalArgumentException.class, () -> index.search("sharedtoken", null, null, null, null, null, -1, 1));
    }

    @Test
    void fulltextCombinesLiteralSessionPrefixAgentProjectAndDateFilters() throws Exception {
        transcript("prefix-one", "Codex", "/work/project", "sharedtoken");
        transcript("prefix-two", "claude", "/work/not-project", "sharedtoken");
        transcript("other-prefix", "codex", "/work/project", "sharedtoken");
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        var page = index.search("sharedtoken", "prefix", "ODE", "project", "2026-10-01", "2026-10-01", 0, 50);
        assertEquals(List.of("prefix-one"), page.hits().stream().map(ConversationSearchIndex.Hit::sessionId).toList());
        assertTrue(index.search("sharedtoken", "PREFIX", null, null, null, null, 0, 50).hits().isEmpty());
        assertTrue(index.search("sharedtoken", null, null, "project-substring", null, null, 0, 50).hits().isEmpty());
        assertTrue(index.search("sharedtoken", null, null, null, "2026-10-02", null, 0, 50).hits().isEmpty());
        assertEquals(3, index.search("sharedtoken", null, null, null, null, "2026-10-01T12:00:00", 0, 50).hits().size());
    }

    @Test
    void longLinesAreChunkedAndSnippetsBoundedWithLocations() throws Exception {
        transcript("session", "codex", "/work/project", "padding ".repeat(3000) + "needleword " + "suffix ".repeat(3000));
        var page = search(new ConversationSearchIndex(directory), "needleword");
        assertFalse(page.hits().isEmpty());
        assertTrue(page.hits().size() <= 2, "Only boundary overlap can repeat a hit");
        for (var hit : page.hits()) {
            assertEquals(4, hit.line());
            assertTrue(hit.chunk() > 0);
            assertTrue(hit.snippet().length() <= 1202);
            assertTrue(hit.snippet().contains("[needleword]"));
        }
    }

    @Test
    void resumedAgentIsMetadataButMessageTextCannotSpoofIt() throws Exception {
        Path file = transcript("session", "codex", "/work/project", "firstword");
        Files.writeString(file, "Agent: attacker\n[resumed 2026-10-02 12:00:00]\nAgent: claude\nCWD: /work/other\n< secondword\nAgent: ignored\n",
                StandardOpenOption.APPEND);
        var hit = search(new ConversationSearchIndex(directory), "secondword").hits().get(0);
        assertEquals("claude", hit.agent());
        assertEquals("/work/other", hit.project());
    }

    @Test
    void failedSourceRefreshRollsBackItsRowsAndCheckpoint() throws Exception {
        Path file = transcript("session", "codex", "/work/project", "retainedword");
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        search(index, "retainedword");
        Files.write(file, new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(java.io.IOException.class, index::syncTranscripts);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(".search-index.db"));
             var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM transcript_chunks WHERE text LIKE '%retainedword%'")) {
            assertTrue(r.next());
            assertEquals(1, r.getInt(1));
        }
    }

    @Test
    void retirementHidesUnchangedCombinedCatalogAndRestoreMakesItVisibleAgain() throws Exception {
        transcript("one", "codex", "/work/project", "retirableword");
        var call = call("a", "one", "Read", "result", "codex", "/work/project", true);
        calls("all-tool-calls", call);
        calls("one", call);
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        assertEquals(1, catalog(index, null).size());
        assertEquals(1, search(index, "retirableword").hits().size());
        Path retired = Files.createDirectories(directory.resolve(".retired"));
        Files.writeString(retired.resolve("one"), ".trash/one/example");
        assertTrue(catalog(index, null).isEmpty());
        assertTrue(catalog(index, "one").isEmpty());
        assertEquals(0, index.catalogStats().get("totalToolCalls"));
        assertTrue(index.catalogDistinct("session").isEmpty());
        assertTrue(search(index, "retirableword").hits().isEmpty());
        Files.delete(retired.resolve("one"));
        assertEquals(1, catalog(index, null).size());
        assertEquals(1, search(index, "retirableword").hits().size());
    }

    @Test
    void groupedQueriesApplyLimitsBeforeMaterializingCalls() throws Exception {
        calls("all-tool-calls", call("a", "one", "Read", "a", "codex", "/work/a", false),
                call("b", "one", "Read", "b", "codex", "/work/a", false),
                call("c", "two", "Bash", "c", "codex", "/work/b", false),
                call("d", "two", "Bash", "d", "codex", "/work/b", false));
        var index = new ConversationSearchIndex(directory);
        var byCategory = index.catalogGrouped(null, null, null, null, null, null, null, null, 1, false);
        var byProject = index.catalogGrouped(null, null, null, null, null, null, null, null, 1, true);
        assertEquals(List.of("a", "c"), byCategory.stream().map(ToolCallRecord::getId).toList());
        assertEquals(List.of("a", "c"), byProject.stream().map(ToolCallRecord::getId).toList());
    }

    @Test
    void indexRefusesSymlinkedDatabaseAndDoesNotIndexLinkedTranscripts() throws Exception {
        Path external = Files.writeString(directory.resolve("external"), "do not modify");
        Files.createSymbolicLink(directory.resolve(".search-index.db"), external);
        assertThrows(java.io.IOException.class, () -> search(new ConversationSearchIndex(directory), "anything"));
        assertEquals("do not modify", Files.readString(external));
        Files.delete(directory.resolve(".search-index.db"));
        Files.createSymbolicLink(directory.resolve("linked.txt"), external);
        assertTrue(search(new ConversationSearchIndex(directory), "modify").hits().isEmpty());
    }

    private ToolCallRecord call(String id, String session, String tool, String input, String agent,
                                String project, boolean error) {
        return new ToolCallRecord(id, session, tool, input, "summary " + input,
                Instant.parse("2026-10-01T12:00:00Z"), "test-import", agent, error, 5,
                ToolCallRecord.categorize(tool), project, Map.of("payloadTokens", 7));
    }

    private Path calls(String name, ToolCallRecord... calls) throws Exception {
        Path root = directory.resolve("tool-calls");
        Files.createDirectories(root);
        Path file = root.resolve(name + ".jsonl");
        StringBuilder text = new StringBuilder();
        for (var call : calls) text.append(JsonUtils.standardMapper().writeValueAsString(call)).append('\n');
        Files.writeString(file, text);
        return file;
    }

    private List<ToolCallRecord> catalog(ConversationSearchIndex index, String session) throws Exception {
        return index.catalogSearch(null, null, session, null, null, null,
                ToolCallIndex.SortField.TIMESTAMP, ToolCallIndex.SortDirection.DESC, 50);
    }

    @Test
    void purgeCompactionAndLaterAppendReconcileCatalogProjectionWithoutStaleOrDuplicateRows() throws Exception {
        var gone = call("a", "gone", "Read", "old", "codex", "/work/project", false);
        var live = call("b", "live", "Bash", "new", "claude", "/work/other", false);
        calls("all-tool-calls", gone, live);
        var index = new ConversationSearchIndex(directory);
        assertEquals(2, catalog(index, null).size());
        Files.writeString(Files.createDirectories(directory.resolve(".purged")).resolve("gone"), "receipt");
        var compacted = new ai.kompile.cli.common.metrics.ToolJournalCompactor(directory).compact(false);
        assertEquals(1, compacted.removedRecords());
        assertEquals(List.of("b"), catalog(index, null).stream().map(ToolCallRecord::getId).toList());
        assertEquals(1, index.catalogStats().get("totalToolCalls"));
        var appended = call("c", "live", "Read", "later", "claude", "/work/other", false);
        ai.kompile.cli.common.metrics.ToolJournalFiles.appendCatalog(directory.resolve("tool-calls"), "live",
                JsonUtils.standardMapper().writeValueAsString(appended) + "\n");
        assertEquals(List.of("b", "c"), catalog(index, null).stream().map(ToolCallRecord::getId).toList());
        index.rebuild();
        assertEquals(List.of("b", "c"), catalog(index, null).stream().map(ToolCallRecord::getId).toList());
    }

    @Test
    void catalogMaintainsIndependentCombinedAndSessionStreamsWithoutDoubleCounting() throws Exception {
        var a = call("a", "one", "Read", "first", "codex", "/work/project", false);
        var b = call("b", "two", "Bash", "second", "claude", "/work/other", true);
        Path combined = calls("all-tool-calls", a, b);
        Path perSession = calls("one", a);
        calls("two", b);
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        assertEquals(2, catalog(index, null).size());
        assertEquals(List.of("a"), catalog(index, "one").stream().map(ToolCallRecord::getId).toList());
        assertEquals(2, index.catalogStats().get("totalToolCalls"));
        assertEquals(1L, index.catalogStats().get("totalErrors"));
        assertEquals(2, index.catalogStats().get("sessionCount"));
        assertEquals(Map.of("codex", 1L, "claude", 1L), index.catalogStats().get("byAgent"));
        assertEquals(Map.of("test-import", 2L), index.catalogStats().get("bySource"));
        Files.delete(perSession);
        assertTrue(catalog(index, "one").isEmpty());
        assertEquals(2, catalog(index, null).size(), "Combined stream compatibility is independent of a session file");
        Files.delete(combined);
        assertTrue(catalog(index, null).isEmpty());
        assertEquals(1, catalog(index, "two").size());
    }

    @Test
    void catalogAppendRewriteMalformedAndUnterminatedImportsKeepCheckpointParity() throws Exception {
        var a = call("a", "one", "Read", "first", "codex", "/work/project", false);
        var b = call("b", "one", "Bash", "second", "codex", "/work/project", false);
        Path file = calls("all-tool-calls", a);
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        assertEquals(1, catalog(index, null).size());
        Files.writeString(file, "malformed\n" + JsonUtils.standardMapper().writeValueAsString(b), StandardOpenOption.APPEND);
        assertEquals(2, catalog(index, null).size(), "Valid final JSON needs no newline for legacy imports");
        Files.writeString(file, "\n", StandardOpenOption.APPEND);
        assertEquals(2, catalog(index, null).size(), "Finalized pending line replaces rather than duplicates");
        assertEquals(2, catalog(index, null).size());
        calls("all-tool-calls", b);
        assertEquals(List.of("b"), catalog(index, null).stream().map(ToolCallRecord::getId).toList());
        index.rebuild();
        assertEquals(List.of("b"), catalog(index, null).stream().map(ToolCallRecord::getId).toList());
    }

    @Test
    void catalogSubstringFiltersUnicodeSortStableTiesAndUsageArePreserved() throws Exception {
        var a = call("a", "one", "Read", "KEYWORD_%", "Cödex", "C:\\work\\project", false);
        var b = call("b", "one", "read", "keyword_%", "cödex", "/work/project", false);
        var c = call("c", "two", "Bash", "other", "claude", "/work/other", false);
        calls("all-tool-calls", a, b, c);
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        var hits = index.catalogSearch("word_%", "READ", null, "FILESYSTEM", "CÖDEX", "project",
                ToolCallIndex.SortField.TOOL_NAME, ToolCallIndex.SortDirection.DESC, 1);
        assertEquals(List.of("a"), hits.stream().map(ToolCallRecord::getId).toList());
        assertEquals(a.getUsage(), hits.get(0).getUsage());
        assertEquals(List.of("one", "two"), index.catalogDistinct("session"));
        assertEquals(List.of("Bash", "Read", "read"), index.catalogDistinct("tool"));
        assertTrue(index.catalogSearch(null, null, null, null, null, "roject",
                null, null, 50).isEmpty(), "Project filter is an exact path/suffix, not an arbitrary substring");
    }

    @Test
    void incompleteJsonlTailIsRetriedWithoutSkippingOrDuplicatingCalls() throws Exception {
        var first = call("a", "one", "Read", "first", "codex", "/work/project", false);
        var second = call("b", "one", "Read", "second", "codex", "/work/project", false);
        Path file = calls("all-tool-calls", first);
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        String json = JsonUtils.standardMapper().writeValueAsString(second);
        int middle = json.length() / 2;
        Files.writeString(file, json.substring(0, middle), StandardOpenOption.APPEND);
        assertEquals(1, catalog(index, null).size());
        Files.writeString(file, json.substring(middle) + "\n", StandardOpenOption.APPEND);
        assertEquals(List.of("a", "b"), catalog(index, null).stream().map(ToolCallRecord::getId).toList());
        assertEquals(2, catalog(index, null).size());
    }

    @Test
    void atomicGrowingSourceReplacementIsNotMistakenForAnAppend() throws Exception {
        String input = "padding".repeat(1000) + "markerold" + "padding".repeat(1000);
        var first = call("a", "one", "Read", input, "codex", "/work/project", false);
        var second = call("b", "one", "Read", "second", "codex", "/work/project", false);
        Path file = calls("all-tool-calls", first);
        ConversationSearchIndex index = new ConversationSearchIndex(directory);
        assertEquals(1, catalog(index, null).size());
        Path replacement = directory.resolve("replacement.tmp");
        Files.writeString(replacement, Files.readString(file).replace("markerold", "markernew") +
                JsonUtils.standardMapper().writeValueAsString(second) + "\n");
        Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(2, catalog(index, null).size());
        assertTrue(index.catalogSearch("markerold", null, null, null, null, null, null, null, 10).isEmpty());
        assertEquals(1, index.catalogSearch("markernew", null, null, null, null, null, null, null, 10).size());
    }

    @Test
    void publicToolCallIndexQueriesProjectionAndPreservesSessionAndUsageBehavior() {
        ToolCallIndex index = ToolCallIndex.getInstance();
        var first = call("first", "one", "Read", "literal_%", "codex", "/work/project", false);
        var second = call("second", "two", "Bash", "command", "claude", "/work/other", true);
        index.record(first);
        index.record(second);
        assertEquals(List.of("first"), index.search("literal_%", "read", null, "filesystem", "CODEX", "project",
                ToolCallIndex.SortField.TIMESTAMP, ToolCallIndex.SortDirection.DESC, 1).stream().map(ToolCallRecord::getId).toList());
        assertEquals(first.getUsage(), index.listBySession("one", 1).get(0).getUsage());
        assertEquals(2, index.getStats().get("totalToolCalls"));
        assertEquals(List.of("Bash", "Read"), index.getDistinctToolNames());
        assertEquals(List.of("one", "two"), index.getDistinctSessionIds());
        assertEquals(1, index.groupByCategory(null, null, null, null, null, null, null, 1).get("filesystem").size());
        assertEquals(2, index.groupByProject(null, null, null, null, null, null, null, 1).size());
    }
}
