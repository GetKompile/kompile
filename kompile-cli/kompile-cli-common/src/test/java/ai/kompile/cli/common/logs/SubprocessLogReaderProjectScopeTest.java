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
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubprocessLogReaderProjectScopeTest {

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
    void writesAndReadsProjectScopedSubprocessRun() throws Exception {
        Path projectRoot = Files.createTempDirectory("kompile-subprocess-project");
        Files.createDirectories(projectRoot.resolve(".kompile"));
        String type = "embedding";
        String runId = UUID.randomUUID().toString();

        try (SubprocessLogWriter writer = new SubprocessLogWriter(type, runId, projectRoot)) {
            writer.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    "project-task", List.of("java"), projectRoot.toString(), 123L, "1g"));
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "project scoped output");
            writer.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    "COMPLETED", 0, null, false, false));
        }

        File projectLog = LogPaths.subprocessLogFile(projectRoot, type, runId);
        File globalLog = LogPaths.subprocessLogFile(type, runId);
        assertTrue(projectLog.isFile(), "log should be written under the project .kompile directory");
        assertFalse(globalLog.isFile(), "project-scoped runs should not leak into the global subprocess log root");

        SubprocessLogMetadata meta = AgentLogReader.findSubprocessByRunId(projectRoot, runId).orElseThrow();
        assertEquals(type, meta.getSubprocessType());
        assertEquals(projectRoot.toString(), meta.getWorkingDirectory());

        List<SubprocessLogMetadata> projectRuns = AgentLogReader.listSubprocessRuns(
                projectRoot,
                new AgentLogReader.SubprocessRunFilter(type, runId, null, null));
        assertEquals(1, projectRuns.size());
        assertTrue(AgentLogReader.findSubprocessByRunId(runId).isEmpty(),
                "global lookup should not include project-scoped runs");
    }

    @Test
    void resolvesLegacyGlobalLogWhenMetadataWorkingDirectoryPointsAtProjectScope() throws Exception {
        Path projectRoot = Files.createTempDirectory("kompile-subprocess-project");
        Files.createDirectories(projectRoot.resolve(".kompile"));
        String type = "ingest";
        String runId = UUID.randomUUID().toString();

        try (SubprocessLogWriter writer = new SubprocessLogWriter(type, runId)) {
            writer.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    "legacy-task", List.of("java"), projectRoot.toString(), 456L, "2g"));
            writer.writeLine(AgentLogRecord.Stream.STDOUT, "legacy global output");
            writer.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    "COMPLETED", 0, null, false, false));
        }

        SubprocessLogMetadata meta = AgentLogReader.findSubprocessByRunId(projectRoot, runId).orElseThrow();
        File resolved = AgentLogReader.resolveSubprocessLogFile(projectRoot, meta);
        assertEquals(LogPaths.subprocessLogFile(type, runId), resolved);
        assertTrue(resolved.isFile(), "legacy global log must remain readable");

        List<AgentLogRecord> records;
        try (Stream<AgentLogRecord> stream = AgentLogReader.readRecords(resolved)) {
            records = stream.toList();
        }
        assertTrue(records.stream().anyMatch(record -> "legacy global output".equals(record.getLine())));
    }
}
