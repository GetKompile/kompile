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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.utils.FormatUtils;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * File-based message bus and coordination state manager for multi-agent environments.
 *
 * <p>All state is persisted as individual JSON files under
 * {@code <projectRoot>/.kompile/coordination/}. Cross-process safety is achieved
 * via a {@link FileLock} on a sentinel file during mutations (held for
 * milliseconds only). Edit locks are advisory — they warn agents of conflicts
 * but do not block writes.
 *
 * <p>A daemon heartbeat thread updates the current session's agent file and
 * edit lock files every 30 seconds. It also heartbeats user-wide high-memory
 * activity reservations so builds, tests, and crawls can coordinate before launch.
 * Stale entries (past TTL) are lazily evicted on query operations.
 */
public class CoordinationStateManager {

    private static final int DEFAULT_EDIT_TTL_SECONDS = 60;
    private static final int DEFAULT_AGENT_TTL_SECONDS = 120;
    private static final int DEFAULT_PROCESS_TTL_SECONDS = 120;
    private static final int DEFAULT_TERMINAL_PROCESS_TTL_SECONDS = 900;
    private static final Duration PROCESS_EXIT_RECONCILIATION_GRACE = Duration.ofSeconds(2);
    private static final long PROCESS_LIFECYCLE_LOCK_WAIT_MS = 500L;
    private static final int DEFAULT_MESSAGE_TTL_SECONDS = 86_400;
    private static final int MAX_MESSAGE_BYTES = 65_536;
    private static final int MAX_MESSAGE_RESULTS = 100;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path projectRoot;
    private final Path coordinationDir;
    private final Path editsDir;
    private final Path agentsDir;
    private final Path processesDir;
    private final Path messagesDir;
    private final Path corruptDir;
    private final Path lockFile;
    private final String sessionId;
    private final ObjectMapper mapper;
    private final SystemActivityCoordinator systemActivityCoordinator;
    private final ActivityWaitRegistry activityWaits = new ActivityWaitRegistry();
    private final ScheduledExecutorService heartbeatExecutor;
    private final Object agentEntryLock = new Object();
    private final Set<String> ownedLockIds = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Consumer<String>> warningSink = new AtomicReference<>();
    private volatile boolean shutdown = false;

    /**
     * Create a new coordination state manager.
     *
     * @param workDir   the project working directory
     * @param sessionId unique session identifier for this MCP server instance
     * @param mapper    Jackson ObjectMapper (will be configured for Instant support)
     */
    public CoordinationStateManager(Path workDir, String sessionId, ObjectMapper mapper) {
        this(workDir, sessionId, mapper, SystemActivityCoordinator.defaultStateRoot());
    }

