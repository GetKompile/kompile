/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only JSONL journal of {@link ToolCallUsage} events for the tool-call catalog.
 *
 * <p><b>Format safety:</b> every line carries {@code "recordType":"usage"}. Legacy
 * catalog readers deserialize lines into ToolCallRecord with lenient settings and SKIP
 * lines that fail — so usage lines never inflate legacy call totals, and legacy call
 * lines are ignored here (typed by recordType, not guessed).</p>
 *
 * <p><b>Idempotent folding:</b> records carry {@code recordId} (= invocationId) and a
 * monotonically increasing {@code revision}. Re-appending a record with the same
 * (recordId, revision) is a no-op on read (duplicate finalization is idempotent); a
 * HIGHER revision supersedes; a LOWER revision is retained for diagnostics but does not
 * override — conflicting duplicates are reported, not last-file-wins.</p>
 *
 * <p><b>Cross-process safety:</b> appends take an OS-level lock file
 * ({@code .usage-journal.lock}) via {@link java.nio.channels.FileChannel#lock} so two
 * processes (CLI stdio + app) appending the same stream cannot interleave partial lines.
 * Instance synchronization alone would not cover separate JVMs. Read-modify-truncate
 * compaction is under the same lock. Write errors are nonfatal: callers surface
 * accounting health, the tool call itself is never retried.</p>
 *
 * <p><b>Path safety:</b> session keys are validated against traversal before use.</p>
 */
public final class ToolCallUsageJournal {

    public static final String RECORD_TYPE = "usage";
    public static final String SCHEMA_VERSION = "1";

    private final ObjectMapper mapper;
    private final Path journalFile;
    private final Path lockFile;
    private final Object instanceLock = new Object();

    /** result codes for record() */
    public sealed interface WriteResult {
        record Written() implements WriteResult {}
        record Skipped(String reason) implements WriteResult {}
        record Failed(String reason) implements WriteResult {}
    }

    public ToolCallUsageJournal(Path journalFile, ObjectMapper mapper) {
        this.journalFile = journalFile.toAbsolutePath().normalize();
        this.lockFile = this.journalFile.resolveSibling("." + this.journalFileNamePart() + ".lock");
        this.mapper = mapper;
    }

    private String journalFileNamePart() {
        String name = journalFile.getFileName() != null
                ? journalFile.getFileName().toString() : "usage-journal.jsonl";
        return name;
    }

    public static Path defaultJournalFile(Path toolCallsDir) {
        return toolCallsDir.resolve("tool-usage.jsonl");
    }

    /**
     * Validate a caller-supplied session/storage key: rejects traversal, separators,
     * and non-portable characters. Returns the key unchanged when safe.
     */
    public static String validateSessionKey(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("session key must not be blank");
        }
        if (sessionKey.length() > 200) {
            throw new IllegalArgumentException("session key too long");
        }
        for (int i = 0; i < sessionKey.length(); i++) {
            char c = sessionKey.charAt(i);
            if (c == '/' || c == '\\' || c == 0 || c == ':') {
                throw new IllegalArgumentException(
                        "session key contains path separator or reserved character: " + sessionKey);
            }
        }
        if (sessionKey.equals(".") || sessionKey.equals("..")
                || sessionKey.contains("../") || sessionKey.contains("..\\")
                || sessionKey.startsWith("-")) {
            throw new IllegalArgumentException("session key looks like a traversal or flag: " + sessionKey);
        }
        return sessionKey;
    }

    /**
     * Append one usage event. Idempotent per (invocationId, revision). Never throws
     * for I/O problems — returns {@code Failed} so accounting health can be surfaced.
     */
    public WriteResult record(ToolCallUsage usage, long revision) {
        if (usage == null || usage.invocationId() == null || usage.invocationId().isBlank()) {
            return new WriteResult.Skipped("usage or invocationId missing");
        }
        String line;
        try {
            ObjectNode node = usage.toJsonNode(mapper);
            node.put("recordType", RECORD_TYPE);
            node.put("schemaVersion", SCHEMA_VERSION);
            node.put("revision", revision);
            node.put("writtenEpochMs", Instant.now().toEpochMilli());
            line = mapper.writeValueAsString(node) + "\n";
        } catch (Exception serializationProblem) {
            return new WriteResult.Failed("serialization failed: "
                    + serializationProblem.getClass().getSimpleName());
        }

        synchronized (instanceLock) {
            try (java.nio.channels.FileChannel channel =
                         java.nio.channels.FileChannel.open(lockFile,
                                 StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                try (java.nio.channels.FileLock ignore = channel.lock()) {
                    Files.createDirectories(journalFile.getParent());
                    // Idempotency check under lock: skip same-revision duplicates,
                    // supersede on higher revision.
                    ExistingState state = scanInvocations();
                    String key = usage.invocationId();
                    Long existing = state.revisions.get(key);
                    if (existing != null) {
                        if (existing >= revision) {
                            return new WriteResult.Skipped(
                                    "duplicate (invocationId=" + key + ", revision=" + revision
                                            + ", existing=" + existing + ")");
                        }
                        // higher revision: append supersedes on read; old lines stay for diagnostics
                    }
                    Files.writeString(journalFile, line,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    return new WriteResult.Written();
                }
            } catch (IOException ioProblem) {
                return new WriteResult.Failed(ioProblem.getClass().getSimpleName()
                        + ": " + String.valueOf(ioProblem.getMessage()));
            }
        }
    }

    private record ExistingState(Map<String, Long> revisions) {}

    private ExistingState scanInvocations() throws IOException {
        Map<String, Long> revisions = new LinkedHashMap<>();
        if (!Files.exists(journalFile)) {
            return new ExistingState(revisions);
        }
        try (BufferedReader reader = Files.newBufferedReader(journalFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                UsageLine parsed = parseLine(line);
                if (parsed == null) {
                    continue;
                }
                revisions.merge(parsed.invocationId(), parsed.revision(), Math::max);
            }
        }
        return new ExistingState(revisions);
    }

    /**
     * Fold the journal into the newest usage per invocationId. Duplicate same-revision
     * lines collapse; higher revision wins; lower revisions are reported as conflicts.
     */
    public FoldedSnapshot snapshot() {
        Map<String, UsageLine> newest = new LinkedHashMap<>();
        Map<String, List<Long>> conflicts = new ConcurrentHashMap<>();
        List<String> malformed = new ArrayList<>();
        synchronized (instanceLock) {
            if (!Files.exists(journalFile)) {
                return new FoldedSnapshot(newest, conflicts, malformed, 0);
            }
            try (BufferedReader reader = Files.newBufferedReader(journalFile, StandardCharsets.UTF_8)) {
                String line;
                long lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    UsageLine parsed = parseLine(line);
                    if (parsed == null) {
                        malformed.add("line " + lineNo);
                        continue;
                    }
                    UsageLine existing = newest.get(parsed.invocationId());
                    if (existing == null) {
                        newest.put(parsed.invocationId(), parsed);
                    } else if (parsed.revision() > existing.revision()) {
                        newest.put(parsed.invocationId(), parsed);
                    } else if (parsed.revision() < existing.revision()) {
                        conflicts.computeIfAbsent(parsed.invocationId(), k -> new ArrayList<>())
                                .add(parsed.revision());
                    } else if (!existing.usage().equals(parsed.usage())) {
                        // same revision, different content: diagnostic, keep first
                        conflicts.computeIfAbsent(parsed.invocationId(), k -> new ArrayList<>())
                                .add(parsed.revision());
                    }
                    // same revision same content: pure duplicate, idempotent collapse
                }
            } catch (IOException ioProblem) {
                malformed.add("read failure: " + ioProblem.getClass().getSimpleName());
            }
        }
        return new FoldedSnapshot(newest, conflicts, malformed, 0);
    }

    public record FoldedSnapshot(
            Map<String, UsageLine> byInvocationId,
            Map<String, List<Long>> conflictingRevisions,
            List<String> malformedLines,
            long reservedForFuture) {

        public List<ToolCallUsage> usages() {
            List<ToolCallUsage> list = new ArrayList<>(byInvocationId.size());
            for (UsageLine line : byInvocationId.values()) {
                list.add(line.usage());
            }
            return list;
        }
    }

    public record UsageLine(String invocationId, long revision, ToolCallUsage usage,
                            ObjectNode raw) {}

    private UsageLine parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            ObjectNode node = (ObjectNode) mapper.readTree(trimmed);
            if (!RECORD_TYPE.equals(node.path("recordType").asText(""))) {
                return null; // not a usage line (legacy call line or foreign record)
            }
            String invocationId = node.path("invocationId").asText("");
            if (invocationId.isBlank()) {
                return null;
            }
            long revision = node.path("revision").asLong(0);
            ToolCallUsage usage = ToolCallUsage.fromJsonNode(node);
            return new UsageLine(invocationId, revision, usage, node);
        } catch (Exception parseProblem) {
            return null;
        }
    }

    /**
     * Recover a torn trailing line (partial write without newline): returns bytes to
     * truncate, or -1 when the tail is clean. Must be called under a lock context;
     * performs its own channel lock.
     */
    public long tornTailBytes() {
        synchronized (instanceLock) {
            if (!Files.exists(journalFile)) {
                return -1;
            }
            try {
                List<String> lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    return -1;
                }
                String last = lines.get(lines.size() - 1);
                if (last.isBlank()) {
                    return -1;
                }
                try (InputStream in = Files.newInputStream(journalFile)) {
                    byte[] all = in.readAllBytes();
                    // valid if last line parses
                    if (parseLine(last) != null) {
                        return -1;
                    }
                    // find start offset of last line
                    int start = all.length;
                    for (int i = all.length - 1; i >= 0; i--) {
                        if (all[i] == '\n') {
                            start = i + 1;
                            break;
                        }
                        if (i == 0) {
                            start = 0;
                            break;
                        }
                    }
                    return all.length - start;
                }
            } catch (IOException ioProblem) {
                return -1;
            }
        }
    }

    /** Repair a torn tail by truncating the incomplete final line. Returns bytes removed. */
    public long repairTornTail() throws IOException {
        long bytes = tornTailBytes();
        if (bytes <= 0) {
            return 0;
        }
        synchronized (instanceLock) {
            try (java.nio.channels.FileChannel channel =
                         java.nio.channels.FileChannel.open(lockFile,
                                 StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                try (java.nio.channels.FileLock ignore = channel.lock()) {
                    try (java.nio.channels.FileChannel fileChannel = java.nio.channels.FileChannel.open(
                            journalFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                        long size = fileChannel.size();
                        fileChannel.truncate(size - bytes);
                    }
                }
            }
        }
        return bytes;
    }

    /** Retention: drop lines older than the cutoff instant. Returns lines removed. Nonfatal. */
    public long pruneOlderThan(Instant cutoff) {
        synchronized (instanceLock) {
            try (java.nio.channels.FileChannel channel =
                         java.nio.channels.FileChannel.open(lockFile,
                                 StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                try (java.nio.channels.FileLock ignore = channel.lock()) {
                    if (!Files.exists(journalFile)) {
                        return 0;
                    }
                    List<String> kept = new ArrayList<>();
                    long removed = 0;
                    for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
                        UsageLine parsed = parseLine(line);
                        if (parsed == null) {
                            removed++;
                            continue;
                        }
                        Long written = parsed.raw().path("writtenEpochMs").asLong(0);
                        if (written > 0 && written < cutoff.toEpochMilli()) {
                            removed++;
                        } else {
                            kept.add(line);
                        }
                    }
                    if (removed > 0) {
                        Path tmp = journalFile.resolveSibling(
                                journalFile.getFileName() + ".tmp");
                        Files.write(tmp, kept, StandardCharsets.UTF_8);
                        Files.move(tmp, journalFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return removed;
                }
            } catch (IOException ioProblem) {
                return -1; // nonfatal signal
            }
        }
    }

    /** True when the most recent write attempt failed (accounting-health surface). */
    public boolean journalHealthy() {
        return tornTailBytes() < 0;
    }
}
