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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * File-based coordination state manager for multi-agent environments.
 *
 * <p>All state is persisted as individual JSON files under
 * {@code <workDir>/.kompile/coordination/}. Cross-process safety is achieved
 * via a {@link FileLock} on a sentinel file during mutations (held for
 * milliseconds only). Edit locks are advisory — they warn agents of conflicts
 * but do not block writes.
 *
 * <p>A daemon heartbeat thread updates the current session's agent file and
 * edit lock files every 30 seconds. Stale entries (past TTL) are lazily evicted
 * on query operations.
 */
public class CoordinationStateManager {

    private static final int DEFAULT_EDIT_TTL_SECONDS = 60;
    private static final int DEFAULT_AGENT_TTL_SECONDS = 120;
    private static final int DEFAULT_PROCESS_TTL_SECONDS = 120;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int LOCK_RETRY_COUNT = 10;
    private static final long LOCK_RETRY_DELAY_MS = 50;

    private final Path coordinationDir;
    private final Path editsDir;
    private final Path agentsDir;
    private final Path processesDir;
    private final Path lockFile;
    private final String sessionId;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Set<String> ownedLockIds = ConcurrentHashMap.newKeySet();
    private volatile boolean shutdown = false;

    /**
     * Create a new coordination state manager.
     *
     * @param workDir   the project working directory
     * @param sessionId unique session identifier for this MCP server instance
     * @param mapper    Jackson ObjectMapper (will be configured for Instant support)
     */
    public CoordinationStateManager(Path workDir, String sessionId, ObjectMapper mapper) {
        this.coordinationDir = workDir.resolve(".kompile").resolve("coordination");
        this.editsDir = coordinationDir.resolve("edits");
        this.agentsDir = coordinationDir.resolve("agents");
        this.processesDir = coordinationDir.resolve("processes");
        this.lockFile = coordinationDir.resolve(".coordinator.lock");
        this.sessionId = sessionId;

        // Configure mapper for java.time.Instant serialization
        this.mapper = mapper.copy();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Ensure directories exist
        try {
            Files.createDirectories(editsDir);
            Files.createDirectories(agentsDir);
            Files.createDirectories(processesDir);
        } catch (IOException e) {
            System.err.println("[Coordination] Warning: Could not create coordination directories: " + e.getMessage());
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
        ObjectMapper om = new ObjectMapper();
        CoordinationStateManager mgr = new CoordinationStateManager(workDir, "cli-" + System.currentTimeMillis(), om);
        mgr.heartbeatExecutor.shutdownNow();
        return mgr;
    }

    public String getSessionId() {
        return sessionId;
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
                            && !entry.getSessionId().equals(sessionId)
                            && !entry.isStale()) {
                        String msg = entry.getAbsolutePath() + " is actively being edited by "
                                + entry.getAgentName() + " (session " + entry.getSessionId() + ")";
                        return EditLockResult.conflict(entry, msg);
                    }
                }

                // Write lock file
                EditLockEntry lock = new EditLockEntry(
                        lockId, sessionId, agentName != null ? agentName : "unknown",
                        filePath, filePath, editType,
                        Instant.now(), DEFAULT_EDIT_TTL_SECONDS
                );
                Path lockPath = editsDir.resolve(lockId + ".lock.json");
                mapper.writeValue(lockPath.toFile(), lock);
                ownedLockIds.add(lockId);

