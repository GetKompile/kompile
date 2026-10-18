package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DurableToolResultStoreTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    private ToolResultReferenceCache cache(Path root) {
        return new ToolResultReferenceCache(10, 1, 4096,
                new DurableToolResultStore(root), System::currentTimeMillis);
    }

    private Path resultDirectory(Path root, String handle) {
        String[] parts = handle.split("\\.");
        return root.resolve(parts[1]).resolve("result-cache").resolve(parts[2]);
    }

    @Test
    void restartResolvesOwnerAndFullUuidWithoutAnyInMemoryEntry() throws Exception {
        Path root = temp.resolve("conversations");
        ToolResultReferenceCache original = cache(root);
        String source = "αβ\nemoji 😀\nlast\n";
        ToolResult summary = original.storeAndSummarize("session-one", "read", "title", source,
                Map.of("path", "document.txt"));
        String handle = summary.getMetadata().get("result_id").toString();
        assertTrue(handle.startsWith("d1.session-one."));
        assertEquals(handle.substring("d1.session-one.".length()),
                UUID.fromString(handle.substring("d1.session-one.".length())).toString());
        assertEquals(true, summary.getMetadata().get("durable"));
        assertFalse(summary.getOutput().contains("Expires"));
        assertEquals(source, cache(root).get(handle).orElseThrow().output());
        assertFalse(Files.exists(resultDirectory(root, handle).resolve("output.txt")));
        assertTrue(Files.exists(resultDirectory(root, handle).resolve("metadata.json")));
        assertEquals(0, original.retainedBytes());
        assertEquals(0, original.size());

        ToolResultReferenceCache restarted = cache(root);
        ToolResult page = restarted.getSlice("ref:" + handle, 1, 2);
        assertFalse(page.isError(), page::getOutput);
        assertEquals("emoji 😀\nlast\n\n... (page bounded; continue with offset=4, character_offset=0)",
                page.getOutput());
        assertEquals(4, page.getMetadata().get("totalLines"));
        assertEquals("document.txt", restarted.get(handle).orElseThrow().metadata().get("path"));
        String second = restarted.storeAndSummarize("session-two", "read", "title", source, Map.of())
                .getMetadata().get("result_id").toString();
        assertTrue(Files.exists(resultDirectory(root, second)));
        assertFalse(resultDirectory(root, handle).equals(resultDirectory(root, second)));
    }

    @Test
    void unassociatedCallsUseProcessOwnedConversationNamespace() throws Exception {
        Path root = temp.resolve("conversations");
        String handle = cache(root).store("tool", "data", Map.of());
        assertTrue(handle.startsWith("d1." + DurableToolResultStore.UNASSOCIATED_SESSION + "."));
        assertEquals(Long.toString(ProcessHandle.current().pid()),
                Files.readString(root.resolve(DurableToolResultStore.UNASSOCIATED_SESSION).resolve("owner.pid")));
        assertTrue(Files.exists(resultDirectory(root, handle)));
        String sentinel = cache(root).storeAndSummarize("mcp-stdio", "tool", "title", "more data", Map.of())
                .getMetadata().get("result_id").toString();
        assertTrue(sentinel.startsWith("d1." + DurableToolResultStore.UNASSOCIATED_SESSION + "."));
        assertFalse(Files.exists(root.resolve("mcp-stdio")));
    }

    @Test
    void unicodeLongLinePagesRetainEveryCharacterAndNeverSplitPairs() {
        Path root = temp.resolve("conversations");
        String source = "éλ😀".repeat(12000) + "TAIL";
        String handle = cache(root).store("read", source, Map.of());
        ToolResultReferenceCache restarted = cache(root);
        assertThrows(UncheckedIOException.class, () -> restarted.get(handle),
                "The full-entry compatibility accessor must not materialize a huge durable payload");
        StringBuilder restored = new StringBuilder();
        int offset = 0;
        int character = 0;
        for (int n = 0; n < 20; n++) {
            ToolResult page = restarted.getSlice(handle, offset, 1, null, character, 16000);
            assertFalse(page.isError(), page::getOutput);
            assertTrue(page.getOutput().length() <= 16000);
            boolean truncated = (boolean) page.getMetadata().get("truncated");
            String body = page.getOutput();
            if (truncated) body = body.substring(0, body.indexOf("\n... (page bounded;"));
            assertTrue(body.endsWith("\n"));
            String part = body.substring(0, body.length() - 1);
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            restored.append(part);
            if (!truncated) break;
            offset = (int) page.getMetadata().get("nextOffset") - 1;
            character = (int) page.getMetadata().get("nextCharacterOffset");
        }
        assertEquals(source, restored.toString());
        assertEquals(0, restarted.retainedBytes());
    }

    @Test
    void regexScansDiskBackedLinesAndContinuationUsesMatchIndex() {
        Path root = temp.resolve("conversations");
        ToolResultReferenceCache cache = cache(root);
        String handle = cache.store("grep", "skip\nMatch " + "x".repeat(30000)
                + " END\nskip\nMATCH last\n[broken\n", Map.of());
        ToolResult first = cache.getSlice(handle, 0, 1, "match.*(END|last)");
        assertFalse(first.isError(), first::getOutput);
        assertEquals(2, first.getMetadata().get("matchCount"));
        assertTrue(first.getOutput().startsWith("2: Match "));
        assertEquals(1, first.getMetadata().get("nextOffset"));
        ToolResult tail = cache.getSlice(handle, 0, 1, "match.*(END|last)",
                (int) first.getMetadata().get("nextCharacterOffset"), 16000);
        assertTrue(tail.getOutput().contains("END"));
        assertEquals(2, tail.getMetadata().get("nextOffset"));
        assertEquals(0, tail.getMetadata().get("nextCharacterOffset"));
        assertEquals("4: MATCH last\n", cache.getSlice(handle, 1, 1, "match").getOutput());
        assertEquals("5: [broken\n", cache.getSlice(handle, 0, 20, "[broken").getOutput());
        assertTrue(cache.getSlice(handle, 0, 20, "nonexistent").getOutput().contains("no lines match"));
        assertTrue(cache.getSlice(handle, 100, 20).getOutput().contains("past end"));
    }

    @Test
    void singleAndBatchToolsUseBoundedRestartPages() throws Exception {
        Path root = temp.resolve("conversations");
        String handle = cache(root).store("read", "x".repeat(40000) + "TAIL", Map.of());
        ToolResultReferenceCache restarted = cache(root);
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext context = new ToolContext("test", null, permissions, temp, new ToolRegistry(json));
        ObjectNode request = json.createObjectNode().put("result_id", "ref:" + handle);
        FetchResultTool single = new FetchResultTool(restarted);
        ToolResult page = single.execute(request, context);
        request.put("offset", (int) page.getMetadata().get("nextOffset"))
                .put("character_offset", (int) page.getMetadata().get("nextCharacterOffset"));
        assertFalse(single.execute(request, context).isError());
        ObjectNode batch = json.createObjectNode();
        var requests = batch.putArray("requests");
        for (int i = 0; i < 20; i++) requests.addObject().put("result_id", handle);
        ToolResult pages = new FetchResultBatchTool(restarted).execute(batch, context);
        assertFalse(pages.isError(), pages::getOutput);
        assertTrue(pages.getOutput().length() <= ToolResultReferenceCache.MAX_FETCH_CHARS);
        assertTrue(pages.getOutput().contains("20/20 slices read"));
        requests.removeAll();
        requests.addObject().put("result_id", handle).put("character_offset", 39995);
        requests.addObject().put("result_id", "d1.test.bad");
        ToolResult mixed = new FetchResultBatchTool(restarted).execute(batch, context);
        assertTrue(mixed.getOutput().contains("xxxxxTAIL"));
        assertTrue(mixed.getOutput().contains("1/2 slices read"));
    }

    @Test
    void rejectsInvalidHandlesOwnerTraversalAndSymlinkedFilesOrDirectories() throws Exception {
        Path root = temp.resolve("conversations");
        DurableToolResultStore store = new DurableToolResultStore(root);
        for (String invalid : new String[]{"d1.../escape", "d1...", "d1.session.BAD",
                "d1../" + UUID.randomUUID(), "d1.session." + UUID.randomUUID() + "/file"}) {
            assertThrows(IOException.class, () -> store.getSlice(invalid, 0, 20, null, 0, 16000));
        }
        assertThrows(IOException.class, () -> store.store("../escape", "tool", "data", Map.of()));
        String handle = store.store("safe", "tool", "data", Map.of());
        Path directory = resultDirectory(root, handle);
        Path external = temp.resolve("external");
        Files.writeString(external, "private");
        String digest = json.readTree(directory.resolve("metadata.json").toFile()).path("payloadDigest").asText();
        Path data = directory.getParent().resolve(".blobs").resolve(digest).resolve("data.bin");
        Files.delete(data);
        Files.createSymbolicLink(data, external);
        assertTrue(cache(root).getSlice(handle, 0, 20).isError());
        Files.createDirectories(temp.resolve("other"));
        Files.createSymbolicLink(root.resolve("linked"), temp.resolve("other"));
        assertThrows(IOException.class, () -> store.store("linked", "tool", "data", Map.of()));
        String linkedHandle = "d1.linked." + UUID.randomUUID();
        assertTrue(cache(root).getSlice(linkedHandle, 0, 20).isError());
        Path symlinkRoot = temp.resolve("linked-root");
        Files.createSymbolicLink(symlinkRoot, root);
        assertThrows(IOException.class, () -> new DurableToolResultStore(symlinkRoot)
                .store("safe", "tool", "data", Map.of()));
        for (String file : new String[]{"metadata.json", "lines.idx"}) {
            String linkedFile = store.store("safe", "tool", "distinct " + file, Map.of());
            Path target = resultDirectory(root, linkedFile).resolve(file);
            Files.delete(target);
            Files.createSymbolicLink(target, external);
            assertTrue(cache(root).getSlice(linkedFile, 0, 20).isError());
        }
        // Lookup examines only the handle's owner; unrelated hostile trees cannot force a scan.
        String unrelated = store.store("unrelated", "tool", "available", Map.of());
        assertEquals("available\n", cache(root).getSlice(unrelated, 0, 20).getOutput());
    }

    @Test
    void byteCeilingIdleExpiryAndOversizedFallbackAreExplicitAndLazy() {
        AtomicLong clock = new AtomicLong(100);
        ToolResultReferenceCache cache = new ToolResultReferenceCache(10, 10, 4096, null, clock::get);
        String first = cache.store("tool", "x".repeat(400), Map.of());
        String second = cache.store("tool", "y".repeat(400), Map.of());
        cache.store("tool", "z".repeat(400), Map.of());
        assertTrue(cache.retainedBytes() <= 4096);
        assertEquals(2, cache.size());
        assertTrue(cache.get(first).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> cache.get(second).orElseThrow().metadata().put("unaccounted", "payload"));
        clock.set(105);
        assertTrue(cache.get(second).isPresent()); // idle TTL refreshed on access
        clock.set(111);
        assertEquals(1, cache.size());
        clock.set(116);
        assertEquals(0, cache.retainedBytes());
        assertEquals(0, cache.size());
        ToolResult fallback = cache.storeAndSummarize("read", "large", "q".repeat(5000), Map.of());
        assertEquals(false, fallback.getMetadata().get("cached"));
        assertFalse(fallback.getMetadata().containsKey("result_id"));
        assertTrue(fallback.getOutput().endsWith("q".repeat(5000)));
    }

    @Test
    void retentionAndRestorePreserveDurableHandlesAndProtectLiveUnassociatedPublisher() throws Exception {
        Path root = temp.resolve("conversations");
        String handle = new DurableToolResultStore(root).store("archivable", "read", "full payload", Map.of());
        Files.writeString(root.resolve("archivable.txt"), "transcript");
        String fallback = new DurableToolResultStore(root).store(null, "read", "live", Map.of());
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) Files.setLastModifiedTime(path,
                    java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(100))));
        }
        var retention = new ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention(
                ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention.Policy.DEFAULT);
        var result = retention.apply(root.toFile());
        assertEquals(1, result.totalRetired());
        assertEquals(1, result.protectedSessions());
        ToolResult retired = cache(root).getSlice(handle, 0, 20);
        assertTrue(retired.isError());
        assertTrue(retired.getOutput().contains("retired by conversation retention"));
        assertEquals("live\n", cache(root).getSlice(fallback, 0, 20).getOutput());
        ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention.restore(root.toFile(), "archivable");
        assertEquals("full payload\n", cache(root).getSlice(handle, 0, 20).getOutput());
    }

    @Test
    void publisherDoesNotRaceExclusiveRetentionLease() throws Exception {
        Path root = Files.createDirectories(temp.resolve("conversations"));
        try (var channel = java.nio.channels.FileChannel.open(root.resolve(".retention.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lease = channel.lock()) {
            ToolResult result = cache(root).storeAndSummarize("session", "tool", "title", "full original", Map.of());
            assertFalse(result.getMetadata().containsKey("result_id"));
            assertTrue(result.getOutput().endsWith("full original"));
        }
    }

    @Test
    void persistenceFailurePreservesFullOutputAndNeverPromisesDurability() throws Exception {
        Path broken = temp.resolve("not-a-directory");
        Files.writeString(broken, "file");
        ToolResultReferenceCache cache = cache(broken);
        String original = "FULL OUTPUT " + "é😀".repeat(10000);
        ToolResult result = cache.storeAndSummarize("session", "tool", "title", original, Map.of());
        assertTrue(result.getOutput().endsWith(original));
        assertTrue(result.getOutput().contains("cache failed"));
        assertEquals(false, result.getMetadata().get("durable"));
        assertEquals(false, result.getMetadata().get("cached"));
        assertFalse(result.getMetadata().containsKey("result_id"));
        assertThrows(UncheckedIOException.class, () -> cache.store("tool", original, Map.of()));
    }

    @Test
    void failedMetadataPublicationCleansOnlyItsOwnUnpublishedFiles() throws Exception {
        Path root = temp.resolve("conversations");
        DurableToolResultStore store = new DurableToolResultStore(root);
        String good = store.store("owner", "tool", "previous full output", Map.of());
        assertThrows(IOException.class, () -> store.store("owner", "tool", "new full output",
                Map.of("too-large", "m".repeat(70000))));
        try (var paths = Files.list(root.resolve("owner/result-cache"))) {
            assertEquals(1, paths.filter(p -> !p.getFileName().toString().startsWith(".")).count());
        }
        assertEquals("previous full output\n", cache(root).getSlice(good, 0, 20).getOutput());
    }
}
