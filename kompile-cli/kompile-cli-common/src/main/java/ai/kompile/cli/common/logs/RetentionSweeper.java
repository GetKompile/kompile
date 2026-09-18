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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Automatic, bounded log-retention sweep for process output (agent runs, subprocess runs,
 * and crawl jobs).
 *
 * <p>Retires aged/oversized runs into the archive tree (or deletes them when archiving is
 * disabled) and prunes expired archives via {@link LogArchiver}. It is deliberately scoped
 * to <em>process output</em>; chat transcripts and conversation files are handled separately
 * by {@link ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention} and are never
 * touched here.
 *
 * <p>{@link #runAtStartup()} is safe to call from the CLI main entry point: it is guarded by
 * a stamp file so it runs at most once per {@link #MIN_SWEEP_INTERVAL}, and it swallows all
 * errors so a retention failure can never break startup.
 */
public final class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    static final Duration MIN_SWEEP_INTERVAL = Duration.ofHours(12);

    private RetentionSweeper() {
    }

    /** Best-effort automatic sweep. Never throws. */
    public static void runAtStartup() {
        try {
            runAtStartup(LogRetentionConfig.load().toPolicy());
        } catch (Throwable t) {
            log.warn("Automatic log retention sweep skipped: {}", t.getMessage());
        }
    }

    static void runAtStartup(LogRetentionPolicy policy) throws IOException {
        File stamp = new File(LogPaths.logsDirectory(), ".retention-sweep.stamp");
        if (stamp.isFile()
                && System.currentTimeMillis() - stamp.lastModified() < MIN_SWEEP_INTERVAL.toMillis()) {
            return;
        }
        runOnce(policy);
        touch(stamp);
    }

    static SweepResult runOnce(LogRetentionPolicy policy) {
        LogArchiver archiver = new LogArchiver();
        LogRetentionManager manager = new LogRetentionManager(policy, archiver, true);
        LogRetentionManager.RetentionResult agents = manager.applyToAgents();
        LogRetentionManager.RetentionResult subprocesses = manager.applyToSubprocesses();
        LogRetentionManager.RetentionResult crawls = manager.applyToCrawls();
        LogRetentionManager.RetentionResult misc = manager.applyToRootFiles();
        int coldDeleted = archiver.applyColdRetentionAll(policy.coldRetention());
        return new SweepResult(agents, subprocesses, crawls, misc, coldDeleted);
    }

    private static void touch(File stamp) throws IOException {
        File parent = stamp.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(stamp.toPath(), new byte[0],
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        stamp.setLastModified(System.currentTimeMillis());
    }

    public record SweepResult(
            LogRetentionManager.RetentionResult agents,
            LogRetentionManager.RetentionResult subprocesses,
            LogRetentionManager.RetentionResult crawls,
            LogRetentionManager.RetentionResult misc,
            int coldDeletedBuckets) {

        public int totalRetired() {
            return agents.totalDeleted() + subprocesses.totalDeleted()
                    + crawls.totalDeleted() + misc.totalDeleted();
        }
    }
}
