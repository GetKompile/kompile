/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ToolCallRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Incrementally tails the per-session tool-call JSONL files used by {@code ToolCallIndex}.
 *
 * <p>The combined index can be very large and is deliberately never consulted here. The first
 * read starts from a bounded window at the end of one session file; subsequent reads consume only
 * bytes appended after the retained cursor. A partial final JSON line is left on disk and reread
 * after its newline arrives, which makes reads safe while another process is appending without
 * retaining raw fragments. Cursor records keep only bounded, redacted summaries; raw tool
 * arguments are discarded immediately after parsing.</p>
 */
public final class ToolCallTailReader {

    static final int DEFAULT_INITIAL_BYTES = 64 * 1024;
    static final int DEFAULT_MAX_INCREMENTAL_BYTES = 256 * 1024;
    static final int DEFAULT_MAX_RETAINED_RECORDS = 256;
    static final int MAX_PARTIAL_BYTES = 1024 * 1024;

    public record TailResult(List<ToolCallRecord> records,
                             int malformedLines,
                             boolean reset,
                             long offset) {
        public TailResult {
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    private record InitialWindow(long start, boolean alignedToLine) {}

    private static final class Cursor {
        private long offset = -1L;
        private long observedSize = -1L;
        private Object fileKey;
        private FileTime lastModified;
        private final Deque<ToolCallRecord> records = new ArrayDeque<>();
        private int malformedLines;
    }

    private final Path toolCallsDirectory;
    private final ObjectMapper mapper;
    private final int initialBytes;
    private final int maxIncrementalBytes;
    private final int maxRetainedRecords;
    private final Map<String, Cursor> cursors = new HashMap<>();

    public ToolCallTailReader() {
        this(KompileHome.homeDirectory().toPath()
                        .resolve("conversations").resolve("tool-calls"),
                JsonUtils.standardMapper(),
                DEFAULT_INITIAL_BYTES,
                DEFAULT_MAX_INCREMENTAL_BYTES,
                DEFAULT_MAX_RETAINED_RECORDS);
    }

    ToolCallTailReader(Path toolCallsDirectory,
                       ObjectMapper mapper,
                       int initialBytes,
                       int maxIncrementalBytes,
                       int maxRetainedRecords) {
        this.toolCallsDirectory = Objects.requireNonNull(toolCallsDirectory, "toolCallsDirectory")
                .toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.initialBytes = requirePositive(initialBytes, "initialBytes");
        this.maxIncrementalBytes = requirePositive(maxIncrementalBytes, "maxIncrementalBytes");
        this.maxRetainedRecords = requirePositive(maxRetainedRecords, "maxRetainedRecords");
    }

    /** Return newest-first recent records for one tool session. */
    public synchronized TailResult readRecent(String sessionId, int limit) throws IOException {
        String safeSessionId = requireSafeSessionId(sessionId);
        int boundedLimit = Math.max(0, Math.min(limit, maxRetainedRecords));
        Path file = toolCallsDirectory.resolve(safeSessionId + ".jsonl");
        Cursor cursor = cursors.computeIfAbsent(safeSessionId, ignored -> new Cursor());

        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            boolean reset = cursor.offset >= 0;
            resetCursor(cursor);
            return snapshot(cursor, boundedLimit, reset);
        }

        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Tool-call session path is not a regular file: " + file);
        }

        long size = attributes.size();
        FileTime modified = attributes.lastModifiedTime();
        Object fileKey = attributes.fileKey();
        boolean replaced = cursor.offset >= 0
                && cursor.fileKey != null
                && fileKey != null
                && !cursor.fileKey.equals(fileKey);
        boolean replacedWithoutFileKey = cursor.offset >= 0
                && cursor.fileKey == null
                && fileKey == null
                && cursor.lastModified != null
                && size == cursor.offset
                && !cursor.lastModified.equals(modified);
        boolean sameSizeRewrite = cursor.offset >= 0
                && cursor.observedSize >= 0
                && size == cursor.observedSize
                && cursor.lastModified != null
                && !cursor.lastModified.equals(modified);
        boolean truncated = cursor.offset > size
                || (cursor.observedSize >= 0 && size < cursor.observedSize);
        boolean firstRead = cursor.offset < 0;
        boolean reset = firstRead || replaced || replacedWithoutFileKey
                || sameSizeRewrite || truncated;

        if (reset) {
            initializeFromTail(file, safeSessionId, cursor, size);
        } else if (size > cursor.offset && size != cursor.observedSize) {
            long appended = size - cursor.offset;
            if (appended > maxIncrementalBytes) {
                initializeFromTail(file, safeSessionId, cursor, size);
                reset = true;
            } else {
                byte[] bytes = readRange(file, cursor.offset, Math.toIntExact(appended));
                cursor.offset += consume(cursor, safeSessionId, bytes, 0);
            }
        }

        cursor.fileKey = fileKey;
        cursor.observedSize = size;
        cursor.lastModified = modified;
        return snapshot(cursor, boundedLimit, reset);
    }

    public synchronized void forget(String sessionId) {
        cursors.remove(requireSafeSessionId(sessionId));
    }

