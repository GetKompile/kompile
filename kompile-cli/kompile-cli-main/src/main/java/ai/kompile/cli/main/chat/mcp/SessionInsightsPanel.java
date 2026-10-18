/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.Insights;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.insights.Panel;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.InsightsTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tui.KompileTui;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Static terminal discovery hint when the project configures no custom dashboard.
 * Fetching the hint never constructs metric sources. The web drawer continues to read real
 * metrics, including its live flag, through the independent {@link #snapshot} path.
 */
public final class SessionInsightsPanel implements McpDashboardController.DashboardFetcher {

    static final String TITLE = "+ /dashboard to see session metrics";
    static final String HINT_DETAIL = "On-demand reports · /dashboard-summary · /dashboard-tools";
    /** The web drawer's title: the terminal's {@code /dashboard hide} means nothing there. */
    static final String SNAPSHOT_TITLE = "Session insights";
    static final String INSIGHTS_TOOL = "insights";

    private final ObjectMapper mapper;

    SessionInsightsPanel(Supplier<ToolContext> contextSupplier, KompileTui tui, InsightsConfig config) {
        this(contextSupplier, () -> contentRows(tui), (context, workDir) -> sessionInsights(context, workDir, config),
                Clock.systemDefaultZone(), JsonUtils.standardMapper());
    }

    SessionInsightsPanel(Supplier<ToolContext> contextSupplier, IntSupplier maxLines,
                         BiFunction<ToolContext, Path, Insights> insightsFactory, Clock clock, ObjectMapper mapper) {
        Objects.requireNonNull(contextSupplier, "contextSupplier");
        Objects.requireNonNull(maxLines, "maxLines");
        Objects.requireNonNull(insightsFactory, "insightsFactory");
        this.mapper = mapper == null ? JsonUtils.standardMapper() : mapper;
    }

    @Override
    public synchronized String fetch() throws Exception {
        // Terminal discovery is intentionally static: no context lookup, source scans or watches.
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", McpDashboardController.SCHEMA_VERSION);
        root.put("title", TITLE);
        root.put("contextVersion", "dashboard-hint-v1");
        root.putArray("lines").add(HINT_DETAIL);
        return mapper.writeValueAsString(root);
    }

    /** Terminal hints have no metrics panel or watches. Web snapshots are independent. */
    Panel lastPanel() {
        return null;
    }

    /**
     * The session's panel, read once for a reader outside the terminal: the web chat's insights
     * drawer. A web run is project-local, so are the sources read here. The rows are cleaned and
     * cut as the terminal's dashboard area shows them; {@code live} says a crawl is running.
     *
     * @return {@code schemaVersion}, {@code title}, {@code contextVersion}, {@code lines} and {@code live}
     */
    public static ObjectNode snapshot(Path workDir, String sessionId) throws Exception {
        return snapshot(new InsightsTool().newInsights(workDir, InsightsConfig.load()), sessionId,
                Clock.systemDefaultZone(), JsonUtils.standardMapper());
    }

    static ObjectNode snapshot(Insights insights, String sessionId, Clock clock, ObjectMapper mapper)
            throws Exception {
        Instant now = clock.instant();
        Panel panel = insights.panel(InsightsQuery.session(sessionId, now, clock.getZone()),
                McpDashboardController.MAX_LINES);
        McpDashboardController.DashboardSnapshot shown = McpDashboardController.parse(
                mapper.writeValueAsString(dashboard(panel, SNAPSHOT_TITLE, now, mapper)), mapper);
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", McpDashboardController.SCHEMA_VERSION);
        root.put("title", shown.title());
        root.put("contextVersion", shown.contextVersion());
        ArrayNode lines = root.putArray("lines");
        shown.lines().forEach(lines::add);
        root.put("live", panel.live());
        return root;
    }

    /** The panel in the {@code kompile.dashboard.v1} shape. */
    private static ObjectNode dashboard(Panel panel, String title, Instant now, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", McpDashboardController.SCHEMA_VERSION);
        root.put("title", title);
        root.put("contextVersion", now.truncatedTo(ChronoUnit.SECONDS).toString());
        ArrayNode lines = root.putArray("lines");
        panel.lines().forEach(lines::add);
        return root;
    }

    /** The sources of the session's {@code insights} tool, or the project-local ones without it. */
    static Insights sessionInsights(ToolContext context, Path workDir, InsightsConfig config) {
        CliTool tool = context.getToolRegistry() == null ? null : context.getToolRegistry().get(INSIGHTS_TOOL);
        InsightsTool source = tool instanceof InsightsTool sessionTool ? sessionTool : new InsightsTool();
        return source.newInsights(workDir, config == null ? InsightsConfig.defaults() : config);
    }

    /**
     * The rows below the dashboard's heading, or the dashboard's limit while the TUI has not laid
     * the region out. The panel fills them itself, every topic's summary before any detail.
     */
    static int contentRows(KompileTui tui) {
        int rows = tui == null ? 0 : tui.getDashboardRegionRows() - 1;
        return rows > 0 ? Math.min(McpDashboardController.MAX_LINES, rows) : McpDashboardController.MAX_LINES;
    }
}
