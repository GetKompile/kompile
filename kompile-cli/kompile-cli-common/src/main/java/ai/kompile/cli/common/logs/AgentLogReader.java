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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads agent logs written by {@link AgentLogWriter}.
 *
 * <p>Three usage modes:
 * <ul>
 *   <li>{@link #listRuns} — list all known runs with metadata (fast, meta-only scan)</li>
 *   <li>{@link #readRecords} — stream JSON-lines records for a single run</li>
 *   <li>{@link #aggregateAcrossRuns} — merge records from multiple runs in timestamp order</li>
 * </ul>
 */
public final class AgentLogReader {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private AgentLogReader() {
    }

    /** Filters for {@link #listRuns}. All fields are optional (null = no filter). */
    public record RunFilter(
            String orchestratorInstanceId,
            String agentName,
            Long sessionId,
            Instant since,
            Instant until) {

        public static RunFilter none() {
            return new RunFilter(null, null, null, null, null);
        }

        public boolean matches(AgentLogMetadata meta) {
            if (orchestratorInstanceId != null
                    && !orchestratorInstanceId.equals(meta.getOrchestratorInstanceId())) {
                return false;
            }
            if (agentName != null && !agentName.equals(meta.getAgentName())) {
                return false;
            }
            if (sessionId != null && !sessionId.equals(meta.getSessionId())) {
                return false;
            }
            if (since != null && meta.getStartedAt() != null && meta.getStartedAt().isBefore(since)) {
                return false;
            }
            if (until != null && meta.getStartedAt() != null && meta.getStartedAt().isAfter(until)) {
                return false;
            }
            return true;
        }
    }

    /** Returns all known runs (most recent first), filtered by {@code filter}. */
    public static List<AgentLogMetadata> listRuns(RunFilter filter) {
        File agentsRoot = LogPaths.agentsRoot();
        List<AgentLogMetadata> out = new ArrayList<>();
        if (!agentsRoot.isDirectory()) return out;

        File[] instances = agentsRoot.listFiles(File::isDirectory);
        if (instances == null) return out;
        for (File instance : instances) {
            File[] agents = instance.listFiles(File::isDirectory);
            if (agents == null) continue;
            for (File agent : agents) {
                File[] metas = agent.listFiles((d, name) -> name.endsWith(".meta.json"));
                if (metas == null) continue;
                for (File metaFile : metas) {
                    Optional<AgentLogMetadata> loaded = loadMeta(metaFile);
                    loaded.filter(filter::matches).ifPresent(out::add);
                }
            }
        }
        out.sort(Comparator.comparing(
                (AgentLogMetadata m) -> m.getStartedAt() == null ? Instant.EPOCH : m.getStartedAt())
                .reversed());
        return out;
    }

    /** Loads the metadata sidecar for a specific run, if present. */
    public static Optional<AgentLogMetadata> loadMeta(File metaFile) {
        if (!metaFile.isFile()) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(metaFile, AgentLogMetadata.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Finds a run by its {@code processId}, scanning all agent directories. */
    public static Optional<AgentLogMetadata> findByProcessId(String processId) {
        return listRuns(RunFilter.none()).stream()
                .filter(m -> processId.equals(m.getProcessId()))
                .findFirst();
    }

    /** Filters for subprocess run listing. */
    public record SubprocessRunFilter(
            String subprocessType,
            String runId,
            Instant since,
            Instant until) {

        public static SubprocessRunFilter none() {
            return new SubprocessRunFilter(null, null, null, null);
        }

        public boolean matches(SubprocessLogMetadata meta) {
            if (subprocessType != null && !subprocessType.equals(meta.getSubprocessType())) {
                return false;
            }
            if (runId != null && !runId.equals(meta.getRunId())) {
                return false;
            }
            if (since != null && meta.getStartedAt() != null && meta.getStartedAt().isBefore(since)) {
                return false;
            }
            if (until != null && meta.getStartedAt() != null && meta.getStartedAt().isAfter(until)) {
                return false;
            }
            return true;
        }
    }

    /** Lists all subprocess runs under {@code logs/subprocesses} (most recent first). */
    public static List<SubprocessLogMetadata> listSubprocessRuns(SubprocessRunFilter filter) {
        File root = LogPaths.subprocessesRoot();
        List<SubprocessLogMetadata> out = new ArrayList<>();
        if (!root.isDirectory()) return out;

        File[] types = root.listFiles(File::isDirectory);
        if (types == null) return out;
        for (File typeDir : types) {
            File[] metas = typeDir.listFiles((d, name) -> name.endsWith(".meta.json"));
            if (metas == null) continue;
            for (File metaFile : metas) {
                Optional<SubprocessLogMetadata> loaded = loadSubprocessMeta(metaFile);
                loaded.filter(filter::matches).ifPresent(out::add);
            }
        }
        out.sort(Comparator.comparing(
                (SubprocessLogMetadata m) -> m.getStartedAt() == null ? Instant.EPOCH : m.getStartedAt())
                .reversed());
        return out;
    }

    public static Optional<SubprocessLogMetadata> loadSubprocessMeta(File metaFile) {
        if (!metaFile.isFile()) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(metaFile, SubprocessLogMetadata.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Finds a subprocess run by its {@code runId}, scanning all type directories. */
    public static Optional<SubprocessLogMetadata> findSubprocessByRunId(String runId) {
        return listSubprocessRuns(SubprocessRunFilter.none()).stream()
                .filter(m -> runId.equals(m.getRunId()))
                .findFirst();
    }

    /**
     * Merges records across both agents and subprocesses in ascending timestamp order.
     * Each record carries identifiers from its source (agent fields for agent runs,
     * {@code processId}=runId + {@code agentName}=subprocessType for subprocess runs).
     */
    public static Stream<AgentLogRecord> aggregateAcrossSubprocessRuns(List<SubprocessLogMetadata> runs) {
        List<Stream<AgentLogRecord>> openStreams = new ArrayList<>();
        for (SubprocessLogMetadata meta : runs) {
            File logFile = LogPaths.subprocessLogFile(meta.getSubprocessType(), meta.getRunId());
            if (!logFile.isFile()) continue;
            try {
                Stream<AgentLogRecord> s = readRecords(logFile)
                        .peek(r -> {
                            r.setProcessId(meta.getRunId());
                            r.setAgentName(meta.getSubprocessType());
                        });
                openStreams.add(s);
            } catch (IOException e) {
                // skip unreadable files
            }
        }
        List<AgentLogRecord> all = new ArrayList<>();
        try {
            for (Stream<AgentLogRecord> s : openStreams) {
                s.forEach(all::add);
            }
        } finally {
            openStreams.forEach(Stream::close);
        }
        all.sort(Comparator.comparing(
                (AgentLogRecord r) -> r.getTs() == null ? Instant.EPOCH : r.getTs()));
        return all.stream();
    }

    /**
     * Streams parsed records from a single log file. The caller must close the stream.
     */
    public static Stream<AgentLogRecord> readRecords(File logFile) throws IOException {
        BufferedReader reader = Files.newBufferedReader(logFile.toPath(), StandardCharsets.UTF_8);
        return reader.lines()
                .map(AgentLogReader::parseLine)
                .filter(java.util.Objects::nonNull)
                .onClose(() -> {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                });
    }

    private static AgentLogRecord parseLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            return MAPPER.readValue(line, AgentLogRecord.class);
        } catch (IOException e) {
            AgentLogRecord r = new AgentLogRecord();
            r.setTs(Instant.now());
            r.setStream(AgentLogRecord.Stream.SYSTEM);
            r.setLine("[unparsed] " + line);
            return r;
        }
    }

    /**
     * Aggregates records across multiple runs in ascending timestamp order.
     * Each record is enriched with {@code processId}, {@code agentName},
     * {@code orchestratorInstanceId}, and {@code sessionId} from its run metadata.
     *
     * <p>The caller must close the returned stream to release file handles.
     */
    public static Stream<AgentLogRecord> aggregateAcrossRuns(List<AgentLogMetadata> runs) {
        List<Stream<AgentLogRecord>> openStreams = new ArrayList<>();
        for (AgentLogMetadata meta : runs) {
            File logFile = LogPaths.agentLogFile(
                    meta.getOrchestratorInstanceId(), meta.getAgentName(), meta.getProcessId());
            if (!logFile.isFile()) continue;
            try {
                Stream<AgentLogRecord> s = readRecords(logFile)
                        .peek(r -> {
                            r.setProcessId(meta.getProcessId());
                            r.setAgentName(meta.getAgentName());
                            r.setOrchestratorInstanceId(meta.getOrchestratorInstanceId());
                            r.setSessionId(meta.getSessionId());
                        });
                openStreams.add(s);
            } catch (IOException e) {
                // skip unreadable files
            }
        }
        List<AgentLogRecord> all = new ArrayList<>();
        try {
            for (Stream<AgentLogRecord> s : openStreams) {
                s.forEach(all::add);
            }
        } finally {
            openStreams.forEach(Stream::close);
        }
        all.sort(Comparator.comparing(
                (AgentLogRecord r) -> r.getTs() == null ? Instant.EPOCH : r.getTs()));
        return all.stream();
    }

    /**
     * Tail-follow: reads from {@code logFile} starting at the current end-of-file
     * and yields new records as they are appended. Blocks until {@code timeout}
     * elapses with no new records, or the thread is interrupted.
     *
     * <p>Intended for interactive CLI use; not a high-throughput facility.
     */
    public static void tail(File logFile, Duration pollInterval, Duration quietTimeout,
                             java.util.function.Consumer<AgentLogRecord> sink) throws IOException, InterruptedException {
        long offset = logFile.isFile() ? logFile.length() : 0L;
        Instant lastActivity = Instant.now();
        while (!Thread.currentThread().isInterrupted()) {
            if (logFile.isFile() && logFile.length() > offset) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
                    raf.seek(offset);
                    String line;
                    while ((line = raf.readLine()) != null) {
                        AgentLogRecord r = parseLine(line);
                        if (r != null) sink.accept(r);
                    }
                    offset = raf.getFilePointer();
                    lastActivity = Instant.now();
                }
            } else if (Duration.between(lastActivity, Instant.now()).compareTo(quietTimeout) > 0) {
                return;
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }
}
