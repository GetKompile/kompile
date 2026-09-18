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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Enforces log retention under {@code ~/.kompile/logs}.
 *
 * <p>Each agent/subprocess run is a pair: {@code <id>.log} + {@code <id>.meta.json}.
 * Retention operates on this pair as a unit — retiring one always retires the other.
 *
 * <p>Retirement is either a delete or (when an {@link LogArchiver} is supplied and the
 * policy enables archiving) a move into the cold archive tree. The manager never retires
 * files belonging to the current JVM's writers: {@link #skipRunning} (on by default) keeps
 * any run whose metadata still reports {@code state=RUNNING} with a recent modification
 * time, so an in-flight run is never archived or deleted. Writers use
 * {@link java.nio.file.StandardOpenOption#APPEND}, so a concurrent delete would not corrupt
 * them, but retiring an in-flight log would lose data.
 */
public final class LogRetentionManager {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionManager.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    /** A run still marked RUNNING is only considered in-flight if touched this recently. */
    private static final long ACTIVE_GRACE_MS = 6L * 60 * 60 * 1000;

    private final LogRetentionPolicy policy;
    private final LogArchiver archiver;
    private final boolean skipRunning;

    /** Delete-only retention, skipping in-flight runs. */
    public LogRetentionManager(LogRetentionPolicy policy) {
        this(policy, null, true);
    }

    /**
     * @param archiver    when non-null and {@link LogRetentionPolicy#archiveEnabled()}, retired
     *                    logs are moved into the archive tree instead of deleted
     * @param skipRunning skip runs whose metadata reports {@code state=RUNNING} and that were
     *                    modified recently (in-flight runs)
     */
    public LogRetentionManager(LogRetentionPolicy policy, LogArchiver archiver, boolean skipRunning) {
        this.policy = policy;
        this.archiver = archiver;
        this.skipRunning = skipRunning;
    }

    /** Applies retention to every agent log directory under {@code logs/agents}. */
    public RetentionResult applyToAgents() {
        File agentsRoot = LogPaths.agentsRoot();
        if (!agentsRoot.isDirectory()) {
            return RetentionResult.empty();
        }

        List<File> allLogs = collectAgentLogs(agentsRoot);
        int retiredByAge = deleteByAge(allLogs, "agents");
        int retiredByPerAgent = deleteByPerAgentCap(agentsRoot, "agents");
        long currentSize = totalSize(collectAgentLogs(agentsRoot));
        int retiredBySize = 0;
        if (currentSize > policy.maxTotalBytes()) {
            retiredBySize = deleteBySize(collectAgentLogs(agentsRoot), currentSize, "agents");
        }
        return new RetentionResult(retiredByAge, retiredByPerAgent, retiredBySize);
    }

    /**
     * Applies retention to subprocess logs under {@code logs/subprocesses/<type>/*.log}.
     * Uses the same policy values as {@link #applyToAgents}; the per-agent cap is
     * reinterpreted as a per-type cap.
     */
    public RetentionResult applyToSubprocesses() {
        return applyToSubprocesses((Path) null);
    }

    /**
     * Applies retention to subprocess logs using a working directory-scoped root.
     * Falls back to {@code ~/.kompile/logs/subprocesses} via {@link LogPaths#subprocessesRoot(Path)}.
     */
    public RetentionResult applyToSubprocesses(Path workingDirectory) {
        File subprocessesRoot = LogPaths.subprocessesRoot(workingDirectory);
        if (!subprocessesRoot.isDirectory()) {
            return RetentionResult.empty();
        }

        List<File> allLogs = collectSubprocessLogs(subprocessesRoot);
        int retiredByAge = deleteByAge(allLogs, "subprocesses");
        int retiredByPerAgent = deleteByPerTypeCap(subprocessesRoot, "subprocesses");
        long currentSize = totalSize(collectSubprocessLogs(subprocessesRoot));
        int retiredBySize = 0;
        if (currentSize > policy.maxTotalBytes()) {
            retiredBySize = deleteBySize(collectSubprocessLogs(subprocessesRoot), currentSize, "subprocesses");
        }
        return new RetentionResult(retiredByAge, retiredByPerAgent, retiredBySize);
    }

    /**
     * Applies retention to crawl logs under {@code logs/crawls/*.log} (one flat file per crawl job).
     * The per-agent cap is reinterpreted as a per-crawls-directory cap.
     */
    public RetentionResult applyToCrawls() {
        File crawlsRoot = LogPaths.crawlsRoot();
        if (!crawlsRoot.isDirectory()) {
            return RetentionResult.empty();
        }
        List<File> allLogs = collectCrawlLogs(crawlsRoot);
        int retiredByAge = deleteByAge(allLogs, "crawls");
        int retiredByCap = capLeafDirectory(crawlsRoot, "crawls");
        long currentSize = totalSize(collectCrawlLogs(crawlsRoot));
        int retiredBySize = 0;
        if (currentSize > policy.maxTotalBytes()) {
            retiredBySize = deleteBySize(collectCrawlLogs(crawlsRoot), currentSize, "crawls");
        }
        return new RetentionResult(retiredByAge, retiredByCap, retiredBySize);
    }

    /**
     * Applies retention to flat log files sitting directly under {@code logs/} (e.g.
     * {@code mcp-activity.log}, {@code mcp-stderr.log}, stray {@code *.out.log} /
     * {@code *.err.log} launcher files, and rotated {@code *.log.N} copies). Only files
     * matching {@code *.log} or {@code *.log.<digits>} are in scope; subdirectories
     * (agents, subprocesses, crawls, transcripts, cli, archive, …) are never touched.
     * There is no per-run metadata sidecar for these, so age is the safety signal: an
     * actively-written file has a fresh modification time and is never retired.
     */
    public RetentionResult applyToRootFiles() {
        File logsRoot = LogPaths.logsDirectory();
        if (!logsRoot.isDirectory()) {
            return RetentionResult.empty();
        }
        List<File> allLogs = collectRootLogFiles(logsRoot);
        int retiredByAge = deleteByAge(allLogs, "misc");
        long currentSize = totalSize(collectRootLogFiles(logsRoot));
        int retiredBySize = 0;
        if (currentSize > policy.maxTotalBytes()) {
            retiredBySize = deleteBySize(collectRootLogFiles(logsRoot), currentSize, "misc");
        }
        return new RetentionResult(retiredByAge, 0, retiredBySize);
    }

    private List<File> collectRootLogFiles(File logsRoot) {
        List<File> out = new ArrayList<>();
        File[] files = logsRoot.listFiles(File::isFile);
        if (files == null) {
            return out;
        }
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".log") || name.matches(".*\\.log\\.[0-9]+")) {
                out.add(f);
            }
        }
        return out;
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
                for (File logFile : logs) {
                    if (!isSkipped(logFile)) out.add(logFile);
                }
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
            for (File logFile : logs) {
                if (!isSkipped(logFile)) out.add(logFile);
            }
        }
        return out;
    }

    private List<File> collectCrawlLogs(File crawlsRoot) {
        List<File> out = new ArrayList<>();
        File[] logs = crawlsRoot.listFiles((d, name) -> name.endsWith(".log"));
        if (logs != null) {
            for (File logFile : logs) {
                if (!isSkipped(logFile)) out.add(logFile);
            }
        }
        return out;
    }

    private int deleteByAge(List<File> logs, String destination) {
        Instant cutoff = Instant.now().minus(policy.maxAge());
        int retired = 0;
        for (File logFile : logs) {
            if (Instant.ofEpochMilli(logFile.lastModified()).isBefore(cutoff)) {
                if (retirePair(logFile, destination)) {
                    retired++;
                }
            }
        }
        return retired;
    }

    private int deleteByPerAgentCap(File agentsRoot, String destination) {
        int retired = 0;
        File[] instances = agentsRoot.listFiles(File::isDirectory);
        if (instances == null) return 0;
        for (File instance : instances) {
            File[] agents = instance.listFiles(File::isDirectory);
            if (agents == null) continue;
            for (File agent : agents) {
                retired += capLeafDirectory(agent, destination);
            }
        }
        return retired;
    }

    private int deleteByPerTypeCap(File subprocessesRoot, String destination) {
        int retired = 0;
        File[] types = subprocessesRoot.listFiles(File::isDirectory);
        if (types == null) return 0;
        for (File type : types) {
            retired += capLeafDirectory(type, destination);
        }
        return retired;
    }

    private int capLeafDirectory(File dir, String destination) {
        File[] logs = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (logs == null) return 0;
        List<File> eligible = new ArrayList<>();
        for (File logFile : logs) {
            if (!isSkipped(logFile)) eligible.add(logFile);
        }
        if (eligible.size() <= policy.maxFilesPerAgent()) return 0;
        eligible.sort(Comparator.comparingLong(File::lastModified));
        int excess = eligible.size() - policy.maxFilesPerAgent();
        int retired = 0;
        for (int i = 0; i < excess; i++) {
            if (retirePair(eligible.get(i), destination)) {
                retired++;
            }
        }
        return retired;
    }

    private int deleteBySize(List<File> logs, long currentSize, String destination) {
        List<File> sorted = new ArrayList<>(logs);
        sorted.sort(Comparator.comparingLong(File::lastModified));
        int retired = 0;
        for (File logFile : sorted) {
            if (currentSize <= policy.maxTotalBytes()) break;
            long fileSize = logFile.length();
            if (retirePair(logFile, destination)) {
                currentSize -= fileSize;
                retired++;
            }
        }
        return retired;
    }

    private long totalSize(List<File> logs) {
        long total = 0L;
        for (File f : logs) total += f.length();
        return total;
    }

    /** Archive (if enabled) or delete the log plus its metadata sidecar, as a unit. */
    private boolean retirePair(File logFile, String destination) {
        if (archiver != null && policy.archiveEnabled()) {
            File metaFile = new File(logFile.getParentFile(), pairedMetaName(logFile.getName()));
            return archiver.archive(logFile, metaFile, destination);
        }
        return deletePair(logFile);
    }

    private boolean isSkipped(File logFile) {
        if (!skipRunning) {
            return false;
        }
        File metaFile = new File(logFile.getParentFile(), pairedMetaName(logFile.getName()));
        if (!metaFile.isFile()) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(metaFile);
            boolean running = "RUNNING".equals(node.path("state").asText(null));
            boolean ended = node.has("endedAt") && !node.get("endedAt").isNull();
            if (!running || ended) {
                return false;
            }
            long age = System.currentTimeMillis() - logFile.lastModified();
            return age < ACTIVE_GRACE_MS;
        } catch (IOException e) {
            return false;
        }
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

    /**
     * Counts of runs retired (archived or deleted) by each cap. Field names retain the
     * historical "deleted" wording for backward compatibility with callers that only know
     * about deletion; "retired" is the accurate term when archiving is enabled.
     */
    public record RetentionResult(int deletedByAge, int deletedByPerAgent, int deletedBySize) {
        public int totalDeleted() {
            return deletedByAge + deletedByPerAgent + deletedBySize;
        }

        static RetentionResult empty() {
            return new RetentionResult(0, 0, 0);
        }
    }
}
