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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * sidecar file with the ledger version it has seen. A later process continues that
 * session only while the ledger is still at that version.</p>
 *
 * <p>Files sent with a user turn are stored once, by SHA-256, in a directory beside
 * the ledger; the event records only the reference. A later process can therefore
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
    private Path stateFile;
    private Path nativeSessionFile;
    private Path attachmentDirectory;
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
        sessionId = normalized;
        String safeFileName = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeFileName.isBlank() || ".".equals(safeFileName)
                || "..".equals(safeFileName)) {
            throw new IllegalArgumentException("Invalid conversation session id");
        }
        stateFile = KompileHome.homeDirectory().toPath()
                .resolve("conversations").resolve(safeFileName + ".context.json");
        nativeSessionFile = stateFile.resolveSibling(safeFileName + ".native-session.json");
        attachmentDirectory = stateFile.resolveSibling(safeFileName + ".attachments");
        load();
    }

    public synchronized boolean hasDurableState() {
        return !events.isEmpty() || checkpoint != null;
    }

    public synchronized Event append(CompactionService.ConversationEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Event event = new Event(
                nextSequence++, entry.type, entry.role, entry.content,
                entry.toolName, entry.toolCallId, Instant.now(), entry.attachments);
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

    /**
     * Record that a provider's own session now holds this conversation as of the
     * current version, so a later process can continue it.
     */
    public synchronized void recordNativeSession(
            String transport, String nativeSessionId, String instructionsDigest) {
        if (transport == null || nativeSessionId == null || nativeSessionId.isBlank()) return;
        nativeSession = new NativeSession(
                transport, nativeSessionId, version, instructionsDigest, Instant.now());
        if (nativeSessionFile == null) return;
        try {
            writeAtomically(nativeSessionFile, nativeSession);
        } catch (IOException e) {
            System.err.println("Warning: Could not persist the native session "
                    + nativeSessionFile + ": " + e.getMessage());
        }
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
                || version <= 0L || saved.syncedVersion() != version) {
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
        nativeSession = null;
        if (nativeSessionFile == null) return;
        try {
            Files.deleteIfExists(nativeSessionFile);
        } catch (IOException e) {
            System.err.println("Warning: Could not remove the native session "
                    + nativeSessionFile + ": " + e.getMessage());
        }
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
            Files.createDirectories(attachmentDirectory);
            Path temporary = Files.createTempFile(attachmentDirectory, digest, ".tmp");
            try {
                Files.write(temporary, data);
                try {
                    Files.move(temporary, blob,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, blob, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
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
            byte[] data = Files.readAllBytes(attachmentDirectory.resolve(attachment.digest()));
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
        events.clear();
        unpersistedAttachments.clear();
        checkpoint = null;
        version = 0L;
        nextSequence = 1L;
        loadNativeSession();
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

    private void loadNativeSession() {
        nativeSession = null;
        if (nativeSessionFile == null || !Files.exists(nativeSessionFile)) return;
        try {
            nativeSession = objectMapper.readValue(nativeSessionFile.toFile(), NativeSession.class);
        } catch (Exception e) {
            System.err.println("Warning: Could not restore the native session "
                    + nativeSessionFile + ": " + e.getMessage());
        }
    }

    private boolean persist() {
        if (stateFile == null) return true;
        try {
            writeAtomically(stateFile,
                    new State(SCHEMA_VERSION, version, List.copyOf(events), checkpoint));
            return true;
        } catch (IOException e) {
            System.err.println("Warning: Could not persist compacted context "
                    + stateFile + ": " + e.getMessage());
            return false;
        }
    }

    private void writeAtomically(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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
            List<CompactionService.Attachment> attachments) {

        public Event {
            // Ledgers written before attachments were recorded have no such field.
            attachments = attachments == null ? List.of()
                    : attachments.stream().filter(Objects::nonNull).toList();
        }

        public CompactionService.ConversationEntry toEntry() {
            return new CompactionService.ConversationEntry(
                    type, role, content, toolName, toolCallId, attachments);
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

    /** A provider's own session and the ledger version it holds. */
    public record NativeSession(
            String transport,
            String sessionId,
            long syncedVersion,
            String instructionsDigest,
            Instant updatedAt) {
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
