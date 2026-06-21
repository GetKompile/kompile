/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.audit;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable store for {@link PinRecord}s, backed by
 * {@code <dataDir>/data/graph/inferred/factsheet-<id>-pins.jsonl}.
 *
 * <p>The file holds one JSON object per line. On each write the full current pin state is
 * rewritten (latest-state semantics, not append-only), so a reverted PIN shows {@code pinned=false}
 * in the file. This makes the file readable as a snapshot.</p>
 *
 * <p>Null dataDir → in-memory-only mode (for tests).</p>
 */
@Slf4j
public class FileBackedPinStore {

    private final Path inferredDir;
    private final long factSheetId;
    /** Latest PinRecord per atomKey (only last write per key is kept). */
    private final Map<String, PinRecord> pins = new LinkedHashMap<>();

    public FileBackedPinStore(Path dataDirPath, long factSheetId) {
        this.factSheetId = factSheetId;
        if (dataDirPath != null) {
            this.inferredDir = dataDirPath.resolve("data").resolve("graph").resolve("inferred");
            ensureDir();
            loadFromDisk();
        } else {
            this.inferredDir = null;
        }
    }

    /** In-memory-only constructor (no file I/O). */
    public FileBackedPinStore(long factSheetId) {
        this(null, factSheetId);
    }

    /** Save or update a pin record. Thread-safe via synchronized. */
    public synchronized void save(PinRecord record) {
        pins.put(record.atomKey(), record);
        persist();
    }

    /** Return the latest PinRecord for this atom key, if any. */
    public synchronized Optional<PinRecord> get(String atomKey) {
        return Optional.ofNullable(pins.get(atomKey));
    }

    /** True iff atomKey has an active (pinned=true) pin record. */
    public synchronized boolean isPinned(String atomKey) {
        PinRecord r = pins.get(atomKey);
        return r != null && r.pinned();
    }

    /** All pin records (including reverted ones). */
    public synchronized Collection<PinRecord> all() {
        return List.copyOf(pins.values());
    }

    /** All currently active (pinned=true) records. */
    public synchronized List<PinRecord> activePins() {
        List<PinRecord> result = new ArrayList<>();
        for (PinRecord r : pins.values()) {
            if (r.pinned()) result.add(r);
        }
        return result;
    }

    // ── File helpers ──────────────────────────────────────────────────────────────

    private Path pinsFile() {
        return inferredDir.resolve("factsheet-" + factSheetId + "-pins.jsonl");
    }

    private void ensureDir() {
        try { Files.createDirectories(inferredDir); }
        catch (IOException e) { log.warn("FileBackedPinStore: cannot create dir {}", inferredDir); }
    }

    private void loadFromDisk() {
        Path file = pinsFile();
        if (!Files.exists(file)) return;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    PinRecord rec = PinRecord.fromJson(line);
                    pins.put(rec.atomKey(), rec);
                } catch (Exception e) {
                    log.warn("FileBackedPinStore[{}]: skipping malformed line — {}", factSheetId, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("FileBackedPinStore[{}]: could not read pins file — {}", factSheetId, e.getMessage());
        }
    }

    private void persist() {
        if (inferredDir == null) return;
        Path tmp = inferredDir.resolve("factsheet-" + factSheetId + "-pins.jsonl.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (PinRecord r : pins.values()) {
                w.write(r.toJson());
                w.newLine();
            }
        } catch (IOException e) {
            log.warn("FileBackedPinStore[{}]: could not write pins file — {}", factSheetId, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, pinsFile(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.move(tmp, pinsFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ex) { log.warn("FileBackedPinStore[{}]: could not move pins file — {}", factSheetId, ex.getMessage()); }
        }
    }
}
