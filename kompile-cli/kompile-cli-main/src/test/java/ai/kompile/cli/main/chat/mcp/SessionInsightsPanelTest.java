/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.InsightSource;
import ai.kompile.cli.insights.Insights;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.insights.Panel;
import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.InsightsTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tui.KompileTui;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionInsightsPanelTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:34:56.789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void servesThePanelInTheDashboardShapeTheControllerReads() throws Exception {
        FakeSource judge = new FakeSource("judge",
                new Panel.Line("Judge: 2 flagged · 1 blocked", "↳ blocked: bash rm -rf build", false));
        FakeSource tools = new FakeSource("tools", Panel.Line.of("Tools: 14 calls · p50 120ms"));
        SessionInsightsPanel panel = panel(() -> session("chat-1", tempDir), () -> 8, judge, tools);

        String raw = panel.fetch();

        JsonNode root = mapper.readTree(raw);
        assertEquals(McpDashboardController.SCHEMA_VERSION, root.path("schemaVersion").asText());
        assertEquals("dashboard-hint-v1", root.path("contextVersion").asText());
        McpDashboardController.DashboardSnapshot snapshot = McpDashboardController.parse(raw, mapper);
        assertEquals(SessionInsightsPanel.TITLE, snapshot.title());
        assertEquals("+ /dashboard to see session metrics", snapshot.title());
        assertEquals(List.of(SessionInsightsPanel.HINT_DETAIL), snapshot.lines());
        assertTrue(judge.queries.isEmpty());
        assertTrue(tools.queries.isEmpty());
    }

    @Test
    void hintHasOneCompactDetailAcrossResponsiveRowCounts() throws Exception {
        AtomicInteger rows = new AtomicInteger(1);
        SessionInsightsPanel panel = panel(() -> session("chat-1", tempDir), rows::get,
                new FakeSource("judge", new Panel.Line("Judge: 1 flagged", "↳ flagged: git push", false)),
                new FakeSource("tools", Panel.Line.of("Tools: 3 calls")));

        assertEquals(List.of(SessionInsightsPanel.HINT_DETAIL), lines(panel.fetch()));
        rows.set(2);
        assertEquals(List.of(SessionInsightsPanel.HINT_DETAIL), lines(panel.fetch()));
        rows.set(3);
        assertEquals(List.of(SessionInsightsPanel.HINT_DETAIL), lines(panel.fetch()));
    }

    @Test
    void terminalHintNeverAsksMetricSources() throws Exception {
        FakeSource judge = new FakeSource("judge", Panel.Line.of("Judge: no verdicts yet"));
        SessionInsightsPanel panel = panel(() -> session("chat-7", tempDir), () -> 8, judge);

        panel.fetch();

        assertTrue(judge.queries.isEmpty());
    }

    @Test
    void terminalHintDoesNotBuildSourcesWhenWorkingDirectoryChanges() throws Exception {
        Path other = tempDir.resolve("other");
        AtomicReference<Path> workDir = new AtomicReference<>(tempDir);
        List<Path> built = new CopyOnWriteArrayList<>();
        SessionInsightsPanel panel = new SessionInsightsPanel(() -> session("chat-1", workDir.get()), () -> 8,
                (context, dir) -> {
                    built.add(dir);
                    return new Insights().register(new FakeSource("judge", Panel.Line.of("Judge: " + dir)));
                }, CLOCK, mapper);

        panel.fetch();
        panel.fetch();
        assertTrue(built.isEmpty(), "terminal hint never builds sources");

        workDir.set(other);
        assertEquals(List.of(SessionInsightsPanel.HINT_DETAIL), lines(panel.fetch()));
        panel.fetch();
        assertTrue(built.isEmpty());

        workDir.set(null);
        panel.fetch();
        assertTrue(built.isEmpty());
    }

    @Test
    void terminalHintNeverExposesMetricWatchesOrLiveState() throws Exception {
        FakeSource crawl = new FakeSource("crawl",
                new Panel.Line("Crawl: 1 active: docs 45% (extraction)", null, true));
        crawl.watches = List.of(new Panel.Watch(tempDir, "state.json"));
        SessionInsightsPanel panel = panel(() -> session("chat-1", tempDir), () -> 8, crawl);
        assertNull(panel.lastPanel(), "nothing before the first refresh");

        panel.fetch();
        assertNull(panel.lastPanel(), "terminal hint has no live metrics or watches");

        crawl.line = Panel.Line.of("Crawl: none active");
        crawl.watches = List.of();
        panel.fetch();
        assertNull(panel.lastPanel());
    }

    @Test
    void hintDoesNotRequireAChatContext() throws Exception {
        AtomicInteger built = new AtomicInteger();
        SessionInsightsPanel panel = new SessionInsightsPanel(() -> null, () -> 8, (context, dir) -> {
            built.incrementAndGet();
            return new Insights();
        }, CLOCK, mapper);

        assertEquals(List.of(SessionInsightsPanel.HINT_DETAIL), lines(panel.fetch()));
        assertEquals(0, built.get());
        assertNull(panel.lastPanel());
    }

    @Test
    void readsWithTheSessionsOwnInsightsToolAndItsConfig() {
        Insights fromTool = new Insights();
        List<InsightsConfig> configs = new ArrayList<>();
        InsightsTool tool = new InsightsTool() {
            @Override
            public Insights newInsights(Path workDir, InsightsConfig config) {
                configs.add(config);
                return fromTool;
            }
        };
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(tool);
        InsightsConfig config = InsightsConfig.defaults().toBuilder().defaultWindowDays(3).build();

        assertSame(fromTool, SessionInsightsPanel.sessionInsights(
                new ToolContext("chat-1", null, null, tempDir, registry), tempDir, config));
        assertSame(fromTool, SessionInsightsPanel.sessionInsights(
                new ToolContext("chat-1", null, null, tempDir, registry), tempDir, null));
        assertEquals(List.of(config, InsightsConfig.defaults()), configs);
    }

    @Test
    void withoutAnInsightsToolReadsTheProjectsOwnData() {
        List<String> topics = List.of("judge", "tools", "tests", "crawl", "graph");

        assertEquals(topics, List.copyOf(SessionInsightsPanel.sessionInsights(
                new ToolContext("chat-1", null, null, tempDir, new ToolRegistry(mapper)), tempDir, null).topics()));
        assertEquals(topics, List.copyOf(SessionInsightsPanel.sessionInsights(
                new ToolContext("chat-1", null, null, tempDir, null), tempDir, InsightsConfig.defaults()).topics()));
    }

    @Test
    void fitsTheRowsBelowTheDashboardHeading() {
        assertEquals(McpDashboardController.MAX_LINES, SessionInsightsPanel.contentRows(null));
        BackgroundProcessManager processes = new BackgroundProcessManager("session-panel-rows-test");
        try {
            AtomicInteger regionRows = new AtomicInteger();
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("session-panel-rows-queue"), new TerminalRenderer(false)) {
                @Override
                public int getDashboardRegionRows() {
                    return regionRows.get();
                }
            };
            assertEquals(McpDashboardController.MAX_LINES, SessionInsightsPanel.contentRows(tui),
                    "the dashboard's limit until the TUI lays the area out");
            regionRows.set(6);
            assertEquals(5, SessionInsightsPanel.contentRows(tui));
            regionRows.set(McpDashboardController.MAX_LINES + 1);
            assertEquals(McpDashboardController.MAX_LINES, SessionInsightsPanel.contentRows(tui));
            regionRows.set(McpDashboardController.MAX_LINES + 5);
            assertEquals(McpDashboardController.MAX_LINES, SessionInsightsPanel.contentRows(tui),
                    "never more than the dashboard shows");
        } finally {
            processes.close();
        }
    }

    @Test
    void snapshotCleansAndCutsTheRowsAsTheDashboardAreaShowsThem() throws Exception {
        String longDetail = "↳ " + "x".repeat(150);
        FakeSource judge = new FakeSource("judge",
                new Panel.Line("\033[31mJudge:\t1 flagged\033[0m HIGH", longDetail, false));
        FakeSource crawl = new FakeSource("crawl",
                new Panel.Line("Crawl: 1 active: docs 45% (extraction)", null, true));

        ObjectNode snapshot = SessionInsightsPanel.snapshot(
                new Insights().register(judge).register(crawl), "chat-9", CLOCK, mapper);

        assertEquals(McpDashboardController.SCHEMA_VERSION, snapshot.path("schemaVersion").asText());
        assertEquals(SessionInsightsPanel.SNAPSHOT_TITLE, snapshot.path("title").asText());
        assertEquals("2026-10-03T12:34:56Z", snapshot.path("contextVersion").asText());
        assertEquals(List.of("Judge: 1 flagged HIGH",
                longDetail.substring(0, McpDashboardController.MAX_LINE_CHARS - 1) + "…",
                "Crawl: 1 active: docs 45% (extraction)"), lines(mapper.writeValueAsString(snapshot)),
                "escapes stripped, the tab a space, the long detail cut to the dashboard's width");
        assertTrue(snapshot.path("live").asBoolean(), "a running crawl asks the drawer to refresh sooner");
        InsightsQuery query = judge.queries.get(0);
        assertEquals(Set.of("chat-9"), query.getSessionIds());
        assertEquals(NOW, query.getNow());
    }

    @Test
    void snapshotWithoutRowsSaysSoAndIsNotLive() throws Exception {
        ObjectNode snapshot = SessionInsightsPanel.snapshot(
                new Insights().register(new FakeSource("graph", null)), "chat-9", CLOCK, mapper);

        assertEquals(List.of("No dashboard details available."), lines(mapper.writeValueAsString(snapshot)));
        assertFalse(snapshot.path("live").asBoolean());
    }

    private SessionInsightsPanel panel(java.util.function.Supplier<ToolContext> context, IntSupplier rows,
                                       InsightSource... sources) {
        return new SessionInsightsPanel(context, rows, (ignored, dir) -> {
            Insights insights = new Insights();
            for (InsightSource source : sources) {
                insights.register(source);
            }
            return insights;
        }, CLOCK, mapper);
    }

    private static ToolContext session(String sessionId, Path workDir) {
        return new ToolContext(sessionId, null, null, workDir, null);
    }

    private List<String> lines(String raw) throws Exception {
        List<String> lines = new ArrayList<>();
        mapper.readTree(raw).path("lines").forEach(line -> lines.add(line.asText()));
        return lines;
    }

    /** A source with only a panel row, recording what it was asked. */
    private static final class FakeSource implements InsightSource {
        private final String topic;
        final List<InsightsQuery> queries = new CopyOnWriteArrayList<>();
        volatile Panel.Line line;
        volatile List<Panel.Watch> watches = List.of();

        FakeSource(String topic, Panel.Line line) {
            this.topic = topic;
            this.line = line;
        }

        @Override
        public String topic() {
            return topic;
        }

        @Override
        public String description() {
            return topic + " data";
        }

        @Override
        public List<String> keywords() {
            return List.of(topic);
        }

        @Override
        public InsightReport report(InsightsQuery query) {
            throw new AssertionError("the panel must not build a report");
        }

        @Override
        public Panel.Line panelLine(InsightsQuery query) {
            queries.add(query);
            return line;
        }

        @Override
        public List<Panel.Watch> panelWatches(InsightsQuery query) {
            return watches;
        }
    }
}
