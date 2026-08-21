/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.render.CompactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Durable, append-only source of truth for the context sent to chat providers.
 *
 * <p>Compaction never deletes events. A committed checkpoint changes only the
 * active projection: a portable summary represents events through
 * {@code coveredThroughSequence}, followed by all newer events verbatim. This
 * preserves the full audit trail while making compaction transactional and
 * resumable.</p>
 */
public final class ConversationLedger {

    public static final int SCHEMA_VERSION = 1;
    public static final String SUMMARY_MARKER = "[Compacted summary of prior conversation]\n";

    private final ObjectMapper objectMapper;
    private final List<Event> events = new ArrayList<>();
    private long version;
    private long nextSequence = 1L;
    private CompactionCheckpoint checkpoint;
    private Path stateFile;
    private String sessionId;

    public ConversationLedger(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Bind this ledger to one chat session and restore its durable state. */
    public synchronized void configureSession(String requestedSessionId) {
        if (requestedSessionId == null || requestedSessionId.isBlank()) return;
        String normalized = requestedSessionId.strip();
        if (normalized.equals(sessionId)) return;
        if (sessionId != null && (!events.isEmpty() || checkpoint != null)) {
            throw new IllegalStateException("Conversation ledger is already bound to " + sessionId);
        }
        sessionId = normalized;
        String safeFileName = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeFileName.isBlank() || ".".equals(safeFileName)
                || "..".equals(safeFileName)) {
            throw new IllegalArgumentException("Invalid conversation session id");
        }
        stateFile = KompileHome.homeDirectory().toPath()
                .resolve("conversations").resolve(safeFileName + ".context.json");
        load();
    }

    public synchronized boolean hasDurableState() {
        return !events.isEmpty() || checkpoint != null;
    }

    public synchronized Event append(CompactionService.ConversationEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Event event = new Event(
                nextSequence++, entry.type, entry.role, entry.content,
                entry.toolName, entry.toolCallId, Instant.now());
        events.add(event);
        version++;
        if (!persist()) {
            events.remove(events.size() - 1);
            version--;
            nextSequence--;
            throw new IllegalStateException("Could not durably append conversation context");
        }
        return event;
    }

    public synchronized Snapshot snapshot() {
        List<Event> activeEvents = activeEvents();
        List<CompactionService.ConversationEntry> activeEntries = new ArrayList<>();
        if (checkpoint != null && checkpoint.summary() != null
                && !checkpoint.summary().isBlank()) {
            activeEntries.add(CompactionService.ConversationEntry.system(
                    SUMMARY_MARKER + checkpoint.summary()));
        }
        for (Event event : activeEvents) {
            activeEntries.add(event.toEntry());
        }
        return new Snapshot(
                version,
                List.copyOf(events),
                List.copyOf(activeEvents),
                List.copyOf(activeEntries),
                checkpoint);
    }

    /**
     * Atomically publish a new active-context checkpoint if no event changed
     * since the summarizer captured its input snapshot.
     */
    public synchronized boolean commitCompaction(
            long expectedVersion,
            long coveredThroughSequence,
            String summary,
            String strategy,
            String provider,
            String model,
            long tokensBefore,
            long tokensAfter) {
        return commitCompaction(
                expectedVersion, coveredThroughSequence, summary, strategy,
                provider, model, tokensBefore, tokensAfter, null);
    }

    public synchronized boolean commitNativeCompaction(
            long expectedVersion,
            long coveredThroughSequence,
            String portableSummary,
            String strategy,
            String provider,
            String model,
            long tokensBefore,
            long tokensAfter,
            JsonNode nativePayload) {
        return commitCompaction(
                expectedVersion, coveredThroughSequence, portableSummary, strategy,
                provider, model, tokensBefore, tokensAfter, nativePayload);
    }