    private void initializeFromTail(Path file, String sessionId, Cursor cursor, long size)
            throws IOException {
        resetCursor(cursor);
        InitialWindow window = findInitialWindow(file, size);
        long start = window.start();
        int length = Math.toIntExact(size - start);
        byte[] bytes = readRange(file, start, length);
        int contentStart = window.alignedToLine() ? 0 : firstCompleteLineOffset(bytes);
        if (!window.alignedToLine() && contentStart >= bytes.length && bytes.length > 0) {
            cursor.malformedLines++;
            cursor.offset = size;
            return;
        }
        cursor.offset = start + contentStart
                + consume(cursor, sessionId, bytes, contentStart);
    }

    private InitialWindow findInitialWindow(Path file, long size) throws IOException {
        long desiredStart = Math.max(0L, size - initialBytes);
        if (desiredStart == 0L) return new InitialWindow(0L, true);

        long scanStart = Math.max(0L, desiredStart - MAX_PARTIAL_BYTES);
        int scanLength = Math.toIntExact(desiredStart - scanStart);
        byte[] prefix = readRange(file, scanStart, scanLength);
        for (int i = prefix.length - 1; i >= 0; i--) {
            if (prefix[i] == '\n') {
                return new InitialWindow(scanStart + i + 1L, true);
            }
        }
        if (scanStart == 0L) {
            return new InitialWindow(0L, true);
        }
        // One JSON line is larger than the bounded back-scan. Start with the
        // ordinary tail window and discard its leading partial line.
        return new InitialWindow(desiredStart, false);
    }

    private byte[] readRange(Path file, long start, int length) throws IOException {
        if (length <= 0) return new byte[0];
        byte[] bytes = new byte[length];
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(start);
            int offset = 0;
            while (offset < length) {
                int read = input.read(bytes, offset, length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset == length) return bytes;
            byte[] shortened = new byte[offset];
            System.arraycopy(bytes, 0, shortened, 0, offset);
            return shortened;
        }
    }

    private int consume(Cursor cursor, String expectedSessionId, byte[] bytes, int start) {
        if (start >= bytes.length) return 0;
        int completeEnd = -1;
        for (int i = bytes.length - 1; i >= start; i--) {
            if (bytes[i] == '\n') {
                completeEnd = i + 1;
                break;
            }
        }
        // Never retain an incomplete raw JSON fragment in memory. Keep the
        // cursor at its start and reread it after the writer appends a newline.
        if (completeEnd < 0) return 0;
        String chunk = new String(
                bytes, start, completeEnd - start, StandardCharsets.UTF_8);
        int lineStart = 0;
        int newline;
        while ((newline = chunk.indexOf('\n', lineStart)) >= 0) {
            String line = chunk.substring(lineStart, newline);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            parseLine(cursor, expectedSessionId, line);
            lineStart = newline + 1;
        }
        return completeEnd - start;
    }

    private void parseLine(Cursor cursor, String expectedSessionId, String line) {
        if (line == null || line.isBlank()) return;
        try {
            ToolCallRecord record = mapper.readValue(line, ToolCallRecord.class);
            if (record.getSessionId() == null
                    || !expectedSessionId.equals(record.getSessionId())) {
                cursor.malformedLines++;
                return;
            }
            cursor.records.addLast(retainedRecord(record));
            while (cursor.records.size() > maxRetainedRecords) {
                cursor.records.removeFirst();
            }
        } catch (IOException | RuntimeException malformed) {
            cursor.malformedLines++;
        }
    }

    private static ToolCallRecord retainedRecord(ToolCallRecord record) {
        return new ToolCallRecord(
                ActivityToolText.boundedClean(record.getId(), 256),
                ActivityToolText.boundedClean(record.getSessionId(), 200),
                ActivityToolText.boundedClean(record.getToolName(), 128),
                "",
                ActivityToolText.summary(record.getToolInputSummary()),
                record.getTimestampInstant(),
                "",
                "",
                record.isError(),
                Math.max(0L, record.getDurationMs()),
                "",
                null);
    }

    private TailResult snapshot(Cursor cursor, int limit, boolean reset) {
        if (limit == 0 || cursor.records.isEmpty()) {
            return new TailResult(List.of(), cursor.malformedLines, reset, cursor.offset);
        }
        List<ToolCallRecord> newestFirst = new ArrayList<>(cursor.records);
        Collections.reverse(newestFirst);
        if (newestFirst.size() > limit) {
            newestFirst = new ArrayList<>(newestFirst.subList(0, limit));
        }
        return new TailResult(newestFirst, cursor.malformedLines, reset, cursor.offset);
    }

    private static int firstCompleteLineOffset(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') return i + 1;
        }
        return bytes.length;
    }

    private static void resetCursor(Cursor cursor) {
        cursor.offset = -1L;
        cursor.observedSize = -1L;
        cursor.fileKey = null;
        cursor.lastModified = null;
        cursor.records.clear();
        cursor.malformedLines = 0;
    }

    private static String requireSafeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 200
                || !sessionId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "sessionId must contain only letters, digits, '.', '_', or '-' and be at most 200 characters");
        }
        return sessionId;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
