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

package ai.kompile.cli.common.chat.aggregate;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Session-wide retention for Kompile-owned conversation artifacts.
 *
 * <p>Retirement moves a session into {@code .trash}, never follows native transcript
 * links, and leaves a {@code .retired/<id>} tombstone for derived catalogs. Active
 * writer leases, live registry PIDs and {@code <id>.pinned} markers protect sessions.
 * Shared catalogs and provider-owned originals are not deleted. Trash is purged only
 * by an explicit operation after a grace period; retirement alone reclaims no disk.
 * The legacy deleted-count accessors describe retired sessions for compatibility.</p>
 */
public class ChatTranscriptRetention {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptRetention.class);

    private final Policy policy;

    public ChatTranscriptRetention(Policy policy) {
        this.policy = policy;
    }

    public Result apply(File conversationsDir) {
        return apply(conversationsDir, false);
    }

    public synchronized Result apply(File conversationsDir, boolean dryRun) {
        if (conversationsDir == null) return Result.empty();
        Path root = conversationsDir.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return Result.empty();
        try {
            safe(root);
            if (dryRun) return applyLocked(root, true);
            Path lockPath = root.resolve(".retention.lock");
            safe(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Another retention operation is running");
                recover(root);
                return applyLocked(root, false);
            }
        } catch (IOException | OverlappingFileLockException e) {
            log.warn("Conversation retention skipped: {}", e.getMessage());
            return new Result(0, 0, 0, List.of(), 0, 0, 0, List.of(e.getMessage()));
        }
    }

    private Result applyLocked(Path root, boolean dryRun) throws IOException {
        Set<String> active = activeSessions(root);
        List<Session> sessions = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                    for (String suffix : List.of(".txt", ".context.json", ".native-session.json"))
                        if (name.endsWith(suffix)) ids.add(name.substring(0, name.length() - suffix.length()));
                } else if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                    if (name.endsWith(".context")) ids.add(name.substring(0, name.length() - ".context".length()));
                    else if (Files.isDirectory(file.resolve("tool-results"), NOFOLLOW_LINKS)
                            || Files.isDirectory(file.resolve("result-cache"), NOFOLLOW_LINKS)) ids.add(name);
                }
            }
        }
        for (String id : ids) {
            if (!safeId(id)) continue;
            List<Path> paths = artifacts(root, id);
            long bytes = 0, modified = 0;
            for (Path path : paths) {
                bytes += size(path);
                modified = Math.max(modified, lastModified(path));
            }
            sessions.add(new Session(id, root.resolve(id + ".txt"), paths, bytes, modified));
        }
        sessions.sort(Comparator.comparingLong(Session::modified).thenComparing(Session::id));
        Map<String, Integer> counts = new HashMap<>();
        long total = 0;
        for (Session session : sessions) {
            counts.merge(source(session.id), 1, Integer::sum);
            total += session.bytes;
        }
        long initialBytes = total, archivedBytes = 0;
        int byAge = 0, byCount = 0, bySize = 0, protectedCount = 0;
        List<File> retired = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        long cutoff = policy.maxAge == null || policy.maxAge.isZero() ? Long.MIN_VALUE
                : Instant.now().minus(policy.maxAge).toEpochMilli();
        for (Session session : sessions) {
            boolean aged = session.modified < cutoff;
            boolean counted = policy.maxFilesPerSource > 0
                    && counts.get(source(session.id)) > policy.maxFilesPerSource;
            boolean sized = policy.maxTotalBytes > 0 && total > policy.maxTotalBytes;
            if (!aged && !counted && !sized) continue;
            if (Files.exists(root.resolve(session.id + ".pinned"), NOFOLLOW_LINKS)
                    || active.contains(session.id) || publisherAlive(root, session.id)) {
                protectedCount++;
                continue;
            }
            // Reuse the stable writer-lock inode used by TranscriptLogScope. Holding it
            // until every move completes prevents a normal CLI resume from racing cleanup.
            Path writer = writerLock(root, session.id);
            safe(writer);
            if (!dryRun) Files.createDirectories(writer.getParent());
            try (FileChannel channel = dryRun && !Files.exists(writer, NOFOLLOW_LINKS) ? null
                    : FileChannel.open(writer, dryRun ? new OpenOption[]{StandardOpenOption.WRITE}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE});
                 FileLock lock = channel == null ? null : channel.tryLock()) {
                if (channel != null && lock == null) { protectedCount++; continue; }
                if (activeSessions(root).contains(session.id) || publisherAlive(root, session.id)
                        || Files.exists(root.resolve(session.id + ".pinned"), NOFOLLOW_LINKS)) {
                    protectedCount++; continue;
                }
                if (!dryRun) archive(root, session);
                retired.add(session.transcript.toFile());
                archivedBytes += session.bytes;
                total -= session.bytes;
                counts.merge(source(session.id), -1, Integer::sum);
                if (aged) byAge++; else if (counted) byCount++; else bySize++;
            } catch (OverlappingFileLockException busy) {
                protectedCount++;
            } catch (IOException failure) {
                log.warn("Could not retire conversation {}: {}", session.id, failure.getMessage());
                failures.add(session.id + ": " + failure.getMessage());
            }
        }
        return new Result(byAge, byCount, bySize, List.copyOf(retired), archivedBytes,
                initialBytes, protectedCount, List.copyOf(failures));
    }

    private static List<Path> artifacts(Path root, String id) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String suffix : List.of(".context.json", ".context", ".native-session.json", ".attachments",
                ".metrics.json", ".title", ".todos.json", ".activity", "")) {
            Path path = root.resolve(id + suffix);
            safe(path);
            if (Files.exists(path, NOFOLLOW_LINKS)) paths.add(path);
        }
        Path activity = root.resolve("kompile__" + id + ".activity");
        safe(activity);
        if (Files.exists(activity, NOFOLLOW_LINKS)) paths.add(activity);
        for (Path path : List.of(root.resolve("tool-calls").resolve(id + ".jsonl"),
                root.resolve("tool-calls/details").resolve(id))) {
            safe(path);
            if (Files.exists(path, NOFOLLOW_LINKS)) paths.add(path);
        }
        // Hide the listing/resume entry first. If the process dies midway, an incomplete
        // archive must not advertise a resumable transcript with missing context/payloads.
        Path transcript = root.resolve(id + ".txt");
        if (Files.exists(transcript, NOFOLLOW_LINKS)) paths.add(0, transcript);
        return paths;
    }

    private static boolean ownedPath(String id, String name) {
        if (name.equals(id) || name.equals("kompile__" + id + ".activity")
                || name.equals(Path.of("tool-calls", id + ".jsonl").toString())
                || name.equals(Path.of("tool-calls", "details", id).toString())) return true;
        for (String suffix : List.of(".txt", ".context.json", ".context", ".native-session.json", ".attachments",
                ".metrics.json", ".title", ".todos.json", ".activity"))
            if (name.equals(id + suffix)) return true;
        return false;
    }

    private static void writeManifest(Path file, Object value) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "manifest-", ".tmp");
        try {
            JsonUtils.standardMapper().writeValue(temporary.toFile(), value);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void archive(Path root, Session session) throws IOException {
        Path directory = root.resolve(".trash").resolve(session.id).resolve(UUID.randomUUID().toString());
        safe(directory);
        Files.createDirectories(directory);
        List<String> relative = session.paths.stream().map(p -> root.relativize(p).toString()).toList();
        Path manifest = directory.resolve("manifest.json");
        writeManifest(manifest, Map.of("sessionId", session.id,
                "paths", relative, "retiredAt", Instant.now().toEpochMilli(), "complete", false));
        try {
            finishArchive(root, directory, session.id, relative);
            writeManifest(manifest, Map.of("sessionId", session.id,
                    "paths", relative, "retiredAt", Instant.now().toEpochMilli(), "complete", true));
        } catch (IOException failure) {
            // Roll back ordinary I/O failures while the writer lease is still held.
            // Crashes leave the durable pending manifest for the next cleanup pass.
            try {
                List<String> reversed = new ArrayList<>(relative); Collections.reverse(reversed);
                for (String name : reversed) {
                    Path original = root.resolve(name), archived = directory.resolve("files").resolve(name);
                    if (!Files.exists(archived, NOFOLLOW_LINKS)) continue;
                    safe(original); safe(archived);
                    if (Files.exists(original, NOFOLLOW_LINKS)) throw new IOException("Rollback conflict: " + name);
                    Files.createDirectories(original.getParent());
                    Files.move(archived, original);
                }
                Path tombstone = root.resolve(".retired").resolve(session.id); safe(tombstone);
                if (Files.isRegularFile(tombstone, NOFOLLOW_LINKS)
                        && Files.readString(tombstone).equals(root.relativize(directory).toString()))
                    Files.delete(tombstone);
                writeManifest(manifest, Map.of("sessionId", session.id, "paths", relative,
                        "retiredAt", Instant.now().toEpochMilli(), "complete", true, "restored", true));
            } catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private static void finishArchive(Path root, Path directory, String id, List<String> relative) throws IOException {
        for (String name : relative) {
            Path source = root.resolve(name).normalize();
            Path target = directory.resolve("files").resolve(name).normalize();
            if (!source.startsWith(root) || !target.startsWith(directory.resolve("files")))
                throw new IOException("Invalid archive path");
            safe(source); safe(target);
            if (Files.exists(source, NOFOLLOW_LINKS)) {
                if (Files.exists(target, NOFOLLOW_LINKS)) throw new IOException("Conflicting archive artifact: " + name);
                Files.createDirectories(target.getParent());
                try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target); }
            } else if (!Files.exists(target, NOFOLLOW_LINKS)) {
                throw new IOException("Missing archived artifact: " + name);
            }
        }
        Path tombstone = root.resolve(".retired").resolve(id);
        safe(tombstone);
        Files.createDirectories(tombstone.getParent());
        Files.writeString(tombstone, root.relativize(directory).toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Finish interrupted archives before selecting any more sessions. No symlinks are traversed. */
    private static void recover(Path root) throws IOException {
        Path trash = root.resolve(".trash");
        safe(trash);
        if (!Files.isDirectory(trash, NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(trash, 3)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().equals("manifest.json")
                    && Files.isRegularFile(p, NOFOLLOW_LINKS)).toList()) {
                JsonNode manifest = JsonUtils.standardMapper().readTree(path.toFile());
                if (manifest.path("complete").asBoolean(false) || manifest.path("restoring").asBoolean(false)) continue;
                String id = manifest.path("sessionId").asText();
                if (!safeId(id) || activeSessions(root).contains(id) || publisherAlive(root, id)
                        || Files.exists(root.resolve(id + ".pinned"), NOFOLLOW_LINKS)) continue;
                List<String> relative = new ArrayList<>();
                manifest.path("paths").forEach(n -> relative.add(n.asText()));
                // Only paths originally owned by this ID may be recovered.
                for (String name : relative) {
                    if (!ownedPath(id, name)) throw new IOException("Invalid archive manifest");
                }
                Path writer = writerLock(root, id);
                safe(writer); Files.createDirectories(writer.getParent());
                try (FileChannel channel = FileChannel.open(writer, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) continue;
                    finishArchive(root, path.getParent(), id, relative);
                    ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("complete", true);
                    writeManifest(path, manifest);
                } catch (OverlappingFileLockException busy) { /* active local writer */ }
            }
        }
    }

    /** Explicit irreversible purge; hot sessions and provider originals are never in scope. */
    public static long purgeTrash(File conversationsDir, Duration grace, boolean dryRun) throws IOException {
        if (grace == null || grace.compareTo(Duration.ofDays(1)) < 0)
            throw new IllegalArgumentException("Trash grace must be at least one day");
        Path root = conversationsDir.toPath().toAbsolutePath().normalize();
        Path trash = root.resolve(".trash"); safe(trash);
        if (!Files.isDirectory(trash, NOFOLLOW_LINKS)) return 0;
        Path lockPath = root.resolve(".retention.lock"); safe(lockPath);
        if (dryRun) return purgeUnlocked(trash, grace, true);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another retention operation is running");
            return purgeUnlocked(trash, grace, false);
        } catch (OverlappingFileLockException busy) { throw new IOException("Retention is running", busy); }
    }

    private static long purgeUnlocked(Path trash, Duration grace, boolean dryRun) throws IOException {
        long reclaimed = 0, cutoff = Instant.now().minus(grace).toEpochMilli();
        try (Stream<Path> paths = Files.walk(trash, 3)) {
            for (Path manifestPath : paths.filter(p -> p.getFileName().toString().equals("manifest.json")
                    && Files.isRegularFile(p, NOFOLLOW_LINKS)).toList()) {
                JsonNode manifest = JsonUtils.standardMapper().readTree(manifestPath.toFile());
                if (!manifest.path("complete").asBoolean(false)
                        || manifest.path("restoring").asBoolean(false) || manifest.path("restored").asBoolean(false)
                        || manifest.path("retiredAt").asLong(Long.MAX_VALUE) >= cutoff) continue;
                String id = manifest.path("sessionId").asText();
                if (!safeId(id) || Files.exists(trash.getParent().resolve(id + ".pinned"), NOFOLLOW_LINKS)) continue;
                Path directory = manifestPath.getParent();
                if (!directory.getParent().equals(trash.resolve(id)))
                    throw new IOException("Archive session identity does not match its directory");
                reclaimed += size(directory);
                if (!dryRun) {
                    Path root = trash.getParent();
                    Path purged = root.resolve(".purged").resolve(id); safe(purged);
                    Files.createDirectories(purged.getParent());
                    try (Stream<Path> entries = Files.walk(directory)) {
                        for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
                    }
                    // Only the archive currently named by the tombstone can finalize this ID.
                    // Older archives can coexist with a newer/restorable generation.
                    Path tombstone = root.resolve(".retired").resolve(id); safe(tombstone);
                    if (Files.isRegularFile(tombstone, NOFOLLOW_LINKS)
                            && Files.readString(tombstone).strip().equals(root.relativize(directory).toString())) {
                        Files.writeString(purged, Instant.now().toString(), StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING, NOFOLLOW_LINKS);
                        try (FileChannel marker = FileChannel.open(purged, StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                            marker.force(true);
                        }
                    }
                }
            }
        }
        return reclaimed;
    }

    /** Restore an archive without replacing any newly created session data. */
    public static void restore(File conversationsDir, String id) throws IOException {
        if (!safeId(id)) throw new IllegalArgumentException("Invalid session ID");
        Path root = conversationsDir.toPath().toAbsolutePath().normalize();
        Path tombstone = root.resolve(".retired").resolve(id); safe(tombstone);
        if (!Files.isRegularFile(tombstone, NOFOLLOW_LINKS)) throw new IOException("No retired session: " + id);
        Path lockPath = root.resolve(".retention.lock"); safe(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null || activeSessions(root).contains(id)) throw new IOException("Session or retention is active");
            Path directory = root.resolve(Files.readString(tombstone).strip()).normalize();
            if (!directory.startsWith(root.resolve(".trash").resolve(id))) throw new IOException("Invalid archive pointer");
            safe(directory);
            Path manifestPath = directory.resolve("manifest.json"); safe(manifestPath);
            var manifest = JsonUtils.standardMapper().readTree(manifestPath.toFile());
            if (manifest == null || !manifest.isObject() || !id.equals(manifest.path("sessionId").asText()))
                throw new IOException("Invalid restore manifest");
            boolean resuming = manifest.path("restoring").asBoolean(false);
            List<String> names = new ArrayList<>();
            manifest.path("paths").forEach(n -> names.add(n.asText()));
            names.sort(Comparator.comparing(name -> name.equals(id + ".txt"))); // publish listing last on restore
            for (String name : names) {
                if (!ownedPath(id, name)) throw new IOException("Invalid restore path");
                Path original = root.resolve(name); safe(original);
                Path archived = directory.resolve("files").resolve(name); safe(archived);
                if (Files.exists(original, NOFOLLOW_LINKS)
                        && (!resuming || Files.exists(archived, NOFOLLOW_LINKS)))
                    throw new IOException("Restore would overwrite: " + name);
                if (!Files.exists(original, NOFOLLOW_LINKS) && !Files.exists(archived, NOFOLLOW_LINKS))
                    throw new IOException("Archive payload was purged: " + name);
            }
            Path writer = writerLock(root, id); safe(writer); Files.createDirectories(writer.getParent());
            try (FileChannel writerChannel = FileChannel.open(writer, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock writerLease = writerChannel.tryLock()) {
                if (writerLease == null) throw new IOException("Session is active");
                var object = (com.fasterxml.jackson.databind.node.ObjectNode) manifest;
                object.put("restoring", true); writeManifest(manifestPath, object);
                for (String name : names) {
                    Path original = root.resolve(name), archived = directory.resolve("files").resolve(name);
                    if (!Files.exists(archived, NOFOLLOW_LINKS)) continue;
                    Files.createDirectories(original.getParent());
                    Files.move(archived, original);
                }
                object.put("restoring", false).put("restored", true);
                writeManifest(manifestPath, object);
                Files.delete(tombstone);
            }
        } catch (OverlappingFileLockException busy) { throw new IOException("Session or retention is active", busy); }
    }

    public static void setPinned(File conversationsDir, String id, boolean pinned) throws IOException {
        if (!safeId(id)) throw new IllegalArgumentException("Invalid session ID");
        Path root = conversationsDir.toPath().toAbsolutePath().normalize();
        Path marker = root.resolve(id + ".pinned"); safe(marker);
        Files.createDirectories(root);
        Path lockPath = root.resolve(".retention.lock"); safe(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another retention operation is running");
            if (pinned) Files.writeString(marker, "Protected from transcript retention\n", StandardOpenOption.CREATE);
            else Files.deleteIfExists(marker);
        } catch (OverlappingFileLockException busy) { throw new IOException("Retention is running", busy); }
    }

    private static boolean publisherAlive(Path root, String id) throws IOException {
        Path marker = root.resolve(id).resolve("owner.pid"); safe(marker);
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) return false;
        if (Files.size(marker) > 32) throw new IOException("Invalid publisher PID marker");
        try {
            long pid = Long.parseLong(Files.readString(marker).strip());
            if (pid <= 0) throw new NumberFormatException("nonpositive PID");
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException bad) { throw new IOException("Invalid publisher PID marker", bad); }
    }

    private static Set<String> activeSessions(Path root) throws IOException {
        Path registry = root.getParent().resolve("sessions/registry.json"); safe(registry);
        Set<String> active = new HashSet<>();
        if (!Files.isRegularFile(registry, NOFOLLOW_LINKS)) return active;
        JsonNode data = JsonUtils.standardMapper().readTree(registry.toFile());
        if (data == null || !data.isObject() || (data.has("sessions") && !data.path("sessions").isArray()))
            throw new IOException("Invalid session registry; refusing cleanup");
        for (JsonNode node : data.path("sessions")) {
            long pid = node.path("pid").asLong(0);
            if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                active.add(node.path("kompileSessionId").asText());
                active.add(node.path("conversationId").asText());
            }
        }
        return active;
    }

    private static Path writerLock(Path root, String id) {
        String segment = id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                ? id.toLowerCase(Locale.ROOT) : "~" + HexFormat.of().formatHex(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return root.getParent().resolve("logs/transcripts").resolve(segment).resolve("writer.lock");
    }

    private static String source(String id) {
        int split = id.indexOf("__");
        return split > 0 ? id.substring(0, split) : "kompile";
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*") && !id.contains("..");
    }

    private static void safe(Path path) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Symlinked retention path: " + p);
    }

    private static long size(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return 0;
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) return Files.size(path);
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) return 0;
        try (Stream<Path> files = Files.walk(path)) {
            long bytes = 0;
            for (Path file : files.filter(p -> Files.isRegularFile(p, NOFOLLOW_LINKS)).toList()) bytes += Files.size(file);
            return bytes;
        }
    }

    private static long lastModified(Path path) throws IOException {
        long latest = Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis();
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : files.filter(p -> Files.isRegularFile(p, NOFOLLOW_LINKS)).toList())
                    latest = Math.max(latest, Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toMillis());
            }
        }
        return latest;
    }

    private record Session(String id, Path transcript, List<Path> paths, long bytes, long modified) {}

    public record Policy(Duration maxAge, long maxTotalBytes, int maxFilesPerSource) {

        public static final Policy DEFAULT = new Policy(
                Duration.ofDays(90),
                2L * 1024 * 1024 * 1024,
                1000);

        public Policy {
            if ((maxAge != null && maxAge.isNegative()) || maxTotalBytes < 0 || maxFilesPerSource < 0)
                throw new IllegalArgumentException("Retention limits must be nonnegative (0 disables a cap)");
        }

        public static Policy of(long maxAgeDays, long maxTotalMb, int maxFilesPerSource) {
            if (maxAgeDays < 0 || maxTotalMb < 0 || maxFilesPerSource < 0)
                throw new IllegalArgumentException("Retention limits must be nonnegative");
            return new Policy(
                    maxAgeDays > 0 ? Duration.ofDays(maxAgeDays) : Duration.ZERO,
                    Math.multiplyExact(maxTotalMb, 1024L * 1024L), maxFilesPerSource);
        }
    }

    public record Result(int deletedByAge, int deletedByCount, int deletedBySize, List<File> deletedFiles,
                         long archivedBytes, long hotBytesBefore, int protectedSessions, List<String> failures) {
        public Result(int byAge, int byCount, int bySize, List<File> files) {
            this(byAge, byCount, bySize, files, 0, 0, 0, List.of());
        }

        public Result(int byAge, int byCount, int bySize, List<File> files, long archived, long hot, int protectedCount) {
            this(byAge, byCount, bySize, files, archived, hot, protectedCount, List.of());
        }

        public int totalDeleted() {
            return deletedByAge + deletedByCount + deletedBySize;
        }

        public int totalRetired() { return totalDeleted(); }

        /** Archive moves do not free filesystem space. Purge reports actual payload bytes separately. */
        public long reclaimedBytes() { return 0; }

        public static Result empty() {
            return new Result(0, 0, 0, List.of());
        }
    }
}
