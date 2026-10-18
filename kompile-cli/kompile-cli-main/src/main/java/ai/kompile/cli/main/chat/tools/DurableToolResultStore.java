package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Session-owned result files, retained/deleted with the whole conversation by the host.
 * Handles are {@code d1.<owner-session>.<full UUID>}; lookup never scans conversations.
 * Unassociated calls use {@code conversations/mcp-stdio-<pid>/result-cache}, protected by owner.pid.
 * No payloads or indexes are retained in memory. UTF-8 output, metadata and a fixed-width line
 * index are published together by an atomic directory rename; failed writes never yield a handle.
 */
public final class DurableToolResultStore {
    public static final String UNASSOCIATED_SESSION = "mcp-stdio-" + ProcessHandle.current().pid();
    private static final int MAX_METADATA_BYTES = 65536;
    private static final int INDEX_RECORD_BYTES = Long.BYTES + Integer.BYTES;
    private final Path conversations;

    public DurableToolResultStore(Path conversations) {
        this.conversations = conversations.toAbsolutePath().normalize();
    }

    static boolean isDurableHandle(String handle) {
        return handle != null && handle.startsWith("d1.");
    }

    private static String owner(String session) throws IOException {
        String value = session == null || session.isBlank() ? UNASSOCIATED_SESSION : session;
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IOException("Invalid result owner session");
        }
        return value;
    }

    private Path location(String handle) throws IOException {
        String[] parts = handle.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("d1") || !owner(parts[1]).equals(parts[1])
                || !parts[2].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Invalid durable result handle");
        }
        return conversations.resolve(parts[1]).resolve("result-cache").resolve(parts[2]);
    }

    /** Reject symlinks in every existing path component, including configured root ancestors. */
    private static void safeDirectories(Path directory, boolean create) throws IOException {
        Path current = directory.getRoot();
        for (Path component : directory) {
            current = current.resolve(component);
            if (create && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (java.nio.file.FileAlreadyExistsException raced) {
                    // Validate the winner below, never follow it blindly.
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                throw new IOException("Unsafe or missing result directory: " + current);
            }
        }
    }

    private static FileChannel openFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe or missing result file: " + path.getFileName());
        }
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    public String store(String session, String toolName, String output, Map<String, Object> metadata)
            throws IOException {
        safeDirectories(conversations, true);
        Path lockPath = conversations.resolve(".retention.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Unsafe retention lock");
        // Shared lease is reentrant across ledger/result publishers in the same JVM.
        return ai.kompile.cli.common.metrics.ToolJournalFiles.sharedRetention(conversations,
                () -> storeLocked(session, toolName, output, metadata));
    }

    private String storeLocked(String session, String toolName, String output, Map<String, Object> metadata)
            throws IOException {
        // MCP usage/context uses the legacy sentinel when no transcript was resolved.
        // Treat it as unassociated for storage only; do not change journal/session IDs.
        String sessionOwner = "mcp-stdio".equals(session) ? UNASSOCIATED_SESSION : owner(session);
        String uuid = UUID.randomUUID().toString();
        String handle = "d1." + sessionOwner + "." + uuid;
        Path target = location(handle);
        safeDirectories(target.getParent(), true);
        if (sessionOwner.equals(UNASSOCIATED_SESSION)) {
            Files.writeString(conversations.resolve(sessionOwner).resolve("owner.pid"),
                    Long.toString(ProcessHandle.current().pid()), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        }
        Path pending = target.resolveSibling(uuid + ".tmp");
        Files.createDirectory(pending);
        boolean published = false;
        try {
            int lines = 1;
            long byteOffset = 0;
            long lineStart = 0;
            int lineChars = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(pending.resolve("output.txt"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 DataOutputStream index = new DataOutputStream(Files.newOutputStream(
                         pending.resolve("lines.idx"), StandardOpenOption.CREATE_NEW))) {
                writer.write(output);
                for (int i = 0; i < output.length(); i++) {
                    char c = output.charAt(i);
                    if (c == '\n') {
                        index.writeLong(lineStart);
                        index.writeInt(lineChars);
                        lines = Math.incrementExact(lines);
                        lineChars = 0;
                        lineStart = byteOffset + 1;
                    } else {
                        lineChars++;
                    }
                    if (Character.isHighSurrogate(c) && i + 1 < output.length()
                            && Character.isLowSurrogate(output.charAt(i + 1))) {
                        byteOffset += 4;
                        lineChars++;
                        i++;
                    } else {
                        if (Character.isSurrogate(c)) throw new IOException("Result contains unpaired UTF-16 surrogate");
                        byteOffset += c < 128 ? 1 : c < 2048 ? 2 : 3;
                    }
                }
                index.writeLong(lineStart);
                index.writeInt(lineChars);
            }
            SessionResultPayloadStore.Stored payload = SessionResultPayloadStore.publish(target.getParent(), pending.resolve("output.txt"));
            ObjectNode meta = JsonUtils.standardMapper().createObjectNode();
            meta.put("version", 2).put("handle", handle).put("toolName", toolName == null ? "" : toolName)
                    .put("totalChars", output.length()).put("totalLines", lines).put("outputBytes", byteOffset)
                    .put("payloadDigest", payload.digest()).put("payloadStoredBytes", payload.storedBytes());
            Files.delete(pending.resolve("output.txt"));
            meta.set("metadata", JsonUtils.standardMapper().valueToTree(metadata == null ? Map.of() : metadata));
            byte[] json = JsonUtils.standardMapper().writeValueAsBytes(meta);
            if (json.length > MAX_METADATA_BYTES) throw new IOException("Result metadata exceeds 64 KiB");
            Files.write(pending.resolve("metadata.json"), json, StandardOpenOption.CREATE_NEW);
            for (String file : new String[]{"lines.idx", "metadata.json"}) {
                try (FileChannel channel = FileChannel.open(pending.resolve(file), StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
                    channel.force(true);
                }
            }
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            safeDirectories(target.getParent(), false);
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            try (FileChannel channel = FileChannel.open(target.getParent(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            return handle;
        } finally {
            if (!published) {
                // Only this call's unpublished files, never existing session data or retention pruning.
                for (String file : new String[]{"output.txt", "lines.idx", "metadata.json"}) {
                    Files.deleteIfExists(pending.resolve(file));
                }
                Files.deleteIfExists(pending);
            }
        }
    }

    private JsonNode metadata(Path directory, String handle) throws IOException {
        try (FileChannel channel = openFile(directory.resolve("metadata.json"))) {
            long size = channel.size();
            if (size > MAX_METADATA_BYTES) throw new IOException("Oversized result metadata");
            ByteBuffer json = ByteBuffer.allocate((int) size);
            while (json.hasRemaining()) {
                if (channel.read(json) < 0) throw new IOException("Incomplete result metadata");
            }
            if (channel.size() != size) throw new IOException("Result metadata changed during read");
            JsonNode meta = JsonUtils.standardMapper().readTree(json.array());
            if (meta == null || (meta.path("version").asInt() != 1 && meta.path("version").asInt() != 2)
                    || !handle.equals(meta.path("handle").asText())
                    || meta.path("totalLines").asInt() < 1 || meta.path("totalChars").asLong(-1) < 0) {
                throw new IOException("Invalid result metadata");
            }
            return meta;
        }
    }

    private static SessionResultPayloadStore.Access output(Path directory, JsonNode meta) throws IOException {
        return meta.path("version").asInt() == 1 ? SessionResultPayloadStore.raw(directory.resolve("output.txt"))
                : SessionResultPayloadStore.packed(directory.getParent(), meta.path("payloadDigest").asText(),
                        meta.path("outputBytes").asLong(-1));
    }

    private static java.nio.channels.ReadableByteChannel view(SessionResultPayloadStore.Access output, long offset) {
        return new java.nio.channels.ReadableByteChannel() {
            private long position = offset;
            private boolean open = true;
            public int read(ByteBuffer buffer) throws IOException {
                if (!open) throw new java.nio.channels.ClosedChannelException();
                int n = output.read(buffer, position); if (n > 0) position += n;
                return n;
            }
            public boolean isOpen() { return open; }
            public void close() { open = false; }
        };
    }

    /** Stream-verify a retained original before any context retirement. No full-payload allocation. */
    public boolean verify(String handle, String expectedDigest, long expectedChars) {
        try {
            if (expectedDigest == null || !expectedDigest.matches("[0-9a-f]{64}")) return false;
            Path directory = location(handle);
            safeDirectories(directory, false);
            JsonNode meta = metadata(directory, handle);
            if (meta.path("totalChars").asLong(-1) != expectedChars) return false;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (SessionResultPayloadStore.Access channel = output(directory, meta);
                 FileChannel indexChannel = openFile(directory.resolve("lines.idx"));
                 var index = new java.io.DataInputStream(new java.io.BufferedInputStream(Channels.newInputStream(indexChannel)))) {
                if (channel.size() != meta.path("outputBytes").asLong(-1)
                        || indexChannel.size() != meta.path("totalLines").asLong() * INDEX_RECORD_BYTES) return false;
                ByteBuffer buffer = ByteBuffer.allocate(65_536);
                long position = 0, lineStart = 0, lineChars = 0, chars = 0, lines = 1;
                while (position < channel.size()) {
                    buffer.clear();
                    int read = channel.read(buffer, position);
                    if (read <= 0) return false;
                    buffer.flip();
                    digest.update(buffer);
                    buffer.rewind();
                    while (buffer.hasRemaining()) {
                        int value = buffer.get() & 255;
                        if (value == '\n') {
                            if (index.readLong() != lineStart || index.readInt() != lineChars) return false;
                            lines++;
                            chars++;
                            lineStart = position + 1;
                            lineChars = 0;
                        } else if (value < 128 || value >= 192) {
                            int units = value >= 240 ? 2 : 1;
                            lineChars += units;
                            chars += units;
                        }
                        position++;
                    }
                }
                if (index.readLong() != lineStart || index.readInt() != lineChars || index.read() != -1
                        || lines != meta.path("totalLines").asLong() || chars != expectedChars) return false;
            }
            return expectedDigest.equals(java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Compatibility full-entry accessor only for small results; large callers must page. */
    ToolResultReferenceCache.CacheEntry getSmallEntry(String handle, int maxChars) throws IOException {
        Path directory = location(handle);
        safeDirectories(directory, false);
        JsonNode meta = metadata(directory, handle);
        if (meta.path("totalChars").asLong() > maxChars) throw new IOException("Full result requires paging");
        StringBuilder text = new StringBuilder();
        try (SessionResultPayloadStore.Access channel = output(directory, meta);
             Reader reader = Channels.newReader(view(channel, 0), StandardCharsets.UTF_8)) {
            if (channel.size() != meta.path("outputBytes").asLong(-1)) throw new IOException("Incomplete durable result");
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (text.length() + read > maxChars) throw new IOException("Full result requires paging");
                text.append(buffer, 0, read);
            }
        }
        if (text.length() != meta.path("totalChars").asInt()) throw new IOException("Incomplete durable result");
        return new ToolResultReferenceCache.CacheEntry(meta.path("toolName").asText(), text.toString(),
                JsonUtils.standardMapper().convertValue(meta.path("metadata"), Map.class), Long.MAX_VALUE);
    }

    /** Bounded streaming page: even one enormous line is a disk-backed CharSequence for regex. */
    public ToolResult getSlice(String handle, int offset, int limit, String pattern,
                               int characterOffset, int maxChars) throws IOException {
        Path directory = location(handle);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            String session = handle.split("\\.", -1)[1];
            Path retired = conversations.resolve(".retired");
            if (Files.isDirectory(retired, LinkOption.NOFOLLOW_LINKS)) {
                safeDirectories(retired, false);
                if (Files.isRegularFile(retired.resolve(session), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Result was retired by conversation retention; restore its session if still archived, otherwise it was pruned");
            }
        }
        safeDirectories(directory, false);
        JsonNode meta = metadata(directory, handle);
        int totalLines = meta.path("totalLines").asInt();
        boolean filtered = pattern != null && !pattern.isBlank();
        Pattern matcher = null;
        if (filtered) {
            try {
                matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException invalid) {
                matcher = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            }
        }
        offset = Math.max(0, offset);
        limit = limit <= 0 ? 200 : limit;
        int budget = Math.max(128, Math.min(ToolResultReferenceCache.MAX_FETCH_CHARS, maxChars) - 256);
        StringBuilder out = new StringBuilder();
        int selected = 0;
        int returned = 0;
        int next = offset;
        int nextCharacter = 0;
        boolean pageDone = false;
        try (FileChannel index = openFile(directory.resolve("lines.idx"));
             SessionResultPayloadStore.Access output = output(directory, meta)) {
            if (index.size() != (long) totalLines * INDEX_RECORD_BYTES
                    || output.size() != meta.path("outputBytes").asLong(-1)) {
                throw new IOException("Incomplete durable result");
            }
            int first = filtered ? 0 : Math.min(offset, totalLines);
            selected = filtered ? 0 : first;
            ByteBuffer record = ByteBuffer.allocate(INDEX_RECORD_BYTES);
            for (int lineIndex = first; lineIndex < totalLines; lineIndex++) {
                if (!filtered && pageDone) break;
                record.clear();
                index.position((long) lineIndex * INDEX_RECORD_BYTES);
                while (record.hasRemaining()) {
                    if (index.read(record) < 0) throw new IOException("Incomplete line index");
                }
                record.flip();
                long startByte = record.getLong();
                int length = record.getInt();
                if (startByte < 0 || startByte > output.size() || length < 0
                        || length > meta.path("totalChars").asLong()) {
                    throw new IOException("Invalid line index");
                }
                try (DiskLine line = new DiskLine(output, startByte, length)) {
                    if (filtered && !matcher.matcher(line).find()) continue;
                    int cursor = selected++;
                    if (pageDone || cursor < offset) continue;
                    if ((long) cursor >= (long) offset + limit) {
                        pageDone = true;
                        continue;
                    }
                    int start = cursor == offset ? Math.min(Math.max(0, characterOffset), length) : 0;
                    String prefix = filtered ? (lineIndex + 1) + ": " : "";
                    int available = budget - out.length() - prefix.length() - 1;
                    if (available <= 0 || (length - start > available && !out.isEmpty())) {
                        pageDone = true;
                        next = cursor;
                        continue;
                    }
                    int stop = (int) Math.min(length, (long) start + available);
                    if (stop < length && stop > start && Character.isHighSurrogate(line.charAt(stop - 1))) stop--;
                    out.append(prefix);
                    for (int c = start; c < stop; c++) out.append(line.charAt(c));
                    out.append('\n');
                    returned++;
                    next = stop < length ? cursor : cursor + 1;
                    if (stop < length) {
                        nextCharacter = stop;
                        pageDone = true;
                    }
                } catch (java.io.UncheckedIOException failed) {
                    throw failed.getCause();
                }
            }
        }
        int count = filtered ? selected : totalLines;
        Map<String, Object> resultMeta = new LinkedHashMap<>();
        resultMeta.put("result_id", handle);
        resultMeta.put("durable", true);
        resultMeta.put("toolName", meta.path("toolName").asText());
        resultMeta.put("totalLines", totalLines);
        if (filtered) resultMeta.put("matchCount", count);
        if (count == 0 || offset >= count) {
            String label = pattern != null && pattern.length() > 128 ? pattern.substring(0, 128) + "…" : pattern;
            String message = count == 0 ? "(no lines match /" + label + "/ in " + totalLines + " lines)"
                    : "(offset " + offset + (filtered ? " past " : " past end of ")
                            + count + (filtered ? " matches)" : " lines)");
            return ToolResult.success("fetch_result: " + handle, message, resultMeta);
        }
        boolean truncated = next < count;
        resultMeta.put(filtered ? "matchesReturned" : "linesReturned", returned);
        resultMeta.put("truncated", truncated);
        if (truncated) {
            resultMeta.put("nextOffset", next + 1);
            resultMeta.put("nextCharacterOffset", nextCharacter);
            out.append("\n... (page bounded; continue with offset=").append(next + 1)
                    .append(", character_offset=").append(nextCharacter).append(")");
        }
        return ToolResult.success("fetch_result: " + handle, out.toString(), resultMeta);
    }

    /** Regex needs random character access, not an unbounded readLine()/whole-file String.
     * Keep one 4K UTF-16 window. Backward regex seeks reset the reader; sequential scans stream.
     * All lines use the page's already-open output file, not repeated path resolution/open calls.
     */
    private static final class DiskLine implements CharSequence, AutoCloseable {
        private final SessionResultPayloadStore.Access output;
        private final long byteOffset;
        private final int length;
        private final char[] window = new char[4096];
        private int windowStart = -1;
        private int windowLength;
        private Reader reader;

        DiskLine(SessionResultPayloadStore.Access output, long byteOffset, int length) {
            this.output = output;
            this.byteOffset = byteOffset;
            this.length = length;
        }
        @Override public int length() { return length; }
        @Override public char charAt(int index) {
            if (index < 0 || index >= length) throw new IndexOutOfBoundsException(index);
            try {
                if (windowStart < 0 || index < windowStart || index >= windowStart + windowLength) {
                    int position = windowStart + windowLength;
                    if (reader == null || index < position) {
                        close();
                        // A positional view pins the validated file and never closes the page's channel.
                        reader = Channels.newReader(view(output, byteOffset), StandardCharsets.UTF_8);
                        position = 0;
                    }
                    while (position <= index) {
                        windowStart = position;
                        windowLength = reader.read(window, 0, Math.min(window.length, length - position));
                        if (windowLength <= 0) throw new IOException("Incomplete result line");
                        position += windowLength;
                    }
                }
                return window[index - windowStart];
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
        @Override public CharSequence subSequence(int start, int end) {
            if (start < 0 || end < start || end > length) throw new IndexOutOfBoundsException();
            return new CharSequence() {
                public int length() { return end - start; }
                public char charAt(int i) {
                    if (i < 0 || i >= length()) throw new IndexOutOfBoundsException(i);
                    return DiskLine.this.charAt(start + i);
                }
                public CharSequence subSequence(int from, int to) {
                    if (from < 0 || to < from || to > length()) throw new IndexOutOfBoundsException();
                    return DiskLine.this.subSequence(start + from, start + to);
                }
            };
        }
        @Override public void close() throws IOException {
            if (reader != null) reader.close();
            reader = null;
        }
    }
}
