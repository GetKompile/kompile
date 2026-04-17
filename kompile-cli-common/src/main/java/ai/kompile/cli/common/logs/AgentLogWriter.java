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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes a single agent run's raw output to
 * {@code ~/.kompile/logs/agents/<instanceId>/<agentName>/<processId>.log}
 * as JSON-lines records.
 *
 * <p>One instance per run. Thread-safe: callers may invoke {@link #writeLine}
 * from multiple reader threads (e.g. stdout + stderr pumps). All writes are
 * serialized on this writer's monitor and flushed immediately so tail-follow
 * consumers see output without delay.
 *
 * <p>The companion metadata file ({@code <processId>.meta.json}) is written
 * when {@link #writeStart} is called and rewritten by {@link #writeEnd}.
 */
public final class AgentLogWriter implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final File logFile;
    private final File metaFile;
    private final AgentLogMetadata metadata;
    private final AtomicInteger seq = new AtomicInteger(0);
    private volatile BufferedWriter writer;
    private volatile boolean closed;

    public AgentLogWriter(String orchestratorInstanceId, String agentName, String processId) throws IOException {
        LogPaths.ensureAgentLogDir(orchestratorInstanceId, agentName);
        this.logFile = LogPaths.agentLogFile(orchestratorInstanceId, agentName, processId);
        this.metaFile = LogPaths.agentMetaFile(orchestratorInstanceId, agentName, processId);
        this.metadata = new AgentLogMetadata();
        this.metadata.setProcessId(processId);
        this.metadata.setAgentName(agentName);
        this.metadata.setOrchestratorInstanceId(orchestratorInstanceId);
    }

    /** Returns the path of the log file backing this writer. */
    public File getLogFile() {
        return logFile;
    }

    /** Returns the path of the metadata sidecar. */
    public File getMetaFile() {
        return metaFile;
    }

    /**
     * Records the start of the run: opens the log file, writes metadata with
     * {@code startedAt}, and emits an initial SYSTEM record for the header.
     */
    public synchronized void writeStart(AgentRunContext context) throws IOException {
        if (writer != null) {
            throw new IllegalStateException("writeStart already called");
        }
        writer = Files.newBufferedWriter(
                logFile.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);

        metadata.setSessionId(context.sessionId());
        metadata.setCommand(context.command());
        metadata.setWorkingDirectory(context.workingDirectory());
        metadata.setStartedAt(Instant.now());
        metadata.setState("RUNNING");
        metadata.setPid(context.pid());
        writeMeta();

        AgentLogRecord header = new AgentLogRecord(
                metadata.getStartedAt(),
                AgentLogRecord.Stream.SYSTEM,
                "agent run started: " + metadata.getAgentName(),
                seq.getAndIncrement());
        writeRecord(header);
    }

    /**
     * Appends a single line of agent output. Safe to call from multiple threads.
     */
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
     * Writes the terminal state to metadata and emits a closing SYSTEM record.
     * Safe to call multiple times (second call is a no-op).
     */
    public synchronized void writeEnd(AgentRunResult result) throws IOException {
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
        metadata.setCostUsd(result.costUsd());
        metadata.setNumTurns(result.numTurns());

        AgentLogRecord footer = new AgentLogRecord(
                metadata.getEndedAt(),
                AgentLogRecord.Stream.SYSTEM,
                "agent run ended: state=" + result.state() + " exit=" + result.exitCode(),
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

    /** Context captured at run start. */
    public record AgentRunContext(
            Long sessionId,
            java.util.List<String> command,
            String workingDirectory,
            Long pid) {
    }

    /** Terminal state captured at run end. */
    public record AgentRunResult(
            String state,
            Integer exitCode,
            String errorMessage,
            Double costUsd,
            Integer numTurns) {
    }
}
