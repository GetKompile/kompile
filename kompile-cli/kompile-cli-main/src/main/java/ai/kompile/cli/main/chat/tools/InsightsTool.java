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
import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.InsightSource;
import ai.kompile.cli.insights.Insights;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.insights.JudgeInsights;
import ai.kompile.cli.insights.MilestoneInsights;
import ai.kompile.cli.insights.TestMilestoneReader;
import ai.kompile.cli.insights.ToolUsageInsights;
import ai.kompile.cli.main.chat.enforcer.JudgementLog;
import ai.kompile.cli.main.chat.tools.grounding.CrawlInsights;
import ai.kompile.cli.main.chat.tools.grounding.GraphInsights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only answers about chat-session data: judge verdicts, tool calls, test milestones, crawls
 * and knowledge graphs.
 *
 * <p>The caller asks in plain words and may name a topic; without one, {@link Insights} infers
 * it from the question, and gives an overview of every topic when it cannot. The answer is a
 * short table with sparklines, read the same by the terminal and by agents, plus a chart
 * description under {@link ToolResult#CHART_METADATA} that the web tool card draws.</p>
 *
 * <p>"This session" in a question limits the report to the calling session. A chat passes its
 * own session id to the MCP server and the agents it starts ({@code KOMPILE_PARENT_SESSION_ID}), so a
 * call that arrives through MCP also matches the records the chat wrote under its id.</p>
 *
 * <p>Crawls and graphs are read where the crawl and graph tools of the same session read them:
 * from the crawl manager and the app when the session has their URLs, and from the project
 * otherwise.</p>
 */
public class InsightsTool implements CliTool {

    private static final String TOOL_ID = "insights";
    /** Set by a chat for the MCP server and agents it starts: the chat's own session id. */
    private static final String PARENT_SESSION_ENV = "KOMPILE_PARENT_SESSION_ID";

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final String crawlBaseUrl;
    private final String graphBaseUrl;
    private final String parentSessionId;

    /** Crawls and graphs of the working directory's project only. */
    public InsightsTool() {
        this(null, null);
    }

    /**
     * @param crawlBaseUrl the crawl manager the session's crawl tools use, or null when they crawl
     *                     project-locally
     * @param graphBaseUrl the app the session's knowledge-graph tools use, or null when its graphs
     *                     are project-local
     */
    public InsightsTool(String crawlBaseUrl, String graphBaseUrl) {
        this(crawlBaseUrl, graphBaseUrl, System.getenv(PARENT_SESSION_ENV));
    }

    /** @param parentSessionId the session that started this process, or null */
    InsightsTool(String parentSessionId) {
        this(null, null, parentSessionId);
    }

    InsightsTool(String crawlBaseUrl, String graphBaseUrl, String parentSessionId) {
        this.crawlBaseUrl = crawlBaseUrl;
        this.graphBaseUrl = graphBaseUrl;
        this.parentSessionId = parentSessionId;
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder("Read-only answers about chat sessions, crawls and knowledge "
                + "graphs, as a short table with sparklines. Topics: ");
        List<String> topics = new ArrayList<>();
        for (InsightSource source : catalog().sources()) {
            topics.add(source.topic() + " (" + source.description() + ")");
        }
        sb.append(String.join("; ", topics)).append(". ");
        sb.append("Ask in plain words, e.g. 'what did the judge block today', 'pass-rate trend for module X', "
                + "'slowest tools this week', 'failed crawls', 'what is connected to Alice'. Time words (today, yesterday, last 3 days, this week, all time) "
                + "set the window, by default the last ").append(InsightsConfig.defaults().getDefaultWindowDays())
                .append(" days (configurable in ").append(InsightsConfig.FILE_NAME).append("); 'this session' "
                + "limits it to the current chat. Without a topic it is inferred from the question; with "
                + "neither you get an overview of every topic.");
        return sb.toString();
    }

    @Override
    public String compactHint() {
        return "Read-only session insights (" + String.join(", ", topicNames()) + "): ask in plain words, "
                + "e.g. 'slowest tools this week'; topic is optional. Returns a table with sparklines.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode topic = props.putObject("topic");
        topic.put("type", "string");
        ArrayNode values = topic.putArray("enum");
        topicNames().forEach(values::add);
        topic.put("description", "What to report on; omit it to infer the topic from the question. '"
                + Insights.OVERVIEW + "' gives one line per topic.");

        props.putObject("question").put("type", "string")
                .put("description", "The question in plain words, with any time window, module, tool, "
                        + "crawl job, knowledge base, fact sheet, graph node or 'this session'");

        return schema;
    }

    @Override
    public String permissionKey() {
        return "read";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Read session insights");

        InsightsConfig config = InsightsConfig.load();
        InsightsQuery query = InsightsQuery.parse(params.path("topic").asText(null),
                params.path("question").asText(null), sessionIds(context), Instant.now(),
                ZoneId.systemDefault(), config.getDefaultWindowDays());
        InsightReport report;
        try {
            report = insights(context.getWorkingDirectory(), config, crawlBaseUrl, graphBaseUrl).answer(query);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("Could not read insights data: " + e.getMessage());
        }

        String text = config.appendWarning(report.getText());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("topic", report.getTopic());
        if (report.getChart() != null) metadata.put(ToolResult.CHART_METADATA, report.getChart());
        return ToolResult.success(report.getHeadline(), text, metadata);
    }

    /** The calling session's ids: its own, and the chat's when a chat started this process. */
    List<String> sessionIds(ToolContext context) {
        return Arrays.asList(context.getSessionId(), parentSessionId);
    }

    /**
     * The sources this tool reads, for the chat's session panel. The panel keeps the instance
     * across refreshes, so that each source reads only what was written since the last one.
     */
    public Insights newInsights(Path workDir, InsightsConfig config) {
        return insights(workDir, config, crawlBaseUrl, graphBaseUrl);
    }

    /**
     * Every source, reading the data the chat, the MCP server, {@code test_milestone} and the
     * crawl and graph tools write.
     *
     * @param crawlBaseUrl the crawl manager, or null for the project's own crawls
     * @param graphBaseUrl the app holding the fact sheets, or null for the project's own graphs
     */
    static Insights insights(Path workDir, InsightsConfig config, String crawlBaseUrl, String graphBaseUrl) {
        return new Insights()
                .register(new JudgeInsights(JudgementLog.sessionsRoot(), config))
                .register(new ToolUsageInsights(ToolUsageInsights.defaultToolCallsDir(), config))
                .register(new MilestoneInsights(TestMilestoneReader.storeDir(workDir),
                        TestMilestoneReader.storeDir(Path.of(System.getProperty("user.home"))), config))
                .register(new CrawlInsights(workDir, crawlBaseUrl, config))
                .register(new GraphInsights(workDir, graphBaseUrl, config));
    }

    /** The sources, for naming topics; nothing is read. */
    private static Insights catalog() {
        return insights(Path.of(""), InsightsConfig.defaults(), null, null);
    }

    private static List<String> topicNames() {
        List<String> names = new ArrayList<>(catalog().topics());
        names.add(Insights.OVERVIEW);
        return names;
    }
}
