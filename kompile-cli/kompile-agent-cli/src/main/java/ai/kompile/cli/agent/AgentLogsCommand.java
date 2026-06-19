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
import ai.kompile.cli.common.logs.AgentLogMetadata;
import ai.kompile.cli.common.logs.AgentLogReader;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.common.logs.LogRetentionManager;
import ai.kompile.cli.common.logs.LogRetentionPolicy;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * CLI subcommand for inspecting aggregated agent logs.
 *
 * <p>All operations default to the local filesystem at {@code ~/.kompile/logs/agents}.
 * Pass {@code --remote} to fetch from a running orchestrator via REST (useful when
 * the logs live on another host).
 */
@CommandLine.Command(name = "logs",
        description = "Inspect aggregated agent logs (list, show, tail, aggregate, cleanup).",
        subcommands = {
                AgentLogsCommand.ListCmd.class,
                AgentLogsCommand.ShowCmd.class,
                AgentLogsCommand.TailCmd.class,
                AgentLogsCommand.AggregateCmd.class,
                AgentLogsCommand.CleanupCmd.class
        })
public class AgentLogsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("No operation specified. Use: list | show | tail | aggregate | cleanup");
        new CommandLine(this).usage(System.err);
        return 1;
    }

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_INSTANT;

    // ------------------------------------------------------------
    // Shared remote options
    // ------------------------------------------------------------
    static class RemoteOptions {
        @CommandLine.Option(names = {"--remote"},
                description = "Fetch logs from orchestrator REST instead of local filesystem")
        boolean remote;

        @CommandLine.Option(names = {"--url"}, description = "Orchestrator URL (implies --remote)")
        String url;

        @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080",
                description = "Orchestrator port (implies --remote when --url set)")
        int port;

        boolean useRemote() {
            return remote || (url != null && !url.isBlank());
        }
    }

    // ------------------------------------------------------------
    // list
    // ------------------------------------------------------------
    @CommandLine.Command(name = "list", description = "List agent runs with metadata.")
    public static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Option(names = {"--instance"}, description = "Filter by orchestrator instance id")
        String instance;
        @CommandLine.Option(names = {"--agent"}, description = "Filter by agent name (claude-cli, codex-cli, gemini-cli)")
        String agent;
        @CommandLine.Option(names = {"--session"}, description = "Filter by session id")
        Long sessionId;
        @CommandLine.Option(names = {"--since"}, description = "Only runs started at or after this ISO-8601 instant")
        String since;
        @CommandLine.Option(names = {"--until"}, description = "Only runs started at or before this ISO-8601 instant")
        String until;
        @CommandLine.Option(names = {"--limit"}, defaultValue = "50")
        int limit;
        @CommandLine.Option(names = {"--json"}, description = "Emit raw JSON instead of a table")
        boolean json;

        @Override
        public Integer call() throws Exception {
            List<AgentLogMetadata> runs = remote.useRemote()
                    ? fetchRemoteRuns(remote, instance, agent, sessionId, since, until, limit)
                    : fetchLocalRuns(instance, agent, sessionId, since, until, limit);

            if (json) {
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(runs));
                return 0;
            }
            printRunTable(runs);
            return 0;
        }
    }

    // ------------------------------------------------------------
    // show
    // ------------------------------------------------------------
    @CommandLine.Command(name = "show", description = "Print all records for one run.")
    public static class ShowCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Parameters(index = "0", description = "Process id of the run")
        String processId;
        @CommandLine.Option(names = {"--json"}, description = "Emit one JSON record per line")
        boolean json;
        @CommandLine.Option(names = {"--only"}, description = "Filter by stream: STDOUT, STDERR, SYSTEM")
        String only;

        @Override
        public Integer call() throws Exception {
            List<AgentLogRecord> records = remote.useRemote()
                    ? fetchRemoteRecords(remote, processId, null, Integer.MAX_VALUE)
                    : fetchLocalRecords(processId);
            if (records == null) {
                System.err.println("No run found with processId=" + processId);
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

    // ------------------------------------------------------------
    // tail
    // ------------------------------------------------------------
    @CommandLine.Command(name = "tail", description = "Follow a run's log as it is appended.")
    public static class TailCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Parameters(index = "0", description = "Process id of the run")
        String processId;
        @CommandLine.Option(names = {"--poll-ms"}, defaultValue = "500",
                description = "Poll interval for new records (remote or local)")
        long pollMs;
        @CommandLine.Option(names = {"--quiet-timeout-s"}, defaultValue = "60",
                description = "Stop tailing after this many seconds of silence")
        long quietTimeoutS;

        @Override
        public Integer call() throws Exception {
            if (remote.useRemote()) {
                return tailRemote(remote, processId, pollMs, quietTimeoutS);
            }
            return tailLocal(processId, pollMs, quietTimeoutS);
        }
    }

    // ------------------------------------------------------------
    // aggregate
    // ------------------------------------------------------------
    @CommandLine.Command(name = "aggregate",
            description = "Merge records across multiple runs in timestamp order.")
    public static class AggregateCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Option(names = {"--instance"}) String instance;
        @CommandLine.Option(names = {"--agent"}) String agent;
        @CommandLine.Option(names = {"--session"}) Long sessionId;
        @CommandLine.Option(names = {"--since"}) String since;
        @CommandLine.Option(names = {"--until"}) String until;
        @CommandLine.Option(names = {"--limit"}, defaultValue = "10000") int limit;
        @CommandLine.Option(names = {"--json"}) boolean json;

        @Override
        public Integer call() throws Exception {
            List<AgentLogRecord> records = remote.useRemote()
                    ? fetchRemoteAggregate(remote, instance, agent, sessionId, since, until, limit)
                    : fetchLocalAggregate(instance, agent, sessionId, since, until, limit);

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

    // ------------------------------------------------------------
    // cleanup
    // ------------------------------------------------------------
    @CommandLine.Command(name = "cleanup",
            description = "Enforce retention policy now (delete aged/oversized logs).")
    public static class CleanupCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;

        @CommandLine.Option(names = {"--max-age-days"}, defaultValue = "30")
        long maxAgeDays;
        @CommandLine.Option(names = {"--max-total-mb"}, defaultValue = "2048")
        long maxTotalMb;
        @CommandLine.Option(names = {"--max-per-agent"}, defaultValue = "100")
        int maxFilesPerAgent;

        @Override
        public Integer call() throws Exception {
            if (remote.useRemote()) {
                KompileHttpClient client = KompileHttpClient.create(remote.url, remote.port);
                System.out.println(client.postEmpty("/api/agent-logs/cleanup"));
                return 0;
            }
            LogRetentionPolicy policy = LogRetentionPolicy.of(maxAgeDays, maxTotalMb, maxFilesPerAgent);
            LogRetentionManager.RetentionResult result = new LogRetentionManager(policy).applyToAgents();
            System.out.printf("Deleted %d runs (age=%d, perAgent=%d, size=%d)%n",
                    result.totalDeleted(), result.deletedByAge(),
                    result.deletedByPerAgent(), result.deletedBySize());
            System.out.println("Logs root: " + LogPaths.agentsRoot().getAbsolutePath());
            return 0;
        }
    }

    // ------------------------------------------------------------
    // Local filesystem readers
    // ------------------------------------------------------------
    private static List<AgentLogMetadata> fetchLocalRuns(
            String instance, String agent, Long sessionId, String since, String until, int limit) {
        AgentLogReader.RunFilter filter = new AgentLogReader.RunFilter(
                instance, agent, sessionId, parseInstant(since), parseInstant(until));
        List<AgentLogMetadata> runs = AgentLogReader.listRuns(filter);
        return runs.size() > limit ? runs.subList(0, limit) : runs;
    }

    private static List<AgentLogRecord> fetchLocalRecords(String processId) throws Exception {
        return AgentLogReader.findByProcessId(processId).map(meta -> {
            File logFile = LogPaths.agentLogFile(
                    meta.getOrchestratorInstanceId(), meta.getAgentName(), meta.getProcessId());
            if (!logFile.isFile()) return List.<AgentLogRecord>of();
            try (Stream<AgentLogRecord> stream = AgentLogReader.readRecords(logFile)) {
                return stream.toList();
            } catch (Exception e) {
                return List.<AgentLogRecord>of();
            }
        }).orElse(null);
    }

    private static List<AgentLogRecord> fetchLocalAggregate(
            String instance, String agent, Long sessionId, String since, String until, int limit) {
        AgentLogReader.RunFilter filter = new AgentLogReader.RunFilter(
                instance, agent, sessionId, parseInstant(since), parseInstant(until));
        List<AgentLogMetadata> runs = AgentLogReader.listRuns(filter);
        try (Stream<AgentLogRecord> stream = AgentLogReader.aggregateAcrossRuns(runs)) {
            return stream.limit(limit).toList();
        }
    }

    private static int tailLocal(String processId, long pollMs, long quietTimeoutS) throws Exception {
        AgentLogMetadata meta = AgentLogReader.findByProcessId(processId).orElse(null);
        if (meta == null) {
            System.err.println("No run found with processId=" + processId);
            return 1;
        }
        File logFile = LogPaths.agentLogFile(
                meta.getOrchestratorInstanceId(), meta.getAgentName(), meta.getProcessId());
        // Print the backlog first so the user sees context
        try (Stream<AgentLogRecord> stream = AgentLogReader.readRecords(logFile)) {
            stream.forEach(AgentLogsCommand::printRecordLine);
        }
        AgentLogReader.tail(
                logFile,
                Duration.ofMillis(pollMs),
                Duration.ofSeconds(quietTimeoutS),
                AgentLogsCommand::printRecordLine);
        return 0;
    }

    // ------------------------------------------------------------
    // Remote readers (REST)
    // ------------------------------------------------------------
    private static List<AgentLogMetadata> fetchRemoteRuns(
            RemoteOptions remote, String instance, String agent, Long sessionId,
            String since, String until, int limit) throws Exception {
        StringBuilder path = new StringBuilder("/api/agent-logs?limit=").append(limit);
        appendParam(path, "instance", instance);
        appendParam(path, "agent", agent);
        appendParam(path, "sessionId", sessionId);
        appendParam(path, "since", since);
        appendParam(path, "until", until);
        KompileHttpClient client = KompileHttpClient.create(remote.url, remote.port);
        return client.get(path.toString(), new TypeReference<List<AgentLogMetadata>>() {});
    }

    private static List<AgentLogRecord> fetchRemoteRecords(
            RemoteOptions remote, String processId, Integer fromSeq, int limit) throws Exception {
        StringBuilder path = new StringBuilder("/api/agent-logs/").append(processId).append("/records?limit=").append(limit);
        if (fromSeq != null) {
            path.append("&fromSeq=").append(fromSeq);
        }
        KompileHttpClient client = KompileHttpClient.create(remote.url, remote.port);
        return client.get(path.toString(), new TypeReference<List<AgentLogRecord>>() {});
    }

    private static List<AgentLogRecord> fetchRemoteAggregate(
            RemoteOptions remote, String instance, String agent, Long sessionId,
            String since, String until, int limit) throws Exception {
        StringBuilder path = new StringBuilder("/api/agent-logs/aggregate?limit=").append(limit);
        appendParam(path, "instance", instance);
        appendParam(path, "agent", agent);
        appendParam(path, "sessionId", sessionId);
        appendParam(path, "since", since);
        appendParam(path, "until", until);
        KompileHttpClient client = KompileHttpClient.create(remote.url, remote.port);
        return client.get(path.toString(), new TypeReference<List<AgentLogRecord>>() {});
    }

    private static int tailRemote(RemoteOptions remote, String processId,
                                   long pollMs, long quietTimeoutS) throws Exception {
        Integer lastSeq = null;
        Instant lastActivity = Instant.now();
        while (!Thread.currentThread().isInterrupted()) {
            List<AgentLogRecord> batch = fetchRemoteRecords(remote, processId, lastSeq, 5000);
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

    // ------------------------------------------------------------
    // Output formatting
    // ------------------------------------------------------------
    private static void printRunTable(List<AgentLogMetadata> runs) {
        if (runs.isEmpty()) {
            System.out.println("(no runs found)");
            return;
        }
        System.out.printf("%-36s  %-15s  %-12s  %-25s  %-10s  %s%n",
                "PROCESS_ID", "AGENT", "SESSION", "STARTED", "STATE", "DURATION_MS");
        System.out.println("-".repeat(120));
        for (AgentLogMetadata m : runs) {
            System.out.printf("%-36s  %-15s  %-12s  %-25s  %-10s  %s%n",
                    nullToDash(m.getProcessId()),
                    nullToDash(m.getAgentName()),
                    nullToDash(m.getSessionId() == null ? null : m.getSessionId().toString()),
                    m.getStartedAt() == null ? "-" : TS_FMT.format(m.getStartedAt()),
                    nullToDash(m.getState()),
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
        String agent = r.getAgentName() == null ? "-" : r.getAgentName();
        String proc = r.getProcessId() == null ? "-" : r.getProcessId().substring(0, Math.min(8, r.getProcessId().length()));
        System.out.printf("[%s][%s][%s@%s] %s%n",
                ts, stream, agent, proc, r.getLine() == null ? "" : r.getLine());
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------
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
