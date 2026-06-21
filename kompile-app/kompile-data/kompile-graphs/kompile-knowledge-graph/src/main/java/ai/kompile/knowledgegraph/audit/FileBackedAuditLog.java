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
import java.util.List;

/**
 * Append-only JSONL audit log for a single fact sheet.
 *
 * <p>File: {@code <dataDir>/data/graph/inferred/factsheet-<id>-audit.jsonl}</p>
 *
 * <p>Null {@code inferredDir} → in-memory-only mode (for tests).</p>
 */
@Slf4j
public class FileBackedAuditLog {

    private final Path inferredDir;
    private final long factSheetId;
    /** In-memory buffer so queries work without dataDir in tests. */
    private final List<FactAuditEvent> buffer = new ArrayList<>();

    public FileBackedAuditLog(Path dataDirPath, long factSheetId) {
        this.factSheetId = factSheetId;
        if (dataDirPath != null) {
            this.inferredDir = dataDirPath.resolve("data").resolve("graph").resolve("inferred");
            ensureDir();
        } else {
            this.inferredDir = null;
        }
    }

    /** In-memory-only constructor (no file I/O). */
    public FileBackedAuditLog(long factSheetId) {
        this(null, factSheetId);
    }

    /**
     * Append an audit event.
     * Thread-safety: callers must hold the per-fact-sheet write lock (same contract as
     * FileBackedInferredFactStore).
     */
    public synchronized void append(FactAuditEvent event) {
        buffer.add(event);
        if (inferredDir != null) {
            try (BufferedWriter w = Files.newBufferedWriter(auditFile(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(event.toJson());
                w.newLine();
            } catch (IOException e) {
                log.warn("FileBackedAuditLog[{}]: could not append audit event — {}", factSheetId, e.getMessage());
            }
        }
    }

    /**
     * Load all events, optionally filtering by atomKey and/or eventType.
     * Merges file content (if available) with the in-memory buffer.
     */
    public synchronized List<FactAuditEvent> load(String atomKeyFilter, String eventTypeFilter) {
        List<FactAuditEvent> events = new ArrayList<>();
        if (inferredDir != null && Files.exists(auditFile())) {
            try (BufferedReader r = Files.newBufferedReader(auditFile(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        events.add(FactAuditEvent.fromJson(line));
                    } catch (Exception e) {
                        log.warn("FileBackedAuditLog[{}]: skipping malformed line — {}", factSheetId, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("FileBackedAuditLog[{}]: could not read audit file — {}", factSheetId, e.getMessage());
                // fall through — return in-memory buffer below
            }
        } else {
            events.addAll(buffer);
        }
        return events.stream()
                .filter(e -> atomKeyFilter == null || atomKeyFilter.equals(e.atomKey()))
                .filter(e -> eventTypeFilter == null || eventTypeFilter.equalsIgnoreCase(e.eventType()))
                .toList();
    }

    /** Load all events without filter. */
    public List<FactAuditEvent> loadAll() {
        return load(null, null);
    }

    private Path auditFile() {
        return inferredDir.resolve("factsheet-" + factSheetId + "-audit.jsonl");
    }

    private void ensureDir() {
        try { Files.createDirectories(inferredDir); }
        catch (IOException e) { log.warn("FileBackedAuditLog: cannot create dir {} — {}", inferredDir, e.getMessage()); }
    }
}
