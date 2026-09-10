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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Central registry of all agent sessions launched by the kompile CLI.
 * Stored at {@code ~/.kompile/sessions/registry.json}.
 * <p>
 * Tracks session metadata including project directory, conversation ID,
 * launch mode, agent type, and status. Used by {@code kompile resume-all}
 * to mass-resume previously started sessions.
 */
public class SessionRegistry {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Object JVM_REGISTRY_LOCK = new Object();
    private static final Duration RESUME_CLAIM_TIMEOUT = Duration.ofMinutes(2);
    private static final String CLAIM_ID = "resumeClaimId";
    private static final String CLAIMED_AT = "resumeClaimedAt";

    private final List<SessionEntry> entries;

    public SessionRegistry() {
        this.entries = new ArrayList<>();
    }

    // ── Load / Save ─────────────────────────────────────────────────────────

    /**
     * Resolve the registry file at call time (not class-load time) so tests can
     * isolate {@code user.home} the same way the other chat tests do.
     */
    static Path registryFile() {
        return KompileHome.homeDirectory().toPath().resolve("sessions").resolve("registry.json");
    }

    private static Path registryLockFile() {
        return registryFile().resolveSibling("registry.json.lock");
    }

    /**
     * Load the registry from disk. Creates the file if it doesn't exist.
     */
    public static SessionRegistry load() {
        try {
            return loadFromDiskStrict();
        } catch (IOException e) {
            System.err.println("Warning: Could not load session registry: " + e.getMessage());
            return new SessionRegistry();
        }
    }

    private static SessionRegistry loadFromDiskStrict() throws IOException {
        SessionRegistry registry = new SessionRegistry();
        Path registryFile = registryFile();
        if (!Files.exists(registryFile)) {
            return registry;
        }

        String content = Files.readString(registryFile, StandardCharsets.UTF_8);
        var parsed = MAPPER.readTree(content);
        if (parsed == null || !parsed.isObject()) {
            throw new IOException("Session registry root must be a JSON object");
        }
        ObjectNode root = (ObjectNode) parsed;
        if (root.has("sessions") && root.get("sessions").isArray()) {
            for (var node : root.get("sessions")) {
                if (node.isObject()) {
                    registry.entries.add(SessionEntry.fromJson((ObjectNode) node));
                }
            }
        }
        return registry;
    }

    /**
     * Save caller-mutated entries without dropping rows concurrently registered
     * by another process. Production mutations use {@link #updateLocked(Function)}
     * so they reload and mutate the latest state while holding the same lock.
     */
    public void save() {
        updateLocked(latest -> {
            Map<String, SessionEntry> merged = new LinkedHashMap<>();
            for (SessionEntry entry : latest.entries) {
                merged.put(resumeIdentity(entry), entry);
            }
            for (SessionEntry entry : entries) {
                merged.put(resumeIdentity(entry), entry);
            }
            latest.entries.clear();
            latest.entries.addAll(merged.values());
            return null;
        });
    }

