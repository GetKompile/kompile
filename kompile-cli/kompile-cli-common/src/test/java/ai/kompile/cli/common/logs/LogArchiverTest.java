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
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogArchiverTest {

    private String originalUserHome;
    private LogArchiver archiver;

    @BeforeEach
    void redirectHome(@TempDir Path tempHome) {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        archiver = new LogArchiver();
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void archiveMovesLogAndSidecarIntoMonthBucket(@TempDir Path tempHome) throws Exception {
        LogPaths.ensureAgentLogDir("instance-1", "claude-cli");
        File logFile = LogPaths.agentLogFile("instance-1", "claude-cli", "proc-1");
        File metaFile = LogPaths.agentMetaFile("instance-1", "claude-cli", "proc-1");
        Files.writeString(logFile.toPath(), "{\"line\":\"hello\"}\n");
        Files.writeString(metaFile.toPath(), "{\"state\":\"COMPLETED\"}");

        long oldMtime = LocalDate.of(2020, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        logFile.setLastModified(oldMtime);

        assertTrue(archiver.archive(logFile, metaFile, "agents"));

        assertFalse(logFile.exists(), "original log moved out of the hot tree");
        assertFalse(metaFile.exists(), "sidecar moved with the log");
        File archivedLog = new File(archiver.destinationRoot("agents"), "2020-03/proc-1.log");
        File archivedMeta = new File(archiver.destinationRoot("agents"), "2020-03/proc-1.meta.json");
        assertTrue(archivedLog.isFile(), "log present under archive/agents/2020-03");
        assertTrue(archivedMeta.isFile(), "sidecar present under archive/agents/2020-03");
    }

    @Test
    void coldRetentionDeletesExpiredMonthBuckets(@TempDir Path tempHome) throws Exception {
        File agentsArchive = archiver.destinationRoot("agents");
        File expired = new File(agentsArchive, "2000-01");
        File current = new File(agentsArchive, java.time.YearMonth.now().toString());
        Files.createDirectories(expired.toPath());
        Files.createDirectories(current.toPath());
        Files.writeString(expired.toPath().resolve("old.log"), "old\n");

        int deleted = archiver.applyColdRetention("agents", Duration.ofDays(30));

        assertTrue(deleted >= 1, "expired bucket removed");
        assertFalse(expired.exists(), "2000-01 bucket deleted");
        assertTrue(current.isDirectory(), "recent bucket preserved");
    }

    @Test
    void archiveReturnsFalseForMissingFile() {
        assertFalse(archiver.archive(new File("/nonexistent/proc.log"), "agents"));
    }
}
