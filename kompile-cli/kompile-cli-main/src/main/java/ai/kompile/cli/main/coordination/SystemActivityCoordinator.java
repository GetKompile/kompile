/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * User-wide admission lane for memory-intensive agent activity.
 *
 * <p>Edit locks are project-local because files belong to a project. RAM and GPU capacity
 * belong to the machine, so activity reservations are stored under the user's global Kompile
 * directory and guarded by one OS file lock. Reservation failure is fail-closed: a high-memory
 * launch must not proceed when peer state cannot be checked atomically.</p>
 */
public final class SystemActivityCoordinator implements AutoCloseable {

    public static final String SYSTEM_ROOT_PROPERTY = "kompile.coordination.systemRoot";
    public static final String SYSTEM_ROOT_ENV = "KOMPILE_COORDINATION_SYSTEM_ROOT";

    private static final int DEFAULT_TTL_SECONDS = 180;
    private static final int LOCK_ATTEMPTS = 40;
    private static final long LOCK_RETRY_MILLIS = 25L;
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    public record ReservationResult(boolean admitted,
                                    CoordinationActivity activity,
                                    List<CoordinationActivity> blockers,
                                    String reason) {
        public ReservationResult {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T run() throws Exception;
    }

    private final Path projectRoot;
    private final String sessionId;
    private final long ownerPid;
    private final Path activitiesDir;
    private final Path lockFile;
    private final ObjectMapper mapper;
    private final Consumer<String> warningSink;
    private final Set<String> ownedActivityIds = ConcurrentHashMap.newKeySet();

    public SystemActivityCoordinator(Path stateRoot, Path projectRoot, String sessionId,
                                     ObjectMapper mapper, Consumer<String> warningSink) {
        Path root = Objects.requireNonNull(stateRoot, "stateRoot").toAbsolutePath().normalize();
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        this.sessionId = safeComponent(sessionId, "sessionId");
        this.ownerPid = ProcessHandle.current().pid();
        this.activitiesDir = root.resolve("activities");
        this.lockFile = root.resolve(".activities.lock");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.warningSink = warningSink == null ? ignored -> { } : warningSink;
    }

    public static Path defaultStateRoot() {
        String configured = System.getProperty(SYSTEM_ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(SYSTEM_ROOT_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".kompile", "coordination", "system")
                .toAbsolutePath().normalize();
    }

    /** Atomically acquire the single user-wide high-memory activity lane. */
    public ReservationResult reserve(String agentName, String kind, String toolName,
                                     String description) {
        String normalizedKind = bounded(kind, "OTHER", 80).toUpperCase(Locale.ROOT);
        String normalizedTool = bounded(toolName, "unknown", 160);
        String normalizedDescription = bounded(description, normalizedTool, 2_000);
        try {
            return withLock(() -> {
                List<CoordinationActivity> active = readActiveLocked();
                if (!active.isEmpty()) {
                    return new ReservationResult(false, null, active,
                            "another high-memory activity already owns the system lane");
                }
                Instant now = Instant.now();
                CoordinationActivity activity = new CoordinationActivity(
                        UUID.randomUUID().toString(), sessionId,
                        bounded(agentName, "unknown", 160), projectRoot.toString(),
                        normalizedKind, normalizedTool, normalizedDescription,
                        ownerPid, now, DEFAULT_TTL_SECONDS);
                writeAtomically(activityFile(activity.getActivityId()), activity);
                ownedActivityIds.add(activity.getActivityId());
                return new ReservationResult(true, activity, List.of(), "admitted");
            });
        } catch (Exception e) {
            warn("Could not reserve system activity lane: " + safeMessage(e));
            return new ReservationResult(false, null, List.of(),
                    "system activity coordination unavailable: " + safeMessage(e));
        }
    }

    /** Active reservations across every Kompile project owned by this OS user. */
    public List<CoordinationActivity> queryActive() {
        try {
            return withLock(this::readActiveLocked);
        } catch (Exception e) {
            warn("Could not query system activities: " + safeMessage(e));
            return List.of();
        }
    }

    /** Attach an admitted activity to a background process and/or asynchronous job. */
    public boolean attach(String activityId, String processId, long processPid,
                          String externalId) {
        String safeId = safeComponent(activityId, "activityId");
        try {
            return withLock(() -> {
                Path file = activityFile(safeId);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
                CoordinationActivity activity = mapper.readValue(file.toFile(), CoordinationActivity.class);
                if (!sessionId.equals(activity.getSessionId())) return false;
                activity.setProcessId(blankToNull(processId));
                activity.setProcessPid(Math.max(0L, processPid));
                activity.setExternalId(blankToNull(externalId));
                refresh(activity, Instant.now());
                writeAtomically(file, activity);
                ownedActivityIds.add(safeId);
                return true;
            });
        } catch (Exception e) {
            warn("Could not attach system activity " + safeId + ": " + safeMessage(e));
            return false;
        }
    }

    /** Release one reservation owned by this coordination session. */
    public boolean release(String activityId) {
        String safeId = safeComponent(activityId, "activityId");
        try {
            return withLock(() -> releaseOwnedLocked(safeId));
        } catch (Exception e) {
            warn("Could not release system activity " + safeId + ": " + safeMessage(e));
            return false;
        }
    }

    /** Release an attached background process after its owning process manager observes exit. */
    public int releaseByProcess(String processId) {
        String wanted = blankToNull(processId);
        if (wanted == null) return 0;
        return releaseMatching(activity -> sessionId.equals(activity.getSessionId())
                && wanted.equals(activity.getProcessId()));
    }

    /** Release an async job from any replacement session in the same project. */
    public int releaseByExternalId(String externalId) {
        String wanted = blankToNull(externalId);
        if (wanted == null) return 0;
        return releaseMatching(activity -> projectRoot.toString().equals(activity.getProjectRoot())
                && wanted.equals(activity.getExternalId()));
    }

    /** Refresh owned reservations and discard entries whose attached process has exited. */
    public void heartbeat() {
        if (ownedActivityIds.isEmpty()) return;
        try {
            withLock(() -> {
                Instant now = Instant.now();
                for (String activityId : new ArrayList<>(ownedActivityIds)) {
                    Path file = activityFile(activityId);
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        ownedActivityIds.remove(activityId);
                        continue;
                    }
                    CoordinationActivity activity = mapper.readValue(
                            file.toFile(), CoordinationActivity.class);
                    if (!sessionId.equals(activity.getSessionId()) || attachedProcessExited(activity)) {
                        Files.deleteIfExists(file);
                        ownedActivityIds.remove(activityId);
                        continue;
                    }
                    refresh(activity, now);
                    writeAtomically(file, activity);
                }
                return null;
            });
        } catch (Exception e) {
            warn("Could not heartbeat system activities: " + safeMessage(e));
        }
    }

    @Override
    public void close() {
        if (ownedActivityIds.isEmpty()) return;
        try {
            withLock(() -> {
                if (Files.isDirectory(activitiesDir)) {
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                            activitiesDir, "*.activity.json")) {
                        for (Path file : stream) {
                            CoordinationActivity activity;
                            try {
                                activity = mapper.readValue(file.toFile(), CoordinationActivity.class);
                            } catch (IOException ignored) {
                                continue;
                            }
                            if (sessionId.equals(activity.getSessionId())) {
                                Files.deleteIfExists(file);
                            }
                        }
                    }
                }
                ownedActivityIds.clear();
                return null;
            });
        } catch (Exception e) {
            warn("Could not release system activities during shutdown: " + safeMessage(e));
        }
    }

    private int releaseMatching(Predicate<CoordinationActivity> predicate) {
        try {
            return withLock(() -> {
                int released = 0;
                for (CoordinationActivity activity : readActiveLocked()) {
                    if (!predicate.test(activity)) continue;
                    Files.deleteIfExists(activityFile(activity.getActivityId()));
                    ownedActivityIds.remove(activity.getActivityId());
                    released++;
                }
                return released;
            });
        } catch (NoSuchFileException missingState) {
            // The owner may finish while an isolated/project state directory is
            // being removed. No directory means there is nothing left to release.
            return 0;
        } catch (Exception e) {
            warn("Could not release matching system activity: " + safeMessage(e));
            return 0;
        }
    }

    private boolean releaseOwnedLocked(String activityId) throws IOException {
        Path file = activityFile(activityId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        CoordinationActivity activity = mapper.readValue(file.toFile(), CoordinationActivity.class);
        if (!sessionId.equals(activity.getSessionId())) return false;
        boolean deleted = Files.deleteIfExists(file);
        if (deleted) ownedActivityIds.remove(activityId);
        return deleted;
    }

    private List<CoordinationActivity> readActiveLocked() throws IOException {
        ensureStateDirectories();
        List<CoordinationActivity> active = new ArrayList<>();
        Instant now = Instant.now();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                activitiesDir, "*.activity.json")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                CoordinationActivity activity;
                try {
                    activity = mapper.readValue(file.toFile(), CoordinationActivity.class);
                } catch (IOException malformed) {
                    throw new IOException("malformed activity record " + file.getFileName(), malformed);
                }
                validate(activity, file);
                if (expired(activity, now) || ownerExited(activity) || attachedProcessExited(activity)) {
                    Files.deleteIfExists(file);
                    ownedActivityIds.remove(activity.getActivityId());
                } else {
                    active.add(activity);
                }
            }
        }
        active.sort(Comparator.comparing(CoordinationActivity::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(active);
    }

    private static void validate(CoordinationActivity activity, Path file) throws IOException {
        try {
            safeComponent(activity.getActivityId(), "activityId");
            safeComponent(activity.getSessionId(), "sessionId");
        } catch (IllegalArgumentException e) {
            throw new IOException("unsafe activity record " + file.getFileName(), e);
        }
    }

    private static boolean expired(CoordinationActivity activity, Instant now) {
        Instant expires = activity.getExpiresAt();
        if (expires != null) return now.isAfter(expires);
        Instant heartbeat = activity.getLastHeartbeat() != null
                ? activity.getLastHeartbeat() : activity.getStartedAt();
        int ttl = activity.getTtlSeconds() > 0 ? activity.getTtlSeconds() : DEFAULT_TTL_SECONDS;
        return heartbeat == null || now.isAfter(heartbeat.plusSeconds(ttl));
    }

    private static boolean ownerExited(CoordinationActivity activity) {
        long pid = activity.getOwnerPid();
        return pid > 0 && ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    private static boolean attachedProcessExited(CoordinationActivity activity) {
        long pid = activity.getProcessPid();
        return pid > 0 && ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    private static void refresh(CoordinationActivity activity, Instant now) {
        int ttl = activity.getTtlSeconds() > 0 ? activity.getTtlSeconds() : DEFAULT_TTL_SECONDS;
        activity.setLastHeartbeat(now);
        activity.setExpiresAt(now.plusSeconds(ttl));
    }

    private Path activityFile(String activityId) {
        return activitiesDir.resolve(safeComponent(activityId, "activityId") + ".activity.json");
    }

    private <T> T withLock(LockedAction<T> action) throws Exception {
        ensureStateDirectories();
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            harden(lockFile, OWNER_FILE_PERMISSIONS);
            for (int attempt = 0; attempt < LOCK_ATTEMPTS; attempt++) {
                FileLock lock = null;
                try {
                    lock = channel.tryLock();
                    if (lock != null) return action.run();
                } catch (OverlappingFileLockException ignored) {
                    // Another coordinator in this JVM owns the user-wide lane briefly.
                } finally {
                    if (lock != null && lock.isValid()) lock.release();
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("interrupted while waiting for system activity lock");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(LOCK_RETRY_MILLIS));
            }
            throw new IOException("system activity lock remained busy for "
                    + (LOCK_ATTEMPTS * LOCK_RETRY_MILLIS) + " ms");
        }
    }

    private void ensureStateDirectories() throws IOException {
        Files.createDirectories(activitiesDir);
        if (Files.isSymbolicLink(activitiesDir)
                || !Files.isDirectory(activitiesDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("activity state path is not a real directory: " + activitiesDir);
        }
        harden(activitiesDir.getParent(), OWNER_DIRECTORY_PERMISSIONS);
        harden(activitiesDir, OWNER_DIRECTORY_PERMISSIONS);
    }

    private void writeAtomically(Path target, CoordinationActivity activity) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            mapper.writeValue(temp.toFile(), activity);
            harden(temp, OWNER_FILE_PERMISSIONS);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            harden(target, OWNER_FILE_PERMISSIONS);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void harden(Path path, Set<PosixFilePermission> permissions) {
        if (path == null || !Files.exists(path)) return;
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort on non-POSIX filesystems.
        }
    }

    private static String safeComponent(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 160
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field
                    + " must contain only letters, digits, '.', '_', or '-' and be at most 160 characters");
        }
        return value;
    }

    private static String bounded(String value, String fallback, int maxChars) {
        String result = value == null || value.isBlank() ? fallback : value.strip();
        return result.length() <= maxChars ? result : result.substring(0, maxChars);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void warn(String message) {
        warningSink.accept("[Coordination] Warning: " + message);
    }
}