    private boolean commitCompaction(
            long expectedVersion,
            long coveredThroughSequence,
            String summary,
            String strategy,
            String provider,
            String model,
            long tokensBefore,
            long tokensAfter,
            JsonNode nativePayload) {
        if (version != expectedVersion || summary == null || summary.isBlank()) {
            return false;
        }
        long priorCoverage = checkpoint == null ? 0L : checkpoint.coveredThroughSequence();
        long lastSequence = events.isEmpty() ? 0L : events.get(events.size() - 1).sequence();
        long coverage = Math.max(priorCoverage,
                Math.min(Math.max(0L, coveredThroughSequence), lastSequence));
        CompactionCheckpoint previous = checkpoint;
        checkpoint = new CompactionCheckpoint(
                coverage,
                summary.strip(),
                strategy == null ? "generic" : strategy,
                provider,
                model,
                Math.max(0L, tokensBefore),
                Math.max(0L, tokensAfter),
                nativePayload == null ? null : nativePayload.deepCopy(),
                Instant.now());
        version++;
        if (!persist()) {
            version--;
            checkpoint = previous;
            return false;
        }
        return true;
    }

    /** Replace imported legacy context only when no durable ledger exists. */
    public synchronized void importLegacyTurns(
            List<ai.kompile.cli.main.chat.ChatHistory.Turn> turns) {
        if (hasDurableState() || turns == null) return;
        for (ai.kompile.cli.main.chat.ChatHistory.Turn turn : turns) {
            if (turn == null || turn.content() == null) continue;
            if ("user".equalsIgnoreCase(turn.role())) {
                append(CompactionService.ConversationEntry.user(turn.content()));
            } else if ("assistant".equalsIgnoreCase(turn.role())) {
                append(CompactionService.ConversationEntry.assistant(turn.content()));
            }
        }
    }

    private List<Event> activeEvents() {
        long covered = checkpoint == null ? 0L : checkpoint.coveredThroughSequence();
        return events.stream().filter(event -> event.sequence() > covered).toList();
    }

    private void load() {
        events.clear();
        checkpoint = null;
        version = 0L;
        nextSequence = 1L;
        if (stateFile == null || !Files.exists(stateFile)) return;
        try {
            State state = objectMapper.readValue(stateFile.toFile(), State.class);
            if (state.schemaVersion() != SCHEMA_VERSION) {
                throw new IOException("unsupported conversation context schema "
                        + state.schemaVersion());
            }
            if (state.events() != null) events.addAll(state.events());
            checkpoint = state.checkpoint();
            version = Math.max(0L, state.version());
            nextSequence = events.stream().mapToLong(Event::sequence).max().orElse(0L) + 1L;
        } catch (Exception e) {
            System.err.println("Warning: Could not restore compacted context "
                    + stateFile + ": " + e.getMessage());
            events.clear();
            checkpoint = null;
            version = 0L;
            nextSequence = 1L;
        }
    }

    private boolean persist() {
        if (stateFile == null) return true;
        try {
            Files.createDirectories(stateFile.getParent());
            Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    temporary.toFile(),
                    new State(SCHEMA_VERSION, version, List.copyOf(events), checkpoint));
            try {
                Files.move(temporary, stateFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            System.err.println("Warning: Could not persist compacted context "
                    + stateFile + ": " + e.getMessage());
            return false;
        }
    }

    public record Event(
            long sequence,
            CompactionService.EntryType type,
            String role,
            String content,
            String toolName,
            String toolCallId,
            Instant createdAt) {

        public CompactionService.ConversationEntry toEntry() {
            return new CompactionService.ConversationEntry(
                    type, role, content, toolName, toolCallId);
        }
    }

    public record CompactionCheckpoint(
            long coveredThroughSequence,
            String summary,
            String strategy,
            String provider,
            String model,
            long tokensBefore,
            long tokensAfter,
            JsonNode nativePayload,
            Instant createdAt) {
    }

    public record State(
            int schemaVersion,
            long version,
            List<Event> events,
            CompactionCheckpoint checkpoint) {
    }

    public record Snapshot(
            long version,
            List<Event> allEvents,
            List<Event> activeEvents,
            List<CompactionService.ConversationEntry> activeEntries,
            CompactionCheckpoint checkpoint) {

        public long coveredThroughForPrefix(int activeEndExclusive) {
            long covered = checkpoint == null ? 0L : checkpoint.coveredThroughSequence();
            int summaryOffset = checkpoint == null ? 0 : 1;
            int eventCount = Math.max(0,
                    Math.min(activeEvents.size(), activeEndExclusive - summaryOffset));
            for (int i = 0; i < eventCount; i++) {
                covered = Math.max(covered, activeEvents.get(i).sequence());
            }
            return covered;
        }
    }
}
