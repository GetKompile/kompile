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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.chat.sources.KompileTranscriptFormat;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Registry and resume semantics for resumable subagent runs. Single owner of the
 * subagent-scoped listing so the main resume flow can never drift into it, and it
 * can never drift into the main flow.
 *
 * <ul>
 *   <li><b>Identity boundary</b> — only {@code subagent-}-prefixed sessions with a
 *       {@code .subagent.json} sidecar are visible here; nothing else is scanned,
 *       and no parameter can widen the scan. Main-chat transcripts are invisible
 *       to this service, and these runs stay invisible to the main resume flow.</li>
 *   <li><b>Subagents stay subagents</b> — resuming rehydrates a child for the
 *       resuming caller through the normal delegation machinery; this service
 *       never imports child content into a main conversation.</li>
 * </ul>
 *
 * <p>This class owns registry data (list, resolve, name allocation, rename,
 * prune, locking). Rehydration mechanics live in {@link DirectSubagentRunner},
 * which owns {@code DirectLlmClient} construction; the service hands it the
 * metadata and ledger.</p>
 */
public final class SubagentResumeService {

    /** One listing row. */
    public record Summary(String sessionId, String name, String agentType, String parentSession,
                          String model, long lastModified, String lastActivity) {
    }

    /** Resolution outcome: metadata when resolved, error text otherwise. */
    public record Resolution(SubagentResumeMetadata metadata, String error) {
        public boolean ok() {
            return metadata != null;
        }
    }

    private final Path conversationsDir;
    private final ObjectMapper mapper;

    public SubagentResumeService(ObjectMapper mapper) {
        this(KompileHome.homeDirectory().toPath().resolve("conversations"), mapper);
    }

    /** Test-visible constructor. */
    public SubagentResumeService(Path conversationsDir, ObjectMapper mapper) {
        this.conversationsDir = conversationsDir;
        this.mapper = mapper;
    }

    Path conversationsDir() {
        return conversationsDir;
    }

    // ------------------------------------------------------------------ list

