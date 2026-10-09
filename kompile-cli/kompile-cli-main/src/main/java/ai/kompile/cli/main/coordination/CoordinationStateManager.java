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
import ai.kompile.cli.main.chat.harness.HarnessConfig;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * File-based message bus and coordination state manager for multi-agent environments.
 *
 * <p>All state is persisted as individual JSON files under
 * {@code <projectRoot>/.kompile/coordination/}. Cross-process safety is achieved
 * via a {@link FileLock} on a sentinel file during mutations (held for
 * milliseconds only). Edit locks are advisory for callers without an agent identity —
 * they warn of conflicts but do not block writes; an edit made under an agent identity is
 * guarded ({@link #guardAgentEdit}) and is refused on a file another live owner holds.
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
    /** {@link #requireSafeComponent} bound; lock ids double as file-name components. */
    private static final int MAX_COMPONENT_LENGTH = 160;
    /** Longest sanitized agent-name component embedded in an agent-scoped lock id. */
    private static final int MAX_OWNER_AGENT_LENGTH = 64;
    /**
     * Bounded wait for every edit-lock operation that must not silently fail on a busy
     * coordinator: owner-checked releases, agent-scoped acquires, the agent edit guard and
     * lease renewal.
     * A caller that cannot get the coordinator lock within it gets an explicit busy outcome.
     */
    private static final long EDIT_LOCK_WAIT_MS = 500L;
    /** Bounded wait for releasing an ended agent's locks before the unlocked fallback. */
    private static final long AGENT_RELEASE_LOCK_WAIT_MS = 2_000L;
    /**
     * Shortest agent lease: two heartbeat intervals, so an agent pausing between turns (a
     * long model response, a build) does not lose its locks to a too-aggressive setting.
     */
    private static final Duration MIN_AGENT_LEASE = Duration.ofSeconds(2L * HEARTBEAT_INTERVAL_SECONDS);
    /** Upper bound of the lease-renewal write throttle (see {@link #renewAgentLease}). */
    private static final Duration MAX_LEASE_RENEW_SLACK = Duration.ofSeconds(15);
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
    /** Parent session of the registered agent, stamped on every process it publishes. */
    private volatile String registeredParentSessionId;
    /** Lease of an agent-owned edit lock (from {@link HarnessConfig#getEditLockLeaseSeconds()}). */
    private final Duration agentLease;
    /**
     * Renewal write throttle. Every persisted lease end is {@code activity + lease + slack},
     * and a lock is rewritten only when its persisted end (read from disk inside the
     * coordinator critical section) is less than {@code now + lease}; so the lease never ends
     * earlier than {@code lease} after the agent's last activity, while a busy agent writes
     * its lock files at most once per slack.
     */
    private final Duration leaseRenewSlack;
    /** Time source for agent leases; the system UTC clock outside tests. */
    private final Clock leaseClock;
    /**
     * Test seam: run once per bounded coordinator-lock wait, when the first attempt finds the
     * lock busy and the caller starts waiting. Lets tests synchronise on "the caller is now
     * blocked on the coordinator" with a latch instead of sleeping. Null in production.
     */
    private volatile Runnable coordinatorBusyObserver;
    /**
     * The bounded coordinator wait of edit-lock operations; {@link #EDIT_LOCK_WAIT_MS} outside
     * tests. Tests that hold the coordinator while asserting on blocked callers raise it so
     * the callers cannot time out before the test lets them go.
     */
    private volatile long editLockWaitMillis = EDIT_LOCK_WAIT_MS;

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
        this(workDir, sessionId, mapper, systemActivityStateRoot, configuredAgentLease(),
                Clock.systemUTC());
    }

    /**
     * Create a manager with an explicit agent edit-lock lease and lease clock. Production
     * callers take the lease from {@code ~/.kompile/harness-config.json}
     * ({@code editLockLeaseSeconds}); tests pass a controllable clock.
     *
     * @param agentLease lease of an agent-owned edit lock; null means the configured default,
     *                   and anything shorter than 60 seconds is raised to 60 seconds
     * @param leaseClock time source for agent leases only (session heartbeats use wall time)
     */
    public CoordinationStateManager(Path workDir, String sessionId, ObjectMapper mapper,
                                    Path systemActivityStateRoot, Duration agentLease,
                                    Clock leaseClock) {
        Duration lease = agentLease == null ? configuredAgentLease() : agentLease;
        this.agentLease = lease.compareTo(MIN_AGENT_LEASE) < 0 ? MIN_AGENT_LEASE : lease;
        Duration quarter = this.agentLease.dividedBy(4);
        this.leaseRenewSlack = quarter.compareTo(MAX_LEASE_RENEW_SLACK) < 0
                ? quarter : MAX_LEASE_RENEW_SLACK;
        this.leaseClock = Objects.requireNonNull(leaseClock, "leaseClock");
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

    /** The edit-lock lease length from the coordinator's JSON config (harness-config.json). */
    private static Duration configuredAgentLease() {
        return Duration.ofSeconds(HarnessConfig.load().getEditLockLeaseSeconds());
    }

    public String getSessionId() {
        return sessionId;
    }

    /** Effective lease of an agent-owned edit lock. */
    public Duration getAgentLease() {
        return agentLease;
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
    //
    // Ownership contract. A lock is owned by the pair (sessionId, ownerAgent):
    //  * ownerAgent == null  -> session-level lock (no agent_name given). Every caller in
    //    the same session shares it: re-acquire succeeds and any caller in the session may
    //    release it. This is the historical behaviour and what pre-ownership lock files
    //    deserialize to. Lock id: <sessionId>-<fileHash>.
    //  * ownerAgent != null  -> agent-scoped lock (agent_name given, sanitized by
    //    ownerAgentKey). Subagents that share ONE MCP connection share the sessionId, so
    //    the agent name is what tells them apart: a live lock held by the same session
    //    under a DIFFERENT agent is a conflict, the same agent re-acquires idempotently,
    //    and only that agent may release it. Lock id: <sessionId>-<ownerAgent>-<fileHash>.
    //  * A live lock held by another session always conflicts, whatever the agent names.
    //  * A session-level lock and an agent-scoped lock of the same session never conflict
    //    with each other: a caller without agent_name has no identity to arbitrate on.
    //  Stale and provably-dead-holder locks never conflict; they are evicted.
    //
    // Liveness contract. A lock is stale when EITHER clock runs out:
    //  * Session liveness: lastHeartbeat + TTL (60s), refreshed by the owning session's
    //    heartbeat while the session runs. Another session's lock whose holder is provably
    //    dead (stale presence or dead PID) is evicted as well.
    //  * Agent liveness (agent-scoped locks only): leaseExpiresAt, a per-(session, agent)
    //    lease of HarnessConfig.editLockLeaseSeconds (default 600s). It is moved forward ONLY
    //    by that agent's own activity — any edit_coordinator call, edit-tool call or register
    //    made under its agent name (renewAgentLease, guardAgentEdit) and every acquire — never by the session
    //    heartbeat. So a lock a sibling agent forgot expires one lease after its last
    //    activity even though the session is alive, and is then reclaimed through the same
    //    stale-cleanup path as a dead holder's lock. A lock renewed within its lease is never
    //    stale through the lease: every persisted end is >= last activity + lease.
    //  * Renewal is SYNCHRONOUS with the activity that triggers it; there is no deferred or
    //    retried renewal. Renewal and reclaim both run inside the coordinator critical
    //    section and read the lease clock and the lock files inside it, so they are
    //    serialized: a renewal that commits first always wins (the reclaimer then sees the
    //    renewed lease), and a reclaim that commits first is seen by the renewal.
    //  * An edit-tool call under an agent identity runs guardAgentEdit BEFORE it mutates a
    //    file: one critical section that renews the agent's leases AND re-verifies that no
    //    other live owner holds the target file. A busy coordinator is waited for
    //    (EDIT_LOCK_WAIT_MS); on timeout the edit fails and nothing is written. A file whose
    //    lock was lost to another owner (lease expired, then reclaimed) fails with CONFLICT
    //    naming the new holder. After the guard commits, the agent's locks are good for at
    //    least one full lease, so the immediately following write never lands on a file
    //    whose lock the agent no longer holds.
    //  * An in-process subagent's locks are released outright when its run ends
    //    (releaseAgentLocks, called by the subagent runners on completion, failure or cancel).
    //  Session-level locks carry no lease.

    /**
     * Lock-ownership key for an agent name, or null for session-level ownership.
     *
     * <p>Blank names and the historical {@code "unknown"} placeholder mean "no agent
     * identity". Any other name is trimmed and reduced to the coordinator's safe
     * component alphabet ({@code [A-Za-z0-9._-]}); when that changes the name or it is
     * longer than 64 characters, a hash of the original
     * name is appended so distinct names never collapse onto one owner.
     */
    public static String ownerAgentKey(String agentName) {
        if (agentName == null) return null;
        String trimmed = agentName.trim();
        if (trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed)) return null;
        String safe = trimmed.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.equals(trimmed) || safe.length() > MAX_OWNER_AGENT_LENGTH) {
            String hash = Integer.toHexString(trimmed.hashCode());
            int keep = MAX_OWNER_AGENT_LENGTH - hash.length() - 1;
            if (safe.length() > keep) safe = safe.substring(0, keep);
            safe = safe + "-" + hash;
        }
        return requireSafeComponent(safe, "agentName");
    }

    /**
     * Attempt to acquire a session-level advisory edit lock on a file.
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
     * @param agentName optional agent name; when present it both labels the lock and scopes
     *                  its ownership to that agent within this session (see the ownership
     *                  contract above); when absent the lock is session-level
     * @return result indicating whether the lock was acquired or a conflict exists
     */
    public EditLockResult tryAcquireEditLock(String filePath, String editType, String agentName) {
        return tryAcquireEditLock(filePath, editType, agentName, agentName);
    }

    /**
     * Attempt to acquire an advisory edit lock with an ownership identity distinct from
     * its display label (for example an internal auto-locker acting for a known caller).
     *
     * @param ownerAgentName agent identity that owns the lock, or null for session-level
     * @param displayName    label shown to other agents; defaults to the owner name
     */
    public EditLockResult tryAcquireEditLock(String filePath, String editType,
                                             String ownerAgentName, String displayName) {
        String owner = ownerAgentKey(ownerAgentName);
        String label = displayLabel(displayName, ownerAgentName);
        String lockId = lockIdFor(owner, filePath);

        try {
            // Staleness is judged inside the critical section, so a lease renewal that
            // committed first is always seen here. An agent-scoped acquire (it renews and
            // reclaims leases) waits a bounded time for a busy coordinator; a session-level
            // acquire stays single-attempt and fails open, so chat never blocks on it.
            return withCoordinatorLock(acquireWaitMillis(owner), () -> {
                Instant now = leaseClock.instant();
                boolean alreadyHeld = false;
                for (EditLockEntry entry : readEditLocks()) {
                    if (!filePath.equals(entry.getAbsolutePath())) continue;
                    if (!heldByAnotherOwner(entry, owner)) {
                        alreadyHeld |= lockId.equals(entry.getLockId());
                        continue;
                    }
                    if (!isStale(entry, now) && !holderIsDead(entry)) {
                        // A refused register is still the agent's activity.
                        if (owner != null) extendLeases(owner, now, Set.of());
                        return EditLockResult.conflict(entry, conflictMessage(filePath, entry));
                    }
                    // TTL- or lease-expired, or provably dead holder: clear it instead of conflict.
                    Files.deleteIfExists(lockPath(entry.getLockId()));
                }

                writeJsonAtomically(lockPath(lockId),
                        newLockEntry(lockId, owner, label, filePath, editType, now));
                ownedLockIds.add(lockId);
                // Acquiring is agent activity: it renews the lease of the agent's other locks.
                if (owner != null) extendLeases(owner, now, Set.of());
                return EditLockResult.acquired(lockId, alreadyHeld);
            });
        } catch (Exception e) {
            if (owner != null) {
                // An agent-scoped acquire never fakes success: the caller would edit under a
                // lock (and a lease) that was neither taken nor renewed.
                notifyWarning("[Coordination] Warning: Could not acquire edit lock for agent '"
                        + owner + "': " + e.getMessage());
                return EditLockResult.busy(busyMessage(owner, "acquire the edit lock on " + filePath,
                        e, "nothing was acquired or renewed"));
            }
            // Session-level locks keep failing open: don't block the edit, warn instead.
            notifyWarning("[Coordination] Warning: Could not acquire edit lock: " + e.getMessage());
            return EditLockResult.acquired(lockId);
        }
    }

    /** Coordinator wait of an acquire: bounded for an agent owner, single-attempt otherwise. */
    private long acquireWaitMillis(String owner) {
        return owner == null ? 0L : editLockWaitMillis;
    }

    /** Explanation of a busy-coordinator failure under an agent identity. */
    private String busyMessage(String owner, String what, Exception cause, String consequence) {
        return "Coordinator busy: could not " + what + " for agent '" + owner + "' within "
                + editLockWaitMillis + " ms (" + cause.getMessage() + "); " + consequence + " — retry.";
    }

    /**
     * Administrative release of an advisory edit lock by id, WITHOUT an ownership check.
     * Used by this session's own lifecycle (shutdown, internal auto-lockers releasing
     * locks they just created) and by the {@code kompile edit-coordinator release} CLI
     * escape hatch. Agent-facing release goes through {@link #releaseEditLock(String, String)}.
     *
     * @param lockId the lock ID returned by {@link #tryAcquireEditLock}
     * @return true if the lock was found and released
     */
    public boolean releaseEditLock(String lockId) {
        if (lockId == null || !isSafeComponent(lockId)) return false;
        try {
            boolean deleted = Files.deleteIfExists(lockPath(lockId));
            ownedLockIds.remove(lockId);
            return deleted;
        } catch (IOException e) {
            notifyWarning("[Coordination] Warning: Could not release edit lock: " + e.getMessage());
            return false;
        }
    }

    /** Outcome of an owner-checked {@link #releaseEditLock(String, String)}. */
    public enum ReleaseStatus {
        RELEASED,
        /** No such lock (already released, expired and evicted, or never existed). */
        NOT_FOUND,
        /** The lock is live and owned by another agent or another session; left in place. */
        NOT_OWNER,
        /** The coordinator could not be consulted; the lock was left in place. */
        FAILED
    }

    /**
     * Per-lock outcome of an owner-checked release.
     *
     * @param holder  the lock entry as found (null when absent or unreadable)
     * @param message human-readable explanation, naming the holder on NOT_OWNER
     */
    public record ReleaseResult(String lockId, ReleaseStatus status,
                                EditLockEntry holder, String message) {
        public boolean released() {
            return status == ReleaseStatus.RELEASED;
        }
    }

    /**
     * Owner-checked release used by the {@code edit_coordinator} tool.
     *
     * <p>A live lock may be released only by its owner: the same session and, for an
     * agent-scoped lock, the same agent name (sanitized with {@link #ownerAgentKey}).
     * Session-level locks may be released by any caller in the owning session, as before.
     * Stale, dead-holder and unreadable lock files are released by anyone — they are
     * evicted on the next coordination read anyway. An agent can therefore no longer drop
     * a sibling subagent's lock in the same MCP session by lock id.
     *
     * @param agentName the releasing agent's name, or null for a caller without identity
     */
    public ReleaseResult releaseEditLock(String lockId, String agentName) {
        if (lockId == null || !isSafeComponent(lockId)) {
            return new ReleaseResult(lockId, ReleaseStatus.NOT_FOUND, null,
                    "Lock " + lockId + " not found (not a valid lock id)");
        }
        String owner = ownerAgentKey(agentName);
        try {
            return withCoordinatorLock(editLockWaitMillis, () -> {
                Path lockPath = lockPath(lockId);
                if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                    ownedLockIds.remove(lockId);
                    return new ReleaseResult(lockId, ReleaseStatus.NOT_FOUND, null,
                            "Lock " + lockId + " not found (may have already expired)");
                }
                EditLockEntry holder = readLockQuietly(lockPath);
                if (holder != null && !mayRelease(holder, owner)) {
                    return new ReleaseResult(lockId, ReleaseStatus.NOT_OWNER, holder,
                            notOwnerMessage(lockId, holder, owner));
                }
                boolean deleted = Files.deleteIfExists(lockPath);
                ownedLockIds.remove(lockId);
                return deleted
                        ? new ReleaseResult(lockId, ReleaseStatus.RELEASED, holder,
                                "Edit lock " + lockId + " released")
                        : new ReleaseResult(lockId, ReleaseStatus.NOT_FOUND, holder,
                                "Lock " + lockId + " not found (may have already expired)");
            });
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not release edit lock: " + e.getMessage());
            return new ReleaseResult(lockId, ReleaseStatus.FAILED, null,
                    "Could not release lock " + lockId + ": " + e.getMessage() + " (retry)");
        }
    }

    /** Owner-checked batch release; per-lock outcomes in input order (duplicates collapse). */
    public Map<String, ReleaseResult> releaseEditLocks(List<String> lockIds, String agentName) {
        Map<String, ReleaseResult> results = new LinkedHashMap<>();
        for (String lockId : new LinkedHashSet<>(lockIds)) {
            results.put(lockId, releaseEditLock(lockId, agentName));
        }
        return results;
    }

    /** Release permission for a caller identified by (this session, {@code owner}). */
    private boolean mayRelease(EditLockEntry holder, String owner) {
        if (holder.getSessionId() == null || holder.getLastHeartbeat() == null) return true;
        if (isStale(holder) || holderIsDead(holder)) return true;
        if (!sessionId.equals(holder.getSessionId())) return false;
        return holder.getOwnerAgent() == null || holder.getOwnerAgent().equals(owner);
    }

    private String notOwnerMessage(String lockId, EditLockEntry holder, String owner) {
        String caller = owner == null ? "a caller without agent_name" : "agent '" + owner + "'";
        if (!sessionId.equals(holder.getSessionId())) {
            return "Lock " + lockId + " on " + holder.getAbsolutePath() + " is held by "
                    + holder.getAgentName() + " (session " + holder.getSessionId()
                    + "), not by this session; only its owner can release it. Coordinate with "
                    + "that agent, wait for it to expire, or force it with "
                    + "`kompile edit-coordinator release --lock-id " + lockId + "`.";
        }
        return "Lock " + lockId + " on " + holder.getAbsolutePath() + " is owned by agent '"
                + holder.getOwnerAgent() + "' (session " + holder.getSessionId() + "), not by "
                + caller + "; an agent cannot release another agent's lock. Only the owning "
                + "agent can release it, using the same agent_name it registered with. "
                + "Coordinate with that agent, or force it with "
                + "`kompile edit-coordinator release --lock-id " + lockId + "`.";
    }

    private EditLockEntry readLockQuietly(Path lockPath) {
        try {
            return mapper.readValue(lockPath.toFile(), EditLockEntry.class);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * True when {@code entry} belongs to an owner other than (this session, {@code owner}).
     * Another session always differs. Within this session only two agent-scoped owners can
     * differ; a session-level lock or a caller without agent identity shares the session.
     */
    private boolean heldByAnotherOwner(EditLockEntry entry, String owner) {
        if (!sessionId.equals(entry.getSessionId())) return true;
        String holder = entry.getOwnerAgent();
        return owner != null && holder != null && !owner.equals(holder);
    }

    private static String conflictMessage(String path, EditLockEntry holder) {
        return path + " is actively being edited by " + holder.getAgentName()
                + " (session " + holder.getSessionId() + ")";
    }

    private String lockIdFor(String owner, String path) {
        String fileHash = Integer.toHexString(path.hashCode());
        if (owner == null) return fitLockId("-" + fileHash);
        String id = sessionId + "-" + owner + "-" + fileHash;
        if (id.length() <= MAX_COMPONENT_LENGTH) return id;
        // Keep long session/agent combinations inside the safe-component bound.
        return fitLockId("-a" + Integer.toHexString(owner.hashCode()) + "-" + fileHash);
    }

    /**
     * {@code sessionId + suffix}, shortened to the safe-component bound when needed: an
     * over-long session id is truncated and tagged with a hash of the FULL session id, so
     * the result stays readable by {@link #readJsonFiles} and distinct per session.
     */
    private String fitLockId(String suffix) {
        if (sessionId.length() + suffix.length() <= MAX_COMPONENT_LENGTH) return sessionId + suffix;
        String sessionTag = "-s" + Integer.toHexString(sessionId.hashCode());
        int keep = MAX_COMPONENT_LENGTH - suffix.length() - sessionTag.length();
        return sessionId.substring(0, keep) + sessionTag + suffix;
    }

    private static String displayLabel(String displayName, String ownerAgentName) {
        if (displayName != null && !displayName.isBlank()) return displayName.trim();
        if (ownerAgentName != null && !ownerAgentName.isBlank()) return ownerAgentName.trim();
        return "unknown";
    }

    /**
     * A fresh lock entry. An agent-scoped lock starts with a full lease from
     * {@code activity}; a session-level lock has none.
     */
    private EditLockEntry newLockEntry(String lockId, String owner, String label,
                                       String path, String editType, Instant activity) {
        EditLockEntry entry = new EditLockEntry(lockId, sessionId, owner, label, path, path,
                editType, Instant.now(), DEFAULT_EDIT_TTL_SECONDS);
        if (owner != null) entry.setLeaseExpiresAt(leaseEnd(activity));
        return entry;
    }

    /** Persisted lease end for activity at {@code activity}; see {@link #leaseRenewSlack}. */
    private Instant leaseEnd(Instant activity) {
        return activity.plus(agentLease).plus(leaseRenewSlack);
    }

    /**
     * Edit-lock staleness: the holder session's heartbeat TTL (wall time) or the holder
     * agent's lease (lease clock) has run out. The single staleness test of every edit-lock
     * path — acquire, batch acquire, conflict probe, query, release check and eviction.
     */
    private boolean isStale(EditLockEntry entry) {
        return isStale(entry, leaseClock.instant());
    }

    /**
     * {@link #isStale(EditLockEntry)} against a lease-clock reading the caller took inside
     * its coordinator critical section.
     */
    private boolean isStale(EditLockEntry entry, Instant leaseNow) {
        return entry.isHeartbeatExpired(Instant.now()) || entry.isLeaseExpired(leaseNow);
    }

    /**
     * Renew the lease of every edit lock {@code agentName} holds in this session, as one
     * unit of agent activity, synchronously: the call holds the coordinator lock (waiting up
     * to {@link #EDIT_LOCK_WAIT_MS} for it) and reads the lease clock inside that critical
     * section, so it is serialized with every reclaim. Used for edit_coordinator calls that
     * are not themselves acquires; edit tools use {@link #guardAgentEdit}, which also
     * re-verifies ownership. A no-op for a caller without agent identity.
     *
     * <p>Renewal never shortens a lease and is throttled from disk: a lock is rewritten only
     * when its persisted lease end is less than one lease ahead of now (so at most once per
     * slack of 15 seconds or a quarter lease).
     *
     * @return false when the coordinator stayed busy for the whole wait: nothing was renewed,
     *         and the caller must report that rather than proceed as if it had been
     */
    public boolean renewAgentLease(String agentName) {
        String owner = ownerAgentKey(agentName);
        if (owner == null || shutdown) return true;
        try {
            withCoordinatorLock(editLockWaitMillis, () -> {
                extendLeases(owner, leaseClock.instant(), Set.of());
                return null;
            });
            return true;
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not renew the edit-lock lease of agent '"
                    + owner + "': " + e.getMessage());
            return false;
        }
    }

    /** Outcome kind of {@link #guardAgentEdit}. */
    public enum EditGuardStatus {
        /** Leases renewed, no other live owner holds any target file: the edit may proceed. */
        ALLOWED,
        /** Leases renewed, but at least one target file is held by another live owner. */
        CONFLICT,
        /** The coordinator stayed busy for the whole bounded wait; nothing was renewed. */
        BUSY
    }

    /**
     * Result of {@link #guardAgentEdit}.
     *
     * @param conflicts   target path -> the other live owner's lock, for CONFLICT paths
     * @param lost        CONFLICT paths this agent had locked whose lock was reclaimed by the
     *                    holder in {@code conflicts} after its lease expired
     * @param busyMessage explanation for BUSY, else null
     */
    public record AgentEditGuard(EditGuardStatus status, Map<String, EditLockEntry> conflicts,
                                 Set<String> lost, String busyMessage) {

        static AgentEditGuard allowed() {
            return new AgentEditGuard(EditGuardStatus.ALLOWED, Map.of(), Set.of(), null);
        }

        static AgentEditGuard busy(String message) {
            return new AgentEditGuard(EditGuardStatus.BUSY, Map.of(), Set.of(), message);
        }

        public boolean isBusy() {
            return status == EditGuardStatus.BUSY;
        }

        /** The other owner's live lock on {@code path}, or null when the edit may write it. */
        public EditLockEntry conflictFor(String path) {
            return conflicts.get(path);
        }

        /**
         * The CONFLICT explanation for {@code path}, naming the holder; null when
         * {@code path} is not in conflict. Always contains "locked by &lt;holder&gt;".
         */
        public String conflictMessage(String path) {
            EditLockEntry holder = conflicts.get(path);
            if (holder == null) return null;
            String head = "CONFLICT: " + path + " is locked by " + holder.getAgentName()
                    + " (session " + holder.getSessionId() + ", lock_id " + holder.getLockId()
                    + ") via edit_coordinator";
            if (lost.contains(path)) {
                return head + " — your lock on it expired (no activity for a full lease) and "
                        + holder.getAgentName() + " reclaimed it; nothing was written. Coordinate "
                        + "with that agent and register the file again once it is free.";
            }
            return head + "; nothing was written. Wait, coordinate, or register the file with "
                    + "register_edits once it is free.";
        }
    }

    /**
     * Pre-mutation guard of an edit-tool call (edit, write, edit_batch, edit_patch) made under
     * an agent identity. In ONE coordinator critical section — waiting up to
     * {@link #EDIT_LOCK_WAIT_MS} for a busy coordinator — it reads the lease clock and the
     * lock files, renews every lease of (this session, {@code agentName}), and re-verifies
     * each target file:
     * <ul>
     *   <li>held by another live owner (another session, or a sibling agent of this session)
     *       -> CONFLICT naming that holder; if this agent had held it, the loss is reported
     *       (its lease expired and the holder reclaimed it);</li>
     *   <li>this agent's own lock, even lease-expired but not yet evicted -> renewed in place
     *       (a renewal that commits before any reclaim wins);</li>
     *   <li>this agent's lock was evicted but nobody holds the file -> the lock is
     *       re-established for the agent, who is demonstrably active again.</li>
     * </ul>
     * After an ALLOWED or CONFLICT guard every lock of the agent has at least one full lease
     * left, so the caller's immediately following write cannot be overtaken by a reclaim.
     * On BUSY nothing was renewed or verified and the caller must not write.
     *
     * @param absolutePaths the files the call is about to mutate
     * @return ALLOWED for a caller without agent identity (session semantics; no lease)
     */
    public AgentEditGuard guardAgentEdit(String agentName, Collection<String> absolutePaths) {
        String owner = ownerAgentKey(agentName);
        if (owner == null) return AgentEditGuard.allowed();
        Set<String> paths = new LinkedHashSet<>(absolutePaths);
        try {
            return withCoordinatorLock(editLockWaitMillis, () -> {
                Instant now = leaseClock.instant();
                List<EditLockEntry> locks = readEditLocks();
                Map<String, EditLockEntry> conflicts = new LinkedHashMap<>();
                Set<String> lost = new LinkedHashSet<>();
                for (String path : paths) {
                    String lockId = lockIdFor(owner, path);
                    boolean held = false;
                    EditLockEntry conflict = null;
                    for (EditLockEntry entry : locks) {
                        if (!path.equals(entry.getAbsolutePath())) continue;
                        if (lockId.equals(entry.getLockId())) {
                            held = true;
                        } else if (conflict == null && heldByAnotherOwner(entry, owner)
                                && !isStale(entry, now) && !holderIsDead(entry)) {
                            conflict = entry;
                        }
                    }
                    boolean ownedHere = ownedLockIds.contains(lockId);
                    if (conflict != null) {
                        conflicts.put(path, conflict);
                        if (!held && ownedHere) lost.add(path);
                    } else if (!held && ownedHere) {
                        // Evicted (lease ran out) but never reclaimed: the active agent keeps it.
                        writeJsonAtomically(lockPath(lockId),
                                newLockEntry(lockId, owner, owner, path, "edit", now));
                    }
                }
                extendLeases(owner, now, paths);
                return conflicts.isEmpty()
                        ? AgentEditGuard.allowed()
                        : new AgentEditGuard(EditGuardStatus.CONFLICT,
                                Collections.unmodifiableMap(conflicts),
                                Collections.unmodifiableSet(lost), null);
            });
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not guard an edit of agent '" + owner
                    + "': " + e.getMessage());
            return AgentEditGuard.busy(busyMessage(owner,
                    "renew the edit-lock lease and verify lock ownership", e, "nothing was written"));
        }
    }

    /**
     * Renew every lock of (this session, {@code owner}) whose persisted lease end is less
     * than one lease ahead of {@code activity}, to {@code activity + lease + slack}; also
     * refresh the session heartbeat of the owner's locks on {@code activePaths} when it is
     * older than one heartbeat interval, so a lock about to be written through cannot go
     * TTL-stale under the write either. Caller holds the coordinator lock and read
     * {@code activity} inside it. Expired-but-not-yet-evicted locks are renewed too: the
     * owner is demonstrably active again.
     */
    private void extendLeases(String owner, Instant activity, Collection<String> activePaths)
            throws IOException {
        Instant renewBelow = activity.plus(agentLease);
        Instant until = leaseEnd(activity);
        Instant wallNow = Instant.now();
        Instant heartbeatBelow = wallNow.minusSeconds(HEARTBEAT_INTERVAL_SECONDS);
        for (EditLockEntry entry : readEditLocks()) {
            if (!sessionId.equals(entry.getSessionId()) || !owner.equals(entry.getOwnerAgent())) continue;
            boolean renewLease = entry.getLeaseExpiresAt() == null
                    || entry.getLeaseExpiresAt().isBefore(renewBelow);
            boolean refreshHeartbeat = activePaths.contains(entry.getAbsolutePath())
                    && (entry.getLastHeartbeat() == null
                    || entry.getLastHeartbeat().isBefore(heartbeatBelow));
            if (!renewLease && !refreshHeartbeat) continue;
            if (renewLease) entry.setLeaseExpiresAt(until);
            if (refreshHeartbeat) entry.setLastHeartbeat(wallNow);
            writeJsonAtomically(lockPath(entry.getLockId()), entry);
        }
    }

    /**
     * Release every edit lock {@code agentName} holds in this session, at once, whatever its
     * lease. Called by the in-process subagent runners when a subagent's run ends (completion,
     * failure or cancel) so its files are free immediately rather than one lease later.
     * A no-op for a caller without agent identity: session-level locks are untouched.
     *
     * @return number of lock files removed
     */
    public int releaseAgentLocks(String agentName) {
        String owner = ownerAgentKey(agentName);
        if (owner == null) return 0;
        try {
            return withCoordinatorLock(AGENT_RELEASE_LOCK_WAIT_MS, () -> deleteAgentLocks(owner));
        } catch (Exception e) {
            // The agent has ended, so nothing renews these locks. Delete them without the
            // coordinator lock (as the administrative release does); having dropped them from
            // ownedLockIds first, no heartbeat of this session refreshes them again.
            notifyWarning("[Coordination] Warning: Coordinator busy while releasing the edit locks of "
                    + "agent '" + owner + "'; releasing them directly: " + e.getMessage());
            try {
                return deleteAgentLocks(owner);
            } catch (IOException failure) {
                notifyWarning("[Coordination] Warning: Could not release the edit locks of agent '"
                        + owner + "' (they expire with their lease): " + failure.getMessage());
                return 0;
            }
        }
    }

    private int deleteAgentLocks(String owner) throws IOException {
        int released = 0;
        for (EditLockEntry entry : readEditLocks()) {
            if (!sessionId.equals(entry.getSessionId()) || !owner.equals(entry.getOwnerAgent())) continue;
            ownedLockIds.remove(entry.getLockId());
            if (Files.deleteIfExists(lockPath(entry.getLockId()))) released++;
        }
        return released;
    }

    private Path lockPath(String lockId) {
        return editsDir.resolve(lockId + ".lock.json");
    }

    /**
     * Attempt to acquire advisory edit locks on several files in ONE coordinator
     * pass (one file-lock round instead of N sequential tool calls).
     *
     * <p>All-or-nothing by default: when any file is locked by another live
     * owner (another session, or another agent of this session — see the ownership
     * contract above), nothing is acquired — conflicting files report CONFLICT and the
     * rest report SKIPPED. With {@code allowPartial=true} the non-conflicting
     * files are locked anyway.
     *
     * @param filePaths absolute paths (duplicates collapse to one lock)
     * @param agentName optional agent name; labels the locks and scopes their ownership
     * @return per-path results in input order
     */
    public BatchAcquireResult tryAcquireEditLocks(List<String> filePaths, String editType,
                                                  String agentName, boolean allowPartial) {
        return tryAcquireEditLocks(filePaths, editType, agentName, agentName, allowPartial);
    }

    /**
     * Batch acquire with an ownership identity distinct from the display label.
     *
     * @param ownerAgentName agent identity that owns the locks, or null for session-level
     * @param displayName    label shown to other agents; defaults to the owner name
     */
    public BatchAcquireResult tryAcquireEditLocks(List<String> filePaths, String editType,
                                                  String ownerAgentName, String displayName,
                                                  boolean allowPartial) {
        LinkedHashSet<String> paths = new LinkedHashSet<>(filePaths);
        String owner = ownerAgentKey(ownerAgentName);
        String label = displayLabel(displayName, ownerAgentName);
        try {
            // In-section staleness and the same wait policy as the single acquire.
            return withCoordinatorLock(acquireWaitMillis(owner), () -> {
                Instant now = leaseClock.instant();
                List<EditLockEntry> existing = readEditLocks();
                Map<String, EditLockResult> results = new LinkedHashMap<>();
                Map<String, EditLockEntry> conflicts = new LinkedHashMap<>();
                Set<String> alreadyHeld = new HashSet<>();
                for (String path : paths) {
                    String lockId = lockIdFor(owner, path);
                    for (EditLockEntry entry : existing) {
                        if (!path.equals(entry.getAbsolutePath())) continue;
                        if (!heldByAnotherOwner(entry, owner)) {
                            if (lockId.equals(entry.getLockId())) alreadyHeld.add(path);
                            continue;
                        }
                        if (!isStale(entry, now) && !holderIsDead(entry)) {
                            conflicts.put(path, entry);
                            break;
                        }
                        Files.deleteIfExists(lockPath(entry.getLockId()));
                    }
                }

                boolean aborted = !conflicts.isEmpty() && !allowPartial;
                for (String path : paths) {
                    EditLockEntry conflict = conflicts.get(path);
                    if (conflict != null) {
                        results.put(path, EditLockResult.conflict(conflict,
                                conflictMessage(path, conflict)));
                    } else if (aborted) {
                        results.put(path, EditLockResult.skipped(
                                "not acquired — batch aborted because other files conflict "
                                        + "(pass allow_partial=true to lock what is free)"));
                    } else {
                        String lockId = lockIdFor(owner, path);
                        writeJsonAtomically(lockPath(lockId),
                                newLockEntry(lockId, owner, label, path, editType, now));
                        ownedLockIds.add(lockId);
                        results.put(path, EditLockResult.acquired(lockId, alreadyHeld.contains(path)));
                    }
                }
                // A register is agent activity even when it aborts: renew the agent's leases.
                if (owner != null) extendLeases(owner, now, Set.of());
                return new BatchAcquireResult(results, aborted);
            });
        } catch (Exception e) {
            Map<String, EditLockResult> results = new LinkedHashMap<>();
            if (owner != null) {
                // Agent-scoped: never report locks (or a lease renewal) that did not happen.
                notifyWarning("[Coordination] Warning: Could not batch-acquire edit locks for agent '"
                        + owner + "': " + e.getMessage());
                String message = busyMessage(owner, "acquire the edit locks", e,
                        "nothing was acquired or renewed");
                for (String path : paths) {
                    results.put(path, EditLockResult.busy(message));
                }
                return new BatchAcquireResult(results, true);
            }
            // Session-level: coordination failure never blocks edits — report all paths
            // acquired with deterministic lock ids, matching tryAcquireEditLock.
            notifyWarning("[Coordination] Warning: Could not batch-acquire edit locks: " + e.getMessage());
            for (String path : paths) {
                results.put(path, EditLockResult.acquired(lockIdFor(owner, path)));
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
        /** True when the coordinator stayed busy and nothing was acquired (agent-scoped only). */
        public boolean busy() {
            return results.values().stream().anyMatch(EditLockResult::isBusy);
        }
    }

    /**
     * Administrative release of several advisory edit locks in one call, WITHOUT an
     * ownership check (see {@link #releaseEditLock(String)}); agent-facing callers use
     * {@link #releaseEditLocks(List, String)}.
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
     * Read-only conflict probe for a caller without agent identity: the first non-stale
     * edit lock on {@code absolutePath} held by ANOTHER session, or null.
     */
    public EditLockEntry findConflictingLock(String absolutePath) {
        return findConflictingLock(absolutePath, null);
    }

    /**
     * Read-only conflict probe: the first non-stale edit lock on {@code absolutePath}
     * held by another owner than (this session, {@code agentName}), or null. With a null
     * agent name only other sessions conflict (session semantics); with an agent name a
     * sibling agent's lock in this session conflicts too. Reads without the coordinator
     * file lock — an advisory report for callers without agent identity and for display;
     * it never reclaims a stale lock, and its dead-holder eviction takes the lock. Edits
     * under an agent identity use {@link #guardAgentEdit} instead.
     */
    public EditLockEntry findConflictingLock(String absolutePath, String agentName) {
        String owner = ownerAgentKey(agentName);
        for (EditLockEntry entry : readEditLocks()) {
            if (absolutePath.equals(entry.getAbsolutePath())
                    && heldByAnotherOwner(entry, owner)
                    && !isStale(entry)) {
                if (holderIsDead(entry)) {
                    evictDeadHolderLock(entry.getLockId());
                    continue;
                }
                return entry;
            }
        }
        return null;
    }

    /**
     * Evict a dead holder's lock found by the lock-free conflict probe. Like every other
     * eviction it runs inside the coordinator critical section and re-reads and re-checks
     * the lock there, so it never deletes a lock that was refreshed or replaced meanwhile.
     * A busy coordinator skips it; the next coordination pass retries.
     */
    private void evictDeadHolderLock(String lockId) {
        try {
            withCoordinatorLock(() -> {
                Path lockPath = lockPath(lockId);
                EditLockEntry current = readLockQuietly(lockPath);
                if (current != null && holderIsDead(current)) Files.deleteIfExists(lockPath);
                return null;
            });
        } catch (Exception ignored) {
            // The next coordination pass retries the eviction.
        }
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
                        // Released on purpose: the edit guard must not re-establish it.
                        ownedLockIds.remove(lock.getLockId());
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
                ownedLockIds.remove(lock.getLockId());
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
     *
     * <p>This check is per SESSION: agents of one session share its process and presence
     * file, so a lock held by this session (any agent) is never "dead" here. Per-AGENT
     * liveness is the lease ({@link EditLockEntry#getLeaseExpiresAt()}): a sibling agent's
     * forgotten lock goes stale one lease after that agent's last activity even while the
     * session heartbeats it, and in-process subagents' locks are released when they end.
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
                    if (isStale(entry) || holderIsDead(entry)) {
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
        registeredParentSessionId = parentSessionId == null || parentSessionId.isBlank()
                ? null : parentSessionId.trim();
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
                entry.setParentSessionId(registeredParentSessionId);
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
                    // Re-check inside the lock: shutdown() deletes proc files under this
                    // same lock, and a publication that started before shutdown() can
                    // otherwise reach here after the file is gone and resurrect it.
                    if (shutdown || !Files.exists(procFile)) {
                        return null;
                    }
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
     * Record whether this session holds a completion monitor on a published process,
     * so the parent session can be woken when the process ends.
     */
    public void updateProcessMonitor(String processId, boolean monitored, String message) {
        String safeProcessId = requireSafeComponent(processId, "processId");
        String resolvedMessage = !monitored || message == null || message.isBlank()
                ? null : message.strip();
        try {
            Path procFile = processFile(sessionId, safeProcessId);
            if (Files.exists(procFile)) {
                withCoordinatorLock(PROCESS_LIFECYCLE_LOCK_WAIT_MS, () -> {
                    // Same in-lock re-check as updateProcessState: shutdown() may delete
                    // this proc file under the lock between the check above and here.
                    if (shutdown || !Files.exists(procFile)) {
                        return null;
                    }
                    ProcessCoordEntry entry = mapper.readValue(procFile.toFile(), ProcessCoordEntry.class);
                    entry.setMonitored(monitored);
                    entry.setMonitorMessage(resolvedMessage);
                    writeJsonAtomically(procFile, entry);
                    return null;
                });
            }
        } catch (Exception e) {
            notifyWarning("[Coordination] Warning: Could not update process monitor: " + e.getMessage());
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

    /**
     * Read-only process snapshot for passive observers such as the chat activity
     * panel's shared-process mirror. Unlike {@link #queryProcesses()} this never
     * rewrites coordination files, never promotes LOST state, and stays silent on
     * unreadable entries — polling must not churn the coordinator lock or spam the
     * user's screen. A quietly-dead RUNNING entry is still returned so the mirror
     * can render it as running; liveness reconciliation remains the owner's job.
     */
    public List<ProcessCoordEntry> snapshotProcesses() {
        try {
            return withCoordinatorLock(() -> {
                List<ProcessCoordEntry> all = readProcessEntries();
                List<ProcessCoordEntry> active = new ArrayList<>();
                for (ProcessCoordEntry entry : all) {
                    if (!hasSafeProcessIdentity(entry) || entry.isStale()) {
                        continue;
                    }
                    active.add(entry);
                }
                return active;
            });
        } catch (Exception e) {
            // Transient failures return null rather than an empty list: a passive
            // observer must never mistake a lost lock race for mass eviction.
            return null;
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
                    if (isStale(e) || holderIsDead(e)) {
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

        // Remove this session's process entries. Locked so a concurrent
        // updateProcessState/updateProcessMonitor call sees either the file or its
        // absence atomically, and never writes one back after this deletes it.
        try {
            withCoordinatorLock(PROCESS_LIFECYCLE_LOCK_WAIT_MS, () -> {
                if (Files.exists(processesDir)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(processesDir,
                            sessionId + "-*.proc.json")) {
                        for (Path file : stream) {
                            Files.deleteIfExists(file);
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
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
                // Full lock id (never truncated) so a reader can release it.
                sb.append(String.format("  %-50s %-20s %-6s %-8s lock_id=%s\n",
                        StringUtils.truncate(e.getFilePath(), 50),
                        e.getAgentName(), e.getEditType(), age, e.getLockId()));
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

            // Update owned edit lock heartbeats (session liveness only). The heartbeat never
            // touches an agent lease: leases move only with the agent's own activity.
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
     * terminal process state is worse than delaying its background callback briefly;
     * agent-scoped edit-lock acquire, owner-checked release, lease renewal and the agent
     * edit guard wait up to {@link #EDIT_LOCK_WAIT_MS} and then fail explicitly.
     */
    private <T> T withCoordinatorLock(CoordinatedAction<T> action) throws Exception {
        return withCoordinatorLock(0L, action);
    }

    /**
     * Test seam (package-private): {@code observer} runs once per bounded coordinator-lock
     * wait, on the waiting thread, right after its first attempt found the lock busy. Pass
     * null to clear.
     */
    void onCoordinatorBusyWait(Runnable observer) {
        this.coordinatorBusyObserver = observer;
    }

    /**
     * Test seam (package-private): the bounded coordinator wait of edit-lock operations
     * (default {@link #EDIT_LOCK_WAIT_MS}).
     */
    void setEditLockWaitMillis(long waitMillis) {
        this.editLockWaitMillis = Math.max(0L, waitMillis);
    }

    /**
     * In-JVM gate in front of each project's coordinator file lock, shared by every manager
     * of this JVM. Only the gate holder ever opens the lock file, which matters for
     * cross-process exclusion: POSIX releases ALL of a process's record locks on a file
     * when ANY descriptor of that file is closed, so a same-JVM thread that opened the lock
     * file, found it busy and closed its channel again would silently drop the holder's OS
     * lock and let another process into the critical section. With the gate, same-JVM
     * contention is resolved before any descriptor is opened.
     */
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_COORDINATOR_GATES =
            new ConcurrentHashMap<>();

    private <T> T withCoordinatorLock(long waitMillis, CoordinatedAction<T> action)
            throws Exception {
        Files.createDirectories(coordinationDir);
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
        ReentrantLock gate = JVM_COORDINATOR_GATES.computeIfAbsent(
                lockFile.toAbsolutePath().normalize(), key -> new ReentrantLock());
        if (gate.isHeldByCurrentThread()) {
            // Nested coordination from inside a critical section: busy, as it always was,
            // and without opening (and then closing) a second descriptor of the lock file.
            throw new IOException("Coordinator lock is busy (nested coordination call)");
        }
        boolean waiting = false;
        if (!gate.tryLock()) {
            if (waitMillis <= 0L) throw new IOException("Coordinator lock is busy");
            waiting = true;
            notifyBusyWait();
            boolean gated;
            try {
                gated = gate.tryLock(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for coordinator lock", interrupted);
            }
            if (!gated) throw new IOException("Coordinator lock is busy");
        }
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            hardenPermissions(lockFile, OWNER_FILE_PERMISSIONS);
            while (true) {
                FileLock fileLock = null;
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException ignored) {
                    // A channel of this JVM outside the gate holds it (test harnesses only).
                }
                if (fileLock != null) {
                    try {
                        return action.execute();
                    } finally {
                        if (fileLock.isValid()) fileLock.release();
                    }
                }
                // Another process holds the coordinator lock.
                if (waitMillis <= 0L || System.nanoTime() >= deadline) {
                    throw new IOException("Coordinator lock is busy");
                }
                if (!waiting) {
                    waiting = true;
                    notifyBusyWait();
                }
                try {
                    Thread.sleep(Math.min(5L, Math.max(1L, waitMillis)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while waiting for coordinator lock", interrupted);
                }
            }
        } finally {
            gate.unlock();
        }
    }

    private void notifyBusyWait() {
        Runnable observer = coordinatorBusyObserver;
        if (observer != null) observer.run();
    }

    @FunctionalInterface
    private interface CoordinatedAction<T> {
        T execute() throws Exception;
    }

}
