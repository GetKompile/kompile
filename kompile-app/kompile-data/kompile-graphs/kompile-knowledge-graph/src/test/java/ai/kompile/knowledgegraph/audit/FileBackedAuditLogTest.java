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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FileBackedAuditLog} — append, load, and filter behaviours.
 */
class FileBackedAuditLogTest {

    // ── In-memory mode ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("in-memory mode: append then loadAll returns all appended events")
    void inMemory_appendAndLoadAll() {
        FileBackedAuditLog log = new FileBackedAuditLog(99L);

        FactAuditEvent derived  = FactAuditEvent.derived("foo(x)", Double.NaN, 0.7, Double.NaN, 0.7, "run-1", null);
        FactAuditEvent asserted = FactAuditEvent.asserted("bar(y)", 0.9, "s1", "CRAWL");

        log.append(derived);
        log.append(asserted);

        List<FactAuditEvent> all = log.loadAll();
        assertEquals(2, all.size());
        assertEquals("DERIVED",  all.get(0).eventType());
        assertEquals("ASSERTED", all.get(1).eventType());
    }

    @Test
    @DisplayName("in-memory mode: filter by atomKey returns only matching events")
    void inMemory_filterByAtomKey() {
        FileBackedAuditLog log = new FileBackedAuditLog(100L);

        log.append(FactAuditEvent.derived("foo(x)", Double.NaN, 0.7, Double.NaN, 0.7, "run-1", null));
        log.append(FactAuditEvent.derived("bar(y)", Double.NaN, 0.5, Double.NaN, 0.5, "run-1", null));
        log.append(FactAuditEvent.asserted("foo(x)", 0.9, "s1", "CRAWL"));

        List<FactAuditEvent> fooOnly = log.load("foo(x)", null);
        assertEquals(2, fooOnly.size(), "Expected 2 events for foo(x)");
        assertTrue(fooOnly.stream().allMatch(e -> "foo(x)".equals(e.atomKey())));
    }

    @Test
    @DisplayName("in-memory mode: filter by eventType returns only matching events")
    void inMemory_filterByEventType() {
        FileBackedAuditLog log = new FileBackedAuditLog(101L);

        log.append(FactAuditEvent.derived("foo(x)", Double.NaN, 0.7, Double.NaN, 0.7, "run-1", null));
        log.append(FactAuditEvent.asserted("bar(y)", 0.9, "s1", "CRAWL"));
        log.append(FactAuditEvent.corrected("baz(z)", 0.5, 0.0, 0.5, 0.0, "HUMAN:adam", null, true, "wrong"));

        List<FactAuditEvent> correctedOnly = log.load(null, "CORRECTED");
        assertEquals(1, correctedOnly.size());
        assertEquals("baz(z)", correctedOnly.get(0).atomKey());
    }

    @Test
    @DisplayName("in-memory mode: no events → loadAll returns empty list")
    void inMemory_empty() {
        FileBackedAuditLog log = new FileBackedAuditLog(102L);
        assertTrue(log.loadAll().isEmpty());
    }

    // ── File-backed mode ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("file-backed mode: appended events survive a new log instance (disk reload)")
    void fileBacked_appendedEventsSurvivediskReload(@TempDir Path tmpDir) {
        long factSheetId = 200L;

        // First instance: append two events
        FileBackedAuditLog log1 = new FileBackedAuditLog(tmpDir, factSheetId);
        log1.append(FactAuditEvent.derived("alpha(x)", Double.NaN, 0.6, Double.NaN, 0.6, "run-A", null));
        log1.append(FactAuditEvent.corrected("beta(y)", 0.4, 0.9, 0.4, 0.9, "HUMAN:bob", null, true, "manual fix"));

        // Second instance: reads from disk
        FileBackedAuditLog log2 = new FileBackedAuditLog(tmpDir, factSheetId);
        List<FactAuditEvent> all = log2.loadAll();

        assertEquals(2, all.size(), "Both events must be persisted and reloaded");
        assertEquals("DERIVED",   all.get(0).eventType());
        assertEquals("CORRECTED", all.get(1).eventType());
        assertEquals("alpha(x)",  all.get(0).atomKey());
        assertEquals("beta(y)",   all.get(1).atomKey());
    }

    @Test
    @DisplayName("file-backed mode: filter by atomKey + eventType works after reload")
    void fileBacked_filterAfterReload(@TempDir Path tmpDir) {
        long factSheetId = 201L;

        FileBackedAuditLog log = new FileBackedAuditLog(tmpDir, factSheetId);
        log.append(FactAuditEvent.derived("foo(x)", Double.NaN, 0.7, Double.NaN, 0.7, "run-1", null));
        log.append(FactAuditEvent.asserted("foo(x)", 0.95, "s1", "CRAWL"));
        log.append(FactAuditEvent.derived("bar(y)", Double.NaN, 0.3, Double.NaN, 0.3, "run-1", null));

        // New instance reads from disk, then filters
        FileBackedAuditLog log2 = new FileBackedAuditLog(tmpDir, factSheetId);
        List<FactAuditEvent> derivedFoo = log2.load("foo(x)", "DERIVED");

        assertEquals(1, derivedFoo.size());
        assertEquals("DERIVED", derivedFoo.get(0).eventType());
        assertEquals("foo(x)",  derivedFoo.get(0).atomKey());
    }

    @Test
    @DisplayName("file-backed mode: audit file is created under <dataDir>/data/graph/inferred/")
    void fileBacked_fileCreatedAtCorrectPath(@TempDir Path tmpDir) {
        long factSheetId = 202L;
        FileBackedAuditLog log = new FileBackedAuditLog(tmpDir, factSheetId);
        log.append(FactAuditEvent.asserted("test(x)", 1.0, "s1", "CRAWL"));

        Path expectedFile = tmpDir.resolve("data").resolve("graph").resolve("inferred")
                .resolve("factsheet-" + factSheetId + "-audit.jsonl");
        assertTrue(expectedFile.toFile().exists(),
                "Audit file must exist at: " + expectedFile);
    }
}
