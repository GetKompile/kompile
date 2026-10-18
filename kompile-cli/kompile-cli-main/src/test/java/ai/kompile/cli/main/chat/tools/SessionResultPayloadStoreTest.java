package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionResultPayloadStoreTest {
    @TempDir Path root;

    private Path result(String handle) {
        String[] parts = handle.split("\\.");
        return root.resolve(parts[1]).resolve("result-cache").resolve(parts[2]);
    }

    @Test void compressesAndDeduplicatesFullPayloadButKeepsInvocationMetadataAndSessionOwnership() throws Exception {
        var store = new DurableToolResultStore(root);
        String data = "é😀 repeated payload\n".repeat(30000);
        String first = store.store("one", "read", data, Map.of("a", 1));
        String second = store.store("one", "grep", data, Map.of("b", 2));
        assertNotEquals(first, second);
        var a = JsonUtils.standardMapper().readTree(result(first).resolve("metadata.json").toFile());
        var b = JsonUtils.standardMapper().readTree(result(second).resolve("metadata.json").toFile());
        assertEquals(2, a.path("version").asInt());
        assertEquals(a.path("payloadDigest").asText(), b.path("payloadDigest").asText());
        assertTrue(a.path("payloadStoredBytes").asLong() < a.path("outputBytes").asLong() / 10);
        assertEquals("read", a.path("toolName").asText()); assertEquals("grep", b.path("toolName").asText());
        Path blobs = root.resolve("one/result-cache/.blobs");
        try (var files = Files.list(blobs)) { assertEquals(1, files.count()); }
        String other = store.store("two", "read", data, Map.of());
        assertTrue(Files.exists(root.resolve("two/result-cache/.blobs").resolve(a.path("payloadDigest").asText())));
        assertFalse(store.getSlice(other, 5, 20, null, 0, 16000).isError());
    }

    @Test void blockCodecDoesNotExpandRandomPayloadAndSupportsBoundedRandomSeeks() throws Exception {
        byte[] bytes = new byte[SessionResultPayloadStore.BLOCK_BYTES * 3 + 7];
        new java.util.Random(42).nextBytes(bytes);
        Path input = Files.write(root.resolve("input"), bytes);
        var payload = SessionResultPayloadStore.publish(root.resolve("cache"), input);
        assertEquals(bytes.length, payload.bytes());
        assertTrue(payload.storedBytes() <= bytes.length);
        try (var access = SessionResultPayloadStore.packed(root.resolve("cache"), payload.digest(), payload.bytes())) {
            ByteBuffer sample = ByteBuffer.allocate(500);
            assertEquals(7, access.read(sample, bytes.length - 7));
            assertArrayEquals(java.util.Arrays.copyOfRange(bytes, bytes.length - 7, bytes.length),
                    java.util.Arrays.copyOf(sample.array(), 7));
            sample.clear(); assertEquals(1, access.read(sample, SessionResultPayloadStore.BLOCK_BYTES - 1));
            assertEquals(bytes[SessionResultPayloadStore.BLOCK_BYTES - 1], sample.array()[0]);
            sample.clear(); assertEquals(500, access.read(sample, SessionResultPayloadStore.BLOCK_BYTES));
        }
    }

    @Test void compressedUnicodeLongLinePagesAndPatternWorkAcrossBlockBoundaries() throws Exception {
        var store = new DurableToolResultStore(root);
        String data = "λ😀".repeat(30000) + "NEEDLE\nlast";
        String handle = store.store("owner", "read", data, Map.of());
        StringBuilder restored = new StringBuilder();
        int offset = 0, character = 0;
        for (int pages = 0; pages < 100; pages++) {
            var page = store.getSlice(handle, offset, 2, null, character, 16000);
            assertFalse(page.isError());
            String text = page.getOutput(); int notice = text.indexOf("\n... (page bounded;");
            String content = notice < 0 ? text : text.substring(0, notice);
            boolean more = Boolean.TRUE.equals(page.getMetadata().get("truncated"));
            int nextChar = more ? ((Number) page.getMetadata().get("nextCharacterOffset")).intValue() : 0;
            if (nextChar > 0) content = content.substring(0, content.length() - 1);
            restored.append(content);
            if (!more) break;
            offset = ((Number) page.getMetadata().get("nextOffset")).intValue() - 1; character = nextChar;
        }
        assertEquals(data + "\n", restored.toString());
        var match = store.getSlice(handle, 0, 2, "NEEDLE$", 0, 16000);
        assertEquals(1, match.getMetadata().get("matchCount"));
    }

    @Test void readsLegacyFormatOneAndRejectsMissingOrCorruptCompressedBlob() throws Exception {
        var store = new DurableToolResultStore(root);
        String handle = store.store("one", "read", "old payload", Map.of());
        Path directory = result(handle), metaPath = directory.resolve("metadata.json");
        var meta = (com.fasterxml.jackson.databind.node.ObjectNode) JsonUtils.standardMapper().readTree(metaPath.toFile());
        meta.put("version", 1); meta.remove("payloadDigest"); meta.remove("payloadStoredBytes");
        JsonUtils.standardMapper().writeValue(metaPath.toFile(), meta);
        Files.writeString(directory.resolve("output.txt"), "old payload");
        assertEquals("old payload\n", store.getSlice(handle, 0, 20, null, 0, 16000).getOutput());
        String compressed = store.store("two", "grep", "payload\n".repeat(50000), Map.of());
        var encoded = JsonUtils.standardMapper().readTree(result(compressed).resolve("metadata.json").toFile());
        Path blob = root.resolve("two/result-cache/.blobs").resolve(encoded.path("payloadDigest").asText());
        byte[] corrupted = Files.readAllBytes(blob.resolve("data.bin")); corrupted[0] ^= 1;
        Files.write(blob.resolve("data.bin"), corrupted);
        assertThrows(java.io.IOException.class, () -> store.getSlice(compressed, 0, 10, null, 0, 16000));
        assertThrows(java.io.IOException.class, () -> store.store("two", "grep", "payload\n".repeat(50000), Map.of()));
    }

    @Test void sameJvmSharedRetentionLeasePermitsNestedLedgerAndPayloadPublication() throws Exception {
        Files.createDirectories(root);
        ai.kompile.cli.common.metrics.ToolJournalFiles.sharedRetention(root, () -> {
            String handle = new DurableToolResultStore(root).store("same", "read", "safe", Map.of());
            assertEquals("safe\n", new DurableToolResultStore(root).getSlice(handle, 0, 10, null, 0, 16000).getOutput());
            return null;
        });
    }
}
