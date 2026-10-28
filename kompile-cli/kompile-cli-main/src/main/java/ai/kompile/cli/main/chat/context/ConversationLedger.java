/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.render.CompactionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable, append-only source of truth for the context sent to chat providers.
 *
 * <p>Compaction never deletes events. A committed checkpoint changes only the
 * active projection: a portable summary represents events through
 * {@code coveredThroughSequence}, followed by all newer events verbatim. This
 * preserves the full audit trail while making compaction transactional and
 * resumable.</p>
 *
 * <p>A provider that keeps its own session (the Claude Code CLI) is recorded in a
 * journal record with the ledger version it has seen. A later process continues that
 * session only while the ledger is still at that version.</p>
 *
 * <p>Files sent with a user turn are stored once, by SHA-256, in a directory beside
 * the journal; the event records only the reference. A later process can therefore
 * resend an image from an earlier turn.</p>
 */
public final class ConversationLedger {

    public static final int SCHEMA_VERSION = 1;
    public static final String SUMMARY_MARKER = "[Compacted summary of prior conversation]\n";

    private final ObjectMapper objectMapper;
    private final List<Event> events = new ArrayList<>();
    private long version;
    private long nextSequence = 1L;
    private CompactionCheckpoint checkpoint;
    private ConversationLedgerJournal journal;
    private Path attachmentDirectory;
    private Path legacyAttachmentDirectory;
    /** Attachment bytes with no file: the ledger is unbound, or the write failed. */
    private final Map<String, byte[]> unpersistedAttachments = new HashMap<>();
    private NativeSession nativeSession;
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
        String safeFileName = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeFileName.isBlank() || ".".equals(safeFileName)
                || "..".equals(safeFileName)) {
            throw new IllegalArgumentException("Invalid conversation session id");
        }
        Path root = KompileHome.homeDirectory().toPath().resolve("conversations");
        journal = new ConversationLedgerJournal(objectMapper, root, safeFileName);
        attachmentDirectory = journal.attachmentDirectory();
        legacyAttachmentDirectory = root.resolve(safeFileName + ".attachments");
        load();
        sessionId = normalized;
    }

    public synchronized boolean hasDurableState() {
        return !events.isEmpty() || checkpoint != null;
    }

    public synchronized Event append(CompactionService.ConversationEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(entry.type, "entry.type");
        Event event = new Event(
                nextSequence, entry.type, entry.role, entry.content,
                entry.toolName, entry.toolCallId, Instant.now(), entry.attachments, entry.outcome);
        if (!persist(version + 1, "event", event)) {
            throw new IllegalStateException("Could not durably append conversation context");
        }
        events.add(event);
        version++;
        nextSequence++;
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
        CompactionCheckpoint next = new CompactionCheckpoint(
                coverage,
                summary.strip(),
                strategy == null ? "generic" : strategy,
                provider,
                model,
                Math.max(0L, tokensBefore),
                Math.max(0L, tokensAfter),
                nativePayload == null ? null : nativePayload.deepCopy(),
                Instant.now());
        if (!persist(version + 1, "checkpoint", next)) return false;
        checkpoint = next;
        version++;
        return true;
    }

    /**
     * Record that a provider's own session now holds this conversation as of the
     * current version, so a later process can continue it.
     */
    public synchronized void recordNativeSession(
            String transport, String nativeSessionId, String instructionsDigest) {
        recordNativeSession(transport, nativeSessionId, instructionsDigest, null);
    }

    /** Save measured context with the same version/session receipt, never billing totals. */
    public synchronized void recordNativeSession(
            String transport, String nativeSessionId, String instructionsDigest,
            NativeContextUsage contextUsage) {
        if (transport == null || nativeSessionId == null || nativeSessionId.isBlank()) return;
        NativeSession next = new NativeSession(
                transport, nativeSessionId, version, instructionsDigest, Instant.now(), contextUsage);
        if (persist(version, "native", next)) nativeSession = next;
    }

    /**
     * The recorded session for {@code transport} if it holds exactly the current
     * conversation; null once anything changed after it (a turn on another route,
     * a compaction, a failed restore).
     */
    public synchronized NativeSession resumableNativeSession(String transport) {
        NativeSession saved = nativeSession;
        if (saved == null || !Objects.equals(saved.transport(), transport)
                || saved.sessionId() == null || saved.sessionId().isBlank()
                || version <= 0L || saved.syncedVersion() != version
                || (journal != null && !journal.isCurrent(version))) {
            return null;
        }
        return saved;
    }

    /**
     * Forget the recorded session for {@code transport}: the conversation moved to
     * a new one, so neither this process nor a later one may resume the old.
     */
    public synchronized void forgetNativeSession(String transport) {
        NativeSession saved = nativeSession;
        if (saved == null || !Objects.equals(saved.transport(), transport)) return;
        if (persist(version, "native", null)) nativeSession = null;
    }

    /**
     * Store a user-turn attachment's bytes so the turn can be resent after a restart.
     * Call before appending the event that references it. When the ledger is unbound,
     * or the write fails, the bytes stay in memory for this process.
     */
    public synchronized CompactionService.Attachment storeAttachment(
            String path, String mimeType, boolean image, byte[] data) {
        Objects.requireNonNull(data, "data");
        String digest = sha256(data);
        CompactionService.Attachment attachment = new CompactionService.Attachment(
                path, mimeType, image, digest, data.length);
        if (attachmentDirectory == null) {
            unpersistedAttachments.put(digest, data.clone());
            return attachment;
        }
        Path blob = attachmentDirectory.resolve(digest);
        // A damaged blob is rewritten: attaching the image again is what repairs it.
        if (holds(blob, digest)) return attachment;
        try {
            journal.storeBlob(version, digest, data);
            unpersistedAttachments.remove(digest);
        } catch (IOException e) {
            System.err.println("Warning: Could not save attachment " + path
                    + " for later turns: " + e.getMessage());
            unpersistedAttachments.put(digest, data.clone());
        }
        return attachment;
    }

    /**
     * The bytes stored for an attachment, or null when they are gone or no longer
     * match their digest.
     */
    public synchronized byte[] readAttachment(CompactionService.Attachment attachment) {
        if (attachment == null || attachment.digest() == null
                || !attachment.digest().matches("[0-9a-f]{64}")) {
            return null;
        }
        byte[] held = unpersistedAttachments.get(attachment.digest());
        if (held != null) return held.clone();
        if (attachmentDirectory == null) return null;
        try {
            Path blob = attachmentDirectory.resolve(attachment.digest());
            if (!Files.exists(blob) && legacyAttachmentDirectory != null) {
                blob = legacyAttachmentDirectory.resolve(attachment.digest());
            }
            if (Files.isSymbolicLink(blob) || Files.isSymbolicLink(blob.getParent())) return null;
            byte[] data = Files.readAllBytes(blob);
            if (sha256(data).equals(attachment.digest())) return data;
            System.err.println("Warning: Saved attachment " + attachment.path()
                    + " is damaged; it will not be resent");
        } catch (NoSuchFileException ignored) {
            // Removed outside Kompile; the caller reports it as unavailable.
        } catch (IOException e) {
            System.err.println("Warning: Could not read saved attachment "
                    + attachment.path() + ": " + e.getMessage());
        }
        return null;
    }

    private static boolean holds(Path blob, String digest) {
        try {
            return Files.isRegularFile(blob) && sha256(Files.readAllBytes(blob)).equals(digest);
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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
        try {
            ConversationLedgerJournal.Restored restored = journal.open();
            State state = restored.state();
            events.clear();
            if (state.events() != null) events.addAll(state.events());
            checkpoint = state.checkpoint();
            nativeSession = restored.nativeSession();
            version = state.version();
            nextSequence = events.size() + 1L;
        } catch (IOException e) {
            // Never reset and overwrite a corrupt source of truth.
            throw new IllegalStateException("Could not restore conversation context", e);
        }
    }

    private boolean persist(long newVersion, String kind, Object payload) {
        if (journal == null) return true;
        try {
            journal.append(version, newVersion, kind, payload);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Warning: Could not persist conversation context: " + e.getMessage());
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
            Instant createdAt,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<CompactionService.Attachment> attachments,
            @JsonInclude(JsonInclude.Include.NON_NULL) CompactionService.ToolOutcome outcome) {

        public Event(long sequence, CompactionService.EntryType type, String role, String content,
                     String toolName, String toolCallId, Instant createdAt,
                     List<CompactionService.Attachment> attachments) {
            this(sequence, type, role, content, toolName, toolCallId, createdAt, attachments, null);
        }

        public Event {
            // Ledgers written before attachments were recorded have no such field.
            attachments = attachments == null ? List.of()
                    : attachments.stream().filter(Objects::nonNull).toList();
        }

        public CompactionService.ConversationEntry toEntry() {
            return new CompactionService.ConversationEntry(
                    type, role, content, toolName, toolCallId, attachments, outcome);
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

    /** Last measured request input and native limits, scoped to its selected model. */
    public record NativeContextUsage(
            String model, long inputTokens, long historyTokens, int contextWindow, int maxOutputTokens) {
    }

    /** A provider's own session and the ledger version it holds. */
    public record NativeSession(
            String transport,
            String sessionId,
            long syncedVersion,
            String instructionsDigest,
            Instant updatedAt,
            NativeContextUsage contextUsage) {
        public NativeSession(String transport, String sessionId, long syncedVersion,
                             String instructionsDigest, Instant updatedAt) {
            this(transport, sessionId, syncedVersion, instructionsDigest, updatedAt, null);
        }
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
