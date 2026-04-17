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

package ai.kompile.cli.agent;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.logs.AgentLogReader;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.common.logs.LogRetentionManager;
import ai.kompile.cli.common.logs.LogRetentionPolicy;
import ai.kompile.cli.common.logs.SubprocessLogMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * CLI subcommand for inspecting subprocess logs written by launchers
 * (ingest, vector-population, embedding, model-init, vlm-test, training).
 *
 * <p>Mirrors {@link AgentLogsCommand} but keyed on {@code (subprocessType, runId)}
 * instead of {@code (instance, agent, processId)}.
 */
@CommandLine.Command(name = "subprocess-logs",
        description = "Inspect subprocess logs (list, show, tail, aggregate, cleanup).",
        subcommands = {
                SubprocessLogsCommand.ListCmd.class,
                SubprocessLogsCommand.ShowCmd.class,
                SubprocessLogsCommand.TailCmd.class,
                SubprocessLogsCommand.AggregateCmd.class,
                SubprocessLogsCommand.CleanupCmd.class
        })
public class SubprocessLogsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("No operation specified. Use: list | show | tail | aggregate | cleanup");
        new CommandLine(this).usage(System.err);
        return 1;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_INSTANT;

    static class RemoteOptions {
        @CommandLine.Option(names = {"--remote"},
                description = "Fetch logs from orchestrator REST instead of local filesystem")
        boolean remote;

        @CommandLine.Option(names = {"--url"}, description = "Orchestrator URL (implies --remote)")
        String url;

        @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080",
                description = "Orchestrator port")
        int port;

        boolean useRemote() {
            return remote || (url != null && !url.isBlank());
        }
    }

    @CommandLine.Command(name = "list", description = "List subprocess runs with metadata.")
    public static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Option(names = {"--type"},
                description = "Filter by subprocess type (ingest, vector-population, embedding, model-init, vlm-test, training)")
        String type;
        @CommandLine.Option(names = {"--run-id"}, description = "Filter by run id")
        String runId;
        @CommandLine.Option(names = {"--since"}) String since;
        @CommandLine.Option(names = {"--until"}) String until;
        @CommandLine.Option(names = {"--limit"}, defaultValue = "50") int limit;
        @CommandLine.Option(names = {"--json"}) boolean json;

        @Override
        public Integer call() throws Exception {
            List<SubprocessLogMetadata> runs = remote.useRemote()
                    ? fetchRemoteRuns(remote, type, runId, since, until, limit)
                    : fetchLocalRuns(type, runId, since, until, limit);

            if (json) {
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(runs));
                return 0;
            }
            printRunTable(runs);
            return 0;
        }
    }

    @CommandLine.Command(name = "show", description = "Print all records for one subprocess run.")
    public static class ShowCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Parameters(index = "0", description = "Run id")
        String runId;
        @CommandLine.Option(names = {"--json"}) boolean json;
        @CommandLine.Option(names = {"--only"}, description = "STDOUT, STDERR, SYSTEM") String only;

        @Override
        public Integer call() throws Exception {
            List<AgentLogRecord> records = remote.useRemote()
                    ? fetchRemoteRecords(remote, runId, null, Integer.MAX_VALUE)
                    : fetchLocalRecords(runId);
            if (records == null) {
                System.err.println("No subprocess run found with runId=" + runId);
                return 1;
            }
            AgentLogRecord.Stream filter = parseStream(only);
            for (AgentLogRecord r : records) {
                if (filter != null && r.getStream() != filter) continue;
                if (json) {
                    System.out.println(MAPPER.writeValueAsString(r));
                } else {
                    printRecordLine(r);
                }
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "tail", description = "Follow a subprocess run's log.")
    public static class TailCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Parameters(index = "0", description = "Run id")
        String runId;
        @CommandLine.Option(names = {"--poll-ms"}, defaultValue = "500") long pollMs;
        @CommandLine.Option(names = {"--quiet-timeout-s"}, defaultValue = "60") long quietTimeoutS;

        @Override
        public Integer call() throws Exception {
            if (remote.useRemote()) {
                return tailRemote(remote, runId, pollMs, quietTimeoutS);
            }
            return tailLocal(runId, pollMs, quietTimeoutS);
        }
    }

    @CommandLine.Command(name = "aggregate",
            description = "Merge records across subprocess runs in timestamp order.")
    public static class AggregateCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Option(names = {"--type"}) String type;
        @CommandLine.Option(names = {"--run-id"}) String runId;
        @CommandLine.Option(names = {"--since"}) String since;
        @CommandLine.Option(names = {"--until"}) String until;
        @CommandLine.Option(names = {"--limit"}, defaultValue = "10000") int limit;
        @CommandLine.Option(names = {"--json"}) boolean json;

        @Override
        public Integer call() throws Exception {
            List<AgentLogRecord> records = remote.useRemote()
                    ? fetchRemoteAggregate(remote, type, runId, since, until, limit)
                    : fetchLocalAggregate(type, runId, since, until, limit);

            for (AgentLogRecord r : records) {
                if (json) {
                    System.out.println(MAPPER.writeValueAsString(r));
                } else {
                    printAggregatedRecordLine(r);
                }
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "cleanup",
            description = "Enforce retention policy now on subprocess logs.")
    public static class CleanupCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Option(names = {"--max-age-days"}, defaultValue = "30") long maxAgeDays;
        @CommandLine.Option(names = {"--max-total-mb"}, defaultValue = "2048") long maxTotalMb;
        @CommandLine.Option(names = {"--max-per-type"}, defaultValue = "100") int maxFilesPerType;

        @Override
        public Integer call() throws Exception {
            if (remote.useRemote()) {
                KompileHttpClient client = KompileHttpClient.create(remote.url, remote.port);
                System.out.println(client.postEmpty("/api/subprocess-logs/cleanup"));
                return 0;
            }
            LogRetentionPolicy policy = LogRetentionPolicy.of(maxAgeDays, maxTotalMb, maxFilesPerType);
            LogRetentionManager.RetentionResult result = new LogRetentionManager(policy).applyToSubprocesses();
            System.out.printf("Deleted %d runs (age=%d, perType=%d, size=%d)%n",
                    result.totalDeleted(), result.deletedByAge(),
                    result.deletedByPerAgent(), result.deletedBySize());
            System.out.println("Logs root: " + LogPaths.subprocessesRoot().getAbsolutePath());
            return 0;
        }
    }

    private static List<SubprocessLogMetadata> fetchLocalRuns(
            String type, String runId, String since, String until, int limit) {
        AgentLogReader.SubprocessRunFilter filter = new AgentLogReader.SubprocessRunFilter(
                type, runId, parseInstant(since), parseInstant(until));
        List<SubprocessLogMetadata> runs = AgentLogReader.listSubprocessRuns(filter);
        return runs.size() > limit ? runs.subList(0, limit) : runs;
    }

    private static List<AgentLogRecord> fetchLocalRecords(String runId) {
        return AgentLogReader.findSubprocessByRunId(runId).map(meta -> {
            File logFile = LogPaths.subprocessLogFile(meta.getSubprocessType(), meta.getRunId());
            if (!logFile.isFile()) return List.<AgentLogRecord>of();
            try (Stream<AgentLogRecord> stream = AgentLogReader.readRecords(logFile)) {
                return stream.toList();
            } catch (Exception e) {
                return List.<AgentLogRecord>of();
            }
        }).orElse(null);
    }

    private static List<AgentLogRecord> fetchLocalAggregate(
            String type, String runId, String since, String until, int limit) {
        AgentLogReader.SubprocessRunFilter filter = new AgentLogReader.SubprocessRunFilter(
                type, runId, parseInstant(since), parseInstant(until));
        List<SubprocessLogMetadata> runs = AgentLogReader.listSubprocessRuns(filter);
        try (Stream<AgentLogRecord> stream = AgentLogReader.aggregateAcrossSubprocessRuns(runs)) {
            return stream.limit(limit).toList();
        }
    }

    private static int tailLocal(String runId, long pollMs, long quietTimeoutS) throws Exception {
        SubprocessLogMetadata meta = AgentLogReader.findSubprocessByRunId(runId).orElse(null);
        if (meta == null) {
            System.err.println("No subprocess run found with runId=" + runId);
            return 1;
        }
        File logFile = LogPaths.subprocessLogFile(meta.getSubprocessType(), meta.getRunId());
        try (Stream<AgentLogRecord> stream = AgentLogReader.readRecords(logFile)) {
            stream.forEach(SubprocessLogsCommand::printRecordLine);
        }
        AgentLogReader.tail(
                logFile,
                Duration.ofMillis(pollMs),
                Duration.ofSeconds(quietTimeoutS),
                SubprocessLogsCommand::printRecordLine);
        return 0;
    }

    private static List<SubprocessLogMetadata> fetchRemoteRuns(
            RemoteOptions remote, String type, String runId, String since, String until, int limit) throws Exception {
        StringBuilder path = new StringBuilder("/api/subprocess-logs?limit=").append(limit);
        appendParam(path, "type", type);
        appendParam(path, "runId", runId);
        appendParam(path, "since", since);
        appendParam(path, "until", until);
        KompileHttpClient client = KompileHttpClient.create(remote.url, remote.port);
        return client.get(path.toString(), new TypeReference<List<SubprocessLogMetadata>>() {});
    }

    private static List<AgentLogRecord> fetchRemoteRecords(
            RemoteOptions remote, String runId, Integer fromSeq, int limit) throws Exception {
        StringBuilder path = new StringBuilder("/api/subprocess-logs/").append(runId).append("/records?limit=").append(limit);
        if (fromSeq != null) {
            path.append("&fromSeq=").append(fromSeq);
        }
        KompileHttpClient client = KompileHttpClient.create(remote.url, remote.port);
        return client.get(path.toString(), new TypeReference<List<AgentLogRecord>>() {});
    }

    private static List<AgentLogRecord> fetchRemoteAggregate(
            RemoteOptions remote, String type, String runId, String since, String until, int limit) throws Exception {
        StringBuilder path = new StringBuilder("/api/subprocess-logs/aggregate?limit=").append(limit);
        appendParam(path, "type", type);
        appendParam(path, "runId", runId);
        appendParam(path, "since", since);
        appendParam(path, "until", until);
        KompileHttpClient client = KompileHttpClient.create(remote.url, remote.port);
        return client.get(path.toString(), new TypeReference<List<AgentLogRecord>>() {});
    }

    private static int tailRemote(RemoteOptions remote, String runId,
                                   long pollMs, long quietTimeoutS) throws Exception {
        Integer lastSeq = null;
        Instant lastActivity = Instant.now();
        while (!Thread.currentThread().isInterrupted()) {
            List<AgentLogRecord> batch = fetchRemoteRecords(remote, runId, lastSeq, 5000);
            if (batch != null && !batch.isEmpty()) {
                for (AgentLogRecord r : batch) {
                    printRecordLine(r);
                    if (r.getSeq() != null) lastSeq = r.getSeq();
                }
                lastActivity = Instant.now();
            } else if (Duration.between(lastActivity, Instant.now()).getSeconds() > quietTimeoutS) {
                return 0;
            }
            Thread.sleep(pollMs);
        }
        return 0;
    }

    private static void printRunTable(List<SubprocessLogMetadata> runs) {
        if (runs.isEmpty()) {
            System.out.println("(no runs found)");
            return;
        }
        System.out.printf("%-36s  %-18s  %-25s  %-10s  %-10s  %s%n",
                "RUN_ID", "TYPE", "STARTED", "STATE", "EXIT", "DURATION_MS");
        System.out.println("-".repeat(120));
        for (SubprocessLogMetadata m : runs) {
            System.out.printf("%-36s  %-18s  %-25s  %-10s  %-10s  %s%n",
                    nullToDash(m.getRunId()),
                    nullToDash(m.getSubprocessType()),
                    m.getStartedAt() == null ? "-" : TS_FMT.format(m.getStartedAt()),
                    nullToDash(m.getState()),
                    m.getExitCode() == null ? "-" : m.getExitCode().toString(),
                    m.getDurationMs() == null ? "-" : m.getDurationMs().toString());
        }
    }

    private static void printRecordLine(AgentLogRecord r) {
        String ts = r.getTs() == null ? "-" : TS_FMT.format(r.getTs());
        String stream = r.getStream() == null ? "?" : r.getStream().name();
        System.out.printf("[%s][%s] %s%n", ts, stream, r.getLine() == null ? "" : r.getLine());
    }

    private static void printAggregatedRecordLine(AgentLogRecord r) {
        String ts = r.getTs() == null ? "-" : TS_FMT.format(r.getTs());
        String stream = r.getStream() == null ? "?" : r.getStream().name();
        // For subprocess logs: agentName holds subprocessType, processId holds runId
        String type = r.getAgentName() == null ? "-" : r.getAgentName();
        String run = r.getProcessId() == null ? "-" : r.getProcessId().substring(0, Math.min(8, r.getProcessId().length()));
        System.out.printf("[%s][%s][%s@%s] %s%n",
                ts, stream, type, run, r.getLine() == null ? "" : r.getLine());
    }

    private static void appendParam(StringBuilder sb, String name, Object value) {
        if (value == null) return;
        String s = value.toString();
        if (s.isBlank()) return;
        sb.append('&').append(name).append('=').append(java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static AgentLogRecord.Stream parseStream(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return AgentLogRecord.Stream.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
