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

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes a single subprocess run's stdout/stderr to
 * {@code ~/.kompile/logs/subprocesses/<type>/<runId>.log} as JSON-lines,
 * with a companion {@code <runId>.meta.json} sidecar.
 *
 * <p>Mirrors {@link AgentLogWriter} but keyed on {@code (subprocessType, runId)}
 * rather than {@code (orchestratorInstanceId, agentName, processId)}. The record
 * format ({@link AgentLogRecord}) is shared so aggregation can mix agent and
 * subprocess streams.
 *
 * <p>Thread-safe: {@link #writeLine} may be called from both stdout and stderr
 * reader threads concurrently.
 */
public final class SubprocessLogWriter implements AutoCloseable {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private final File logFile;
    private final File metaFile;
    private final SubprocessLogMetadata metadata;
    private final AtomicInteger seq = new AtomicInteger(0);
    private volatile BufferedWriter writer;
    private volatile boolean closed;

    public SubprocessLogWriter(String subprocessType, String runId) throws IOException {
        LogPaths.ensureSubprocessDir(subprocessType);
        this.logFile = LogPaths.subprocessLogFile(subprocessType, runId);
        this.metaFile = LogPaths.subprocessMetaFile(subprocessType, runId);
        this.metadata = new SubprocessLogMetadata();
        this.metadata.setSubprocessType(subprocessType);
        this.metadata.setRunId(runId);
    }

    public File getLogFile() {
        return logFile;
    }

    public File getMetaFile() {
        return metaFile;
    }

    /**
     * Opens the log for append, records {@code startedAt}, and writes an initial
     * SYSTEM record. Must be called exactly once before {@link #writeLine}.
     */
    public synchronized void writeStart(SubprocessRunContext context) throws IOException {
        if (writer != null) {
            throw new IllegalStateException("writeStart already called");
        }
        writer = Files.newBufferedWriter(
                logFile.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);

        metadata.setParentTaskId(context.parentTaskId());
        metadata.setCommand(context.command());
        metadata.setWorkingDirectory(context.workingDirectory());
        metadata.setStartedAt(Instant.now());
        metadata.setState("RUNNING");
        metadata.setPid(context.pid());
        metadata.setHeapSize(context.heapSize());
        writeMeta();

        AgentLogRecord header = new AgentLogRecord(
                metadata.getStartedAt(),
                AgentLogRecord.Stream.SYSTEM,
                "subprocess run started: " + metadata.getSubprocessType() + "/" + metadata.getRunId(),
                seq.getAndIncrement());
        writeRecord(header);
    }

    /** Appends a single line. Safe to call from multiple threads. */
    public void writeLine(AgentLogRecord.Stream stream, String line) throws IOException {
        AgentLogRecord record = new AgentLogRecord(Instant.now(), stream, line, seq.getAndIncrement());
        writeRecord(record);
    }

    private synchronized void writeRecord(AgentLogRecord record) throws IOException {
        if (closed) {
            return;
        }
        if (writer == null) {
            throw new IllegalStateException("writeStart must be called first");
        }
        writer.write(MAPPER.writeValueAsString(record));
        writer.newLine();
        writer.flush();
        Integer lines = metadata.getLinesWritten();
        metadata.setLinesWritten(lines == null ? 1 : lines + 1);
    }

    /**
     * Records terminal state. Safe to call multiple times (second call is a no-op).
     */
    public synchronized void writeEnd(SubprocessRunResult result) throws IOException {
        if (closed) {
            return;
        }
        metadata.setEndedAt(Instant.now());
        if (metadata.getStartedAt() != null) {
            metadata.setDurationMs(metadata.getEndedAt().toEpochMilli() - metadata.getStartedAt().toEpochMilli());
        }
        metadata.setState(result.state());
        metadata.setExitCode(result.exitCode());
        metadata.setErrorMessage(result.errorMessage());
        metadata.setOomDetected(result.oomDetected());
        metadata.setGpuOomDetected(result.gpuOomDetected());

        AgentLogRecord footer = new AgentLogRecord(
                metadata.getEndedAt(),
                AgentLogRecord.Stream.SYSTEM,
                "subprocess run ended: state=" + result.state() + " exit=" + result.exitCode(),
                seq.getAndIncrement());
        writeRecord(footer);
        writeMeta();
    }

    private void writeMeta() throws IOException {
        Files.writeString(
                metaFile.toPath(),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metadata),
                StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        BufferedWriter w = writer;
        writer = null;
        if (w != null) {
            try {
                w.flush();
                w.close();
            } catch (IOException ignored) {
                // best-effort close
            }
        }
    }

    /** Launch-time context captured once when the subprocess starts. */
    public record SubprocessRunContext(
            String parentTaskId,
            List<String> command,
            String workingDirectory,
            Long pid,
            String heapSize) {
    }

    /** Terminal state captured at run end. */
    public record SubprocessRunResult(
            String state,
            Integer exitCode,
            String errorMessage,
            Boolean oomDetected,
            Boolean gpuOomDetected) {
    }
}
