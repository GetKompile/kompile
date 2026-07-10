package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * In-memory + JSONL-backed store for {@link GraphMutationRecord}.
 * Keeps up to 100,000 entries in a bounded deque (drops oldest on overflow).
 * Every save is also appended to ~/.kompile/graph-mutations.jsonl.
 */
@Component
@Slf4j
public class GraphMutationStore {

    private static final int MAX_IN_MEMORY = 100_000;

    private final Deque<GraphMutationRecord> records = new ArrayDeque<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong idSeq = new AtomicLong(1);
    private final ObjectMapper objectMapper;
    private final Path jsonlPath;

    // With the package-visible test constructor below, this class has two
    // constructors — Spring's implicit single-constructor injection no longer
    // applies, so the primary must be marked explicitly or context startup
    // fails with "No default constructor found".
    @Autowired
    public GraphMutationStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonlPath = resolveStorePath();
        ensureParentDir(jsonlPath);
        rehydrateFromJsonl();
    }

    /**
     * Package-visible constructor for tests that need a custom JSONL path
     * (e.g. using a TempDir instead of ~/.kompile).
     */
    GraphMutationStore(ObjectMapper objectMapper, Path customPath) {
        this.objectMapper = objectMapper;
        this.jsonlPath = customPath;
        ensureParentDir(jsonlPath);
        rehydrateFromJsonl();
    }

    /**
     * On startup, read the tail of the JSONL file and populate the in-memory deque.
     * Reads up to {@link #MAX_IN_MEMORY} records from the file (newest wins when capacity is
     * exceeded). Parsing failures for individual lines are logged and skipped — the rest of
     * the file is still loaded.
     */
    private void rehydrateFromJsonl() {
        if (!Files.exists(jsonlPath)) {
            return;
        }
        List<GraphMutationRecord> loaded = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(jsonlPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    GraphMutationRecord rec = objectMapper.readValue(line, GraphMutationRecord.class);
                    loaded.add(rec);
                } catch (Exception e) {
                    log.warn("GraphMutationStore: skipping malformed JSONL line during rehydration: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("GraphMutationStore: could not rehydrate from {}: {}", jsonlPath, e.getMessage());
            return;
        }

        if (loaded.isEmpty()) {
            return;
        }

        // Keep only the newest MAX_IN_MEMORY entries (file is oldest-first, so take the tail).
        int startIdx = loaded.size() > MAX_IN_MEMORY ? loaded.size() - MAX_IN_MEMORY : 0;
        lock.writeLock().lock();
        try {
            // records deque is newest-first; iterate loaded list in reverse order
            for (int i = loaded.size() - 1; i >= startIdx; i--) {
                GraphMutationRecord rec = loaded.get(i);
                // update idSeq so new saves don't collide
                if (rec.getId() != null && rec.getId() >= idSeq.get()) {
                    idSeq.set(rec.getId() + 1);
                }
                records.addLast(rec);  // add to end (oldest-first within load batch); deque is newest-first normally
            }
        } finally {
            lock.writeLock().unlock();
        }
        log.info("GraphMutationStore: rehydrated {} records from {}", loaded.size() - startIdx, jsonlPath);
    }

    private static Path resolveStorePath() {
        return Paths.get(System.getProperty("user.home"), ".kompile", "graph-mutations.jsonl");
    }

    private static void ensureParentDir(Path p) {
        try {
            Files.createDirectories(p.getParent());
        } catch (IOException e) {
            log.warn("Could not create directory for {}: {}", p, e.getMessage());
        }
    }

    /** Persist and index a mutation record. */
    public GraphMutationRecord save(GraphMutationRecord record) {
        record.initDefaults();
        lock.writeLock().lock();
        try {
            if (record.getId() == null) {
                record.setId(idSeq.getAndIncrement());
            }
            records.addFirst(record); // newest-first order
            if (records.size() > MAX_IN_MEMORY) {
                records.removeLast();
            }
        } finally {
            lock.writeLock().unlock();
        }
        appendToJsonl(record);
        return record;
    }

    public List<GraphMutationRecord> findByEntityKindAndEntityIdOrderByOccurredAtDesc(
            String entityKind, String entityId) {
        lock.readLock().lock();
        try {
            return records.stream()
                    .filter(r -> entityKind.equals(r.getEntityKind()) && entityId.equals(r.getEntityId()))
                    .sorted((a, b) -> b.getOccurredAt().compareTo(a.getOccurredAt()))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Page<GraphMutationRecord> findByFactSheetIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            Long factSheetId, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        lock.readLock().lock();
        try {
            List<GraphMutationRecord> filtered = records.stream()
                    .filter(r -> factSheetId.equals(r.getFactSheetId())
                            && r.getOccurredAt() != null
                            && !r.getOccurredAt().isBefore(from)
                            && !r.getOccurredAt().isAfter(to))
                    .sorted((a, b) -> b.getOccurredAt().compareTo(a.getOccurredAt()))
                    .collect(Collectors.toList());
            return toPage(filtered, pageable);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<GraphMutationRecord> findByChangesetId(String changesetId) {
        lock.readLock().lock();
        try {
            return records.stream()
                    .filter(r -> changesetId.equals(r.getChangesetId()))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Page<GraphMutationRecord> findByTriggerSourceStartingWithOrderByOccurredAtDesc(
            String triggerSourcePrefix, Pageable pageable) {
        lock.readLock().lock();
        try {
            List<GraphMutationRecord> filtered = records.stream()
                    .filter(r -> r.getTriggerSource() != null
                            && r.getTriggerSource().startsWith(triggerSourcePrefix))
                    .sorted((a, b) -> b.getOccurredAt().compareTo(a.getOccurredAt()))
                    .collect(Collectors.toList());
            return toPage(filtered, pageable);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Page<GraphMutationRecord> findByOccurredAtBetweenOrderByOccurredAtDesc(
            LocalDateTime from, LocalDateTime to, Pageable pageable) {
        lock.readLock().lock();
        try {
            List<GraphMutationRecord> filtered = records.stream()
                    .filter(r -> r.getOccurredAt() != null
                            && !r.getOccurredAt().isBefore(from)
                            && !r.getOccurredAt().isAfter(to))
                    .sorted((a, b) -> b.getOccurredAt().compareTo(a.getOccurredAt()))
                    .collect(Collectors.toList());
            return toPage(filtered, pageable);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Page<GraphMutationRecord> findByFactSheetIdOrderByOccurredAtDesc(
            Long factSheetId, Pageable pageable) {
        lock.readLock().lock();
        try {
            List<GraphMutationRecord> filtered = records.stream()
                    .filter(r -> factSheetId.equals(r.getFactSheetId()))
                    .sorted((a, b) -> b.getOccurredAt().compareTo(a.getOccurredAt()))
                    .collect(Collectors.toList());
            return toPage(filtered, pageable);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Page<GraphMutationRecord> findAll(Pageable pageable) {
        lock.readLock().lock();
        try {
            List<GraphMutationRecord> all = new ArrayList<>(records);
            return toPage(all, pageable);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the most recent record(s) for an entity as-of a point in time.
     * Respects pageable.getPageSize() as a result cap (matches JPA query intent).
     */
    public List<GraphMutationRecord> findMostRecentBefore(
            String entityKind, String entityId, LocalDateTime asOf, Pageable pageable) {
        lock.readLock().lock();
        try {
            return records.stream()
                    .filter(r -> entityKind.equals(r.getEntityKind())
                            && entityId.equals(r.getEntityId())
                            && r.getOccurredAt() != null
                            && !r.getOccurredAt().isAfter(asOf))
                    .sorted((a, b) -> b.getOccurredAt().compareTo(a.getOccurredAt()))
                    .limit(pageable.isPaged() ? pageable.getPageSize() : Long.MAX_VALUE)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Delete in-memory records older than {@code before} and compact the JSONL file to match.
     *
     * <p>The JSONL compaction is an atomic temp+move rewrite so readers never see a partially
     * written file. If the atomic move is not supported (e.g. across filesystems) it falls back
     * to a non-atomic copy-then-delete. A compaction failure does NOT roll back the in-memory
     * deletion — the memory and file may temporarily diverge until the next append brings them
     * in sync.</p>
     *
     * @param before records with {@code occurredAt} strictly before this timestamp are removed
     * @return the number of in-memory records removed
     */
    public int deleteOlderThan(LocalDateTime before) {
        List<GraphMutationRecord> survivors;
        int removed;
        lock.writeLock().lock();
        try {
            int beforeSize = records.size();
            records.removeIf(r -> r.getOccurredAt() != null && r.getOccurredAt().isBefore(before));
            removed = beforeSize - records.size();
            // Snapshot the survivors (oldest-first for the file) while still under the write lock.
            // records deque is newest-first, so reverse for the file.
            survivors = new ArrayList<>(records);
            Collections.reverse(survivors);
        } finally {
            lock.writeLock().unlock();
        }

        if (removed > 0) {
            compactJsonl(survivors);
        }
        return removed;
    }

    /**
     * Rewrite the JSONL file to contain exactly {@code records} (in oldest-first order).
     * Uses an atomic temp+move strategy to minimise the window of an incomplete file.
     */
    private void compactJsonl(List<GraphMutationRecord> survivors) {
        Path tmp = jsonlPath.resolveSibling(jsonlPath.getFileName() + ".compact.tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (GraphMutationRecord rec : survivors) {
                writer.write(objectMapper.writeValueAsString(rec));
                writer.newLine();
            }
        } catch (IOException e) {
            log.warn("GraphMutationStore: failed to write compaction temp file {}: {}", tmp, e.getMessage());
            return;
        }

        try {
            Files.move(tmp, jsonlPath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            try {
                Files.move(tmp, jsonlPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                log.warn("GraphMutationStore: compaction move failed (non-atomic fallback) for {}: {}",
                        jsonlPath, e2.getMessage());
            }
        } catch (IOException e) {
            log.warn("GraphMutationStore: compaction atomic move failed for {}: {}", jsonlPath, e.getMessage());
        }
        log.info("GraphMutationStore: compacted {} — retained {} records", jsonlPath, survivors.size());
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static <T> Page<T> toPage(List<T> all, Pageable pageable) {
        if (!pageable.isPaged()) {
            return new PageImpl<>(all);
        }
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), all.size());
        List<T> page = start >= all.size() ? List.of() : all.subList(start, end);
        return new PageImpl<>(page, pageable, all.size());
    }

    private void appendToJsonl(GraphMutationRecord record) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                jsonlPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(objectMapper.writeValueAsString(record));
            writer.newLine();
        } catch (IOException e) {
            log.warn("Failed to append mutation record to {}: {}", jsonlPath, e.getMessage());
        }
    }
}
