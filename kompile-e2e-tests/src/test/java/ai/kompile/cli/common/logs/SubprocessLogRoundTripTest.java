/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.cli.common.logs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SubprocessLogRoundTripTest {

    private String originalUserHome;

    @BeforeEach
    void redirectHome(@TempDir Path tempHome) {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void writesAndReadsSingleSubprocessRun() throws Exception {
        String type = "ingest";
        String runId = UUID.randomUUID().toString();

        try (SubprocessLogWriter writer = new SubprocessLogWriter(type, runId)) {
            writer.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    "parent-task-1", List.of("java", "-cp", "foo", "Main"),
                    "/tmp/work", 99999L, "4g"));
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "starting ingest");
            writer.writeLine(AgentLogRecord.Stream.STDERR, "warning: slow disk");
            writer.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    "COMPLETED", 0, null, false, false));
        }

        File logFile = LogPaths.subprocessLogFile(type, runId);
        File metaFile = LogPaths.subprocessMetaFile(type, runId);
        assertTrue(logFile.isFile(), "log file must exist");
        assertTrue(metaFile.isFile(), "meta file must exist");

        List<AgentLogRecord> records;
        try (Stream<AgentLogRecord> stream = AgentLogReader.readRecords(logFile)) {
            records = stream.toList();
        }
        assertEquals(4, records.size(), "expected header + 2 lines + footer");
        assertEquals(AgentLogRecord.Stream.SYSTEM, records.get(0).getStream());
        assertEquals(AgentLogRecord.Stream.STDOUT, records.get(1).getStream());
        assertEquals("starting ingest", records.get(1).getLine());
        assertEquals(AgentLogRecord.Stream.STDERR, records.get(2).getStream());
        assertEquals("warning: slow disk", records.get(2).getLine());
        assertEquals(AgentLogRecord.Stream.SYSTEM, records.get(3).getStream());

        SubprocessLogMetadata meta = AgentLogReader.findSubprocessByRunId(runId).orElseThrow();
        assertEquals("ingest", meta.getSubprocessType());
        assertEquals(runId, meta.getRunId());
        assertEquals("COMPLETED", meta.getState());
        assertEquals(0, meta.getExitCode());
        assertEquals("parent-task-1", meta.getParentTaskId());
        assertEquals(99999L, meta.getPid());
        assertEquals("4g", meta.getHeapSize());
        assertEquals(4, meta.getLinesWritten());
        assertNotNull(meta.getStartedAt());
        assertNotNull(meta.getEndedAt());
        assertNotNull(meta.getDurationMs());
    }

    @Test
    void listSubprocessRunsFiltersByType() throws Exception {
        writeSubprocessRun("ingest", "run-1");
        writeSubprocessRun("vector-population", "run-2");
        writeSubprocessRun("ingest", "run-3");

        List<SubprocessLogMetadata> all =
                AgentLogReader.listSubprocessRuns(AgentLogReader.SubprocessRunFilter.none());
        assertEquals(3, all.size());

        AgentLogReader.SubprocessRunFilter onlyIngest = new AgentLogReader.SubprocessRunFilter(
                "ingest", null, null, null);
        List<SubprocessLogMetadata> ingestRuns = AgentLogReader.listSubprocessRuns(onlyIngest);
        assertEquals(2, ingestRuns.size());

        AgentLogReader.SubprocessRunFilter byRunId = new AgentLogReader.SubprocessRunFilter(
                null, "run-2", null, null);
        List<SubprocessLogMetadata> run2 = AgentLogReader.listSubprocessRuns(byRunId);
        assertEquals(1, run2.size());
        assertEquals("vector-population", run2.get(0).getSubprocessType());
    }

    @Test
    void aggregateAcrossSubprocessRunsMergesInTimestampOrder() throws Exception {
        writeSubprocessRun("ingest", "run-A");
        Thread.sleep(5);
        writeSubprocessRun("embedding", "run-B");

        List<SubprocessLogMetadata> runs =
                AgentLogReader.listSubprocessRuns(AgentLogReader.SubprocessRunFilter.none());
        List<AgentLogRecord> merged;
        try (Stream<AgentLogRecord> s = AgentLogReader.aggregateAcrossSubprocessRuns(runs)) {
            merged = s.toList();
        }

        Instant prev = Instant.EPOCH;
        for (AgentLogRecord r : merged) {
            assertNotNull(r.getAgentName(), "subprocess type propagated via agentName field");
            assertNotNull(r.getProcessId(), "runId propagated via processId field");
            assertFalse(r.getTs().isBefore(prev), "records must be ascending timestamp");
            prev = r.getTs();
        }
        assertTrue(merged.size() >= 8, "header + 2 + footer per run = 8 records minimum");
    }

    @Test
    void subprocessRetentionByPerTypeCap() throws Exception {
        writeSubprocessRun("ingest", "run-1");
        writeSubprocessRun("ingest", "run-2");
        writeSubprocessRun("ingest", "run-3");

        File oldLog = LogPaths.subprocessLogFile("ingest", "run-1");
        File oldMeta = LogPaths.subprocessMetaFile("ingest", "run-1");
        long oldTs = System.currentTimeMillis() - 1000;
        assertTrue(oldLog.setLastModified(oldTs));
        assertTrue(oldMeta.setLastModified(oldTs));

        LogRetentionPolicy policy = new LogRetentionPolicy(Duration.ofDays(30), 1L << 40, 2);
        LogRetentionManager.RetentionResult result = new LogRetentionManager(policy).applyToSubprocesses();

        assertEquals(1, result.deletedByPerAgent(),
                "per-agent cap doubles as per-type cap for subprocesses");
        assertFalse(oldLog.exists(), "oldest log should be deleted");
        assertFalse(oldMeta.exists(), "oldest meta should be deleted");
    }

    @Test
    void subprocessRetentionByAge() throws Exception {
        writeSubprocessRun("ingest", "fresh");
        writeSubprocessRun("ingest", "stale");

        File staleLog = LogPaths.subprocessLogFile("ingest", "stale");
        File staleMeta = LogPaths.subprocessMetaFile("ingest", "stale");
        long tenDaysAgo = System.currentTimeMillis() - Duration.ofDays(10).toMillis();
        assertTrue(staleLog.setLastModified(tenDaysAgo));
        assertTrue(staleMeta.setLastModified(tenDaysAgo));

        LogRetentionPolicy policy = new LogRetentionPolicy(Duration.ofDays(5), 1L << 40, 1000);
        LogRetentionManager.RetentionResult result = new LogRetentionManager(policy).applyToSubprocesses();

        assertEquals(1, result.deletedByAge());
        assertFalse(staleLog.exists());
        assertTrue(LogPaths.subprocessLogFile("ingest", "fresh").exists(), "fresh run must remain");
    }

    private static void writeSubprocessRun(String type, String runId) throws Exception {
        try (SubprocessLogWriter writer = new SubprocessLogWriter(type, runId)) {
            writer.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    null, List.of("cmd"), null, 1L, null));
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "a");
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "b");
            writer.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    "COMPLETED", 0, null, false, false));
        }
    }
}
