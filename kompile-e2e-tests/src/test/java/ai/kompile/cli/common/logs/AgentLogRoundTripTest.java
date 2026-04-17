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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AgentLogRoundTripTest {

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
    void writesAndReadsSingleRun() throws Exception {
        String instance = "inst-A";
        String agent = "claude-cli";
        String processId = UUID.randomUUID().toString();

        try (AgentLogWriter writer = new AgentLogWriter(instance, agent, processId)) {
            writer.writeStart(new AgentLogWriter.AgentRunContext(
                    42L, List.of("claude", "-p", "hello"), "/tmp/work", 12345L));
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "first line");
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "second line");
            writer.writeEnd(new AgentLogWriter.AgentRunResult("COMPLETED", 0, null, 0.01, 3));
        }

        File logFile = LogPaths.agentLogFile(instance, agent, processId);
        File metaFile = LogPaths.agentMetaFile(instance, agent, processId);
        assertTrue(logFile.isFile(), "log file must exist");
        assertTrue(metaFile.isFile(), "meta file must exist");

        List<AgentLogRecord> records;
        try (Stream<AgentLogRecord> stream = AgentLogReader.readRecords(logFile)) {
            records = stream.toList();
        }
        // header + 2 lines + footer
        assertEquals(4, records.size(), "expected 4 records in log");
        assertEquals(AgentLogRecord.Stream.SYSTEM, records.get(0).getStream());
        assertEquals(AgentLogRecord.Stream.STDOUT, records.get(1).getStream());
        assertEquals("first line", records.get(1).getLine());
        assertEquals(AgentLogRecord.Stream.STDOUT, records.get(2).getStream());
        assertEquals("second line", records.get(2).getLine());
        assertEquals(AgentLogRecord.Stream.SYSTEM, records.get(3).getStream());

        AgentLogMetadata meta = AgentLogReader.findByProcessId(processId).orElseThrow();
        assertEquals("COMPLETED", meta.getState());
        assertEquals(0, meta.getExitCode());
        assertEquals(42L, meta.getSessionId());
        assertEquals(4, meta.getLinesWritten());
        assertNotNull(meta.getStartedAt());
        assertNotNull(meta.getEndedAt());
        assertNotNull(meta.getDurationMs());
    }

    @Test
    void listRunsFiltersByAgent() throws Exception {
        writeRun("inst-A", "claude-cli", "proc-1", 1L);
        writeRun("inst-A", "codex-cli", "proc-2", 2L);
        writeRun("inst-B", "claude-cli", "proc-3", 3L);

        List<AgentLogMetadata> all = AgentLogReader.listRuns(AgentLogReader.RunFilter.none());
        assertEquals(3, all.size());

        AgentLogReader.RunFilter onlyClaude = new AgentLogReader.RunFilter(
                null, "claude-cli", null, null, null);
        List<AgentLogMetadata> claudeRuns = AgentLogReader.listRuns(onlyClaude);
        assertEquals(2, claudeRuns.size());

        AgentLogReader.RunFilter onlyInstA = new AgentLogReader.RunFilter(
                "inst-A", null, null, null, null);
        assertEquals(2, AgentLogReader.listRuns(onlyInstA).size());

        AgentLogReader.RunFilter bySession = new AgentLogReader.RunFilter(
                null, null, 2L, null, null);
        List<AgentLogMetadata> session2 = AgentLogReader.listRuns(bySession);
        assertEquals(1, session2.size());
        assertEquals("proc-2", session2.get(0).getProcessId());
    }

    @Test
    void aggregateMergesInTimestampOrder() throws Exception {
        writeRun("inst-A", "claude-cli", "proc-A", 10L);
        Thread.sleep(5);
        writeRun("inst-A", "codex-cli", "proc-B", 20L);

        List<AgentLogMetadata> runs = AgentLogReader.listRuns(AgentLogReader.RunFilter.none());
        List<AgentLogRecord> merged;
        try (Stream<AgentLogRecord> s = AgentLogReader.aggregateAcrossRuns(runs)) {
            merged = s.toList();
        }

        Instant prev = Instant.EPOCH;
        for (AgentLogRecord r : merged) {
            assertNotNull(r.getAgentName(), "agent name propagated");
            assertNotNull(r.getProcessId(), "processId propagated");
            assertFalse(r.getTs().isBefore(prev), "records must be in ascending timestamp order");
            prev = r.getTs();
        }
        assertTrue(merged.size() >= 8, "should have header+2+footer per run = 8 records");
    }

    @Test
    void retentionByPerAgentCap() throws Exception {
        writeRun("inst-A", "claude-cli", "proc-1", 1L);
        writeRun("inst-A", "claude-cli", "proc-2", 2L);
        writeRun("inst-A", "claude-cli", "proc-3", 3L);

        // Make proc-1 older
        File oldLog = LogPaths.agentLogFile("inst-A", "claude-cli", "proc-1");
        File oldMeta = LogPaths.agentMetaFile("inst-A", "claude-cli", "proc-1");
        long oldTs = System.currentTimeMillis() - 1000;
        assertTrue(oldLog.setLastModified(oldTs));
        assertTrue(oldMeta.setLastModified(oldTs));

        LogRetentionPolicy policy = new LogRetentionPolicy(Duration.ofDays(30), 1L << 40, 2);
        LogRetentionManager.RetentionResult result = new LogRetentionManager(policy).applyToAgents();

        assertEquals(1, result.deletedByPerAgent());
        assertFalse(oldLog.exists(), "oldest log should be deleted");
        assertFalse(oldMeta.exists(), "oldest meta should be deleted");
    }

    @Test
    void retentionByAge() throws Exception {
        writeRun("inst-A", "claude-cli", "fresh", 1L);
        writeRun("inst-A", "claude-cli", "stale", 2L);

        File staleLog = LogPaths.agentLogFile("inst-A", "claude-cli", "stale");
        File staleMeta = LogPaths.agentMetaFile("inst-A", "claude-cli", "stale");
        long tenDaysAgo = System.currentTimeMillis() - Duration.ofDays(10).toMillis();
        assertTrue(staleLog.setLastModified(tenDaysAgo));
        assertTrue(staleMeta.setLastModified(tenDaysAgo));

        LogRetentionPolicy policy = new LogRetentionPolicy(Duration.ofDays(5), 1L << 40, 1000);
        LogRetentionManager.RetentionResult result = new LogRetentionManager(policy).applyToAgents();

        assertEquals(1, result.deletedByAge());
        assertFalse(staleLog.exists());
        assertTrue(LogPaths.agentLogFile("inst-A", "claude-cli", "fresh").exists(), "fresh run must remain");
    }

    private static void writeRun(String instance, String agent, String processId, long sessionId) throws Exception {
        try (AgentLogWriter writer = new AgentLogWriter(instance, agent, processId)) {
            writer.writeStart(new AgentLogWriter.AgentRunContext(
                    sessionId, List.of("cmd"), null, 1L));
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "a");
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "b");
            writer.writeEnd(new AgentLogWriter.AgentRunResult("COMPLETED", 0, null, null, null));
        }
    }
}
