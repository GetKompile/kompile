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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.chat.aggregate.AggregateChatSourceService;
import ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention;
import ai.kompile.cli.common.chat.aggregate.ChatTranscriptSearch;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import ai.kompile.cli.common.http.KompileHttpClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code chat-import} subcommand — discover, list, read, import, search, and
 * retention-manage chat transcripts across every supported agent CLI.
 *
 * <p>Mirrors {@link AgentLogsCommand}: each sub-subcommand works either locally
 * (scanning {@code ~/.kompile/conversations/} and external CLI stores via the
 * {@link ChatSourceRegistry}) or remotely (calling the orchestrator's
 * {@code /api/chat-history/cli} REST endpoints) based on the {@code --remote}
 * flag mixin.</p>
 */
@CommandLine.Command(name = "chat-import",
        description = "Import and aggregate chat transcripts across all supported agent CLIs.",
        subcommands = {
                ChatImportCommand.SourcesCmd.class,
                ChatImportCommand.ListCmd.class,
                ChatImportCommand.ShowCmd.class,
                ChatImportCommand.FetchCmd.class,
                ChatImportCommand.SyncCmd.class,
                ChatImportCommand.SearchCmd.class,
                ChatImportCommand.CleanupCmd.class
        })
public class ChatImportCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("No operation specified. Use: sources | list | show | fetch | sync | search | cleanup");
        new CommandLine(this).usage(System.err);
        return 1;
    }

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_INSTANT;

    private static final String API_BASE = "/api/chat-history/cli";

    // ------------------------------------------------------------
    // Shared remote options
    // ------------------------------------------------------------
    static class RemoteOptions {
        @CommandLine.Option(names = {"--remote"},
                description = "Use the orchestrator REST API instead of the local filesystem")
        boolean remote;

        @CommandLine.Option(names = {"--url"}, description = "Orchestrator URL (implies --remote)")
        String url;

        @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080",
                description = "Orchestrator port (implies --remote when --url set)")
        int port;

        boolean useRemote() {
            return remote || (url != null && !url.isBlank());
        }

        KompileHttpClient client() {
            return KompileHttpClient.create(url, port);
        }
    }

    // ------------------------------------------------------------
    // sources
    // ------------------------------------------------------------
    @CommandLine.Command(name = "sources", description = "List all known chat sources, with discovery status.")
    public static class SourcesCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;
        @CommandLine.Option(names = {"--json"}, description = "Emit raw JSON instead of a table")
        boolean json;

        @Override
        public Integer call() throws Exception {
            List<SourceInfo> sources;
            if (remote.useRemote()) {
                Map<String, Map<String, Object>> raw = remote.client().get(API_BASE + "/sources",
                        new TypeReference<Map<String, Map<String, Object>>>() {});
                sources = new ArrayList<>();
                for (Map.Entry<String, Map<String, Object>> e : raw.entrySet()) {
                    Map<String, Object> v = e.getValue();
                    int count = asInt(v.get("count"));
                    boolean avail = Boolean.TRUE.equals(v.get("available"));
                    String path = String.valueOf(v.getOrDefault("path", ""));
                    sources.add(avail
                            ? SourceInfo.available(e.getKey(), e.getKey(), path, count)
                            : SourceInfo.unavailable(e.getKey(), e.getKey(), path, "unavailable"));
                }
            } else {
                sources = new AggregateChatSourceService().discoverAll();
            }
            if (json) {
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sources));
                return 0;
            }
            printSourceTable(sources);
            return 0;
        }
    }

    // ------------------------------------------------------------
    // list
    // ------------------------------------------------------------
    @CommandLine.Command(name = "list", description = "List chat sessions, optionally filtered by source.")
    public static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;
        @CommandLine.Option(names = {"--source"}, description = "Restrict to a single source id (e.g. claude-code)")
        String source;
        @CommandLine.Option(names = {"--limit"}, defaultValue = "50")
        int limit;
        @CommandLine.Option(names = {"--json"}, description = "Emit raw JSON instead of a table")
        boolean json;

        @Override
        public Integer call() throws Exception {
            List<ChatSessionSummary> sessions;
            if (remote.useRemote()) {
                StringBuilder path = new StringBuilder(API_BASE).append("/sessions");
                if (source != null && !source.isBlank()) {
                    path.append("?source=").append(urlEncode(source));
                }
                List<Map<String, Object>> raw = remote.client().get(path.toString(),
                        new TypeReference<List<Map<String, Object>>>() {});
                sessions = raw.stream().map(ChatImportCommand::remoteToSummary).collect(Collectors.toList());
            } else {
                AggregateChatSourceService svc = new AggregateChatSourceService();
                sessions = source == null || source.isBlank()
                        ? svc.listAll()
                        : svc.listFiltered(List.of(source));
            }
            if (sessions.size() > limit) sessions = sessions.subList(0, limit);
            if (json) {
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sessions));
                return 0;
            }
            printSessionTable(sessions);
            return 0;
        }
    }

    // ------------------------------------------------------------
    // show
    // ------------------------------------------------------------
    @CommandLine.Command(name = "show", description = "Print the parsed turns of a single chat session.")
    public static class ShowCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;
        @CommandLine.Parameters(index = "0", description = "Session id")
        String sessionId;
        @CommandLine.Option(names = {"--source"}, defaultValue = "kompile",
                description = "Source of the session (default: kompile)")
        String source;
        @CommandLine.Option(names = {"--json"}, description = "Emit raw JSON instead of a formatted transcript")
        boolean json;

        @Override
        public Integer call() throws Exception {
            List<ChatTurn> turns;
            String title;
            if (remote.useRemote()) {
                JsonNode node = remote.client().get(API_BASE + "/sessions/" + urlEncode(sessionId)
                        + "?source=" + urlEncode(source), JsonNode.class);
                if (node == null || node.isNull() || node.isMissingNode()) {
                    System.err.println("Session not found: " + sessionId);
                    return 1;
                }
                title = node.path("title").asText(sessionId);
                turns = new ArrayList<>();
                for (JsonNode turn : node.path("turns")) {
                    turns.add(new ChatTurn(turn.path("role").asText("?"),
                            turn.path("content").asText("")));
                }
            } else {
                ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().require(source);
                turns = adapter.readTurns(sessionId);
                title = adapter.resolveTitle(sessionId);
            }
            if (json) {
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(turns));
                return 0;
            }
            System.out.println("Source: " + source);
            System.out.println("Session: " + sessionId);
            if (title != null && !title.equals(sessionId)) System.out.println("Title: " + title);
            System.out.println("Turns: " + turns.size());
            System.out.println("-".repeat(72));
            for (ChatTurn t : turns) {
                System.out.println("[" + t.role() + "]");
                System.out.println(t.content());
                System.out.println();
            }
            return 0;
        }
    }

    // ------------------------------------------------------------
    // fetch
    // ------------------------------------------------------------
    @CommandLine.Command(name = "fetch",
            description = "Import an external chat session into Kompile (writes .txt; optionally syncs to DB via --remote).")
    public static class FetchCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;
        @CommandLine.Parameters(index = "0", description = "Session id")
        String sessionId;
        @CommandLine.Option(names = {"--source"}, required = true,
                description = "Source of the session (e.g. claude-code)")
        String source;

        @Override
        public Integer call() throws Exception {
            if (remote.useRemote()) {
                String resp = remote.client().postEmpty(API_BASE + "/sessions/" + urlEncode(sessionId)
                        + "/import?source=" + urlEncode(source));
                System.out.println(resp);
                return 0;
            }
            ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().require(source);
            List<ChatTurn> turns = adapter.readTurns(sessionId);
            if (turns.isEmpty()) {
                System.err.println("No turns to import for " + source + " session: " + sessionId);
                return 1;
            }
            String title = adapter.resolveTitle(sessionId);
            String cwd = adapter.resolveWorkingDirectory(sessionId)
                    .map(Path::toString).orElse("");
            Path target = writeKompileTranscript(source, sessionId, title, cwd, turns);
            System.out.println("Wrote " + turns.size() + " turns to " + target);
            return 0;
        }
    }

    // ------------------------------------------------------------
    // sync
    // ------------------------------------------------------------
    @CommandLine.Command(name = "sync",
            description = "Sync external CLI sessions into Kompile (local writes .txt; --remote also persists to DB).")
    public static class SyncCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;
        @CommandLine.Option(names = {"--source"},
                description = "Source id (repeat for multiple; omit with --all for every source)")
        List<String> sources;
        @CommandLine.Option(names = {"--all"},
                description = "Sync every registered source (overrides --source)")
        boolean all;
        @CommandLine.Option(names = {"--batch"}, defaultValue = "200",
                description = "Max sessions per source to import")
        int batch;

        @Override
        public Integer call() throws Exception {
            Set<String> ids = selectSourceIds(sources, all);
            int totalImported = 0;
            int totalSkipped = 0;
            int totalErrored = 0;
            for (String sid : ids) {
                SyncStats s = syncOneSource(remote, sid, batch);
                System.out.printf("[%s] imported=%d skipped=%d errored=%d%n",
                        sid, s.imported, s.skipped, s.errored);
                totalImported += s.imported;
                totalSkipped += s.skipped;
                totalErrored += s.errored;
            }
            System.out.printf("TOTAL imported=%d skipped=%d errored=%d%n",
                    totalImported, totalSkipped, totalErrored);
            return totalErrored > 0 ? 2 : 0;
        }
    }

    // ------------------------------------------------------------
    // search
    // ------------------------------------------------------------
    @CommandLine.Command(name = "search",
            description = "Grep-style search across chat transcripts (one or all sources).")
    public static class SearchCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;
        @CommandLine.Parameters(index = "0", description = "Pattern (literal substring by default)")
        String pattern;
        @CommandLine.Option(names = {"--source"}, description = "Restrict to one source id")
        String source;
        @CommandLine.Option(names = {"--all-sources"}, description = "Search every registered source")
        boolean allSources;
        @CommandLine.Option(names = {"-C", "--context"}, defaultValue = "0",
                description = "Include N neighbouring turns of context per hit")
        int contextLines;
        @CommandLine.Option(names = {"--regex"}, description = "Interpret <pattern> as a Java regex")
        boolean regex;
        @CommandLine.Option(names = {"-i", "--ignore-case"}, description = "Case-insensitive match")
        boolean ignoreCase;
        @CommandLine.Option(names = {"--limit"}, defaultValue = "100")
        int limit;
        @CommandLine.Option(names = {"--json"}, description = "Emit raw JSON instead of text hits")
        boolean json;

        @Override
        public Integer call() throws Exception {
            if (remote.useRemote()) {
                StringBuilder path = new StringBuilder(API_BASE).append("/search?q=").append(urlEncode(pattern))
                        .append("&limit=").append(limit).append("&ctx=").append(contextLines)
                        .append("&regex=").append(regex).append("&ignoreCase=").append(ignoreCase);
                if (source != null && !source.isBlank()) {
                    path.append("&source=").append(urlEncode(source));
                }
                JsonNode node = remote.client().get(path.toString(), JsonNode.class);
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
                return 0;
            }
            ChatTranscriptSearch.Query q = new ChatTranscriptSearch.Query()
                    .pattern(pattern)
                    .contextLines(contextLines)
                    .limit(limit)
                    .regex(regex)
                    .caseInsensitive(ignoreCase);
            if (source != null && !source.isBlank()) {
                q.sources(List.of(source));
            } else if (!allSources) {
                System.err.println("Specify --source=<id> or --all-sources");
                return 1;
            }
            List<ChatTranscriptSearch.Hit> hits = new ChatTranscriptSearch().search(q);
            if (json) {
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(hits));
                return 0;
            }
            if (hits.isEmpty()) {
                System.out.println("(no matches)");
                return 0;
            }
            for (ChatTranscriptSearch.Hit h : hits) {
                System.out.printf("[%s:%s #%d %s] %s%n",
                        h.source(), truncate(h.sessionId(), 10), h.turnIndex(),
                        h.role(), firstLine(h.content()));
                for (ChatTranscriptSearch.ContextTurn c : h.context()) {
                    System.out.printf("    ctx[%d %s] %s%n", c.turnIndex(), c.role(),
                            firstLine(c.content()));
                }
            }
            return 0;
        }
    }

    // ------------------------------------------------------------
    // cleanup
    // ------------------------------------------------------------
    @CommandLine.Command(name = "cleanup",
            description = "Enforce retention on Kompile-authored transcripts in ~/.kompile/conversations/.")
    public static class CleanupCmd implements Callable<Integer> {
        @CommandLine.Mixin RemoteOptions remote;
        @CommandLine.Option(names = {"--max-age-days"}, defaultValue = "90")
        long maxAgeDays;
        @CommandLine.Option(names = {"--max-total-mb"}, defaultValue = "2048")
        long maxTotalMb;
        @CommandLine.Option(names = {"--max-per-source"}, defaultValue = "1000")
        int maxPerSource;
        @CommandLine.Option(names = {"--dry-run"}, description = "Report deletions without removing files")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            if (remote.useRemote()) {
                String path = API_BASE + "/retention?dryRun=" + dryRun
                        + "&maxAgeDays=" + maxAgeDays
                        + "&maxTotalMb=" + maxTotalMb
                        + "&maxPerSource=" + maxPerSource;
                System.out.println(remote.client().delete(path));
                return 0;
            }
            File conversationsDir = new File(KompileHome.homeDirectory(), "conversations");
            ChatTranscriptRetention.Policy policy = ChatTranscriptRetention.Policy.of(
                    maxAgeDays, maxTotalMb, maxPerSource);
            ChatTranscriptRetention.Result result = new ChatTranscriptRetention(policy)
                    .apply(conversationsDir, dryRun);
            System.out.printf("%s %d transcripts (age=%d, count=%d, size=%d)%n",
                    dryRun ? "Would delete" : "Deleted",
                    result.totalDeleted(),
                    result.deletedByAge(), result.deletedByCount(), result.deletedBySize());
            System.out.println("Conversations dir: " + conversationsDir.getAbsolutePath());
            return 0;
        }
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------
    private static Set<String> selectSourceIds(List<String> requested, boolean all) {
        ChatSourceRegistry registry = ChatSourceRegistry.getInstance();
        if (all || requested == null || requested.isEmpty()) {
            return new LinkedHashSet<>(registry.ids());
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : requested) {
            if (registry.find(id).isPresent()) out.add(id);
            else System.err.println("Warning: unknown source '" + id + "', skipping");
        }
        return out;
    }

    private static SyncStats syncOneSource(RemoteOptions remote, String sourceId, int batch) {
        SyncStats stats = new SyncStats();
        List<ChatSessionSummary> sessions;
        try {
            sessions = ChatSourceRegistry.getInstance().require(sourceId).list();
        } catch (Exception e) {
            System.err.println("[" + sourceId + "] list failed: " + e.getMessage());
            stats.errored++;
            return stats;
        }
        int upper = Math.min(batch, sessions.size());
        for (int i = 0; i < upper; i++) {
            ChatSessionSummary summary = sessions.get(i);
            try {
                if (remote.useRemote()) {
                    remote.client().postEmpty(API_BASE + "/sessions/" + urlEncode(summary.sessionId())
                            + "/import?source=" + urlEncode(sourceId));
                    stats.imported++;
                } else {
                    ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().require(sourceId);
                    List<ChatTurn> turns = adapter.readTurns(summary.sessionId());
                    if (turns.isEmpty()) {
                        stats.skipped++;
                        continue;
                    }
                    String cwd = adapter.resolveWorkingDirectory(summary.sessionId())
                            .map(Path::toString).orElse("");
                    writeKompileTranscript(sourceId, summary.sessionId(), summary.title(), cwd, turns);
                    stats.imported++;
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.toLowerCase().contains("conflict") || msg.toLowerCase().contains("duplicate")) {
                    stats.skipped++;
                } else {
                    System.err.println("[" + sourceId + "] " + summary.sessionId() + " failed: " + msg);
                    stats.errored++;
                }
            }
        }
        return stats;
    }

    static Path writeKompileTranscript(String sourceId, String sessionId, String title,
                                       String cwd, List<ChatTurn> turns) throws IOException {
        Path dir = KompileHome.homeDirectory().toPath().resolve("conversations");
        Files.createDirectories(dir);
        String safe = sanitize("imported-" + sourceId + "-" + sessionId);
        Path target = dir.resolve(safe + ".txt");
        StringBuilder sb = new StringBuilder();
        sb.append("Started: ").append(title == null ? sessionId : title).append('\n');
        sb.append("Agent: ").append(sourceId).append('\n');
        if (cwd != null && !cwd.isBlank()) sb.append("CWD: ").append(cwd).append('\n');
        sb.append("Source: ").append(sourceId).append('\n');
        sb.append("SessionId: ").append(sessionId).append('\n');
        sb.append("Imported: ").append(Instant.now()).append('\n');
        sb.append('\n');
        for (ChatTurn turn : turns) {
            if (turn.isUser()) {
                sb.append("> ").append(turn.content().replace("\n", "\n> ")).append("\n\n");
            } else {
                sb.append(turn.content()).append("\n\n");
            }
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
        return target;
    }

    private static String sanitize(String raw) {
        if (raw == null) return "unknown";
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ChatSessionSummary remoteToSummary(Map<String, Object> m) {
        String sessionId = String.valueOf(m.getOrDefault("sessionId", ""));
        String source = String.valueOf(m.getOrDefault("source", ""));
        String title = m.get("title") == null ? null : m.get("title").toString();
        String agent = m.get("agent") == null ? source : m.get("agent").toString();
        int count = asInt(m.get("messageCount"));
        long modified = m.get("lastModified") instanceof Number n ? n.longValue() : 0L;
        return new ChatSessionSummary(sessionId, source, title, agent, count, modified);
    }

    private static void printSourceTable(List<SourceInfo> sources) {
        if (sources.isEmpty()) {
            System.out.println("(no sources registered)");
            return;
        }
        System.out.printf("%-12s  %-9s  %-7s  %s%n", "SOURCE", "AVAILABLE", "COUNT", "PATH");
        System.out.println("-".repeat(90));
        for (SourceInfo s : sources) {
            System.out.printf("%-12s  %-9s  %-7d  %s%n",
                    s.source(), s.available() ? "yes" : "no",
                    s.sessionCount(), s.path());
            if (!s.available() && s.reason() != null) {
                System.out.printf("              reason: %s%n", s.reason());
            }
        }
    }

    private static void printSessionTable(List<ChatSessionSummary> sessions) {
        if (sessions.isEmpty()) {
            System.out.println("(no sessions found)");
            return;
        }
        System.out.printf("%-12s  %-36s  %-5s  %-20s  %s%n",
                "SOURCE", "SESSION_ID", "TURNS", "LAST_MODIFIED", "TITLE");
        System.out.println("-".repeat(120));
        for (ChatSessionSummary s : sessions) {
            String ts = s.lastModifiedMillis() > 0
                    ? TS_FMT.format(Instant.ofEpochMilli(s.lastModifiedMillis()))
                    : "-";
            System.out.printf("%-12s  %-36s  %-5d  %-20s  %s%n",
                    nullToDash(s.source()),
                    truncate(nullToDash(s.sessionId()), 36),
                    s.messageCount(),
                    ts,
                    truncate(nullToDash(s.title()), 60));
        }
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String firstLine(String content) {
        if (content == null) return "";
        int n = content.indexOf('\n');
        String line = n < 0 ? content : content.substring(0, n);
        return line.length() > 160 ? line.substring(0, 157) + "..." : line;
    }

    private static final class SyncStats {
        int imported;
        int skipped;
        int errored;
    }

    // Marker to keep Arrays import for future use (kept for the sources table).
    @SuppressWarnings("unused")
    private static final Collection<?> KEEP_IMPORTS = Arrays.asList();
}