    private <T> T updateLocked(Function<SessionRegistry, T> mutation) {
        synchronized (JVM_REGISTRY_LOCK) {
            Path lockFile = registryLockFile();
            try {
                Files.createDirectories(lockFile.getParent());
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    SessionRegistry latest = loadFromDiskStrict();
                    T result = mutation.apply(latest);
                    writeAtomic(latest);
                    replaceEntries(latest);
                    return result;
                }
            } catch (IOException registryFailure) {
                throw new IllegalStateException("Could not update session registry", registryFailure);
            }
        }
    }

    private void replaceEntries(SessionRegistry latest) {
        entries.clear();
        entries.addAll(latest.entries);
    }

    private static void writeAtomic(SessionRegistry registry) throws IOException {
        Path target = registryFile();
        Files.createDirectories(target.getParent());
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode sessionsArray = root.putArray("sessions");
        for (SessionEntry entry : registry.entries) {
            sessionsArray.add(entry.toJson());
        }

        Path temp = Files.createTempFile(target.getParent(), "registry-", ".tmp");
        try {
            Files.writeString(temp, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel tempChannel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                tempChannel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Register a new session. Saves immediately.
     * <p>
     * Upsert semantics: re-registering an existing session ID replaces the prior
     * entry in place (keeping its conversation ID, title, and extra metadata)
     * rather than appending a duplicate row. This happens on resumed sessions —
     * the same transcript can be opened more than once.
     */
    public SessionEntry register(String kompileSessionId, String agent, String projectDirectory,
                                 String launchMode, long pid) {
        return updateLocked(registry -> registry.registerInMemory(
                kompileSessionId, agent, projectDirectory, launchMode, pid));
    }

    private SessionEntry registerInMemory(String kompileSessionId, String agent,
                                          String projectDirectory, String launchMode, long pid) {
        String now = Instant.now().toString();
        closeOtherRunningSessions(pid, kompileSessionId, now);

        SessionEntry existing = null;
        for (SessionEntry entry : entries) {
            if (kompileSessionId != null && kompileSessionId.equals(entry.getKompileSessionId())) {
                existing = entry;
                break;
            }
        }

        if (existing != null) {
            existing.setAgent(agent);
            existing.setProjectDirectory(projectDirectory);
            existing.setLaunchMode(launchMode);
            existing.setStartedAt(now);
            existing.setEndedAt(null);
            existing.setStatus("running");
            existing.setPid(pid);
            if (existing.getExtra() == null) {
                existing.setExtra(new LinkedHashMap<>());
            }
            clearResumeClaim(existing);
            return existing;
        }

        SessionEntry entry = SessionEntry.builder()
                .kompileSessionId(kompileSessionId)
                .agent(agent)
                .projectDirectory(projectDirectory)
                .launchMode(launchMode)
                .startedAt(now)
                .status("running")
                .pid(pid)
                .extra(new LinkedHashMap<>())
                .build();
        entries.add(entry);
        return entry;
    }

    private void closeOtherRunningSessions(long pid, String keepSessionId, String endedAt) {
        if (pid <= 0) return;
        for (SessionEntry entry : entries) {
            if (pid == entry.getPid()
                    && "running".equals(entry.getStatus())
                    && !Objects.equals(keepSessionId, entry.getKompileSessionId())) {
                entry.setStatus("exited");
                entry.setEndedAt(endedAt);
            }
        }
    }

    /**
     * Update session status to "exited" and set end time. Saves immediately.
     */
    public void markExited(String kompileSessionId) {
        markExited(kompileSessionId, 0L);
    }

    /**
     * Mark a session exited only when it is still owned by the expected process.
     * A resumed process may upsert the same transcript ID before the older JVM
     * finishes shutting down; the older owner must not exit the replacement.
     */
    public boolean markExited(String kompileSessionId, long expectedPid) {
        return updateLocked(registry -> {
            for (SessionEntry entry : registry.entries) {
                if (kompileSessionId.equals(entry.getKompileSessionId())) {
                    if (expectedPid > 0 && entry.getPid() != expectedPid) {
                        return false;
                    }
                    entry.setStatus("exited");
                    entry.setEndedAt(Instant.now().toString());
                    clearResumeClaim(entry);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Atomically reserve resumable rows so concurrent resume-all invocations
     * cannot launch the same transcript. A child registration replaces the claim;
     * abandoned claims automatically expire during status refresh.
     */
    public List<SessionEntry> claimResumable(Predicate<SessionEntry> filter,
                                             Comparator<SessionEntry> ordering,
                                             int limit,
                                             String claimId) {
        if (claimId == null || claimId.isBlank()) {
            throw new IllegalArgumentException("claimId is required");
        }
        return updateLocked(registry -> {
            registry.refreshStatusesInMemory();
            List<SessionEntry> selected = registry.entries.stream()
                    .filter(entry -> "exited".equals(entry.getStatus()))
                    .filter(entry -> (entry.getConversationId() != null
                            && !entry.getConversationId().isEmpty())
                            || (entry.getKompileSessionId() != null
                            && !entry.getKompileSessionId().isEmpty()))
                    .filter(filter != null ? filter : ignored -> true)
                    .sorted(ordering != null ? ordering : Comparator.comparing(
                            SessionEntry::getStartedAt).reversed())
                    .limit(Math.max(0, limit))
                    .collect(Collectors.toList());
            String claimedAt = Instant.now().toString();
            for (SessionEntry entry : selected) {
                entry.setStatus("resuming");
                if (entry.getExtra() == null) entry.setExtra(new LinkedHashMap<>());
                entry.getExtra().put(CLAIM_ID, claimId);
                entry.getExtra().put(CLAIMED_AT, claimedAt);
            }
            return List.copyOf(selected);
        });
    }

    public void releaseResumeClaim(String resumeIdentity, String claimId) {
        updateLocked(registry -> {
            registry.entries.stream()
                    .filter(entry -> Objects.equals(resumeIdentity, resumeIdentity(entry)))
                    .findFirst().ifPresent(entry -> {
                if ("resuming".equals(entry.getStatus())
                        && entry.getExtra() != null
                        && Objects.equals(claimId, entry.getExtra().get(CLAIM_ID))) {
                    entry.setStatus("exited");
                    clearResumeClaim(entry);
                }
            });
            return null;
        });
    }

    public static String resumeIdentity(SessionEntry entry) {
        if (entry.getKompileSessionId() != null
                && !entry.getKompileSessionId().isBlank()) {
            return "kompile:" + entry.getKompileSessionId();
        }
        return "conversation:" + Objects.toString(entry.getAgent(), "") + ":"
                + Objects.toString(entry.getConversationId(), "");
    }

    private static void clearResumeClaim(SessionEntry entry) {
        if (entry.getExtra() == null) return;
        entry.getExtra().remove(CLAIM_ID);
        entry.getExtra().remove(CLAIMED_AT);
    }

    /**
     * Force one stuck session back to resumable — the manual workaround for a
     * bad/corrupt shutdown. A crashed {@code resume-all} leaves rows in the
     * {@code resuming} state, and a killed chat leaves a {@code running} row
     * whose dead or recycled PID hides the transcript from every resume path.
     * This resets the row to {@code exited} and drops any resume claim.
     * Matches the kompile session ID first, then the native conversation ID.
     * Returns {@code false} when no tracked row matches.
     */
    public boolean clearResumeLock(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        Boolean cleared = updateLocked(registry -> {
            for (SessionEntry entry : registry.entries) {
                if (sessionId.equals(entry.getKompileSessionId())
                        || sessionId.equals(entry.getConversationId())) {
                    entry.setStatus("exited");
                    clearResumeClaim(entry);
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        });
        return Boolean.TRUE.equals(cleared);
    }

    /**
     * Repair every row a bad/corrupt shutdown left unresumable: abandoned
     * {@code resume-all} claims ({@code resuming} rows) and {@code running}
     * rows whose process is gone. Genuinely live sessions are left untouched
     * so an active chat is never force-stamped as resumable mid-run.
     * Returns the number of repaired rows.
     */
    public int clearAllResumeLocks() {
        Integer repaired = updateLocked(registry -> {
            int count = 0;
            for (SessionEntry entry : registry.entries) {
                String status = entry.getStatus() == null ? "" : entry.getStatus();
                boolean stuck = "resuming".equals(status)
                        || ("running".equals(status) && !entry.isProcessAlive());
                if (stuck) {
                    entry.setStatus("exited");
                    clearResumeClaim(entry);
                    count++;
                }
            }
            return count;
        });
        return repaired == null ? 0 : repaired;
    }

    /**
     * Update the conversation ID (the agent's native session ID) after harvest.
     */
    public void setConversationId(String kompileSessionId, String conversationId) {
        updateLocked(registry -> {
            for (SessionEntry entry : registry.entries) {
                if (kompileSessionId.equals(entry.getKompileSessionId())) {
                    entry.setConversationId(conversationId);
                    break;
                }
            }
            return null;
        });
    }

    /**
     * Update the title for a session.
     */
    public void setTitle(String kompileSessionId, String title) {
        updateLocked(registry -> {
            for (SessionEntry entry : registry.entries) {
                if (kompileSessionId.equals(entry.getKompileSessionId())) {
                    entry.setTitle(title);
                    break;
                }
            }
            return null;
        });
    }

    /**
     * Refresh status of all entries: mark dead processes as exited without
     * inventing an end/activity timestamp, and repair legacy rows where an
     * in-process /clear left multiple transcript IDs tied to the same live PID.
     * One CLI process owns only its newest transcript.
     */
    public void refreshStatuses() {
        updateLocked(registry -> {
            registry.refreshStatusesInMemory();
            return null;
        });
    }

    private void refreshStatusesInMemory() {
        String now = Instant.now().toString();
        Map<Long, SessionEntry> newestRunningByPid = new HashMap<>();
        for (SessionEntry entry : entries) {
            if ("resuming".equals(entry.getStatus())) {
                if (resumeClaimExpired(entry, now)) {
                    entry.setStatus("exited");
                    clearResumeClaim(entry);
                }
                continue;
            }
            if (!"running".equals(entry.getStatus())) continue;
            if (!entry.isProcessAlive()) {
                entry.setStatus("exited");
                continue;
            }

            SessionEntry previous = newestRunningByPid.get(entry.getPid());
            if (previous == null) {
                newestRunningByPid.put(entry.getPid(), entry);
                continue;
            }

            SessionEntry newest = isAtLeastAsRecent(entry, previous) ? entry : previous;
            SessionEntry superseded = newest == entry ? previous : entry;
            superseded.setStatus("exited");
            // This legacy duplicate stopped owning the PID when the newer
            // transcript began. Use that known boundary rather than refresh time.
            superseded.setEndedAt(newest.getStartedAt());
            newestRunningByPid.put(entry.getPid(), newest);
        }
    }

    private static boolean resumeClaimExpired(SessionEntry entry, String now) {
        if (entry.getExtra() == null) return true;
        String claimedAt = entry.getExtra().get(CLAIMED_AT);
        try {
            return Instant.parse(claimedAt).plus(RESUME_CLAIM_TIMEOUT)
                    .isBefore(Instant.parse(now));
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean isAtLeastAsRecent(SessionEntry candidate, SessionEntry previous) {
        try {
            return !Instant.parse(candidate.getStartedAt()).isBefore(
                    Instant.parse(previous.getStartedAt()));
        } catch (Exception ignored) {
            // Entries are persisted in registration order, so prefer the later row
            // when legacy timestamps are absent or malformed.
            return true;
        }
    }

    /**
     * Remove entries older than the given number of days.
     */
    public int pruneOlderThan(int days) {
        return updateLocked(registry -> {
            Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(days));
            int before = registry.entries.size();
            registry.entries.removeIf(e -> {
                try {
                    Instant started = Instant.parse(e.getStartedAt());
                    return started.isBefore(cutoff);
                } catch (Exception ex) {
                    return false;
                }
            });
            return before - registry.entries.size();
        });
    }

    // ── Query ───────────────────────────────────────────────────────────────

    public List<SessionEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Get all sessions that are resumable (exited with a conversation ID or kompile session).
     */
    public List<SessionEntry> getResumable() {
        refreshStatuses();
        return entries.stream()
                .filter(e -> "exited".equals(e.getStatus()))
                .filter(e -> (e.getConversationId() != null && !e.getConversationId().isEmpty())
                        || (e.getKompileSessionId() != null && !e.getKompileSessionId().isEmpty()))
                .collect(Collectors.toList());
    }

    /**
     * Filter sessions by agent name.
     */
    public List<SessionEntry> filterByAgent(String agent) {
        return entries.stream()
                .filter(e -> agent.equalsIgnoreCase(e.getAgent()))
                .collect(Collectors.toList());
    }

    /**
     * Filter sessions by project directory.
     */
    public List<SessionEntry> filterByProject(String projectDir) {
        return entries.stream()
                .filter(e -> projectDir.equals(e.getProjectDirectory()))
                .collect(Collectors.toList());
    }

    /**
     * Get a specific session by its kompile session ID.
     */
    public Optional<SessionEntry> get(String kompileSessionId) {
        return entries.stream()
                .filter(e -> kompileSessionId.equals(e.getKompileSessionId()))
                .findFirst();
    }

    /**
     * Get the most recent sessions, limited to count.
     * <p>
     * Sorted by startedAt descending. Entries with unparsable timestamps sort last.
     */
    public List<SessionEntry> getRecent(int count) {
        Comparator<SessionEntry> byStarted = Comparator.comparing(
                e -> {
                    try {
                        return Instant.parse(e.getStartedAt());
                    } catch (Exception ex) {
                        return Instant.EPOCH;
                    }
                });
        return entries.stream()
                .sorted(byStarted.reversed())
                .limit(Math.max(0, count))
                .collect(Collectors.toList());
    }

    public int size() {
        return entries.size();
    }
}
