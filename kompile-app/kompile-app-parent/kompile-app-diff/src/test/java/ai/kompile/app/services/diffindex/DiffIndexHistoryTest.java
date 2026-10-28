package ai.kompile.app.services.diffindex;

import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ToolCallExtractor.ExtractedToolCall;
import ai.kompile.cli.common.chat.sources.ToolCallExtractor.ExtractionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffIndexHistoryTest {
    @TempDir Path directory;
    private static final String TIME = "2026-10-25T08:57:00Z";
    private static final ChatSessionSummary SESSION = new ChatSessionSummary(
            "parent", "claude-code", "test", "claude", 1, 1_790_000_000_000L, "/project");

    private DiffIndexService service() {
        DiffIndexService service = new DiffIndexService(directory);
        service.init();
        return service;
    }

    private ExtractedToolCall call(String id, String tool, String input) {
        return new ExtractedToolCall(tool, input, false, "claude-code", "/project", TIME, id, "agent-worker");
    }

    private int index(DiffIndexService service, List<ExtractedToolCall> calls) {
        return service.indexExtraction("claude-code", SESSION, null,
                new ExtractionResult("parent", "claude-code", "/project", calls));
    }

    private List<DiffIndexEntry> all(DiffIndexService service) {
        return service.search(null, null, null, null, null, null, null, 1000);
    }

    @Test
    void growingSessionIndexesAppendedEditsAndPreservesRepeatedIdenticalEditsAcrossRestart() {
        DiffIndexService service = service();
        String input = "{\"file_path\":\"src/A.java\",\"old_string\":\"old\",\"new_string\":\"new\"}";
        var first = call("edit-1", "mcp__kompile__edit", input);
        var second = call("edit-2", "mcp__kompile__edit", input);
        assertEquals(1, index(service, List.of(first)));
        String id = all(service).get(0).getId();
        assertEquals(1, index(service, List.of(first, second)));
        assertEquals(0, index(service, List.of(first, second)));
        assertEquals(2, all(service).size());
        assertNotNull(service.get(id));
        assertTrue(all(service).stream().allMatch(e -> TIME.equals(e.getTimestamp())));
        assertEquals(0, index(service(), List.of(first, second)));
        assertEquals(2, all(service()).size());
    }

    @Test
    void upgradesLegacyEntryWithoutDuplicatingOrLosingItsId() {
        DiffIndexService service = service();
        String input = "{\"file_path\":\"src/A.java\",\"old_string\":\"old\",\"new_string\":\"new\"}";
        var call = call("edit-1", "Edit", input);
        index(service, List.of(call));
        DiffIndexEntry legacy = all(service).get(0);
        legacy.setSourceCallId(null);
        legacy.setTimestamp("2099-01-01T00:00:00Z");
        assertEquals(0, index(service, List.of(call)));
        assertEquals(1, all(service).size());
        assertEquals(legacy.getId(), all(service).get(0).getId());
        assertEquals(TIME, all(service).get(0).getTimestamp());
    }

    @Test
    void indexesAllBatchEditsAndPathlessMultiFilePatchesButNotFailedCalls() {
        DiffIndexService service = service();
        var batch = call("batch", "mcp__kompile__edit_batch", """
                {"edits":[
                  {"file_path":"A.java","old_string":"one","new_string":"two"},
                  {"file_path":"B.java","old_string":"three","new_string":"four"}]}
                """);
        var patch = call("patch", "functions.apply_patch",
                "{\"patch\":\"*** Begin Patch\\n*** Update File: C.java\\n@@\\n-old\\n+new\\n*** Add File: D.java\\n+added\\n*** End Patch\"}");
        var editPatch = call("edit-patch", "edit_patch",
                "{\"patches\":[{\"file_path\":\"E.java\",\"patch\":\"@@\\n-a\\n+b\"}]}");
        var failed = new ExtractedToolCall("Write", "{\"file_path\":\"failed\",\"content\":\"bad\"}",
                true, "claude-code", "/project");
        assertEquals(5, index(service, List.of(batch, patch, editPatch, failed)));
        assertEquals(0, index(service, List.of(batch, patch, editPatch, failed)));
        assertEquals(List.of("A.java", "B.java", "C.java", "D.java", "E.java"),
                all(service).stream().map(DiffIndexEntry::getFilePath).sorted().toList());
        assertTrue(all(service).stream().allMatch(e -> e.getLinesAdded() > 0));
        assertEquals("two", all(service).stream().filter(e -> e.getFilePath().equals("A.java"))
                .findFirst().orElseThrow().getNewString());
    }

    @Test
    void pagesPastHundredWithDeterministicTiesAndWorkerFiltering() {
        DiffIndexService service = service();
        List<ExtractedToolCall> calls = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            calls.add(call("edit-" + i, "Write", "{\"file_path\":\"F" + i + ".java\",\"content\":\"new\"}"));
        }
        assertEquals(205, index(service, calls));
        var ids = new HashSet<String>();
        for (int offset = 0; offset < 205; offset += 100) {
            List<DiffIndexEntry> page = service.search(null, null, null, null, null, null, null,
                    100, "timestamp", "desc", offset, "agent-worker");
            assertEquals(offset == 200 ? 5 : 100, page.size());
            for (DiffIndexEntry entry : page) assertTrue(ids.add(entry.getId()), "duplicate across pages");
        }
        assertEquals(205, ids.size());
        assertTrue(service.search(null, null, null, null, null, null, null,
                100, null, null, 0, "other-session").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.search(null, null, null,
                null, null, null, null, 100, null, null, -1, null));
    }
}