    /** All resumable runs, newest first. */
    public List<Summary> list() {
        List<Summary> results = new ArrayList<>();
        if (!Files.isDirectory(conversationsDir)) return results;
        try (var stream = Files.list(conversationsDir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("subagent-") && name.endsWith(".subagent.json");
                    })
                    .map(p -> SubagentResumeMetadata.read(p, mapper))
                    .filter(Objects::nonNull)
                    .map(this::toSummary)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(Summary::lastModified).reversed())
                    .forEach(results::add);
        } catch (IOException ignored) {
            // Missing or unreadable directory yields an empty listing, never a failure.
        }
        return results;
    }

    /** Listing rows whose run was started from {@code workingDirectory}. */
    public List<Summary> list(Path workingDirectory) {
        if (workingDirectory == null) return list();
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        List<Summary> scoped = new ArrayList<>();
        for (Summary summary : list()) {
            if (sameDirectory(summary.sessionId(), normalized)) scoped.add(summary);
        }
        return scoped;
    }

    private Summary toSummary(SubagentResumeMetadata metadata) {
        Path transcript = conversationsDir.resolve(metadata.sessionId() + ".txt");
        long lastModified = metadata.updatedAt();
        try {
            lastModified = Math.max(lastModified, Files.getLastModifiedTime(transcript).toMillis());
        } catch (IOException ignored) {
            // Sidecar timestamp stands in when the transcript is absent.
        }
        return new Summary(metadata.sessionId(), metadata.name(), metadata.agentType(),
                metadata.parentSession(), metadata.model(), lastModified,
                metadata.lastActivityLabel());
    }

    private boolean sameDirectory(String sessionId, Path normalized) {
        Path transcript = conversationsDir.resolve(sessionId + ".txt");
        if (!Files.isRegularFile(transcript)) return false;
        try {
            String recorded = KompileTranscriptFormat.readHeader(transcript).workingDirectory();
            if (recorded == null || recorded.isBlank()) return false;
            return Path.of(recorded).toAbsolutePath().normalize().equals(normalized);
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    // ------------------------------------------------------------------ resolve

    /** Resolve an id, exact name, or unique name prefix. */
    public Resolution resolve(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return new Resolution(null, "A session id or name is required");
        }
        List<SubagentResumeMetadata> all = readAll();
        for (SubagentResumeMetadata metadata : all) {
            if (metadata.sessionId().equals(idOrName)) {
                return new Resolution(metadata, null);
            }
        }
        List<SubagentResumeMetadata> named = all.stream()
                .filter(m -> idOrName.equals(m.name()))
                .toList();
        if (named.size() == 1) return new Resolution(named.get(0), null);
        if (named.isEmpty()) {
            List<SubagentResumeMetadata> prefixed = all.stream()
                    .filter(m -> m.name() != null && m.name().startsWith(idOrName))
                    .toList();
            if (prefixed.size() == 1) return new Resolution(prefixed.get(0), null);
            if (prefixed.isEmpty()) {
                return new Resolution(null, "No resumable subagent matches '" + idOrName + "'");
            }
            return new Resolution(null, "Ambiguous prefix '" + idOrName + "': " + labels(prefixed));
        }
        return new Resolution(null, "Ambiguous name '" + idOrName + "': " + labels(named));
    }

    private List<SubagentResumeMetadata> readAll() {
        List<SubagentResumeMetadata> all = new ArrayList<>();
        if (!Files.isDirectory(conversationsDir)) return all;
        try (var stream = Files.list(conversationsDir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("subagent-") && name.endsWith(".subagent.json");
                    })
                    .map(p -> SubagentResumeMetadata.read(p, mapper))
                    .filter(Objects::nonNull)
                    .forEach(all::add);
        } catch (IOException ignored) {
        }
        return all;
    }

    private static String labels(List<SubagentResumeMetadata> runs) {
        return runs.stream()
                .map(m -> m.name() != null ? m.name() : m.sessionId())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    // ------------------------------------------------------------ name registry

    /**
     * Whether a name is free. Uniqueness is machine-wide across the sidecar scan;
     * the conflicting session id is reported so the caller can self-correct.
     */
    public String nameConflict(String name) {
        for (SubagentResumeMetadata metadata : readAll()) {
            if (name.equals(metadata.name())) {
                return "Name '" + name + "' is already used by " + metadata.sessionId();
            }
        }
        return null;
    }

    public SubagentResumeMetadata metadataFor(String sessionId) {
        return SubagentResumeMetadata.read(
                SubagentResumeMetadata.sidecarFor(conversationsDir, sessionId), mapper);
    }

    public void writeMetadata(SubagentResumeMetadata metadata) throws IOException {
        metadata.write(SubagentResumeMetadata.sidecarFor(conversationsDir, metadata.sessionId()),
                mapper);
    }

    // ------------------------------------------------------------------- locking

    // POSIX locks are process-scoped: even closing a failed probe descriptor
    // can release a different service's lock. Share local ownership so a
    // contender never opens a second descriptor for an already-owned path.
    private static final java.util.Map<Path, HeldLock> JVM_LOCKS = new java.util.HashMap<>();

    /** Handles this service owns, so it cannot unlock another service's lease. */
    private final java.util.concurrent.ConcurrentHashMap<String, HeldLock> heldLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class HeldLock {
        final Path path;
        final RandomAccessFile file;
        final FileLock lock;

        HeldLock(Path path, RandomAccessFile file, FileLock lock) {
            this.path = path;
            this.file = file;
            this.lock = lock;
        }

        void release() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Releasing an already-released lock; closing below is what matters.
            }
            try {
                file.close();
            } catch (IOException ignored) {
                // Best-effort close.
            }
        }
    }

    /** Whether a resume currently holds this child's one-writer lock. */
    public boolean isLocked(String sessionId) {
        synchronized (JVM_LOCKS) {
            Path lock = lockPath(sessionId);
            if (JVM_LOCKS.containsKey(lock)) return true;
            if (!Files.exists(lock)) return false;
            try (RandomAccessFile file = new RandomAccessFile(lock.toFile(), "rw");
                 FileLock acquired = file.getChannel().tryLock()) {
                return acquired == null;
            } catch (IOException | RuntimeException held) {
                return true;
            }
        }
    }

    private Path lockPath(String sessionId) {
        Path directory = conversationsDir.toAbsolutePath().normalize();
        try { directory = directory.toRealPath(); }
        catch (IOException ignored) { }
        return directory.resolve(sessionId + ".lock");
    }

    /** Acquire the one-writer lock, or return the error text. */
    public String tryLock(String sessionId) {
        synchronized (JVM_LOCKS) {
            try {
                Files.createDirectories(conversationsDir);
                Path lock = lockPath(sessionId);
                if (JVM_LOCKS.containsKey(lock)) return "Subagent " + sessionId + " is already resumed in this JVM";
                RandomAccessFile file = new RandomAccessFile(lock.toFile(), "rw");
                FileLock acquired;
                try {
                    acquired = file.getChannel().tryLock();
                } catch (IOException | RuntimeException failure) {
                    file.close();
                    throw failure;
                }
                if (acquired == null) {
                    file.close();
                    return "Subagent " + sessionId + " is being resumed elsewhere; try again later";
                }
                HeldLock held = new HeldLock(lock, file, acquired);
                JVM_LOCKS.put(lock, held);
                heldLocks.put(sessionId, held);
                return null;
            } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
                return "Could not lock subagent " + sessionId + ": " + e.getMessage();
            }
        }
    }

    /** Release the lock previously acquired with {@link #tryLock}; best-effort. */
    public void unlock(String sessionId) {
        synchronized (JVM_LOCKS) {
            HeldLock held = heldLocks.remove(sessionId);
            if (held == null) return;
            if (JVM_LOCKS.remove(held.path, held)) held.release();
            // Keep the inode stable: unlinking would race a new holder.
        }
    }

    // ------------------------------------------------------------- ledger access

    /** Committed ledger entries for a run, for show/resume callers. */
    public List<ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry> ledger(String sessionId) {
        return SubagentLedger.read(SubagentLedger.fileFor(conversationsDir, sessionId), mapper);
    }

    /** Register an already-persisted, completed CLI transcript as portable resume history. */
    public void registerTranscript(String sessionId, String agent, String model, String parentSession) throws IOException {
        if (sessionId == null || !sessionId.matches("subagent-[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid subagent id");
        SubagentLedger ledger = new SubagentLedger(SubagentLedger.fileFor(conversationsDir, sessionId), mapper);
        try {
            ledger.open();
            if (!ledger.usable()) throw new IOException("Subagent ledger unavailable");
            if (metadataFor(sessionId) != null) return;
            var turns = KompileTranscriptFormat.readTurns(conversationsDir.resolve(sessionId + ".txt"));
            if (turns.isEmpty()) return;
            for (var turn : turns) {
                ledger.append("assistant".equals(turn.role())
                        ? ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.assistant(turn.content())
                        : ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.user(turn.content()));
            }
            if (!ledger.usable()) throw new IOException("Could not persist subagent transcript");
            writeMetadata(SubagentResumeMetadata.create(sessionId, null, agent, parentSession, null, model));
        } finally {
            ledger.close();
        }
    }

    @FunctionalInterface
    public interface Continuation {
        String run(List<ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry> history,
                   String prompt) throws Exception;
    }

    /** Portable handoff to any CLI. Own the same one-writer lease as native resume. */
    public String continueWith(String sessionId, String prompt, String parentSession,
                               Continuation continuation) throws Exception {
        if (sessionId == null || !sessionId.matches("subagent-[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid subagent session id");
        }
        SubagentLedger ledger = new SubagentLedger(SubagentLedger.fileFor(conversationsDir, sessionId), mapper);
        try {
            ledger.open();
            if (!ledger.usable()) throw new IOException("Subagent ledger is not writable: " + sessionId);
            SubagentResumeMetadata metadata = metadataFor(sessionId);
            if (metadata == null) throw new IllegalArgumentException("No resumable subagent metadata for " + sessionId);
            var history = ledger(sessionId);
            if (history.isEmpty()) throw new IllegalArgumentException("Subagent has no committed history to resume: " + sessionId);
            String result = Objects.requireNonNull(continuation.run(List.copyOf(history), prompt), "Continuation returned no result");
            ledger.append(ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.user(prompt));
            ledger.append(ai.kompile.cli.main.chat.render.CompactionService.ConversationEntry.assistant(result));
            if (!ledger.usable()) throw new IOException("Could not persist subagent continuation: " + sessionId);
            writeMetadata(metadata.withParent(parentSession == null ? metadata.parentSession() : parentSession));
            return result;
        } finally {
            ledger.close();
        }
    }

    // -------------------------------------------------------------------- prune

    /** Delete all artifacts for a run. Returns the error text, or null on success. */
    public String prune(String sessionId) {
        if (!sessionId.startsWith("subagent-")) {
            return "Refusing to prune a non-subagent session id: " + sessionId;
        }
        String error = tryLock(sessionId);
        if (error != null) return "Subagent " + sessionId + " is live or unavailable; cancel it before pruning: " + error;
        try {
            for (String suffix : List.of(".txt", ".ledger.jsonl", ".subagent.json", ".runs.json")) {
                try {
                    Files.deleteIfExists(conversationsDir.resolve(sessionId + suffix));
                } catch (IOException e) {
                    return "Could not delete " + sessionId + suffix + ": " + e.getMessage();
                }
            }
            return null;
        } finally {
            unlock(sessionId);
        }
    }
}
