package ai.kompile.cli.main.codeindex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded-frequency, in-place SQLite maintenance shared by indexing and background refresh. */
public final class IndexMaintenance {
    private IndexMaintenance() {}

    private static final Map<Path, Result> LAST = new ConcurrentHashMap<>();
    public record Result(Instant checkedAt, String status, boolean reindexRequired, String detail) {
        public String summary() { return status + " at " + checkedAt + ": " + detail; }
    }

    /** Explicit retry bypasses the periodic throttle; never deletes a database or WAL sidecar. */
    public static Result repair(String projectId) throws IOException {
        Path dir = LocalCodeIndexer.getIndexDir(projectId);
        try (var lock = IndexLockManager.acquireWriteLock(projectId, dir)) {
            return checkLocked(dir, new IndexFileStore(dir,
                    ai.kompile.cli.common.util.JsonUtils.standardMapper()).loadMetadata(), true);
        }
    }

    /** Caller must hold the project write lock. Failed checks are throttled too, to avoid repair loops. */
    static Result checkLocked(Path dir, Map<String, Object> metadata, boolean force) throws IOException {
        Path key = dir.toAbsolutePath().normalize();
        Instant now = Instant.now();
        if (!Files.exists(dir.resolve("index.db"))) {
            Result missing = new Result(now, "MISSING", true, "Source reindex required to restore entities and relations");
            LAST.put(key, missing);
            return missing;
        }
        long interval = Math.max(0, Long.getLong("kompile.codeIndex.integrityIntervalMs", 300_000L));
        Result previous = LAST.get(key);
        if (!force && previous != null && now.toEpochMilli() - previous.checkedAt().toEpochMilli() < interval) {
            if ("FAILED".equals(previous.status())) throw new IOException(previous.summary());
            return previous;
        }
        try (IndexDatabase db = IndexDatabase.open(dir)) {
            boolean repaired = db.checkAndRepair();
            String generation = db.getIndexGeneration();
            Object expected = metadata.get("indexedAt");
            boolean reindex = Files.exists(dir.resolve("update.pending"))
                    || !Objects.equals(generation, expected == null ? null : expected.toString());
            Result result = new Result(now, repaired ? "REPAIRED" : "HEALTHY", reindex,
                    (repaired ? "FTS rebuilt and verified from entities_meta" : "SQLite quick_check and FTS content check passed")
                    + (reindex ? "; interrupted update or generation mismatch: source reindex required" : ""));
            LAST.put(key, result);
            if (repaired) CodeIndexDiagnostics.alert("[code-index] " + key + ": " + result.summary());
            return result;
        } catch (SQLException failure) {
            String detail = "SQLite code " + failure.getErrorCode() + ": " + failure.getMessage();
            if (IndexDatabase.isCorruption(failure)) {
                detail += "; in-place repair unavailable. Stop ALL index clients before offline recovery; "
                        + "preserve index.db and its WAL/SHM together. No database files were replaced or deleted.";
            } else {
                detail += "; no destructive recovery attempted (contention, I/O, and schema errors are not corruption)";
            }
            LAST.put(key, new Result(now, "FAILED", false, detail));
            throw new IOException(detail, failure);
        }
    }

    /** A failed update needs a fresh integrity check on the next maintenance pass. */
    static void invalidate(Path dir) { LAST.remove(dir.toAbsolutePath().normalize()); }

    public static String status(String projectId) {
        Result result = LAST.get(LocalCodeIndexer.getIndexDir(projectId).toAbsolutePath().normalize());
        return result == null ? "not checked in this process" : result.summary();
    }
}