                return EditLockResult.acquired(lockId);
            });
        } catch (Exception e) {
            // If coordination fails, don't block the edit — return acquired with a warning
            System.err.println("[Coordination] Warning: Could not acquire edit lock: " + e.getMessage());
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
            System.err.println("[Coordination] Warning: Could not release edit lock: " + e.getMessage());
            return false;
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
                        released = true;
                    }
                }
                return released;
            });
        } catch (Exception e) {
            System.err.println("[Coordination] Warning: Could not force-release lock: " + e.getMessage());
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
                    if (entry.isStale()) {
                        Path lockPath = editsDir.resolve(entry.getLockId() + ".lock.json");
                        Files.deleteIfExists(lockPath);
                    } else {
                        active.add(entry);
                    }
                }
                return active;
            });
        } catch (Exception e) {
            System.err.println("[Coordination] Warning: Could not query edits: " + e.getMessage());
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
        try {
            withCoordinatorLock(() -> {
                AgentEntry entry = new AgentEntry(
                        sessionId, agentName != null ? agentName : "unknown",
                        parentSessionId != null ? "subagent" : "parent",
                        parentSessionId, depth, task,
                        coordinationDir.getParent().getParent().toString(),
                        pid, Instant.now(), DEFAULT_AGENT_TTL_SECONDS
                );
                Path agentFile = agentsDir.resolve(sessionId + ".agent.json");
                mapper.writeValue(agentFile.toFile(), entry);
                return null;
            });
        } catch (Exception e) {
            System.err.println("[Coordination] Warning: Could not register agent: " + e.getMessage());
        }
    }

    /**
     * Register a child agent session (used by StdioTaskTool when spawning subagents).
     */
    public void registerChildAgent(String childSessionId, String task, String agentName,
                                   int depth, long pid) {
        try {
            withCoordinatorLock(() -> {
                AgentEntry entry = new AgentEntry(
                        childSessionId, agentName != null ? agentName : "unknown",
                        "subagent", sessionId, depth, task,
                        coordinationDir.getParent().getParent().toString(),
                        pid, Instant.now(), DEFAULT_AGENT_TTL_SECONDS
                );
                Path agentFile = agentsDir.resolve(childSessionId + ".agent.json");
                mapper.writeValue(agentFile.toFile(), entry);
                return null;
            });
        } catch (Exception e) {
            System.err.println("[Coordination] Warning: Could not register child agent: " + e.getMessage());
        }
    }

    /**
     * Deregister an agent session.
     */
    public void deregisterAgent(String agentSessionId) {
        try {
            Path agentFile = agentsDir.resolve(agentSessionId + ".agent.json");
            Files.deleteIfExists(agentFile);
        } catch (IOException e) {
            System.err.println("[Coordination] Warning: Could not deregister agent: " + e.getMessage());
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
            System.err.println("[Coordination] Warning: Could not query agents: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Process Publication ───────────────────────────────────────────────

    /**
     * Publish a background process to the shared coordination state.
     */
    public void publishProcess(String processId, String command, String description,
                               long pid, String state, String outputFile, String agentName) {
        try {
            withCoordinatorLock(() -> {
                ProcessCoordEntry entry = new ProcessCoordEntry(
                        processId, sessionId, agentName != null ? agentName : "unknown",
                        command, description, pid, state,
                        Instant.now(), outputFile, DEFAULT_PROCESS_TTL_SECONDS
                );
                Path procFile = processesDir.resolve(sessionId + "-" + processId + ".proc.json");
                mapper.writeValue(procFile.toFile(), entry);
                return null;
            });
        } catch (Exception e) {
            System.err.println("[Coordination] Warning: Could not publish process: " + e.getMessage());
        }
    }

    /**
     * Update the state of a published process.
     */
    public void updateProcessState(String processId, String state) {
        try {
            Path procFile = processesDir.resolve(sessionId + "-" + processId + ".proc.json");
            if (Files.exists(procFile)) {
                withCoordinatorLock(() -> {
                    ProcessCoordEntry entry = mapper.readValue(procFile.toFile(), ProcessCoordEntry.class);
                    entry.setState(state);
                    entry.setLastHeartbeat(Instant.now());
                    mapper.writeValue(procFile.toFile(), entry);
                    return null;
                });
            }
        } catch (Exception e) {
            System.err.println("[Coordination] Warning: Could not update process state: " + e.getMessage());
        }
    }

    /**
     * Remove a process from the shared coordination state.
     */
    public boolean unpublishProcess(String processId) {
        try {
            Path procFile = processesDir.resolve(sessionId + "-" + processId + ".proc.json");
            return Files.deleteIfExists(procFile);
        } catch (IOException e) {
            System.err.println("[Coordination] Warning: Could not unpublish process: " + e.getMessage());
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
                    if (entry.isStale()) {
                        Path procFile = processesDir.resolve(
                                entry.getSessionId() + "-" + entry.getProcessId() + ".proc.json");
                        Files.deleteIfExists(procFile);
                    } else {
                        active.add(entry);
                    }
                }
                return active;
            });
        } catch (Exception e) {
            System.err.println("[Coordination] Warning: Could not query processes: " + e.getMessage());
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
                    if (e.isStale()) {
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
                    if (e.isStale()) {
                        Files.deleteIfExists(processesDir.resolve(
                                e.getSessionId() + "-" + e.getProcessId() + ".proc.json"));
                        evicted++;
                    }
                }
                return evicted;
            });
        } catch (Exception e) {
            System.err.println("[Coordination] Warning: Could not evict stale entries: " + e.getMessage());
        }
        return count;
    }

    // ── Shutdown ──────────────────────────────────────────────────────────

    /**
     * Clean shutdown: deregister this session, release all owned locks, stop heartbeat.
     */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;

        heartbeatExecutor.shutdownNow();

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
            System.err.println("[Coordination] Warning: Could not clean up process entries: " + e.getMessage());
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

        StringBuilder sb = new StringBuilder();
        sb.append("=== Kompile Agent Coordination Dashboard ===\n");
        sb.append("Time: ").append(Instant.now()).append("\n\n");

        // Agents
        sb.append("ACTIVE AGENTS (").append(agents.size()).append(")\n");
        if (agents.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (AgentEntry a : agents) {
                String dur = formatDuration(Duration.between(a.getStartedAt(), Instant.now()));
                sb.append(String.format("  %-30s %-10s depth=%d  \"%s\"  %s\n",
                        a.getSessionId(), a.getAgentName(), a.getDepth(),
                        truncate(a.getTask(), 40), dur));
            }
        }

        sb.append("\nACTIVE EDITS (").append(edits.size()).append(")\n");
        if (edits.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (EditLockEntry e : edits) {
                String age = formatDuration(Duration.between(e.getAcquiredAt(), Instant.now()));
                sb.append(String.format("  %-50s %-20s %-6s %s\n",
                        truncate(e.getFilePath(), 50),
                        e.getSessionId(), e.getEditType(), age));
            }
        }

        sb.append("\nACTIVE PROCESSES (").append(processes.size()).append(")\n");
        if (processes.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (ProcessCoordEntry p : processes) {
                String dur = formatDuration(Duration.between(p.getStartedAt(), Instant.now()));
                sb.append(String.format("  %-10s %-20s %-30s %-10s %s\n",
                        p.getProcessId(), p.getSessionId(),
                        truncate(p.getCommand(), 30), p.getState(), dur));
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
                withCoordinatorLock(() -> {
                    if (Files.exists(agentFile)) {
                        AgentEntry entry = mapper.readValue(agentFile.toFile(), AgentEntry.class);
                        entry.setLastHeartbeat(Instant.now());
                        mapper.writeValue(agentFile.toFile(), entry);
                    }
                    return null;
                });
            }

            // Update owned edit lock heartbeats
            for (String lockId : ownedLockIds) {
                Path lockPath = editsDir.resolve(lockId + ".lock.json");
                if (Files.exists(lockPath)) {
                    withCoordinatorLock(() -> {
                        if (Files.exists(lockPath)) {
                            EditLockEntry entry = mapper.readValue(lockPath.toFile(), EditLockEntry.class);
                            entry.setLastHeartbeat(Instant.now());
                            mapper.writeValue(lockPath.toFile(), entry);
                        }
                        return null;
                    });
                }
            }

            // Update owned process heartbeats
            if (Files.exists(processesDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(processesDir,
                        sessionId + "-*.proc.json")) {
                    for (Path procFile : stream) {
                        withCoordinatorLock(() -> {
                            if (Files.exists(procFile)) {
                                ProcessCoordEntry entry = mapper.readValue(
                                        procFile.toFile(), ProcessCoordEntry.class);
                                entry.setLastHeartbeat(Instant.now());
                                mapper.writeValue(procFile.toFile(), entry);
                            }
                            return null;
                        });
                    }
                }
            }
        } catch (Exception e) {
            // Heartbeat failures are non-fatal
            System.err.println("[Coordination] Heartbeat error: " + e.getMessage());
        }
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
                    result.add(entry);
                } catch (IOException e) {
                    // Corrupted file — skip it
                    System.err.println("[Coordination] Warning: Could not read " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[Coordination] Warning: Could not list " + dir + ": " + e.getMessage());
        }
        return result;
    }

    // ── Internal: File Locking ─────────────────────────────────────────────

    /**
     * Execute a callable while holding the coordinator file lock.
     * The lock is held for the duration of the callable (should be milliseconds).
     */
    private <T> T withCoordinatorLock(CoordinatedAction<T> action) throws Exception {
        Files.createDirectories(coordinationDir);

        for (int attempt = 0; attempt < LOCK_RETRY_COUNT; attempt++) {
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock fileLock = null;
                try {
                    fileLock = channel.tryLock();
                    if (fileLock != null) {
                        return action.execute();
                    }
                } catch (OverlappingFileLockException e) {
                    // Another thread in this JVM has the lock
                } finally {
                    if (fileLock != null && fileLock.isValid()) {
                        fileLock.release();
                    }
                }
            }

            // Retry after short delay
            Thread.sleep(LOCK_RETRY_DELAY_MS);
        }

        throw new IOException("Could not acquire coordinator lock after " + LOCK_RETRY_COUNT + " retries");
    }

    @FunctionalInterface
    private interface CoordinatedAction<T> {
        T execute() throws Exception;
    }

    // ── Internal: Formatting ──────────────────────────────────────────────

    private static String formatDuration(Duration d) {
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) {
            return minutes + "m" + seconds + "s";
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h" + minutes + "m" + seconds + "s";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
