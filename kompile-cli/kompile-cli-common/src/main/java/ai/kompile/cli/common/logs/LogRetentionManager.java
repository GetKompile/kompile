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
 * limitations under the License.
 */

package ai.kompile.cli.common.logs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Enforces log retention under {@code ~/.kompile/logs/agents}.
 *
 * <p>Each agent run is a pair: {@code <processId>.log} + {@code <processId>.meta.json}.
 * Retention operates on this pair as a unit — deleting one always deletes the other.
 *
 * <p>The manager never deletes files belonging to the current JVM's writers;
 * callers must only pass retention on runs they are not actively writing.
 * Writers use {@link java.nio.file.StandardOpenOption#APPEND} so a concurrent
 * deletion would not corrupt them, but deleting an in-flight log would lose data.
 */
public final class LogRetentionManager {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionManager.class);

    private final LogRetentionPolicy policy;

    public LogRetentionManager(LogRetentionPolicy policy) {
        this.policy = policy;
    }

    /** Applies retention to every agent log directory under {@code logs/agents}. */
    public RetentionResult applyToAgents() {
        File agentsRoot = LogPaths.agentsRoot();
        if (!agentsRoot.isDirectory()) {
            return RetentionResult.empty();
        }

        List<File> allLogs = collectAgentLogs(agentsRoot);
        int deletedByAge = deleteByAge(allLogs);
        int deletedByPerAgent = deleteByPerAgentCap(agentsRoot);
        long currentSize = totalSize(collectAgentLogs(agentsRoot));
        int deletedBySize = 0;
        if (currentSize > policy.maxTotalBytes()) {
            deletedBySize = deleteBySize(collectAgentLogs(agentsRoot), currentSize);
        }
        return new RetentionResult(deletedByAge, deletedByPerAgent, deletedBySize);
    }

    /**
     * Applies retention to subprocess logs under {@code logs/subprocesses/<type>/*.log}.
     * Uses the same policy values as {@link #applyToAgents}; the per-agent cap is
     * reinterpreted as a per-type cap.
     */
    public RetentionResult applyToSubprocesses() {
        File subprocessesRoot = LogPaths.subprocessesRoot();
        if (!subprocessesRoot.isDirectory()) {
            return RetentionResult.empty();
        }

        List<File> allLogs = collectSubprocessLogs(subprocessesRoot);
        int deletedByAge = deleteByAge(allLogs);
        int deletedByPerAgent = deleteByPerTypeCap(subprocessesRoot);
        long currentSize = totalSize(collectSubprocessLogs(subprocessesRoot));
        int deletedBySize = 0;
        if (currentSize > policy.maxTotalBytes()) {
            deletedBySize = deleteBySize(collectSubprocessLogs(subprocessesRoot), currentSize);
        }
        return new RetentionResult(deletedByAge, deletedByPerAgent, deletedBySize);
    }

    private List<File> collectAgentLogs(File agentsRoot) {
        List<File> out = new ArrayList<>();
        File[] instances = agentsRoot.listFiles(File::isDirectory);
        if (instances == null) {
            return out;
        }
        for (File instance : instances) {
            File[] agents = instance.listFiles(File::isDirectory);
            if (agents == null) continue;
            for (File agent : agents) {
                File[] logs = agent.listFiles((d, name) -> name.endsWith(".log"));
                if (logs == null) continue;
                java.util.Collections.addAll(out, logs);
            }
        }
        return out;
    }

    private List<File> collectSubprocessLogs(File subprocessesRoot) {
        List<File> out = new ArrayList<>();
        File[] types = subprocessesRoot.listFiles(File::isDirectory);
        if (types == null) {
            return out;
        }
        for (File type : types) {
            File[] logs = type.listFiles((d, name) -> name.endsWith(".log"));
            if (logs == null) continue;
            java.util.Collections.addAll(out, logs);
        }
        return out;
    }

    private int deleteByAge(List<File> logs) {
        Instant cutoff = Instant.now().minus(policy.maxAge());
        int deleted = 0;
        for (File logFile : logs) {
            if (Instant.ofEpochMilli(logFile.lastModified()).isBefore(cutoff)) {
                if (deletePair(logFile)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private int deleteByPerAgentCap(File agentsRoot) {
        int deleted = 0;
        File[] instances = agentsRoot.listFiles(File::isDirectory);
        if (instances == null) return 0;
        for (File instance : instances) {
            File[] agents = instance.listFiles(File::isDirectory);
            if (agents == null) continue;
            for (File agent : agents) {
                deleted += capLeafDirectory(agent);
            }
        }
        return deleted;
    }

    private int deleteByPerTypeCap(File subprocessesRoot) {
        int deleted = 0;
        File[] types = subprocessesRoot.listFiles(File::isDirectory);
        if (types == null) return 0;
        for (File type : types) {
            deleted += capLeafDirectory(type);
        }
        return deleted;
    }

    private int capLeafDirectory(File dir) {
        File[] logs = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (logs == null || logs.length <= policy.maxFilesPerAgent()) return 0;
        List<File> sorted = new ArrayList<>(java.util.Arrays.asList(logs));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        int excess = sorted.size() - policy.maxFilesPerAgent();
        int deleted = 0;
        for (int i = 0; i < excess; i++) {
            if (deletePair(sorted.get(i))) {
                deleted++;
            }
        }
        return deleted;
    }

    private int deleteBySize(List<File> logs, long currentSize) {
        List<File> sorted = new ArrayList<>(logs);
        sorted.sort(Comparator.comparingLong(File::lastModified));
        int deleted = 0;
        for (File logFile : sorted) {
            if (currentSize <= policy.maxTotalBytes()) break;
            long fileSize = logFile.length();
            if (deletePair(logFile)) {
                currentSize -= fileSize;
                deleted++;
            }
        }
        return deleted;
    }

    private long totalSize(List<File> logs) {
        long total = 0L;
        for (File f : logs) total += f.length();
        return total;
    }

    private boolean deletePair(File logFile) {
        File metaFile = new File(logFile.getParentFile(), pairedMetaName(logFile.getName()));
        boolean anyDeleted = false;
        try {
            anyDeleted |= Files.deleteIfExists(logFile.toPath());
        } catch (IOException e) {
            log.warn("Failed to delete log file {}: {}", logFile, e.getMessage());
        }
        if (metaFile.exists()) {
            try {
                Files.deleteIfExists(metaFile.toPath());
            } catch (IOException e) {
                log.warn("Failed to delete meta file {}: {}", metaFile, e.getMessage());
            }
        }
        return anyDeleted;
    }

    private static String pairedMetaName(String logName) {
        if (logName.endsWith(".log")) {
            return logName.substring(0, logName.length() - 4) + ".meta.json";
        }
        return logName + ".meta.json";
    }

    public record RetentionResult(int deletedByAge, int deletedByPerAgent, int deletedBySize) {
        public int totalDeleted() {
            return deletedByAge + deletedByPerAgent + deletedBySize;
        }

        static RetentionResult empty() {
            return new RetentionResult(0, 0, 0);
        }
    }
}
