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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static ai.kompile.cli.insights.Reports.appendLines;
import static ai.kompile.cli.insights.Reports.assertLines;
import static ai.kompile.cli.insights.Reports.json;
import static ai.kompile.cli.insights.Reports.labels;
import static ai.kompile.cli.insights.Reports.series;
import static ai.kompile.cli.insights.Reports.writeLines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolUsageInsightsTest {

    /** A Saturday noon; the default window starts on Saturday 09-26. */
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final InsightsConfig CONFIG = InsightsConfig.defaults();

    /** The index in the order calls are appended, oldest first. */
    private static final List<String> CALLS = List.of(
            call("2026-09-20T08:00:00Z", "read_batch", "mcp-stdio", "aaaaaaaa-1111", "old", "'durationMs':100"),
            call("2026-10-01T10:00:00Z", "read_batch", "mcp-stdio", "aaaaaaaa-1111", "files=[A.java]",
                    "'durationMs':120"),
            call("2026-10-01T11:00:00Z", "read_batch", "mcp-stdio", "aaaaaaaa-1111", "files=[B.java]",
                    "'durationMs':80"),
            call("2026-10-02T09:00:00Z", "bash", "mcp-stdio", "aaaaaaaa-1111", "mvn install",
                    "'durationMs':5000,'isError':true"),
            call("2026-10-02T09:30:00Z", "bash", "mcp-stdio", "bbbbbbbb-2222", "git status",
                    "'durationMs':1500,'isError':false"),
            // Passthrough records carry an error flag but no duration.
            call("2026-10-02T10:00:00Z", "grep", "emulated-passthrough", "cccccccc-3333", "pattern=foo",
                    "'error':true"),
            // A zero duration from a source that never times a call is no sample.
            call("2026-10-03T09:00:00Z", "read_batch", "kompile-cli", "bbbbbbbb-2222", "files=[C.java]",
                    "'durationMs':0"),
            "{'timestamp':'2026-10-03T09:15:00Z','source':'mcp-stdio'}",
            call("2026-10-03T09:30:00Z", "grep", "emulated-passthrough", "cccccccc-3333", "pattern=bar",
                    "'error':true"));

    @TempDir
    Path tempDir;

    private static String call(String at, String tool, String source, String session, String input,
                               String fields) {
        return "{'timestamp':'" + at + "','toolName':'" + tool + "','source':'" + source + "','sessionId':'"
                + session + "','toolInputSummary':'" + input + "'" + (fields.isEmpty() ? "" : "," + fields) + "}";
    }

    /** The combined index: an unreadable first line, then {@link #CALLS}. */
    private Path index() throws IOException {
        String[] lines = new String[CALLS.size() + 1];
        lines[0] = "{not json";
        for (int i = 0; i < CALLS.size(); i++) {
            lines[i + 1] = CALLS.get(i);
        }
        return writeLines(tempDir.resolve(ToolUsageInsights.INDEX_FILE), "2026-10-03T09:30:00Z", lines);
    }

    private static InsightReport ask(Path dir, InsightsConfig config, String question, String... ids)
            throws IOException {
        return new ToolUsageInsights(dir, config).report(InsightsQuery.parse(null, question, List.of(ids), NOW,
                ZoneOffset.UTC, config.getDefaultWindowDays()));
    }

    private static InsightsQuery panelQuery(String session) {
        return InsightsQuery.session(session, NOW, ZoneOffset.UTC);
    }

    @Test
    void aWeekOfCallsRanksToolsByVolume() throws IOException {
        index();

        InsightReport report = ask(tempDir, CONFIG, "tool usage");

        assertEquals("tools", report.getTopic());
        assertEquals("Tools, last 7 days: 7 calls to 3 tools, 3 errors (42.9%), median 120ms, across 3 sessions",
                report.getHeadline());
        assertLines(report,
                "calls ▁▁▁▁▁▆█▆ per day from 09-26, peak 3",
                "errors ▁▁▁▁▁▁█▅ peak 2",
                "tool calls errors p50 p95 max trend",
                "read_batch 3 0 80ms 120ms 120ms ▁▁▁▁▁█▁▅",
                "bash 2 1 1.5s 5.0s 5.0s ▁▁▁▁▁▁█▁",
                "grep 2 2 - - - ▁▁▁▁▁▁██",
                "Recorded by: mcp-stdio 4, emulated-passthrough 2 (no durations), kompile-cli 1 (no durations)",
                // The line without a tool name; the scan stops before reaching the malformed first line.
                "1 index line could not be read.");
        assertFalse(report.getText().contains("Slowest calls"), report.getText());
        assertFalse(report.getText().contains("Latest errors"), report.getText());

        ObjectNode chart = report.getChart();
        assertEquals("bar", chart.path("kind").asText());
        assertEquals("Tool calls per day, last 7 days", chart.path("title").asText());
        assertEquals("calls", chart.path("unit").asText());
        assertEquals(json("['09-26','09-27','09-28','09-29','09-30','10-01','10-02','10-03']"), labels(chart));
        assertEquals("[0,0,0,0,0,2,3,2]", series(chart, "calls"));
        assertEquals("[0,0,0,0,0,0,2,1]", series(chart, "errors"));
    }

    @Test
    void aSlownessQuestionRanksByP95AndListsTheSlowestCalls() throws IOException {
        index();

        InsightReport report = ask(tempDir, CONFIG, "which tools are slowest");

        assertEquals("Tools, last 7 days: 7 calls to 3 tools, 3 errors (42.9%), median 120ms, across 3 sessions; "
                + "slowest p95: bash 5.0s", report.getHeadline());
        assertLines(report,
                "bash 2 1 1.5s 5.0s 5.0s ▁▁▁▁▁▁█▁",
                "read_batch 3 0 80ms 120ms 120ms ▁▁▁▁▁█▁▅",
                "grep 2 2 - - - ▁▁▁▁▁▁██",
                "Slowest calls:",
                "when took tool source session input",
                "10-02 09:00 5.0s bash mcp-stdio aaaaaaaa mvn install",
                "10-02 09:30 1.5s bash mcp-stdio bbbbbbbb git status",
                "10-01 10:00 120ms read_batch mcp-stdio aaaaaaaa files=[A.java]",
                "10-01 11:00 80ms read_batch mcp-stdio aaaaaaaa files=[B.java]");

        ObjectNode chart = report.getChart();
        assertEquals("Slowest tools by p95, last 7 days", chart.path("title").asText());
        assertEquals("ms", chart.path("unit").asText());
        // grep has no durations, so it has no bar.
        assertEquals(json("['bash','read_batch']"), labels(chart));
        assertEquals("[1500,80]", series(chart, "p50"));
        assertEquals("[5000,120]", series(chart, "p95"));
    }

    @Test
    void anErrorQuestionRanksByErrorsAndListsTheLatest() throws IOException {
        index();

        InsightReport report = ask(tempDir, CONFIG, "which tools failed");

        assertEquals("Tools, last 7 days: 7 calls to 3 tools, 3 errors (42.9%), median 120ms, across 3 sessions; "
                + "most errors: grep (2)", report.getHeadline());
        assertLines(report,
                "grep 2 2 - - - ▁▁▁▁▁▁██",
                "bash 2 1 1.5s 5.0s 5.0s ▁▁▁▁▁▁█▁",
                "read_batch 3 0 80ms 120ms 120ms ▁▁▁▁▁█▁▅",
                "Latest errors:",
                "when tool source session input",
                "10-03 09:30 grep emulated-passthrough cccccccc pattern=bar",
                "10-02 10:00 grep emulated-passthrough cccccccc pattern=foo",
                "10-02 09:00 bash mcp-stdio aaaaaaaa mvn install");

        ObjectNode chart = report.getChart();
        assertEquals("Tool errors, last 7 days", chart.path("title").asText());
        assertEquals("calls", chart.path("unit").asText());
        assertEquals(json("['grep','bash']"), labels(chart));
        assertEquals("[2,1]", series(chart, "errors"));
    }

    @Test
    void aNamedToolGetsItsOwnLatencyAndSources() throws IOException {
        index();

        InsightReport report = ask(tempDir, CONFIG, "how slow is read_batch");

        assertEquals("Tool read_batch, last 7 days: 3 calls, 0 errors (0.0%), p50 80ms, p95 120ms, max 120ms, "
                + "in 2 sessions", report.getHeadline());
        assertLines(report,
                "calls ▁▁▁▁▁█▁▅ per day from 09-26, peak 2",
                "Recorded by: mcp-stdio 2, kompile-cli 1 (no durations)",
                "Slowest calls:",
                "10-01 10:00 120ms read_batch mcp-stdio aaaaaaaa files=[A.java]",
                "10-01 11:00 80ms read_batch mcp-stdio aaaaaaaa files=[B.java]");
        assertFalse(report.getText().contains("Latest errors"), report.getText());

        ObjectNode chart = report.getChart();
        assertEquals("line", chart.path("kind").asText());
        assertEquals("Latency of read_batch per day, last 7 days", chart.path("title").asText());
        assertEquals("ms", chart.path("unit").asText());
        // A day without a timed call is a gap, not a zero.
        assertEquals("[null,null,null,null,null,80,null,null]", series(chart, "p50"));
        assertEquals("[null,null,null,null,null,120,null,null]", series(chart, "p95"));
    }

    @Test
    void aNamedToolWithoutALatencyQuestionChartsItsCalls() throws IOException {
        index();

        ObjectNode chart = ask(tempDir, CONFIG, "how often was read_batch used").getChart();

        assertEquals("Calls to read_batch per day, last 7 days", chart.path("title").asText());
        assertEquals("[0,0,0,0,0,2,0,1]", series(chart, "calls"));
    }

    @Test
    void aNamedSourceNarrowsEverything() throws IOException {
        index();

        InsightReport report = ask(tempDir, CONFIG, "tool calls from mcp-stdio");

        assertEquals("Tools from mcp-stdio, last 7 days: 4 calls to 2 tools, 1 error (25.0%), median 120ms, "
                + "across 2 sessions", report.getHeadline());
        assertFalse(report.getText().contains("grep"), report.getText());
    }

    @Test
    void allTimeReadsTheWholeIndex() throws IOException {
        index();

        InsightReport report = ask(tempDir, CONFIG, "all time tool usage");

        assertEquals("Tools, all time: 8 calls to 3 tools, 3 errors (37.5%), median 120ms, across 3 sessions",
                report.getHeadline());
        assertLines(report, "2 index lines could not be read.");
    }

    @Test
    void todayStopsAtYesterdaysCalls() throws IOException {
        index();

        InsightReport report = ask(tempDir, CONFIG, "tool usage today");

        // Neither of today's calls is timed, so there is no median.
        assertEquals("Tools, today: 2 calls to 2 tools, 1 error (50.0%), across 2 sessions", report.getHeadline());
        assertLines(report, "1 index line could not be read.");
    }

    @Test
    void thisSessionReadsTheSessionsOwnFile() throws IOException {
        // No combined index here, so every call counted came from the session's file.
        Path dir = tempDir.resolve("by-session");
        List<String> lines = new ArrayList<>(CALLS.subList(0, 4));
        lines.add(call("2026-10-02T11:00:00Z", "bash", "mcp-stdio", "zzzzzzzz-9999", "ls", "'durationMs':700"));
        writeLines(dir.resolve("aaaaaaaa-1111.jsonl"), "2026-10-02T11:00:00Z", lines.toArray(String[]::new));

        InsightReport report = ask(dir, CONFIG, "tool calls in this session", "aaaaaaaa-1111");

        // All of the session's time, and only its own calls.
        assertEquals("Tools, this session: 4 calls to 2 tools, 1 error (25.0%), median 100ms, across 1 session",
                report.getHeadline());

        InsightReport unknown = ask(dir, CONFIG, "tool calls in this session", "nope");
        assertEquals("Tools, this session: no tool calls recorded", unknown.getHeadline());
        assertLines(unknown, "No tool calls are indexed for this session (nope).");
        assertNull(unknown.getChart());
    }

    @Test
    void aMissingIndexIsNamed() throws IOException {
        Path dir = tempDir.resolve("empty");

        InsightReport report = ask(dir, CONFIG, "tool usage");

        assertEquals("Tools, last 7 days: no tool calls recorded", report.getHeadline());
        assertLines(report, "No tool-call index at " + dir.resolve(ToolUsageInsights.INDEX_FILE) + ".");
        assertLines(ask(null, CONFIG, "tool usage"), "No tool-call index is configured.");
    }

    @Test
    void aLargeIndexIsReadFromItsEnd() throws IOException {
        Path index = index();
        InsightsConfig config = CONFIG.toBuilder().maxToolIndexBytes(300).build();

        InsightReport report = ask(tempDir, config, "tool usage");

        String text = report.getText();
        assertTrue(text.contains("Read the newest 300 B of the " + Format.bytes(Files.size(index))
                + " tool-call index; calls before "), text);
        assertTrue(text.contains(" are not counted (maxToolIndexBytes in insights.json)."), text);
    }

    @Test
    void parseReadsTheFieldsOfOneLine() throws IOException {
        assertEquals(new ToolUsageInsights.Line(Instant.parse("2026-10-02T09:00:00Z"), "bash", "mcp-stdio", "s",
                        5000, true, "mvn install"),
                parse(call("2026-10-02T09:00:00Z", "bash", "mcp-stdio", "s", "mvn install",
                        "'durationMs':5000,'isError':true")));
    }

    @Test
    void parseSkipsLinesWithoutATimeOrATool() throws IOException {
        assertNull(parse("{'timestamp':'2026-10-02T09:00:00Z','source':'mcp-stdio'}"));
        assertNull(parse("{'timestamp':'2026-10-02T09:00:00Z','toolName':' '}"));
        assertNull(parse("{'timestamp':'2026-10-02T09:00:00Z','toolName':null}"));
        assertNull(parse("{'toolName':'bash'}"));
        assertNull(parse("{'timestamp':'yesterday','toolName':'bash'}"));
        assertNull(parse("[1,2]"));
        assertThrows(JsonProcessingException.class, () -> parse("{not json"));
    }

    @Test
    void parseReadsTheErrorFlagsAndDuration() throws IOException {
        String at = "'timestamp':'2026-10-02T09:00:00Z','toolName':'bash'";
        assertEquals(-1, parse("{" + at + ",'durationMs':'5000'}").durationMs());
        // isError is the index's own flag and wins over a copied error field.
        assertFalse(parse("{" + at + ",'isError':false,'error':true}").error());
        assertTrue(parse("{" + at + ",'error':true}").error());
        assertFalse(parse("{" + at + "}").error());
    }

    @Test
    void parseSkipsNestedValuesWhole() throws IOException {
        ToolUsageInsights.Line line = parse("{'meta':{'a':[1,{'b':2}]},'durationMs':[1,2],'isError':{'x':true},"
                + "'toolInputSummary':{'cmd':'ls'},'toolName':'grep','timestamp':'2026-10-02T10:00:00Z',"
                + "'error':true}");

        assertEquals(new ToolUsageInsights.Line(Instant.parse("2026-10-02T10:00:00Z"), "grep", null, null, -1, true,
                null), line);
    }

    /** Parses {@code singleQuoted} from the middle of a larger buffer, as the scan does. */
    private static ToolUsageInsights.Line parse(String singleQuoted) throws IOException {
        byte[] line = json(singleQuoted).getBytes(StandardCharsets.UTF_8);
        byte[] buffer = new byte[line.length + 8];
        Arrays.fill(buffer, (byte) 'x');
        System.arraycopy(line, 0, buffer, 4, line.length);
        return ToolUsageInsights.parse(buffer, 4, line.length);
    }

    @Test
    void topKKeepsTheLargestItemsInDescendingOrder() {
        ToolUsageInsights.TopK<Integer> top = new ToolUsageInsights.TopK<>(3, Comparator.<Integer>naturalOrder());
        for (int value : new int[]{5, 1, 9, 3, 7}) {
            top.offer(value);
        }
        assertEquals(List.of(9, 7, 5), top.descending());

        ToolUsageInsights.TopK<Integer> none = new ToolUsageInsights.TopK<>(0, Comparator.<Integer>naturalOrder());
        none.offer(1);
        assertEquals(List.of(), none.descending());
    }

    @Test
    void thePanelCountsTheSessionsCallsLatencyAndLatestError() throws IOException {
        Path file = writeLines(tempDir.resolve("aaaaaaaa-1111.jsonl"), "2026-10-02T09:00:00Z",
                CALLS.get(0), CALLS.get(1), "{not json", CALLS.get(2), CALLS.get(4), CALLS.get(3));
        ToolUsageInsights tools = new ToolUsageInsights(tempDir, CONFIG);

        // The other session's call in the file is not counted.
        assertEquals(new Panel.Line("Tools: 4 calls, 1 error · p50 100ms p95 5.0s · top: read_batch 3, bash 1",
                "↳ last error 1d ago: bash — mvn install", false), tools.panelLine(panelQuery("aaaaaaaa-1111")));

        // A call the session appends counts on the next refresh.
        appendLines(file, call("2026-10-03T11:59:00Z", "bash", "mcp-stdio", "aaaaaaaa-1111", "mvn test",
                "'durationMs':200,'isError':true"));
        assertEquals(new Panel.Line("Tools: 5 calls, 2 errors · p50 120ms p95 5.0s · top: read_batch 3, bash 2",
                "↳ last error 1m ago: bash — mvn test", false), tools.panelLine(panelQuery("aaaaaaaa-1111")));
    }

    @Test
    void withoutErrorsThePanelNamesTheSlowestCall() throws IOException {
        writeLines(tempDir.resolve("bbbbbbbb-2222.jsonl"), "2026-10-03T09:00:00Z", CALLS.get(4), CALLS.get(6));

        // The untimed kompile-cli call counts, but is no latency sample.
        assertEquals(new Panel.Line("Tools: 2 calls, 0 errors · p50 1.5s p95 1.5s · top: bash 1, read_batch 1",
                "↳ slowest: bash 1.5s, 1d ago — git status", false),
                new ToolUsageInsights(tempDir, CONFIG).panelLine(panelQuery("bbbbbbbb-2222")));
    }

    @Test
    void callsWithoutDurationsHaveNoLatency() throws IOException {
        writeLines(tempDir.resolve("cccccccc-3333.jsonl"), "2026-10-03T09:30:00Z", CALLS.get(5), CALLS.get(8));

        assertEquals(new Panel.Line("Tools: 2 calls, 2 errors · top: grep 2", "↳ last error 2h ago: grep — pattern=bar",
                false), new ToolUsageInsights(tempDir, CONFIG).panelLine(panelQuery("cccccccc-3333")));
    }

    @Test
    void aLongSessionFileIsCountedFromItsNewestBytesAndSaysWhereTheCountStarts() throws IOException {
        writeLines(tempDir.resolve("aaaaaaaa-1111.jsonl"), "2026-10-02T09:00:00Z",
                CALLS.get(0), CALLS.get(1), CALLS.get(2), CALLS.get(3));
        // Room for exactly the last two lines.
        long budget = json(CALLS.get(2)).length() + json(CALLS.get(3)).length() + 2;

        assertEquals(new Panel.Line("Tools (since 10-01 11:00): 2 calls, 1 error · p50 80ms p95 5.0s"
                        + " · top: bash 1, read_batch 1", "↳ last error 1d ago: bash — mvn install", false),
                new ToolUsageInsights(tempDir, CONFIG.toBuilder().maxToolIndexBytes(budget).build())
                        .panelLine(panelQuery("aaaaaaaa-1111")));
        // The newest byte holds no whole call.
        assertEquals(Panel.Line.of("Tools (newest 1 B): no calls yet"),
                new ToolUsageInsights(tempDir, CONFIG.toBuilder().maxToolIndexBytes(1).build())
                        .panelLine(panelQuery("aaaaaaaa-1111")));
    }

    @Test
    void aSessionWithoutAFileHasNoCallsAndIsWatchedForOne() throws IOException {
        ToolUsageInsights tools = new ToolUsageInsights(tempDir, CONFIG);

        assertEquals(Panel.Line.of("Tools: no calls yet"), tools.panelLine(panelQuery("aaaaaaaa-1111")));
        assertEquals(List.of(new Panel.Watch(tempDir.toAbsolutePath().normalize(), "aaaaaaaa-1111.jsonl")),
                tools.panelWatches(panelQuery("aaaaaaaa-1111")));
        assertEquals(List.of(),
                new ToolUsageInsights(tempDir.resolve("missing"), CONFIG).panelWatches(panelQuery("aaaaaaaa-1111")));
    }

    @Test
    void thePanelFollowsOnlyANamedSessionInTheDirectory() throws IOException {
        ToolUsageInsights tools = new ToolUsageInsights(tempDir, CONFIG);
        InsightsQuery unscoped = InsightsQuery.parse(null, "tool usage", List.of(), NOW, ZoneOffset.UTC, 7);

        for (InsightsQuery query : List.of(unscoped, panelQuery("../x"), panelQuery("a/b"))) {
            assertNull(tools.panelLine(query));
            assertEquals(List.of(), tools.panelWatches(query));
        }
        assertNull(new ToolUsageInsights(null, CONFIG).panelLine(panelQuery("aaaaaaaa-1111")));
    }
}
