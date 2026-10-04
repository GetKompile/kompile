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

package ai.kompile.cli.insights;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final InsightsConfig CONFIG = InsightsConfig.defaults();
    private static final InsightsQuery SESSION = InsightsQuery.session("s1", NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    /** A panel row, or the failure its source raises instead. */
    private interface Row {
        Panel.Line get() throws IOException;
    }

    /** A source with only a panel row and the files behind it. */
    private static InsightSource panelSource(String topic, Row row, Supplier<List<Panel.Watch>> watches) {
        return new InsightSource() {
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
            public Panel.Line panelLine(InsightsQuery query) throws IOException {
                return row.get();
            }

            @Override
            public List<Panel.Watch> panelWatches(InsightsQuery query) {
                return watches.get();
            }
        };
    }

    /** The three real sources over empty stores; only their topics and keywords matter here. */
    private Insights real() {
        return new Insights()
                .register(new JudgeInsights(tempDir, CONFIG))
                .register(new ToolUsageInsights(tempDir, CONFIG))
                .register(new MilestoneInsights(tempDir, tempDir, CONFIG));
    }

    private static InsightsQuery ask(String topic, String question) {
        return InsightsQuery.parse(topic, question, List.of(), NOW, ZoneOffset.UTC, 7);
    }

    /** A source whose headline names it and the window, or that fails with {@code failure}. */
    private static InsightSource source(String topic, String keyword, IOException failure) {
        return new InsightSource() {
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
                return List.of(keyword);
            }

            @Override
            public InsightReport report(InsightsQuery query) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                String headline = topic + " headline, " + query.getWindow().label();
                return InsightReport.builder().topic(topic).headline(headline).text(headline + "\n").build();
            }
        };
    }

    @Test
    void theTopicIsInferredFromTheQuestionsWords() {
        Insights insights = real();

        // The topic's own word counts double: "judge" outweighs the tools word "latency".
        assertEquals("judge", insights.inferTopic("judge latency"));
        assertEquals("judge", insights.inferTopic("what did the judge block"));
        // Stems match the start of a word: "slow" matches "slowest", "tool" matches "tools".
        assertEquals("tools", insights.inferTopic("slowest tools"));
        assertEquals("tests", insights.inferTopic("pass rate trend for module X"));
        assertEquals("tests", insights.inferTopic("what tests failed"));
    }

    @Test
    void aTieOrNoHitInfersNothing() {
        Insights insights = real();

        assertNull(insights.inferTopic("tool test"));
        assertNull(insights.inferTopic("how are things"));
        assertNull(insights.inferTopic("  "));
        assertNull(insights.inferTopic(null));
    }

    @Test
    void topicsKeepRegistrationOrder() {
        assertEquals(List.of("judge", "tools", "tests"), List.copyOf(real().topics()));
    }

    @Test
    void anUnknownTopicListsTheKnownOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> real().answer(ask("weather", "anything")));

        assertEquals("Unknown insights topic 'weather'. Topics: judge, tools, tests, overview", e.getMessage());
    }

    @Test
    void overviewIsReservedForTheCombinedReport() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Insights().register(source("overview", "all", null)));

        assertEquals("'overview' is reserved for the combined report", e.getMessage());
    }

    @Test
    void theOverviewHasOneLinePerSourceAndSurvivesAFailingOne() throws IOException {
        Insights insights = new Insights()
                .register(source("ok", "fine", null))
                .register(source("broken", "bad", new IOException("disk gone")));

        for (InsightsQuery query : List.of(ask(null, "how are things"), ask("overview", "is it fine"))) {
            InsightReport report = insights.answer(query);

            assertEquals("overview", report.getTopic());
            assertEquals("Overview, last 7 days: 2 topics", report.getHeadline());
            assertEquals("ok headline, last 7 days\n"
                    + "broken: unavailable (disk gone)\n"
                    + "\nAsk about one topic for detail: ok, broken\n", report.getText());
            assertNull(report.getChart());
        }
    }

    @Test
    void theOverviewAsksEachSourceForItsHeadlineOnly() throws IOException {
        InsightSource costly = new InsightSource() {
            @Override
            public String topic() {
                return "graph";
            }

            @Override
            public String description() {
                return "graphs";
            }

            @Override
            public List<String> keywords() {
                return List.of("graph");
            }

            @Override
            public InsightReport report(InsightsQuery query) {
                throw new AssertionError("the overview must not build the full report");
            }

            @Override
            public String headline(InsightsQuery query) {
                return "Graph: 2 knowledge bases";
            }
        };

        InsightReport report = new Insights().register(costly).answer(ask(null, "how are things"));

        assertEquals("Graph: 2 knowledge bases\n\nAsk about one topic for detail: graph\n", report.getText());
    }

    @Test
    void aNamedTopicWinsOverTheQuestion() throws IOException {
        Insights insights = new Insights()
                .register(source("ok", "fine", null))
                .register(source("tools", "tool", null));

        assertEquals("ok headline, last 7 days", insights.answer(ask(null, "is everything fine")).getHeadline());
        assertEquals("tools headline, last 7 days",
                insights.answer(ask("  Tools ", "is everything fine")).getHeadline());
    }

    @Test
    void theRealSourcesAnswerOverEmptyStores() throws IOException {
        InsightReport report = real().answer(ask(null, "how are things"));

        assertEquals("Overview, last 7 days: 3 topics", report.getHeadline());
        assertEquals("Judge, last 7 days: no judge verdicts (no session log was written in this range)\n"
                + "Tools, last 7 days: no tool calls recorded\n"
                // Milestones are written a few times a day, so their default range is all time.
                + "Tests, all time: no test results recorded\n"
                + "\nAsk about one topic for detail: judge, tools, tests\n", report.getText());
    }

    @Test
    void thePanelShowsEverySummaryFirstThenDetailsLiveOnesFirst() {
        Insights insights = new Insights()
                .register(panelSource("a", () -> new Panel.Line("a", "a detail", false), List::of))
                .register(panelSource("b", () -> new Panel.Line("b", "b detail", true), List::of))
                .register(panelSource("c", () -> new Panel.Line("c", " ", false), List::of));

        // Each detail sits under its summary; a blank one is dropped.
        Panel roomy = new Panel(List.of("a", "a detail", "b", "b detail", "c"), true, List.of());
        assertEquals(roomy, insights.panel(SESSION, 9));
        assertEquals(roomy, insights.panel(SESSION, 5));
        // With room for one detail, the live source's wins.
        assertEquals(new Panel(List.of("a", "b", "b detail", "c"), true, List.of()), insights.panel(SESSION, 4));
        assertEquals(List.of("a", "b", "c"), insights.panel(SESSION, 3).lines());
        // Rows beyond the room are dropped, and only a shown row makes the panel live.
        assertEquals(new Panel(List.of("a", "b"), true, List.of()), insights.panel(SESSION, 2));
        assertEquals(new Panel(List.of("a"), false, List.of()), insights.panel(SESSION, 0));
    }

    @Test
    void aFailingSourceSaysSoInItsRow() {
        Insights insights = new Insights()
                .register(panelSource("broken", () -> {
                    throw new IOException("disk gone");
                }, List::of))
                .register(panelSource("failing", () -> {
                    throw new IllegalStateException();
                }, List::of))
                .register(panelSource("ok", () -> Panel.Line.of("Ok: fine"), List::of));

        assertEquals(List.of("Broken: unavailable (disk gone)", "Failing: unavailable (IllegalStateException)",
                "Ok: fine"), insights.panel(SESSION, 8).lines());
    }

    @Test
    void thePanelWatchesEverySourcesFilesOnceEvenWithoutARow() {
        Panel.Watch log = new Panel.Watch(Path.of("/sessions/s1"), "judgements.jsonl");
        Panel.Watch any = new Panel.Watch(Path.of("/milestones"), null);
        Insights insights = new Insights()
                .register(panelSource("judge", () -> Panel.Line.of("Judge: no verdicts yet"), () -> List.of(log)))
                .register(panelSource("graph", () -> null, () -> List.of(log, any)))
                .register(panelSource("crawl", () -> Panel.Line.of("Crawl: no crawl jobs"), () -> {
                    throw new IllegalStateException("no state directory");
                }));

        Panel panel = insights.panel(SESSION, 8);

        assertEquals(List.of("Judge: no verdicts yet", "Crawl: no crawl jobs"), panel.lines());
        assertEquals(List.of(log, any), panel.watches());
        assertTrue(log.matches("judgements.jsonl"));
        assertFalse(log.matches("judgements.jsonl.tmp"));
        assertTrue(any.matches("FooTest.json"));
    }

    @Test
    void theRealSourcesShowAnEmptySessionAndWatchForItsFiles() {
        Path root = tempDir.toAbsolutePath().normalize();

        Panel panel = real().panel(SESSION, 8);

        assertEquals(List.of("Judge: no verdicts yet", "Tools: no calls yet", "Tests: no milestones recorded"),
                panel.lines());
        assertFalse(panel.live());
        assertEquals(List.of(new Panel.Watch(root, "s1"), new Panel.Watch(root, "s1.jsonl"),
                new Panel.Watch(root, TestMilestoneReader.MILESTONES_DIR)), panel.watches());
    }
}
