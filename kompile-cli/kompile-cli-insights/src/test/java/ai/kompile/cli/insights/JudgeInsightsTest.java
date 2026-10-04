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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static ai.kompile.cli.insights.Reports.appendLines;
import static ai.kompile.cli.insights.Reports.assertLines;
import static ai.kompile.cli.insights.Reports.json;
import static ai.kompile.cli.insights.Reports.labels;
import static ai.kompile.cli.insights.Reports.lines;
import static ai.kompile.cli.insights.Reports.series;
import static ai.kompile.cli.insights.Reports.writeLines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeInsightsTest {

    /** A Saturday noon; the default window starts on Saturday 09-26. */
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final InsightsConfig CONFIG = InsightsConfig.defaults();

    @TempDir
    Path tempDir;

    private static InsightReport ask(Path root, InsightsConfig config, String question, String... ids)
            throws IOException {
        return new JudgeInsights(root, config).report(InsightsQuery.parse(null, question, List.of(ids), NOW,
                ZoneOffset.UTC, config.getDefaultWindowDays()));
    }

    private static Path log(Path root, String session) {
        return root.resolve(session).resolve(JudgeInsights.FILE_NAME);
    }

    private static InsightsQuery panelQuery(String session) {
        return InsightsQuery.session(session, NOW, ZoneOffset.UTC);
    }

    /**
     * Three session logs: s1, written this morning; s2, written yesterday, which also holds a
     * verdict from two weeks ago; and "old", last written two weeks ago, whose one record names
     * session s1. Records without a session id belong to their directory's session.
     */
    private Path sessions() throws IOException {
        Path root = tempDir.resolve("sessions");
        writeLines(log(root, "s1"), "2026-10-03T09:10:00Z",
                "{'timestamp':'2026-10-03T09:00:00Z','phase':'JUDGE_TOOL','judgeMode':'llm','compliant':true,"
                        + "'stop':false,'toolName':'Bash','latencyMs':1200}",
                "{'timestamp':'2026-10-03T09:05:00Z','phase':'JUDGE_TOOL','judgeMode':'llm','compliant':false,"
                        + "'stop':true,'severity':'HIGH','toolName':'Bash','latencyMs':2400,"
                        + "'violations':['Used cat instead of read']}",
                "{'timestamp':'2026-10-03T09:06:00Z','phase':'RESULT','status':'BLOCKED'}",
                "{'timestamp':'2026-10-03T09:07:00Z','phase':'OVERRIDE','status':'OVERRIDE_ARMED'}",
                "{'timestamp':'2026-10-03T09:08:00Z','phase':'CONTROL','status':'JUDGE_OFF'}",
                "not json",
                "{'timestamp':'2026-10-03T09:09:00Z','phase':'PROMPT','prompt':'fix the build'}");
        writeLines(log(root, "s2"), "2026-10-02T15:03:00Z",
                "{'timestamp':'2026-09-20T10:00:00Z','phase':'JUDGE_TURN','judgeMode':'heuristic','compliant':false}",
                "{'timestamp':'2026-10-02T15:00:00Z','phase':'JUDGE_TURN','judgeMode':'llm','compliant':false,"
                        + "'reasoning':'Skipped tests'}",
                "{'timestamp':'2026-10-02T15:01:00Z','phase':'JUDGE_TOOL','judgeMode':'llm','compliant':true,"
                        + "'toolName':'read','latencyMs':300}",
                "{'timestamp':'2026-10-02T15:02:00Z','phase':'SWAP','status':'SWAPPED'}");
        writeLines(log(root, "old"), "2026-09-20T10:05:00Z",
                "{'timestamp':'2026-09-20T10:00:00Z','sessionId':'s1','phase':'JUDGE_TOOL','judgeMode':'heuristic',"
                        + "'compliant':false,'stop':false,'toolName':'Bash','violations':['Wrote outside the project']}");
        return root;
    }

    @Test
    void aWeekOfVerdictsCountsFlagsStopsAndOutcomes() throws IOException {
        InsightReport report = ask(sessions(), CONFIG, "what did the judge flag");

        assertEquals("judge", report.getTopic());
        assertEquals("Judge, last 7 days: 4 verdicts in 2 sessions, 2 flagged (50.0%), 1 stop verdict, "
                + "1 turn blocked, 1 override", report.getHeadline());
        assertLines(report,
                "verdicts ▁▁▁▁▁▁██ per day from 09-26, peak 2",
                "flagged ▁▁▁▁▁▁██ peak 1",
                "judged verdicts flagged stopped p50 p95",
                "tool 3 1 1 1.2s 2.4s",
                "turn 1 1 0 - -",
                "tool checks flagged stopped",
                "Bash 2 1 1",
                "read 1 0 0",
                "Turn results: 1 blocked",
                "Overrides: 1 override armed",
                "Judge controls: 1 judge off",
                "Judge model swaps: 1",
                "Latest flags:",
                "when judged tool severity reason",
                "10-03 09:05 tool Bash HIGH Used cat instead of read",
                "10-02 15:00 turn Skipped tests");
        // No notes: every log in range was read whole.
        assertTrue(report.getText().endsWith("Skipped tests\n"), report.getText());

        ObjectNode chart = report.getChart();
        assertEquals("bar", chart.path("kind").asText());
        assertEquals("Judge flags per day, last 7 days", chart.path("title").asText());
        assertEquals("verdicts", chart.path("unit").asText());
        assertEquals(json("['09-26','09-27','09-28','09-29','09-30','10-01','10-02','10-03']"), labels(chart));
        assertEquals("[0,0,0,0,0,0,1,1]", series(chart, "flagged"));
        assertEquals("[0,0,0,0,0,0,0,1]", series(chart, "stopped"));
    }

    @Test
    void aNamedToolNarrowsTheReportToItsChecks() throws IOException {
        InsightReport report = ask(sessions(), CONFIG, "flags on the Bash tool");

        assertEquals("Judge on Bash, last 7 days: 2 verdicts in 1 session, 1 flagged (50.0%), 1 stop verdict",
                report.getHeadline());
        assertLines(report,
                "tool 2 1 1 1.2s 2.4s",
                "10-03 09:05 tool Bash HIGH Used cat instead of read");
        // One tool needs no per-tool table, and turn outcomes are not about it.
        assertFalse(lines(report).contains("tool checks flagged stopped"), report.getText());
        assertFalse(report.getText().contains("Turn results"), report.getText());
        assertFalse(report.getText().contains("Skipped tests"), report.getText());
        assertEquals("Judge flags on Bash per day, last 7 days", report.getChart().path("title").asText());
    }

    @Test
    void todayIsBucketedByHour() throws IOException {
        InsightReport report = ask(sessions(), CONFIG, "what did the judge flag today");

        assertEquals("Judge, today: 2 verdicts in 1 session, 1 flagged (50.0%), 1 stop verdict, 1 turn blocked, "
                + "1 override", report.getHeadline());
        assertLines(report, "verdicts ▁▁▁▁▁▁▁▁▁█▁▁▁ per hour from 00:00, peak 2");
        ObjectNode chart = report.getChart();
        assertEquals("Judge flags per hour, today", chart.path("title").asText());
        assertEquals(13, chart.path("labels").size());
        assertEquals("00:00", chart.path("labels").get(0).asText());
        assertEquals("12:00", chart.path("labels").get(12).asText());
        assertEquals("[0,0,0,0,0,0,0,0,0,1,0,0,0]", series(chart, "flagged"));
    }

    @Test
    void thisSessionReadsOnlyTheCallersLogsOverAllTheirTime() throws IOException {
        // The chat knows the session as s2; "missing" (the MCP server's id) has no log, which is fine.
        InsightReport report = ask(sessions(), CONFIG, "judge flags in this session", "s2", "missing");

        assertEquals("Judge, this session: 3 verdicts in 1 session, 2 flagged (66.7%)", report.getHeadline());
        assertFalse(report.getText().contains("No judge log"), report.getText());
    }

    @Test
    void aSessionWithoutALogSaysSo() throws IOException {
        InsightReport report = ask(sessions(), CONFIG, "judge flags in this session", "nope");

        assertEquals("Judge, this session: no judge verdicts (no session log was written in this range)",
                report.getHeadline());
        assertLines(report, "No judge log for this session (nope).");
        assertNull(report.getChart());
    }

    @Test
    void aSessionIdCannotNameAnotherDirectory() throws IOException {
        Path root = sessions();
        String flagged = "{'timestamp':'2026-10-03T08:00:00Z','phase':'JUDGE_TURN','compliant':false}";
        writeLines(root.resolve(JudgeInsights.FILE_NAME), "2026-10-03T08:01:00Z", flagged);
        writeLines(tempDir.resolve("outside").resolve(JudgeInsights.FILE_NAME), "2026-10-03T08:01:00Z", flagged);

        InsightReport report = ask(root, CONFIG, "judge flags in this session", "../outside", "s1/..", "a\\b", ".");

        assertEquals("Judge, this session: no judge verdicts (no session log was written in this range)",
                report.getHeadline());
        assertLines(report, "No judge log for this session (../outside, s1/.., a\\b, .).");
    }

    @Test
    void maxSessionsKeepsTheMostRecentlyWrittenLogs() throws IOException {
        InsightsConfig config = CONFIG.toBuilder().maxSessions(1).build();

        InsightReport report = ask(sessions(), config, "what did the judge flag");

        // s1 is read; s2 is left out; "old" was not written in the last 7 days, so it is not counted.
        assertEquals("Judge, last 7 days: 2 verdicts in 1 session, 1 flagged (50.0%), 1 stop verdict, "
                + "1 turn blocked, 1 override", report.getHeadline());
        assertLines(report, "Read the 1 most recently written of 2 session logs (maxSessions in insights.json).");
    }

    @Test
    void aLongLogIsReadFromItsEnd() throws IOException {
        InsightsConfig config = CONFIG.toBuilder().maxBytesPerFile(200).build();

        // The last 200 bytes of s1 hold its CONTROL, "not json" and PROMPT lines whole.
        InsightReport report = ask(sessions(), config, "what did the judge flag today");

        assertEquals("Judge, today: no judge verdicts in 1 session log", report.getHeadline());
        assertLines(report,
                "Judge controls: 1 judge off",
                "1 session log longer than 200 B was read from the end only; its older records are not counted.");
    }

    @Test
    void allTimeStartsAtTheOldestVerdict() throws IOException {
        InsightReport report = ask(sessions(), CONFIG, "judge flags over all time");

        // "old" counts now; its record names s1, so there are still two sessions.
        assertEquals("Judge, all time: 6 verdicts in 2 sessions, 4 flagged (66.7%), 1 stop verdict, "
                + "1 turn blocked, 1 override", report.getHeadline());
        ObjectNode chart = report.getChart();
        assertEquals("Judge flags per day, all time", chart.path("title").asText());
        assertEquals(14, chart.path("labels").size());
        assertEquals("09-20", chart.path("labels").get(0).asText());
        assertEquals("[2,0,0,0,0,0,0,0,0,0,0,0,1,1]", series(chart, "flagged"));
    }

    @Test
    void recordsWrittenSlightlyOutOfOrderAreStillCounted() throws IOException {
        Path root = tempDir.resolve("sessions");
        writeLines(log(root, "s"), "2026-10-03T00:10:00Z",
                "{'timestamp':'2026-10-03T00:01:00Z','phase':'JUDGE_TURN','compliant':false}",
                "{'timestamp':'2026-10-02T23:55:00Z','phase':'JUDGE_TURN','compliant':true}",
                "{'timestamp':'2026-10-03T00:05:00Z','phase':'JUDGE_TURN','compliant':true}");

        InsightReport report = ask(root, CONFIG, "judge flags today");

        // Reading back from the end, yesterday's 23:55 verdict does not end the scan early.
        assertEquals("Judge, today: 2 verdicts in 1 session, 1 flagged (50.0%)", report.getHeadline());
    }

    @Test
    void withoutLogsThereAreNoVerdicts() throws IOException {
        String none = "Judge, last 7 days: no judge verdicts (no session log was written in this range)";
        assertEquals(none, ask(tempDir.resolve("missing"), CONFIG, "what did the judge flag").getHeadline());
        assertEquals(none, ask(null, CONFIG, "what did the judge flag").getHeadline());
        assertEquals("Judge, this session: no judge verdicts (no session log was written in this range)",
                ask(null, CONFIG, "judge flags in this session", "s1").getHeadline());
    }

    @Test
    void aLogWithoutVerdictsSaysSo() throws IOException {
        Path root = tempDir.resolve("sessions");
        writeLines(log(root, "p"), "2026-10-03T11:00:00Z",
                "{'timestamp':'2026-10-03T10:59:00Z','phase':'PROMPT','prompt':'hello'}");

        InsightReport report = ask(root, CONFIG, "what did the judge flag");

        assertEquals("Judge, last 7 days: no judge verdicts in 1 session log", report.getHeadline());
        assertEquals(report.getHeadline() + "\n", report.getText());
        assertNull(report.getChart());
    }

    @Test
    void theConfigWarningIsReported() throws IOException {
        InsightsConfig config = CONFIG.toBuilder().warning("Could not read insights.json").build();

        InsightReport report = ask(sessions(), config, "what did the judge flag");

        assertTrue(report.getText().endsWith("\nCould not read insights.json\n"), report.getText());
    }

    @Test
    void thePanelCountsTheSessionsVerdictsAndNamesTheLastFlag() throws IOException {
        Path root = sessions();
        JudgeInsights judge = new JudgeInsights(root, CONFIG);

        assertEquals(new Panel.Line("Judge: 1 flagged of 2 verdicts, 1 stop, 1 turn blocked, 1 override"
                        + " · last flag 2h ago: Bash (high)", "↳ Used cat instead of read", false),
                judge.panelLine(panelQuery("s1")));

        // A record the session appends counts on the next refresh; a turn verdict names its kind.
        appendLines(log(root, "s1"), "{'timestamp':'2026-10-03T11:30:00Z','phase':'JUDGE_TURN','compliant':false,"
                + "'reasoning':'Skipped the tests'}");
        assertEquals(new Panel.Line("Judge: 2 flagged of 3 verdicts, 1 stop, 1 turn blocked, 1 override"
                        + " · last flag 30m ago: turn", "↳ Skipped the tests", false),
                judge.panelLine(panelQuery("s1")));

        // Another session is counted from its own log, back to its first record.
        assertEquals(new Panel.Line("Judge: 2 flagged of 3 verdicts · last flag 21h ago: turn", "↳ Skipped tests",
                false), judge.panelLine(panelQuery("s2")));
    }

    @Test
    void aLongLogIsCountedFromItsNewestBytesAndSaysWhereTheCountStarts() throws IOException {
        Path root = sessions();

        // The newest 200 bytes begin inside the override record, so the count starts at the 09:08 control.
        assertEquals(Panel.Line.of("Judge (since 10-03 09:08): no verdicts yet"),
                new JudgeInsights(root, CONFIG.toBuilder().maxBytesPerFile(200).build()).panelLine(panelQuery("s1")));
        // The newest 10 bytes hold no whole record.
        assertEquals(Panel.Line.of("Judge (newest 10 B): no verdicts yet"),
                new JudgeInsights(root, CONFIG.toBuilder().maxBytesPerFile(10).build()).panelLine(panelQuery("s1")));
    }

    @Test
    void aSessionWithoutALogHasNoVerdictsAndIsWatchedForOne() throws IOException {
        Path root = sessions();
        JudgeInsights judge = new JudgeInsights(root, CONFIG);
        Path absolute = root.toAbsolutePath().normalize();

        assertEquals(Panel.Line.of("Judge: no verdicts yet"), judge.panelLine(panelQuery("s9")));
        // Until the session's directory exists, its creation is what to watch for.
        assertEquals(List.of(new Panel.Watch(absolute, "s9")), judge.panelWatches(panelQuery("s9")));

        Files.createDirectories(root.resolve("s9"));
        assertEquals(List.of(new Panel.Watch(absolute.resolve("s9"), JudgeInsights.FILE_NAME)),
                judge.panelWatches(panelQuery("s9")));
        assertEquals(List.of(), new JudgeInsights(tempDir.resolve("missing"), CONFIG).panelWatches(panelQuery("s9")));
    }

    @Test
    void thePanelFollowsOnlyANamedSessionUnderTheRoot() throws IOException {
        JudgeInsights judge = new JudgeInsights(sessions(), CONFIG);
        InsightsQuery unscoped = InsightsQuery.parse(null, "judge flags", List.of(), NOW, ZoneOffset.UTC, 7);

        for (InsightsQuery query : List.of(unscoped, panelQuery("../s1"), panelQuery("s1/.."), panelQuery("a\\b"))) {
            assertNull(judge.panelLine(query));
            assertEquals(List.of(), judge.panelWatches(query));
        }
        assertNull(new JudgeInsights(null, CONFIG).panelLine(panelQuery("s1")));
    }

    @Test
    void theDefaultRootFollowsTheHomeWhenItIsAskedFor() {
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            assertEquals(tempDir.resolve(".kompile").resolve("sessions"), JudgeInsights.defaultSessionsRoot());
        } finally {
            System.setProperty("user.home", home);
        }
    }
}
