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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.JudgeInsights;
import ai.kompile.cli.main.chat.ToolCallIndex;
import ai.kompile.cli.main.chat.enforcer.JudgementLog;
import ai.kompile.cli.main.chat.enforcer.JudgementRecord;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The insights tool answers from what the chat, the MCP server and {@code test_milestone} write,
 * here under a temporary home: judge verdicts, tool calls and test milestones; and from the crawls
 * and knowledge graphs of the working project, or of the servers the session's tools use.
 */
@TemporaryUserHome
class InsightsToolTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @TempDir
    Path project;

    @Test
    void schemaOffersEveryTopicAndTheToolOnlyReads() {
        InsightsTool tool = new InsightsTool(null);
        List<String> topics = new ArrayList<>();
        tool.parameterSchema().path("properties").path("topic").path("enum").forEach(t -> topics.add(t.asText()));

        assertEquals(List.of("judge", "tools", "tests", "crawl", "graph", "overview"), topics);
        assertSame(McpToolAnnotations.READ_ONLY, tool.mcpAnnotations());
        assertEquals("read", tool.permissionKey());
        assertTrue(tool.compactHint().length() <= 180, tool.compactHint());
        // The judge source reads the file the chat's judgement log writes, where it writes it: the
        // chat app has no enforcer on its classpath and finds the logs through the library's default.
        assertEquals(JudgementLog.FILE_NAME, JudgeInsights.FILE_NAME);
        assertEquals(JudgementLog.sessionsRoot(), JudgeInsights.defaultSessionsRoot());
    }

    @Test
    void judgeReportCountsVerdictsFlagsAndBlockedTurns() throws Exception {
        JudgementLog log = JudgementLog.forSession("judge-session");
        log.record(JudgementRecord.builder().phase("JUDGE_TOOL").judgeMode("llm").compliant(false).stop(true)
                .severity("HIGH").toolName("bash").violations(List.of("deleted files outside the project"))
                .latencyMs(40).build());
        log.record(JudgementRecord.builder().phase("RESULT").status("BLOCKED").build());

        ToolResult result = new InsightsTool(null).execute(
                params("judge", "what did the judge block in this session"), context("judge-session"));

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getTitle().contains(
                "1 verdict in 1 session, 1 flagged (100.0%), 1 stop verdict, 1 turn blocked"), result.getTitle());
        assertTrue(result.getOutput().contains("deleted files outside the project"), result.getOutput());
        assertEquals("judge", result.getMetadata().get("topic"));
        ObjectNode chart = assertInstanceOf(ObjectNode.class, result.getMetadata().get(ToolResult.CHART_METADATA));
        assertEquals("bar", chart.path("kind").asText());
    }

    @Test
    void toolReportNamesTheSlowestToolOfThisSession() throws Exception {
        ToolCallIndex index = ToolCallIndex.getInstance();
        index.record("tools-session", "read", "{\"file_path\":\"a.txt\"}", "claude", "mcp-stdio", false, 120);
        index.record("tools-session", "bash", "{\"command\":\"false\"}", "claude", "mcp-stdio", true, 900);

        ToolResult scoped = new InsightsTool(null).execute(params(null, "slowest tools in this session"),
                context("tools-session"));

        assertFalse(scoped.isError(), scoped.getOutput());
        assertEquals("tools", scoped.getMetadata().get("topic"));
        assertTrue(scoped.getTitle().contains("2 calls to 2 tools, 1 error (50.0%)"), scoped.getTitle());
        assertTrue(scoped.getTitle().contains("slowest p95: bash"), scoped.getTitle());
        assertInstanceOf(ObjectNode.class, scoped.getMetadata().get(ToolResult.CHART_METADATA));

        // Without "this session" every session's calls count.
        ToolResult recent = new InsightsTool(null).execute(params(null, "tool calls in the last 2 days"),
                context("another-session"));
        assertTrue(recent.getTitle().contains("2 calls to 2 tools"), recent.getTitle());
        assertTrue(recent.getTitle().contains("across 1 session"), recent.getTitle());
    }

    @Test
    void testReportFollowsTheModuleTheQuestionNames() throws Exception {
        TestMilestoneTool milestones = new TestMilestoneTool();
        ToolContext context = context("tests-session");
        assertFalse(milestones.execute(mapper.readTree("{\"action\":\"record\",\"module\":\"core\","
                + "\"total_tests\":10,\"passed\":10,\"commit\":\"" + COMMIT + "\",\"branch\":\"feature\"}"),
                context).isError());
        assertFalse(milestones.execute(mapper.readTree("{\"action\":\"fail\",\"module\":\"core\","
                + "\"total_tests\":10,\"passed\":8,\"failed\":2,\"commit\":\"" + COMMIT + "\",\"branch\":\"feature\"}"),
                context).isError());

        ToolResult result = new InsightsTool(null).execute(params(null, "pass-rate trend for core"), context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("tests", result.getMetadata().get("topic"));
        assertTrue(result.getTitle().startsWith("Tests for core, all time: 2 runs, 50.0% green"), result.getTitle());
        ObjectNode chart = assertInstanceOf(ObjectNode.class, result.getMetadata().get(ToolResult.CHART_METADATA));
        assertEquals("line", chart.path("kind").asText());
        assertEquals("Pass rate for core, all time", chart.path("title").asText());
    }

    @Test
    void crawlAndGraphQuestionsReadTheWorkingProject() throws Exception {
        Path crawl = Files.createDirectories(project.resolve("data/crawls/people"));
        Files.writeString(crawl.resolve("crawl-result.json"), "{\"name\":\"People\",\"status\":\"COMPLETED\","
                + "\"finishedAt\":\"" + Instant.now() + "\",\"documentCount\":2,\"chunkCount\":6,"
                + "\"graphEntityCount\":2,\"graphRelationCount\":1}");
        new UnifiedGraph().graphId("local:test:people")
                .addEntity("alice", "PERSON", "Alice")
                .addEntity("acme", "ORGANIZATION", "Acme")
                .addRelation("r1", "alice", "acme", "worksFor", 1.0)
                .save(crawl.resolve("graph.kgraph"));

        ToolResult crawls = new InsightsTool(null).execute(params(null, "crawls this week"), context("crawl-session"));

        assertFalse(crawls.isError(), crawls.getOutput());
        assertEquals("crawl", crawls.getMetadata().get("topic"));
        assertTrue(crawls.getTitle().endsWith(": no crawl jobs; 1 knowledge base, 2 documents"), crawls.getTitle());
        ObjectNode bars = assertInstanceOf(ObjectNode.class, crawls.getMetadata().get(ToolResult.CHART_METADATA));
        assertEquals("Documents per knowledge base", bars.path("title").asText());

        ToolResult graph = new InsightsTool(null).execute(params(null, "what is connected to Acme"),
                context("graph-session"));

        assertFalse(graph.isError(), graph.getOutput());
        assertEquals("graph", graph.getMetadata().get("topic"));
        assertEquals("Graph People: 2 entities, 1 relation, 1 predicate; around Acme (ORGANIZATION)", graph.getTitle());
        assertTrue(graph.getOutput().contains("Acme (ORGANIZATION)\n└─ ← worksFor ─ Alice (PERSON)\n"),
                graph.getOutput());
        ObjectNode drawing = assertInstanceOf(ObjectNode.class, graph.getMetadata().get(ToolResult.CHART_METADATA));
        assertEquals("graph", drawing.path("kind").asText());
        assertEquals("acme", drawing.path("focus").asText());
    }

    @Test
    void crawlsAndGraphsAreAskedOfTheServersTheSessionUses() throws Exception {
        String url = unreachableUrl();
        InsightsTool tool = new InsightsTool(url, url, null);

        ToolResult crawls = tool.execute(params("crawl", null), context("crawl-session"));
        assertFalse(crawls.isError(), crawls.getOutput());
        assertTrue(crawls.getOutput().contains("The crawl manager at " + url + " could not be reached ("),
                crawls.getOutput());

        ToolResult graphs = tool.execute(params("graph", null), context("graph-session"));
        assertFalse(graphs.isError(), graphs.getOutput());
        assertEquals("Graphs: no knowledge graphs", graphs.getTitle());
        assertTrue(graphs.getOutput().contains("The graph server at " + url + " could not be reached ("),
                graphs.getOutput());

        // Without the servers the same questions stay in the project.
        ToolResult local = new InsightsTool(null).execute(params("graph", null), context("graph-session"));
        assertEquals("Graphs: no knowledge graphs in this project", local.getTitle());
        assertFalse(local.getOutput().contains(url), local.getOutput());
    }

    @Test
    void unknownTopicNamesTheTopicsThereAre() throws Exception {
        ToolResult result = new InsightsTool(null).execute(params("weather", "rain tomorrow"), context("any-session"));

        assertTrue(result.isError());
        assertEquals("Unknown insights topic 'weather'. Topics: judge, tools, tests, crawl, graph, overview",
                result.getOutput());
    }

    @Test
    void thisSessionIncludesTheChatThatStartedTheMcpServer() throws Exception {
        JudgementLog.forSession("chat-parent").record(JudgementRecord.builder().phase("JUDGE_TURN")
                .judgeMode("llm").compliant(false).reasoning("skipped the tests").build());
        ObjectNode params = params("judge", "flags in this session");

        ToolResult viaChat = new InsightsTool("chat-parent").execute(params, context("mcp-child"));
        assertTrue(viaChat.getTitle().contains("1 verdict in 1 session, 1 flagged"), viaChat.getTitle());

        ToolResult alone = new InsightsTool(null).execute(params, context("mcp-child"));
        assertTrue(alone.getTitle().contains("no judge verdicts"), alone.getTitle());
        assertTrue(alone.getOutput().contains("No judge log for this session (mcp-child)."), alone.getOutput());
    }

    @Test
    void unreadableConfigIsReportedOnceInEveryAnswer() throws Exception {
        Path file = InsightsConfig.configFile();
        // The config directory follows KOMPILE_PROJECT_ROOT when it is set; write only into the temporary home.
        assumeTrue(file.startsWith(Path.of(System.getProperty("user.home"))), file.toString());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");
        try {
            String warning = InsightsConfig.load(file).getWarning();
            assertNotNull(warning);
            for (ObjectNode params : List.of(params("judge", "flags this week"), params("overview", null))) {
                ToolResult result = new InsightsTool(null).execute(params, context("warning-session"));
                assertFalse(result.isError(), result.getOutput());
                assertEquals(1, occurrences(result.getOutput(), warning), result.getOutput());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private ToolContext context(String sessionId) {
        ToolContext context = new ToolContext(sessionId, null, null, project, null);
        context.setAutoApproveAll(true);
        return context;
    }

    private ObjectNode params(String topic, String question) {
        ObjectNode params = mapper.createObjectNode();
        if (topic != null) params.put("topic", topic);
        if (question != null) params.put("question", question);
        return params;
    }

    /** A loopback address nothing listens on: the port was free a moment ago. */
    private static String unreachableUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
