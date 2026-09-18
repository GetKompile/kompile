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
 *  limitations under the License.
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
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRetentionManagerTest {

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

    private static LogRetentionPolicy archivePolicy() {
        return new LogRetentionPolicy(
                Duration.ofDays(30), 2L * 1024 * 1024 * 1024, 100, true, Duration.ofDays(30));
    }

    private static void writeRun(String instance, String agent, String pid,
                                 String state, long mtimeMillis) throws Exception {
        LogPaths.ensureAgentLogDir(instance, agent);
        File logFile = LogPaths.agentLogFile(instance, agent, pid);
        File metaFile = LogPaths.agentMetaFile(instance, agent, pid);
        Files.writeString(logFile.toPath(), "{\"line\":\"hello\"}\n");
        String metaJson = "COMPLETED".equals(state)
                ? "{\"state\":\"COMPLETED\",\"endedAt\":\"2026-01-01T00:00:00Z\"}"
                : "{\"state\":\"RUNNING\"}";
        Files.writeString(metaFile.toPath(), metaJson);
        logFile.setLastModified(mtimeMillis);
    }

    @Test
    void agedRunIsArchivedNotDeletedWhenArchiverSupplied(@TempDir Path tempHome) throws Exception {
        long oldMtime = System.currentTimeMillis() - Duration.ofDays(60).toMillis();
        writeRun("instance-1", "claude-cli", "proc-1", "COMPLETED", oldMtime);

        LogRetentionManager manager = new LogRetentionManager(archivePolicy(), new LogArchiver(), true);
        manager.applyToAgents();

        assertFalse(LogPaths.agentLogFile("instance-1", "claude-cli", "proc-1").exists(),
                "aged run removed from hot tree");
        YearMonth ym = YearMonth.from(Instant.ofEpochMilli(oldMtime).atZone(ZoneOffset.UTC));
        File archived = new File(new LogArchiver().destinationRoot("agents"),
                String.format("%04d-%02d/proc-1.log", ym.getYear(), ym.getMonthValue()));
        assertTrue(archived.isFile(), "aged run present in the archive tree at " + archived);
    }

    @Test
    void runningRunIsNotRetired(@TempDir Path tempHome) throws Exception {
        writeRun("instance-1", "claude-cli", "proc-1", "RUNNING", System.currentTimeMillis());

        LogRetentionManager manager = new LogRetentionManager(archivePolicy(), new LogArchiver(), true);
        LogRetentionManager.RetentionResult result = manager.applyToAgents();

        assertTrue(result.totalDeleted() == 0, "in-flight run must not be retired");
        assertTrue(LogPaths.agentLogFile("instance-1", "claude-cli", "proc-1").isFile(),
                "in-flight run remains in the hot tree");
    }

    @Test
    void deleteOnlyManagerStillDeletes(@TempDir Path tempHome) throws Exception {
        long oldMtime = System.currentTimeMillis() - Duration.ofDays(60).toMillis();
        writeRun("instance-1", "claude-cli", "proc-1", "COMPLETED", oldMtime);

        LogRetentionManager manager = new LogRetentionManager(archivePolicy());
        manager.applyToAgents();

        assertFalse(LogPaths.agentLogFile("instance-1", "claude-cli", "proc-1").exists(),
                "no archiver supplied → aged run deleted");
    }

    @Test
    void agedRootLogIsArchivedWhileRecentRootLogIsKept(@TempDir Path tempHome) throws Exception {
        File logsRoot = LogPaths.logsDirectory();
        Files.createDirectories(logsRoot.toPath());
        File oldLog = new File(logsRoot, "mcp-activity.log");
        File recentLog = new File(logsRoot, "mcp-stderr.log");
        Files.writeString(oldLog.toPath(), "old\n");
        Files.writeString(recentLog.toPath(), "recent\n");
        long oldMtime = System.currentTimeMillis() - Duration.ofDays(60).toMillis();
        oldLog.setLastModified(oldMtime);
        recentLog.setLastModified(System.currentTimeMillis());

        LogRetentionManager manager = new LogRetentionManager(archivePolicy(), new LogArchiver(), true);
        manager.applyToRootFiles();

        assertFalse(oldLog.exists(), "aged root log retired out of the hot tree");
        assertTrue(recentLog.exists(), "recent root log (in-flight) left untouched");

        YearMonth ym = YearMonth.from(Instant.ofEpochMilli(oldMtime).atZone(ZoneOffset.UTC));
        File archived = new File(new LogArchiver().destinationRoot("misc"),
                String.format("%04d-%02d/mcp-activity.log", ym.getYear(), ym.getMonthValue()));
        assertTrue(archived.isFile(), "aged root log present under archive/misc");
    }
}