    /**
     * Create a manager with an explicit user-wide activity state root. The overload is
     * primarily useful for isolated harnesses and tests; production callers share the
     * default root under {@code ~/.kompile/coordination/system}.
     */
    public CoordinationStateManager(Path workDir, String sessionId, ObjectMapper mapper,
                                    Path systemActivityStateRoot) {
        Path normalizedWorkDir = Objects.requireNonNull(workDir, "workDir")
                .toAbsolutePath().normalize();
        this.projectRoot = new KompileProjectStore().findProjectRoot(normalizedWorkDir)
                .orElse(normalizedWorkDir);
        this.coordinationDir = projectRoot.resolve(".kompile").resolve("coordination");
        this.editsDir = coordinationDir.resolve("edits");
        this.agentsDir = coordinationDir.resolve("agents");
        this.processesDir = coordinationDir.resolve("processes");
        this.messagesDir = coordinationDir.resolve("messages");
        this.corruptDir = coordinationDir.resolve("corrupt");
        this.lockFile = coordinationDir.resolve(".coordinator.lock");
        this.sessionId = requireSafeComponent(sessionId, "sessionId");

        // Configure mapper for java.time.Instant serialization
        this.mapper = mapper.copy();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.systemActivityCoordinator = new SystemActivityCoordinator(
                Objects.requireNonNull(systemActivityStateRoot, "systemActivityStateRoot"),
                projectRoot, this.sessionId, this.mapper, this::notifyWarning);

        // Ensure directories exist
        try {
            Files.createDirectories(editsDir);
            Files.createDirectories(agentsDir);
            Files.createDirectories(processesDir);
            Files.createDirectories(messagesDir);
            Files.createDirectories(corruptDir);
            hardenPermissions(coordinationDir, OWNER_DIRECTORY_PERMISSIONS);
            hardenPermissions(editsDir, OWNER_DIRECTORY_PERMISSIONS);
            hardenPermissions(agentsDir, OWNER_DIRECTORY_PERMISSIONS);
            hardenPermissions(processesDir, OWNER_DIRECTORY_PERMISSIONS);
            hardenPermissions(messagesDir, OWNER_DIRECTORY_PERMISSIONS);
            hardenPermissions(corruptDir, OWNER_DIRECTORY_PERMISSIONS);
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not create coordination directories: " + e.getMessage());
        }

        // Start heartbeat daemon
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "coord-heartbeat-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        this.heartbeatExecutor.scheduleAtFixedRate(
                this::heartbeat,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * Create a manager for CLI-only use (no heartbeat, read-only queries).
     */
    public static CoordinationStateManager forCli(Path workDir) {
        ObjectMapper om = JsonUtils.standardMapper();
        CoordinationStateManager mgr = new CoordinationStateManager(workDir, "cli-" + System.currentTimeMillis(), om);
        mgr.heartbeatExecutor.shutdownNow();
        return mgr;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    /**
     * Route non-fatal coordination diagnostics through a session-owned UI lane.
     * Headless callers retain stderr fallback. The returned cleanup only removes
     * this exact registration, so a newer sink cannot be cleared accidentally.
     */
    public Runnable installWarningSink(Consumer<String> sink) {
        if (sink == null) return () -> { };
        warningSink.set(sink);
        return () -> warningSink.compareAndSet(sink, null);
    }

    // ── Edit Lock Operations ──────────────────────────────────────────────

    /**
     * Attempt to acquire an advisory edit lock on a file.
     *
     * @param filePath  absolute path to the file being edited
     * @param editType  "edit" or "write"
     * @return result indicating whether the lock was acquired or a conflict exists
     */
    public EditLockResult tryAcquireEditLock(String filePath, String editType) {
        return tryAcquireEditLock(filePath, editType, null);
    }

    /**
     * Attempt to acquire an advisory edit lock on a file.
     *
     * @param filePath  absolute path to the file being edited
     * @param editType  "edit" or "write"
     * @param agentName optional agent name for the lock entry
     * @return result indicating whether the lock was acquired or a conflict exists
     */
    public EditLockResult tryAcquireEditLock(String filePath, String editType, String agentName) {
        String fileHash = Integer.toHexString(filePath.hashCode());
        String lockId = sessionId + "-" + fileHash;

        try {
            return withCoordinatorLock(() -> {
                // Check for existing locks on this file from OTHER sessions
                List<EditLockEntry> existing = readEditLocks();
                for (EditLockEntry entry : existing) {
                    if (entry.getAbsolutePath().equals(filePath)
                            && !entry.getSessionId().equals(sessionId)) {
                        if (!entry.isStale() && !holderIsDead(entry)) {
                            String msg = entry.getAbsolutePath() + " is actively being edited by "
                                    + entry.getAgentName() + " (session " + entry.getSessionId() + ")";
                            return EditLockResult.conflict(entry, msg);
                        }
                        // TTL-expired or provably dead holder: clear it instead of conflict.
                        Files.deleteIfExists(editsDir.resolve(entry.getLockId() + ".lock.json"));
                    }
                }

                // Write lock file
                EditLockEntry lock = new EditLockEntry(
                        lockId, sessionId, agentName != null ? agentName : "unknown",
                        filePath, filePath, editType,
                        Instant.now(), DEFAULT_EDIT_TTL_SECONDS
                );
                Path lockPath = editsDir.resolve(lockId + ".lock.json");
                writeJsonAtomically(lockPath, lock);
                ownedLockIds.add(lockId);

                return EditLockResult.acquired(lockId);
            });
        } catch (Exception e) {
            // If coordination fails, don't block the edit — return acquired with a warning
            notifyWarning("[Coordination] Warning: Could not acquire edit lock: " + e.getMessage());
            return EditLockResult.acquired(lockId);
        }
    }

    /**
     * Release an advisory edit lock.
     *
     * @param lockId the lock ID returned by {@link #tryAcquireEditLock}
     * @return true if the lock was found and released
     */
    public boolean releaseEditLock(String lockId) {
        if (lockId == null) return false;
        try {
            Path lockPath = editsDir.resolve(lockId + ".lock.json");
            boolean deleted = Files.deleteIfExists(lockPath);
            ownedLockIds.remove(lockId);
            return deleted;
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not release edit lock: " + e.getMessage());
            return false;
        }
    }

    /**
     * Attempt to acquire advisory edit locks on several files in ONE coordinator
     * pass (one file-lock round instead of N sequential tool calls).
     *
     * <p>All-or-nothing by default: when any file is locked by another live
     * session, nothing is acquired — conflicting files report CONFLICT and the
     * rest report SKIPPED. With {@code allowPartial=true} the non-conflicting
     * files are locked anyway.
     *
     * @param filePaths absolute paths (duplicates collapse to one lock)
     * @return per-path results in input order
     */
    public BatchAcquireResult tryAcquireEditLocks(List<String> filePaths, String editType,
                                                  String agentName, boolean allowPartial) {
        LinkedHashSet<String> paths = new LinkedHashSet<>(filePaths);
        try {
            return withCoordinatorLock(() -> {
                List<EditLockEntry> existing = readEditLocks();
                Map<String, EditLockResult> results = new LinkedHashMap<>();
                Map<String, EditLockEntry> conflicts = new LinkedHashMap<>();
                for (String path : paths) {
                    for (EditLockEntry entry : existing) {
                        if (entry.getAbsolutePath().equals(path)
                                && !entry.getSessionId().equals(sessionId)) {
                            if (!entry.isStale() && !holderIsDead(entry)) {
                                conflicts.put(path, entry);
                                break;
                            }
                            Files.deleteIfExists(editsDir.resolve(entry.getLockId() + ".lock.json"));
                        }
                    }
                }

                boolean aborted = !conflicts.isEmpty() && !allowPartial;
                for (String path : paths) {
                    EditLockEntry conflict = conflicts.get(path);
                    if (conflict != null) {
                        results.put(path, EditLockResult.conflict(conflict,
                                path + " is actively being edited by " + conflict.getAgentName()
                                        + " (session " + conflict.getSessionId() + ")"));
                    } else if (aborted) {
                        results.put(path, EditLockResult.skipped(
                                "not acquired — batch aborted because other files conflict "
                                        + "(pass allow_partial=true to lock what is free)"));
                    } else {
                        String lockId = sessionId + "-" + Integer.toHexString(path.hashCode());
                        EditLockEntry lock = new EditLockEntry(
                                lockId, sessionId, agentName != null ? agentName : "unknown",
                                path, path, editType,
                                Instant.now(), DEFAULT_EDIT_TTL_SECONDS
                        );
                        writeJsonAtomically(editsDir.resolve(lockId + ".lock.json"), lock);
                        ownedLockIds.add(lockId);
                        results.put(path, EditLockResult.acquired(lockId));
                    }
                }
                return new BatchAcquireResult(results, aborted);
            });
        } catch (Exception e) {
            // Coordination failure never blocks edits: report all paths acquired with
            // deterministic lock ids, matching tryAcquireEditLock's failure-open behavior.
            notifyWarning("[Coordination] Warning: Could not batch-acquire edit locks: " + e.getMessage());
            Map<String, EditLockResult> results = new LinkedHashMap<>();
            for (String path : paths) {
                results.put(path, EditLockResult.acquired(
                        sessionId + "-" + Integer.toHexString(path.hashCode())));
            }
            return new BatchAcquireResult(results, false);
        }
    }

    /** Per-path outcome of {@link #tryAcquireEditLocks}. */
    public record BatchAcquireResult(Map<String, EditLockResult> results, boolean aborted) {
        public long acquiredCount() {
            return results.values().stream().filter(EditLockResult::isAcquired).count();
        }
        public long conflictCount() {
            return results.values().stream().filter(EditLockResult::hasConflict).count();
        }
    }

    /**
     * Release several advisory edit locks in one call.
     *
     * @return per-lock-id released flag, in input order
     */
    public Map<String, Boolean> releaseEditLocks(List<String> lockIds) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (String lockId : new LinkedHashSet<>(lockIds)) {
            results.put(lockId, releaseEditLock(lockId));
        }
        return results;
    }

    /**
     * Read-only conflict probe: the first non-stale edit lock on {@code absolutePath}
     * held by ANOTHER session, or null. Skips the coordinator file lock — callers use
     * this on the edit hot path where an advisory racy read is fine.
     */
    public EditLockEntry findConflictingLock(String absolutePath) {
        for (EditLockEntry entry : readEditLocks()) {
            if (entry.getAbsolutePath().equals(absolutePath)
                    && !entry.getSessionId().equals(sessionId)
                    && !entry.isStale()) {
                if (holderIsDead(entry)) {
                    try {
                        Files.deleteIfExists(editsDir.resolve(entry.getLockId() + ".lock.json"));
                    } catch (IOException ignored) {
                        // The next coordination pass retries the eviction.
                    }
                    continue;
                }
                return entry;
            }
        }
        return null;
    }

    /**
     * Force-release all edit locks on a specific file path.
     *
     * @return true if any locks were released
     */
    public boolean forceReleaseLockByFile(String filePath) {
        try {
            return withCoordinatorLock(() -> {
                boolean released = false;
                List<EditLockEntry> locks = readEditLocks();
                for (EditLockEntry lock : locks) {
                    if (lock.getAbsolutePath().equals(filePath)) {
                        Path lockPath = editsDir.resolve(lock.getLockId() + ".lock.json");
                        Files.deleteIfExists(lockPath);
                        released = true;
                    }
                }
                return released;
            });
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not force-release lock: " + e.getMessage());
            return false;
        }
    }

    /**
     * Release every active edit lock regardless of owner. CLI escape hatch for
     * wedged coordination state; advisory locks only, so this is always safe.
     *
     * @return number of lock files removed
     */
    public int forceReleaseAllLocks() {
        int released = 0;
        for (EditLockEntry lock : queryEdits()) {
            try {
                if (Files.deleteIfExists(editsDir.resolve(lock.getLockId() + ".lock.json"))) released++;
            } catch (IOException e) {
                notifyWarning("[Coordination] Warning: Could not release lock "
                        + lock.getLockId() + ": " + e.getMessage());
            }
        }
        return released;
    }

    /**
     * Positive-evidence orphan check for a foreign edit lock. A lock is orphaned
     * when its holder's presence file is TTL-stale or its recorded PID is
     * provably dead. Missing presence is inconclusive (a holder may never have
     * registered) and keeps the lock; TTL eviction still applies.
     */
    private boolean holderIsDead(EditLockEntry entry) {
        if (entry == null || entry.getSessionId() == null
                || entry.getSessionId().equals(sessionId)) return false;
        Path agentFile = agentsDir.resolve(entry.getSessionId() + ".agent.json");
        if (!Files.isRegularFile(agentFile, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            AgentEntry holder = mapper.readValue(agentFile.toFile(), AgentEntry.class);
            if (holder.isStale()) return true;
            return holder.getPid() > 0 && !isProcessAlive(holder.getPid());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Query all active (non-stale) edit locks, evicting stale ones.
     */
    public List<EditLockEntry> queryEdits() {
        try {
            return withCoordinatorLock(() -> {
                List<EditLockEntry> all = readEditLocks();
                List<EditLockEntry> active = new ArrayList<>();
                for (EditLockEntry entry : all) {
                    if (entry.isStale() || holderIsDead(entry)) {
                        Path lockPath = editsDir.resolve(entry.getLockId() + ".lock.json");
                        Files.deleteIfExists(lockPath);
                    } else {
                        active.add(entry);
                    }
                }
                return active;
            });
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not query edits: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Query edit locks for a specific file path.
     */
    public List<EditLockEntry> queryEditsForFile(String filePath) {
        List<EditLockEntry> all = queryEdits();
        List<EditLockEntry> result = new ArrayList<>();
        for (EditLockEntry entry : all) {
            if (entry.getAbsolutePath().equals(filePath)) {
                result.add(entry);
            }
        }
        return result;
    }

    // ── Agent Registration ────────────────────────────────────────────────

    /**
     * Register an agent session in the coordination state.
     */
    public void registerAgent(String task, String parentSessionId, String agentName,
                              int depth, long pid) {
        registerAgent(task, parentSessionId, agentName, depth, pid, sessionId, null);
    }

    /** Register presence together with the tool-call stream and optional role it owns. */
    public void registerAgent(String task, String parentSessionId, String agentName,
                              int depth, long pid, String toolSessionId, String roleName) {
        try {
            synchronized (agentEntryLock) {
                String resolvedToolSessionId = toolSessionId == null || toolSessionId.isBlank()
                        ? sessionId : requireSafeComponent(toolSessionId.trim(), "toolSessionId");
                AgentEntry entry = new AgentEntry(
                        sessionId, agentName != null ? agentName : "unknown",
                        parentSessionId != null ? "subagent" : "parent",
                        parentSessionId, depth, task,
                        projectRoot.toString(),
                        pid, Instant.now(), DEFAULT_AGENT_TTL_SECONDS
                );
                entry.setToolSessionId(resolvedToolSessionId);
                entry.setRoleName(roleName == null || roleName.isBlank() ? null : roleName.trim());
                writeJsonAtomically(agentsDir.resolve(sessionId + ".agent.json"), entry);
            }
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not register agent: " + e.getMessage());
        }
    }

    /**
     * Register a child agent session (used by StdioTaskTool when spawning subagents).
     */
    public void registerChildAgent(String childSessionId, String task, String agentName,
                                   int depth, long pid) {
        String safeChildSessionId = requireSafeComponent(childSessionId, "childSessionId");
        try {
            AgentEntry entry = new AgentEntry(
                    safeChildSessionId, agentName != null ? agentName : "unknown",
                    "subagent", sessionId, depth, task,
                    projectRoot.toString(),
                    pid, Instant.now(), DEFAULT_AGENT_TTL_SECONDS
            );
            entry.setToolSessionId(safeChildSessionId);
            writeJsonAtomically(agentsDir.resolve(safeChildSessionId + ".agent.json"), entry);
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not register child agent: " + e.getMessage());
        }
    }

    /**
     * Deregister an agent session.
     */
    public void deregisterAgent(String agentSessionId) {
        try {
            Path agentFile = agentsDir.resolve(
                    requireSafeComponent(agentSessionId, "agentSessionId") + ".agent.json");
            synchronized (agentEntryLock) {
                Files.deleteIfExists(agentFile);
            }
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not deregister agent: " + e.getMessage());
        }
    }

    /**
     * Query all active (non-stale) agent sessions, evicting stale ones.
     */
    public List<AgentEntry> queryAgents() {
        return queryAgents(false);
    }

    /**
     * Query agent sessions, optionally including stale entries.
     */
    public List<AgentEntry> queryAgents(boolean includeStale) {
        try {
            return withCoordinatorLock(() -> {
                List<AgentEntry> all = readAgentEntries();
                if (includeStale) return all;

                List<AgentEntry> active = new ArrayList<>();
                for (AgentEntry entry : all) {
                    if (entry.isStale()) {
                        Path agentFile = agentsDir.resolve(entry.getSessionId() + ".agent.json");
                        Files.deleteIfExists(agentFile);
                    } else {
                        active.add(entry);
                    }
                }
                return active;
            });
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not query agents: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Project-local Agent Mailbox ───────────────────────────────────────

    /**
     * Deliver an immutable message to another coordination session. Delivery is
     * durable and does not imply that an externally owned model turn was woken.
     */
    public CoordinationMessage sendMessage(String targetSessionId, String kind,
                                           String message, String replyTo) throws IOException {
        String target = requireSafeComponent(targetSessionId, "targetSessionId");
        String normalizedKind = kind == null || kind.isBlank() ? "message" : kind.trim();
        requireSafeComponent(normalizedKind, "kind");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                    "message exceeds " + MAX_MESSAGE_BYTES + " UTF-8 bytes");
        }
        String normalizedReplyTo = replyTo == null || replyTo.isBlank()
                ? null : requireSafeComponent(replyTo, "replyTo");

        Instant now = Instant.now();
        CoordinationMessage delivered = new CoordinationMessage(
                UUID.randomUUID().toString(), sessionId, target, normalizedKind,
                message, normalizedReplyTo, now,
                now.plusSeconds(DEFAULT_MESSAGE_TTL_SECONDS));
        Path inbox = messagesDir.resolve(target);
        Files.createDirectories(inbox);
        if (Files.isSymbolicLink(inbox) || !Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("mailbox path is not a real directory: " + inbox);
        }
        hardenPermissions(inbox, OWNER_DIRECTORY_PERMISSIONS);
        writeJsonAtomically(inbox.resolve(delivered.getMessageId() + ".message.json"), delivered);
        return delivered;
    }

    /** Return pending messages for this session without acknowledging them. */
    public List<CoordinationMessage> readMessages(int maxResults) {
        int limit = Math.max(1, Math.min(MAX_MESSAGE_RESULTS, maxResults));
        Path inbox = messagesDir.resolve(sessionId);
        if (!Files.isDirectory(inbox)) return List.of();

        List<CoordinationMessage> pending = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inbox, "*.message.json")) {
            for (Path file : stream) {
                try {
                    CoordinationMessage message = mapper.readValue(file.toFile(), CoordinationMessage.class);
                    if (message.isExpired()) {
                        Files.deleteIfExists(file);
                    } else if (sessionId.equals(message.getTargetSessionId())) {
                        pending.add(message);
                    }
                } catch (IOException e) {
                    notifyWarning("[Coordination] Warning: Could not read message " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not list mailbox " + inbox + ": " + e.getMessage());
            return List.of();
        }
        pending.sort(Comparator
                .comparing(CoordinationMessage::getSentAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CoordinationMessage::getMessageId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(pending.subList(0, Math.min(limit, pending.size())));
    }

    /** Acknowledge and remove one message from this session's mailbox. */
    public boolean acknowledgeMessage(String messageId) {
        String safeMessageId = requireSafeComponent(messageId, "messageId");
        try {
            return Files.deleteIfExists(messagesDir.resolve(sessionId)
                    .resolve(safeMessageId + ".message.json"));
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not acknowledge message: " + e.getMessage());
            return false;
        }
    }

    /** Deliver the same durable message to every other active project agent. */
    public List<CoordinationMessage> broadcastMessage(String kind, String message,
                                                      String replyTo) {
        List<CoordinationMessage> delivered = new ArrayList<>();
        for (AgentEntry agent : queryAgents()) {
            String target = agent.getSessionId();
            if (target == null || target.equals(sessionId)) continue;
            try {
                delivered.add(sendMessage(target, kind, message, replyTo));
            } catch (IOException | IllegalArgumentException e) {
                notifyWarning("[Coordination] Warning: Could not broadcast to " + target
                        + ": " + e.getMessage());
            }
        }
        return List.copyOf(delivered);
    }

    // ── User-wide High-memory Activity Coordination ───────────────────────

    public ActivityWaitRegistry activityWaits() {
        return activityWaits;
    }

    public SystemActivityCoordinator.ReservationResult reserveHighMemoryActivity(
            String agentName, String kind, String toolName, String description) {
        return systemActivityCoordinator.reserve(
                resolvePublishedAgentName(agentName), kind, toolName, description);
    }

    public List<CoordinationActivity> queryHighMemoryActivities() {
        return systemActivityCoordinator.queryActive();
    }

    public boolean attachHighMemoryActivity(String activityId, String processId,
                                            long processPid, String externalId) {
        return systemActivityCoordinator.attach(
                activityId, processId, processPid, externalId);
    }

    public boolean releaseHighMemoryActivity(String activityId) {
        return systemActivityCoordinator.release(activityId);
    }

    public int releaseHighMemoryActivityByExternalId(String externalId) {
        return systemActivityCoordinator.releaseByExternalId(externalId);
    }

    // ── Process Publication ───────────────────────────────────────────────

    /**
     * Publish a background process to the shared coordination state.
     */
    public void publishProcess(String processId, String command, String description,
                               long pid, String state, String outputFile, String agentName) {
        publishProcess(processId, command, description, pid, state, outputFile,
                agentName, null, null, Instant.now(), null, null);
    }

    /** Publish a process with enough owner and terminal metadata for live dashboards. */
    public void publishProcess(String processId, String command, String description,
                               long pid, String state, String outputFile, String agentName,
                               String roleName, String kind, Instant startedAt,
                               Instant endedAt, Integer exitCode) {
        publishProcess(processId, command, description, pid, state, outputFile, agentName, roleName, kind,
                startedAt, endedAt, exitCode, ai.kompile.cli.main.chat.tools.ResourcePolicy.classify(projectRoot,
                        "process", mapper.createObjectNode().put("action", "launch").put("command", command)).resourceClass());
    }

    public void publishProcess(String processId, String command, String description,
                               long pid, String state, String outputFile, String agentName,
                               String roleName, String kind, Instant startedAt,
                               Instant endedAt, Integer exitCode, String resourceClass) {
        String safeProcessId = requireSafeComponent(processId, "processId");
        String resolvedState = state == null || state.isBlank() ? "RUNNING" : state.trim();
        Instant now = Instant.now();
        try {
            withCoordinatorLock(PROCESS_LIFECYCLE_LOCK_WAIT_MS, () -> {
                ProcessCoordEntry entry = new ProcessCoordEntry(
                        safeProcessId, sessionId, resolvePublishedAgentName(agentName),
                        command, description, pid, resolvedState,
                        startedAt != null ? startedAt : now, outputFile,
                        DEFAULT_PROCESS_TTL_SECONDS
                );
                entry.setRoleName(roleName == null || roleName.isBlank() ? null : roleName.trim());
                entry.setKind(kind == null || kind.isBlank() ? null : kind.trim());
                entry.setResourceClass(resourceClass);
                entry.setEndedAt(endedAt);
                entry.setExitCode(exitCode);
                if (entry.isTerminalState()) {
                    entry.setEndedAt(endedAt != null ? endedAt : now);
                    entry.setLastHeartbeat(entry.getEndedAt());
                    entry.setTtlSeconds(DEFAULT_TERMINAL_PROCESS_TTL_SECONDS);
                }
                Path procFile = processFile(sessionId, safeProcessId);
                writeJsonAtomically(procFile, entry);
                return null;
            });
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not publish process: " + e.getMessage());
        }
    }

    /**
     * Update the state of a published process.
     */
    public void updateProcessState(String processId, String state) {
        updateProcessState(processId, state, null, null);
    }

    /** Update a published process, retaining terminal time and exit status when available. */
    public void updateProcessState(String processId, String state,
                                   Instant endedAt, Integer exitCode) {
        String safeProcessId = requireSafeComponent(processId, "processId");
        try {
            Path procFile = processFile(sessionId, safeProcessId);
            if (Files.exists(procFile)) {
                withCoordinatorLock(isRunningProcessState(state)
                        ? 0L : PROCESS_LIFECYCLE_LOCK_WAIT_MS, () -> {
                    ProcessCoordEntry entry = mapper.readValue(procFile.toFile(), ProcessCoordEntry.class);
                    entry.setState(state);
                    Instant now = Instant.now();
                    if (entry.isTerminalState()) {
                        entry.setEndedAt(endedAt != null ? endedAt : now);
                        entry.setExitCode(exitCode);
                        entry.setTtlSeconds(DEFAULT_TERMINAL_PROCESS_TTL_SECONDS);
                    }
                    entry.setLastHeartbeat(now);
                    writeJsonAtomically(procFile, entry);
                    return null;
                });
            }
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not update process state: " + e.getMessage());
        }
        if (!isRunningProcessState(state)) {
            systemActivityCoordinator.releaseByProcess(safeProcessId);
        }
    }

    /**
     * Remove a process from the shared coordination state.
     */
    public boolean unpublishProcess(String processId) {
        String safeProcessId = requireSafeComponent(processId, "processId");
        try {
            Path procFile = processFile(sessionId, safeProcessId);
            boolean deleted = Files.deleteIfExists(procFile);
            systemActivityCoordinator.releaseByProcess(safeProcessId);
            return deleted;
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not unpublish process: " + e.getMessage());
            return false;
        }
    }

    /**
     * Query all active (non-stale) processes across all agents, evicting stale ones.
     */
    public List<ProcessCoordEntry> queryProcesses() {
        try {
            return withCoordinatorLock(() -> {
                List<ProcessCoordEntry> all = readProcessEntries();
                List<ProcessCoordEntry> active = new ArrayList<>();
                for (ProcessCoordEntry entry : all) {
                    if (!hasSafeProcessIdentity(entry)) {
                        notifyWarning("[Coordination] Warning: Ignoring process entry with invalid identity");
                        continue;
                    }
                    Path procFile = processFile(entry.getSessionId(), entry.getProcessId());
                    reconcileProcessLiveness(entry, procFile);
                    if (shouldEvictProcess(entry)) {
                        Files.deleteIfExists(procFile);
                    } else {
                        active.add(entry);
                    }
                }
                return active;
            });
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not query processes: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Staleness Eviction ────────────────────────────────────────────────

    /**
     * Evict all stale entries across edits, agents, and processes.
     *
     * @return total number of entries evicted
     */
    public int evictStale() {
        int count = 0;
        try {
            count += withCoordinatorLock(() -> {
                int evicted = 0;
                for (EditLockEntry e : readEditLocks()) {
                    if (e.isStale() || holderIsDead(e)) {
                        Files.deleteIfExists(editsDir.resolve(e.getLockId() + ".lock.json"));
                        evicted++;
                    }
                }
                for (AgentEntry e : readAgentEntries()) {
                    if (e.isStale()) {
                        Files.deleteIfExists(agentsDir.resolve(e.getSessionId() + ".agent.json"));
                        evicted++;
                    }
                }
                for (ProcessCoordEntry e : readProcessEntries()) {
                    if (hasSafeProcessIdentity(e)) {
                        Path procFile = processFile(e.getSessionId(), e.getProcessId());
                        reconcileProcessLiveness(e, procFile);
                        if (!shouldEvictProcess(e)) continue;
                        Files.deleteIfExists(procFile);
                        evicted++;
                    }
                }
                return evicted;
            });
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not evict stale entries: " + e.getMessage());
        }
        count += evictExpiredMessages();
        return count;
    }

    private int evictExpiredMessages() {
        if (!Files.isDirectory(messagesDir)) return 0;
        int evicted = 0;
        try (DirectoryStream<Path> inboxes = Files.newDirectoryStream(messagesDir)) {
            for (Path inbox : inboxes) {
                if (!Files.isDirectory(inbox)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(inbox, "*.message.json")) {
                    for (Path file : files) {
                        try {
                            CoordinationMessage message = mapper.readValue(file.toFile(), CoordinationMessage.class);
                            if (message.isExpired() && Files.deleteIfExists(file)) evicted++;
                        } catch (IOException e) {
                            notifyWarning("[Coordination] Warning: Could not inspect message "
                                    + file + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not evict expired messages: " + e.getMessage());
        }
        return evicted;
    }

    // ── Shutdown ──────────────────────────────────────────────────────────

    /**
     * Clean shutdown: deregister this session, release all owned locks, stop heartbeat.
     */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;

        heartbeatExecutor.shutdownNow();
        activityWaits.close();
        systemActivityCoordinator.close();

        // Remove this session's agent file
        deregisterAgent(sessionId);

        // Remove all owned edit locks
        for (String lockId : ownedLockIds) {
            releaseEditLock(lockId);
        }
        ownedLockIds.clear();

        // Remove this session's process entries
        try {
            if (Files.exists(processesDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(processesDir,
                        sessionId + "-*.proc.json")) {
                    for (Path file : stream) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not clean up process entries: " + e.getMessage());
        }
    }

    // ── Status Dashboard ──────────────────────────────────────────────────

    /**
     * Generate a human-readable status dashboard.
     */
    public String statusDashboard() {
        List<AgentEntry> agents = queryAgents();
        List<EditLockEntry> edits = queryEdits();
        List<ProcessCoordEntry> processes = queryProcesses();
        List<CoordinationMessage> messages = readMessages(MAX_MESSAGE_RESULTS);
        List<CoordinationActivity> activities = queryHighMemoryActivities();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Kompile Agent Coordination Dashboard ===\n");
        sb.append("Time: ").append(Instant.now()).append("\n\n");

        // Agents
        sb.append("ACTIVE AGENTS (").append(agents.size()).append(")\n");
        if (agents.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (AgentEntry a : agents) {
                String dur = FormatUtils.formatDuration(Duration.between(a.getStartedAt(), Instant.now()));
                sb.append(String.format("  %-30s %-10s depth=%d  \"%s\"  %s\n",
                        a.getSessionId(), a.getAgentName(), a.getDepth(),
                        StringUtils.truncate(a.getTask(), 40), dur));
            }
        }

        sb.append("\nACTIVE EDITS (").append(edits.size()).append(")\n");
        if (edits.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (EditLockEntry e : edits) {
                String age = FormatUtils.formatDuration(Duration.between(e.getAcquiredAt(), Instant.now()));
                sb.append(String.format("  %-50s %-20s %-6s %s\n",
                        StringUtils.truncate(e.getFilePath(), 50),
                        e.getSessionId(), e.getEditType(), age));
            }
        }

        sb.append("\nACTIVE PROCESSES (").append(processes.size()).append(")\n");
        if (processes.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (ProcessCoordEntry p : processes) {
                String dur = FormatUtils.formatDuration(p.getDuration());
                sb.append(String.format("  %-10s %-20s %-30s %-10s %s\n",
                        p.getProcessId(), p.getSessionId(),
                        StringUtils.truncate(p.getCommand(), 30), p.getState(), dur));
            }
        }

        sb.append("\nPENDING MESSAGES (").append(messages.size()).append(")\n");
        if (messages.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (CoordinationMessage message : messages) {
                sb.append(String.format("  %-36s %-20s %-10s %s\n",
                        message.getMessageId(),
                        StringUtils.truncate(message.getSenderSessionId(), 20),
                        message.getKind(),
                        StringUtils.truncate(message.getMessage().replace('\n', ' '), 60)));
            }
        }

        sb.append("\nSYSTEM HIGH-MEMORY ACTIVITIES (").append(activities.size()).append(")\n");
        if (activities.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (CoordinationActivity activity : activities) {
                String age = activity.getStartedAt() == null ? "unknown"
                        : FormatUtils.formatDuration(
                                Duration.between(activity.getStartedAt(), Instant.now()));
                sb.append(String.format("  %-10s %-20s %-20s %-12s %s\n",
                        activity.getKind(),
                        StringUtils.truncate(activity.getAgentName(), 20),
                        StringUtils.truncate(activity.getToolName(), 20),
                        age,
                        StringUtils.truncate(activity.getDescription(), 70)));
            }
        }

        return sb.toString();
    }

    // ── Internal: Heartbeat ───────────────────────────────────────────────

    private void heartbeat() {
        if (shutdown) return;
        try {
            // Update agent file heartbeat
            Path agentFile = agentsDir.resolve(sessionId + ".agent.json");
            if (Files.exists(agentFile)) {
                synchronized (agentEntryLock) {
                    if (!shutdown && Files.exists(agentFile)) {
                        try {
                            AgentEntry entry = mapper.readValue(agentFile.toFile(), AgentEntry.class);
                            entry.setLastHeartbeat(Instant.now());
                            writeJsonAtomically(agentFile, entry);
                        } catch (IOException malformedAgent) {
                            Path quarantined = quarantineCorruptRecord(agentFile);
                            notifyWarning("[Coordination] Heartbeat error: Could not read "
                                    + agentFile + quarantineSuffix(quarantined) + ": "
                                    + malformedAgent.getMessage());
                        }
                    }
                }
            }

            // Update owned edit lock heartbeats
            for (String lockId : ownedLockIds) {
                Path lockPath = editsDir.resolve(lockId + ".lock.json");
                if (Files.exists(lockPath)) {
                    withCoordinatorLock(() -> {
                        if (Files.exists(lockPath)) {
                            EditLockEntry entry = mapper.readValue(lockPath.toFile(), EditLockEntry.class);
                            entry.setLastHeartbeat(Instant.now());
                            writeJsonAtomically(lockPath, entry);
                        }
                        return null;
                    });
                }
            }

            // Update only live owned processes. Terminal records retain their
            // completion timestamp and age out; dead RUNNING records become LOST.
            if (Files.exists(processesDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(processesDir,
                        sessionId + "-*.proc.json")) {
                    for (Path procFile : stream) {
                        withCoordinatorLock(() -> {
                            if (Files.exists(procFile)) {
                                try {
                                    ProcessCoordEntry entry = mapper.readValue(
                                            procFile.toFile(), ProcessCoordEntry.class);
                                    if (entry.isRunningState()) {
                                        reconcileProcessLiveness(entry, procFile);
                                        if (entry.isRunningState()) {
                                            entry.setLastHeartbeat(Instant.now());
                                            writeJsonAtomically(procFile, entry);
                                        }
                                    }
                                } catch (IOException malformedProcess) {
                                    Path quarantined = quarantineCorruptRecord(procFile);
                                    notifyWarning("[Coordination] Heartbeat error: Could not read "
                                            + procFile + quarantineSuffix(quarantined) + ": "
                                            + malformedProcess.getMessage());
                                }
                            }
                            return null;
                        });
                    }
                }
            }
        } catch (Exception e) {
            // Heartbeat failures are non-fatal
            notifyWarning("[Coordination] Heartbeat error: " + e.getMessage());
        }
        systemActivityCoordinator.heartbeat();
    }

    private static boolean isRunningProcessState(String state) {
        if (state == null || state.isBlank()) return true;
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("RUNNING") || normalized.equals("STARTING")
                || normalized.equals("ACTIVE");
    }

    /** Replace state without ever exposing a truncated JSON document to another process. */
    private void writeJsonAtomically(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(
                target.getParent(), "." + target.getFileName() + ".", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), value);
            hardenPermissions(temp, OWNER_FILE_PERMISSIONS);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void notifyWarning(String message) {
        Consumer<String> sink = warningSink.get();
        if (sink != null) {
            try {
                sink.accept(message);
                return;
            } catch (RuntimeException ignored) {
                // A closing TUI must not hide coordination diagnostics.
            }
        }
        System.err.println(message);
    }

    private void hardenPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems use their platform access-control defaults.
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not restrict permissions on "
                    + path + ": " + e.getMessage());
        }
    }

    private static String requireSafeComponent(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > 160
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(fieldName
                    + " must contain only letters, digits, '.', '_', or '-' and be at most 160 characters");
        }
        return value;
    }

    // ── Internal: File Reading ─────────────────────────────────────────────

    private List<EditLockEntry> readEditLocks() {
        return readJsonFiles(editsDir, "*.lock.json", EditLockEntry.class);
    }

    private List<AgentEntry> readAgentEntries() {
        return readJsonFiles(agentsDir, "*.agent.json", AgentEntry.class);
    }

    private List<ProcessCoordEntry> readProcessEntries() {
        return readJsonFiles(processesDir, "*.proc.json", ProcessCoordEntry.class);
    }

    private <T> List<T> readJsonFiles(Path dir, String glob, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (!Files.exists(dir)) return result;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path file : stream) {
                try {
                    T entry = mapper.readValue(file.toFile(), type);
                    if (!hasRequiredIdentity(entry)) {
                        throw new IOException("record is missing a safe coordination identity");
                    }
                    result.add(entry);
                } catch (IOException e) {
                    Path quarantined = quarantineCorruptRecord(file);
                    notifyWarning("[Coordination] Warning: Could not read " + file
                            + quarantineSuffix(quarantined) + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not list " + dir + ": " + e.getMessage());
        }
        return result;
    }

    private Path processFile(String ownerSessionId, String processId) {
        return processesDir.resolve(
                requireSafeComponent(ownerSessionId, "ownerSessionId") + "-"
                        + requireSafeComponent(processId, "processId") + ".proc.json");
    }

    private boolean hasSafeProcessIdentity(ProcessCoordEntry entry) {
        if (entry == null) return false;
        try {
            requireSafeComponent(entry.getSessionId(), "ownerSessionId");
            requireSafeComponent(entry.getProcessId(), "processId");
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean hasRequiredIdentity(Object entry) {
        if (entry instanceof AgentEntry agent) {
            return isSafeComponent(agent.getSessionId())
                    && (agent.getToolSessionId() == null
                    || isSafeComponent(agent.getToolSessionId()))
                    && agent.getStartedAt() != null;
        }
        if (entry instanceof ProcessCoordEntry process) {
            return hasSafeProcessIdentity(process) && process.getStartedAt() != null;
        }
        if (entry instanceof EditLockEntry edit) {
            return isSafeComponent(edit.getLockId()) && isSafeComponent(edit.getSessionId());
        }
        return entry != null;
    }

    private static boolean isSafeComponent(String value) {
        try {
            requireSafeComponent(value, "component");
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void reconcileProcessLiveness(ProcessCoordEntry entry, Path procFile) throws IOException {
        if (entry == null || !entry.isRunningState() || entry.getPid() <= 0
                || isProcessAlive(entry.getPid())) {
            return;
        }
        Instant now = Instant.now();
        if (hasLiveOwnerPresence(entry.getSessionId())) {
            if (entry.getEndedAt() == null) {
                // Remember the first dead-PID observation without racing the
                // owner's exit callback. A later poll promotes it to LOST only
                // if the owner still has not published a terminal state.
                entry.setEndedAt(now);
                writeJsonAtomically(procFile, entry);
                return;
            }
            if (now.isBefore(entry.getEndedAt().plus(PROCESS_EXIT_RECONCILIATION_GRACE))) {
                return;
            }
        }
        entry.setState("LOST");
        entry.setEndedAt(now);
        entry.setExitCode(null);
        entry.setLastHeartbeat(now);
        entry.setTtlSeconds(DEFAULT_TERMINAL_PROCESS_TTL_SECONDS);
        writeJsonAtomically(procFile, entry);
    }

    private boolean hasLiveOwnerPresence(String ownerSessionId) {
        if (ownerSessionId == null || ownerSessionId.isBlank()) return false;
        Path agentFile = agentsDir.resolve(ownerSessionId + ".agent.json");
        if (!Files.isRegularFile(agentFile, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            AgentEntry owner = mapper.readValue(agentFile.toFile(), AgentEntry.class);
            return !owner.isStale() && isProcessAlive(owner.getPid());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private boolean shouldEvictProcess(ProcessCoordEntry entry) {
        if (entry == null || !entry.isStale()) return false;
        return !entry.isRunningState() || entry.getPid() <= 0 || !isProcessAlive(entry.getPid());
    }

    private static boolean isProcessAlive(long pid) {
        if (pid <= 0) return false;
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String resolvePublishedAgentName(String requested) {
        if (requested != null && !requested.isBlank()
                && !requested.equals(sessionId) && !"unknown".equalsIgnoreCase(requested)) {
            return requested;
        }
        Path agentFile = agentsDir.resolve(sessionId + ".agent.json");
        if (Files.isRegularFile(agentFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                AgentEntry owner = mapper.readValue(agentFile.toFile(), AgentEntry.class);
                if (owner.getAgentName() != null && !owner.getAgentName().isBlank()) {
                    return owner.getAgentName();
                }
            } catch (IOException ignored) {
                // The normal coordination read path reports/quarantines malformed presence.
            }
        }
        return "unknown";
    }

    private Path quarantineCorruptRecord(Path file) {
        if (file == null || !Files.exists(file)) return null;
        try {
            Files.createDirectories(corruptDir);
            hardenPermissions(corruptDir, OWNER_DIRECTORY_PERMISSIONS);
            String name = file.getFileName() + "." + System.currentTimeMillis()
                    + "-" + Long.toUnsignedString(System.nanoTime()) + ".corrupt";
            Path target = corruptDir.resolve(name);
            try {
                return Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                return Files.move(file, target);
            }
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String quarantineSuffix(Path quarantined) {
        return quarantined == null ? "" : " (quarantined as " + quarantined + ")";
    }

    // ── Internal: File Locking ─────────────────────────────────────────────

    /**
     * Execute a callable while holding the coordinator file lock.
     * The lock is held for the duration of the callable (should be milliseconds).
     * Ordinary coordination remains single-attempt and non-blocking. Lifecycle
     * publication can opt into a short bounded wait because permanently losing a
     * terminal process state is worse than delaying its background callback briefly.
     */
    private <T> T withCoordinatorLock(CoordinatedAction<T> action) throws Exception {
        return withCoordinatorLock(0L, action);
    }

    private <T> T withCoordinatorLock(long waitMillis, CoordinatedAction<T> action)
            throws Exception {
        Files.createDirectories(coordinationDir);

        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            hardenPermissions(lockFile, OWNER_FILE_PERMISSIONS);
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
            while (true) {
                FileLock fileLock = null;
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException ignored) {
                    // Another thread in this JVM owns the same project lock.
                }
                if (fileLock != null) {
                    try {
                        return action.execute();
                    } finally {
                        if (fileLock.isValid()) fileLock.release();
                    }
                }
                if (waitMillis <= 0L || System.nanoTime() >= deadline) {
                    throw new IOException("Coordinator lock is busy");
                }
                try {
                    Thread.sleep(Math.min(5L, Math.max(1L, waitMillis)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while waiting for coordinator lock", interrupted);
                }
            }
        }
    }

    @FunctionalInterface
    private interface CoordinatedAction<T> {
        T execute() throws Exception;
    }

}
