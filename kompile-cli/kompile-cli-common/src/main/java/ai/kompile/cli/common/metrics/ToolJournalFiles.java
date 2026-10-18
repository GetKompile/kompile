package ai.kompile.cli.common.metrics;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/** Stable OS lock inodes shared by all catalog writers and maintenance operations. */
public final class ToolJournalFiles {
    private ToolJournalFiles() { }
    // Fixed stripes avoid retaining every session path after its payloads are purged.
    private static final ReentrantLock[] LOCKS = java.util.stream.IntStream.range(0, 64)
            .mapToObj(ignored -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    @FunctionalInterface public interface IOAction<T> { T run() throws IOException; }

    private static final java.util.Map<Path, SharedLease> SHARED = new java.util.HashMap<>();
    private static final class SharedLease {
        final FileChannel channel;
        final FileLock lock;
        int holders = 1;
        SharedLease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    }

    /** Reuses one shared OS lease across nested/concurrent publishers in this JVM. */
    public static <T> T sharedRetention(Path root, IOAction<T> action) throws IOException {
        Path file = root.toAbsolutePath().normalize().resolve(".retention.lock"); safe(file);
        SharedLease lease;
        synchronized (SHARED) {
            lease = SHARED.get(file);
            if (lease != null) lease.holders++;
            else {
                Files.createDirectories(file.getParent());
                FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                try {
                    FileLock lock = channel.tryLock(0, Long.MAX_VALUE, true);
                    if (lock == null) throw new IOException("Conversation retention is active");
                    lease = new SharedLease(channel, lock); SHARED.put(file, lease);
                } catch (IOException | java.nio.channels.OverlappingFileLockException busy) {
                    channel.close(); throw new IOException("Conversation retention is active", busy);
                }
            }
        }
        try { return action.run(); }
        finally {
            synchronized (SHARED) {
                if (--lease.holders == 0) {
                    SHARED.remove(file);
                    try { lease.lock.release(); } finally { lease.channel.close(); }
                }
            }
        }
    }

    public static void safe(Path path) throws IOException {
        for (Path current = path.toAbsolutePath(); current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Symlinked journal path: " + current);
    }

    public static Path lockPath(Path file) {
        return file.resolveSibling("." + file.getFileName() + ".lock");
    }

    public static <T> T locked(Path file, IOAction<T> action) throws IOException {
        Path lockFile = lockPath(file.toAbsolutePath().normalize());
        safe(file); safe(lockFile);
        ReentrantLock local = LOCKS[Math.floorMod(lockFile.hashCode(), LOCKS.length)];
        local.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.lock()) {
                return action.run();
            }
        } finally { local.unlock(); }
    }

    /** Pair stays serialized with compaction across both JVM threads and processes. */
    public static void appendCatalog(Path directory, String session, String jsonLine) throws IOException {
        ToolCallUsageJournal.validateSessionKey(session);
        if (!session.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*") || session.contains(".."))
            throw new IOException("Unsafe catalog session ID");
        Path combined = directory.resolve("all-tool-calls.jsonl");
        locked(combined, () -> {
            Path perSession = directory.resolve(session + ".jsonl"); safe(perSession);
            Path purged = directory.getParent().resolve(".purged").resolve(session); safe(purged);
            if (Files.exists(purged, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Session was permanently purged; use a new session ID");
            Files.writeString(perSession, jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS);
            Files.writeString(combined, jsonLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS);
            return null;
        });
    }
}
