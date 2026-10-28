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
 *  distributed under the license is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.render.CompactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Portable committed-history ledger for one resumable subagent run:
 * {@code ~/.kompile/conversations/<sessionId>.ledger.jsonl}, one
 * {@link CompactionService.ConversationEntry} per line.
 *
 * <p>Write policy mirrors the compactor's durability contract: entries are
 * appended only once committed to the client's wire history (flush points:
 * {@code commitThroughPendingOutbound()} after an accepted exchange, and full
 * rewrites after a Tier-1 shrink or Tier-2 compaction), so the ledger is always
 * exactly what would be replayed into a fresh child. Partial trailing lines from
 * a crash are dropped on read — the "last committed prefix wins" rule.</p>
 *
 * <p>Everything here is best-effort on failure: persistence problems surface as
 * a single warning (the delegation itself must not fail because its ledger
 * became unwritable), and {@link #usable()} tells callers whether the run can
 * actually be resumed later.</p>
 */
final class SubagentLedger {

    private static final String VERSION_LINE = "{\"v\":1}";

    private final Path file;
    private final ObjectMapper mapper;
    private final SubagentResumeService locks;
    private final String sessionId;
    private boolean locked;
    private BufferedWriter writer;
    private boolean writeFailureReported;
    private boolean anyWriteFailed;

    SubagentLedger(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
        this.locks = new SubagentResumeService(file.getParent(), mapper);
        this.sessionId = file.getFileName().toString().replaceFirst("\\.ledger\\.jsonl$", "");
    }

    static Path fileFor(Path conversationsDir, String sessionId) {
        return conversationsDir.resolve(sessionId + ".ledger.jsonl");
    }

    Path file() {
        return file;
    }

    /** Whether every write so far succeeded; a failed ledger must not advertise resumability. */
    synchronized boolean usable() {
        return !anyWriteFailed;
    }

    /** Begin (or continue) the ledger. Safe to call on every run. */
    synchronized void open() {
        if (writer != null) return;
        if (!locked) {
            String error = locks.tryLock(sessionId);
            if (error != null) throw new IllegalStateException(error);
            locked = true;
        }
        try {
            Files.createDirectories(file.getParent());
            boolean newFile = !Files.exists(file);
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            if (newFile) {
                writer.write(VERSION_LINE);
                writer.newLine();
            }
        } catch (IOException e) {
            fail("ledger unavailable", e);
            close();
        }
    }

    /** Append one committed entry. Caller guarantees the entry is committed wire history. */
    synchronized void append(CompactionService.ConversationEntry entry) {
        if (writer == null || entry == null) return;
        try {
            // writeValueAsString, not writeValue(Writer,...): Jackson closes the
            // stream it is handed, which would kill every subsequent append.
            writer.write(mapper.writeValueAsString(toJson(entry)));
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            fail("ledger append failed", e);
        }
    }

    /** Append committed entries in order. */
    synchronized void appendAll(List<CompactionService.ConversationEntry> entries) {
        for (CompactionService.ConversationEntry entry : entries) {
            append(entry);
        }
    }

    /**
     * Atomically replace the whole ledger with the projection (Tier-1/Tier-2
     * rewrite path): temp file + move so a crash never leaves a half-written
     * ledger.
     */
    synchronized void rewrite(List<CompactionService.ConversationEntry> entries) {
        open();
        if (writer == null) return;
        closeWriter();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + "."
                    + UUID.randomUUID() + ".tmp");
            try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                out.write(VERSION_LINE);
                out.newLine();
                for (CompactionService.ConversationEntry entry : entries) {
                    if (entry == null) continue;
                    out.write(mapper.writeValueAsString(toJson(entry)));
                    out.write("\n");
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            fail("ledger rewrite failed", e);
        } finally {
            // Compaction changes the file, not the active run's writer ownership.
            open();
        }
    }

    synchronized void close() {
        closeWriter();
        if (locked) {
            locks.unlock(sessionId);
            locked = false;
        }
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
            // close() on an already-failing writer; anyWriteFailed is already set
        }
        writer = null;
    }

    /**
     * Parse a ledger back into entries. Tolerates a torn trailing line (crash
     * during append) by dropping the last incomplete line, and skips blank or
     * malformed lines. Returns an empty list when the file is absent or unreadable.
     */
    static List<CompactionService.ConversationEntry> read(Path file, ObjectMapper mapper) {
        List<CompactionService.ConversationEntry> entries = new ArrayList<>();
        if (file == null || !Files.exists(file)) return entries;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                CompactionService.ConversationEntry parsed = parse(line, mapper);
                if (parsed != null) entries.add(parsed);
            }
        } catch (IOException e) {
            // Unreadable ledger yields an empty resume source, never a wrong one.
        }
        return entries;
    }

    private static CompactionService.ConversationEntry parse(String line, ObjectMapper mapper) {
        if (line == null || line.isBlank()) return null;
        String trimmed = line.trim();
        if (trimmed.equals(VERSION_LINE)) return null;
        try {
            JsonNode node = mapper.readTree(trimmed);
            if (!node.isObject()) return null;
            String type = node.path("type").asText("");
            String role = node.path("role").asText(null);
            String content = node.path("content").asText(null);
            String toolName = node.hasNonNull("toolName") ? node.path("toolName").asText() : null;
            String toolCallId = node.hasNonNull("toolCallId") ? node.path("toolCallId").asText() : null;
            return new CompactionService.ConversationEntry(
                    CompactionService.EntryType.valueOf(type), role, content, toolName, toolCallId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            return null; // Malformed JSON lines are skipped, same as torn/blank ones.
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private ObjectNode toJson(CompactionService.ConversationEntry entry) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", entry.type.name());
        node.put("role", entry.role);
        node.put("content", entry.content);
        if (entry.toolName != null) node.put("toolName", entry.toolName);
        if (entry.toolCallId != null) node.put("toolCallId", entry.toolCallId);
        return node;
    }

    private void fail(String what, IOException e) {
        anyWriteFailed = true;
        if (!writeFailureReported) {
            System.err.println("Warning: Resumable subagent " + what
                    + " (" + file.getFileName() + "): " + e.getMessage()
                    + "; the run continues but this child will not be resumable.");
            writeFailureReported = true;
        }
    }
}
