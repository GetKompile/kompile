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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static ai.kompile.cli.insights.Reports.assertLines;
import static ai.kompile.cli.insights.Reports.json;
import static ai.kompile.cli.insights.Reports.labels;
import static ai.kompile.cli.insights.Reports.series;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneInsightsTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final InsightsConfig CONFIG = InsightsConfig.defaults();
    private static final String AT = "2026-10-01T10:00:00Z";

    @TempDir
    Path tempDir;

    private static void write(Path file, String singleQuoted) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json(singleQuoted), StandardCharsets.UTF_8);
    }

    private static InsightReport ask(MilestoneInsights source, String question, String... ids) throws IOException {
        return source.report(InsightsQuery.parse(null, question, List.of(ids), NOW, ZoneOffset.UTC, 7));
    }

    private static MilestoneInsights.Result result(Map<String, Object> milestone) {
        return MilestoneInsights.result(milestone, ZoneOffset.UTC);
    }

    private Path projectStore() {
        return tempDir.resolve("project");
    }

    private static InsightsQuery panelQuery() {
        return InsightsQuery.session("chat-1", NOW, ZoneOffset.UTC);
    }

    /** Sets a file's write time, which orders the panel's candidates. */
    private static void modified(Path file, String at) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(at)));
    }

    /**
     * A project store with three tool runs of two modules, a file without an id, a malformed file
     * and two known regressions; and a home store with two test-sweep class results, one written
     * in local time.
     */
    private MilestoneInsights stores(InsightsConfig config) throws IOException {
        Path runs = projectStore().resolve(TestMilestoneReader.MILESTONES_DIR);
        write(runs.resolve("0a1b2c3d.json"), "{'id':'0a1b2c3d','module':'kompile-cli-main',"
                + "'timestamp':'2026-10-01T10:00:00Z','passing':true,'totalTests':120,'passed':120,"
                + "'commit':'0123456789abcdef0123','commitShort':'0123456','branch':'feat/x',"
                + "'commitMessage':'Add insights','tags':'insights','notes':'first run'}");
        // The tool recorded the first seven characters of a label as its short commit.
        write(runs.resolve("0a1b2c3e.json"), "{'id':'0a1b2c3e','module':'kompile-cli-main',"
                + "'timestamp':'2026-10-02T10:00:00Z','passing':false,'totalTests':120,'passed':118,"
                + "'commit':'uncommitted-changes','commitShort':'uncommi','notes':'two failures'}");
        write(runs.resolve("0a1b2c3f.json"), "{'id':'0a1b2c3f','module':'kompile-cli-common',"
                + "'timestamp':'2026-10-03T08:00:00Z','passing':true,'totalTests':40,'passed':40,"
                + "'commit':'fedcba9876543210'}");
        write(runs.resolve("incomplete.json"), "{'module':'kompile-cli-main','passing':true}");
        write(runs.resolve("broken.json"), "{'id':");
        write(projectStore().resolve(TestMilestoneReader.CONFIG_FILE), "{'knownRegressions':["
                + "{'id':'r1','test':'FooIT','module':'kompile-cli-main','sinceCommit':'0a1b2c3',"
                + "'added':'2026-09-30T12:00:00Z'},"
                + "{'id':'r2','test':'BazIT','sinceCommit':'abc1234'}]}");

        Path global = tempDir.resolve("global");
        Path classes = global.resolve(TestMilestoneReader.MILESTONES_DIR);
        write(classes.resolve("FooTest.json"), "{'id':'FooTest','module':'kompile-cli-main',"
                + "'timestamp':'2026-10-02T11:00:00Z','passing':false,'total':5,'passed':4,'notes':'testBar failed'}");
        write(classes.resolve("BarTest.json"), "{'id':'BarTest','module':'kompile-cli-common',"
                + "'timestamp':'2026-10-03T07:30:00','passing':true,'total':3,'passed':3}");
        return new MilestoneInsights(projectStore(), global, config);
    }

    private Path broken() {
        return projectStore().toAbsolutePath().normalize().resolve(TestMilestoneReader.MILESTONES_DIR)
                .resolve("broken.json");
    }

    @Test
    void theOverviewCoversRunsRegressionsAndClassesOverAllTime() throws IOException {
        InsightReport report = ask(stores(CONFIG), "how are the tests doing");

        assertEquals("tests", report.getTopic());
        assertEquals("Tests, all time: 3 runs of 2 modules, 66.7% green; 1 module failing at its latest run; "
                + "2 test classes, 1 passing", report.getHeadline());
        assertLines(report,
                "runs ███ per day from 10-01, peak 1",
                "failing ▁█▁ peak 1",
                "module runs green latest tests when trend",
                // Failing modules first; 118 of 120 still draws a full bar.
                "kompile-cli-main 2 50.0% FAIL 118/120 10-02 10:00 ██",
                "kompile-cli-common 1 100.0% pass 40/40 10-03 08:00 █",
                "Known regressions (2):",
                "test module since added",
                "FooIT kompile-cli-main 0a1b2c3 09-30",
                "BazIT abc1234 -",
                "Test classes, latest result each:",
                "module classes passing failing tests updated",
                "kompile-cli-common 1 1 0 3/3 10-03 07:30",
                "kompile-cli-main 1 0 1 4/5 10-02 11:00",
                "Failing classes, newest first:",
                "class module tests when notes",
                "FooTest kompile-cli-main 4/5 10-02 11:00 testBar failed",
                "1 milestone file without an id or a readable timestamp is not counted.",
                "1 milestone file could not be parsed, such as " + broken() + ".");

        ObjectNode chart = report.getChart();
        assertEquals("bar", chart.path("kind").asText());
        assertEquals("Test runs per day, all time", chart.path("title").asText());
        assertEquals("runs", chart.path("unit").asText());
        assertEquals(json("['10-01','10-02','10-03']"), labels(chart));
        assertEquals("[1,0,1]", series(chart, "passing"));
        assertEquals("[0,1,0]", series(chart, "failing"));
    }

    @Test
    void aModuleShowsItsRunsAndPassRate() throws IOException {
        InsightReport report = ask(stores(CONFIG), "pass rate trend for kompile-cli-main");

        assertEquals("Tests for kompile-cli-main, all time: 2 runs, 50.0% green; latest FAIL 10-02 10:00 "
                + "(118/120 tests); 1 test class, 0 passing", report.getHeadline());
        assertLines(report,
                "pass rate ██ last 2 runs, oldest first",
                "Latest runs:",
                "when result tests commit notes",
                // A label is cut to the column, not to the seven characters the tool recorded beside it.
                "10-02 10:00 FAIL 118/120 uncommitted… two failures",
                "10-01 10:00 pass 120/120 0123456 first run",
                "Known regressions (1):",
                "FooIT kompile-cli-main 0a1b2c3 09-30",
                "Failing classes, newest first:",
                "FooTest kompile-cli-main 4/5 10-02 11:00 testBar failed");
        assertFalse(report.getText().contains("Test classes, latest result each"), report.getText());
        assertFalse(report.getText().contains("kompile-cli-common"), report.getText());
        assertFalse(report.getText().contains("BazIT"), report.getText());

        ObjectNode chart = report.getChart();
        assertEquals("line", chart.path("kind").asText());
        assertEquals("Pass rate for kompile-cli-main, all time", chart.path("title").asText());
        assertEquals("%", chart.path("unit").asText());
        assertEquals(json("['10-01 10:00','10-02 10:00']"), labels(chart));
        assertEquals("[100,98.33]", series(chart, "passed"));
    }

    @Test
    void aRunIdOrClassNameShowsThatResult() throws IOException {
        MilestoneInsights source = stores(CONFIG);

        InsightReport run = ask(source, "show test run 0a1b2c3d");
        assertEquals("Test run 0a1b2c3d (kompile-cli-main), 10-01 10:00: pass, 120/120 tests", run.getHeadline());
        assertLines(run,
                "commit 0123456 on feat/x",
                "message Add insights",
                "tags insights",
                "notes first run");
        assertNull(run.getChart());

        InsightReport testClass = ask(source, "why did FooTest fail");
        assertEquals("Test class FooTest (kompile-cli-main), 10-02 11:00: FAIL, 4/5 tests", testClass.getHeadline());
        assertLines(testClass, "notes testBar failed");
        assertFalse(testClass.getText().contains("commit"), testClass.getText());
    }

    @Test
    void aNamedRangeLimitsTheResults() throws IOException {
        MilestoneInsights source = stores(CONFIG);

        assertEquals("Tests, today: 1 run of 1 module, 100.0% green; every module passed its latest run; "
                + "1 test class, 1 passing", ask(source, "tests today").getHeadline());
        assertEquals("Tests, last 2 days: 2 runs of 2 modules, 50.0% green; 1 module failing at its latest run; "
                + "2 test classes, 1 passing", ask(source, "tests in the last 2 days").getHeadline());
        InsightReport none = ask(source, "tests in the last 1 hour");
        assertEquals("Tests, last hour: no test results recorded", none.getHeadline());
        assertNull(none.getChart());
    }

    @Test
    void aSessionScopeStillCoversEveryMilestone() throws IOException {
        InsightReport report = ask(stores(CONFIG), "tests in this session", "chat-1");

        assertTrue(report.getHeadline().startsWith("Tests, all time: 3 runs of 2 modules"), report.getHeadline());
        assertLines(report,
                "Test milestones are recorded per project, not per chat session, so this covers all of them.");
    }

    @Test
    void maxRowsCutsEveryTable() throws IOException {
        InsightReport report = ask(stores(CONFIG.toBuilder().maxRows(1).build()), "how are the tests doing");

        assertLines(report,
                "kompile-cli-main 2 50.0% FAIL 118/120 10-02 10:00 ██",
                "(+1 more modules)",
                "FooIT kompile-cli-main 0a1b2c3 09-30",
                "(+1 more regressions)",
                "kompile-cli-common 1 1 0 3/3 10-03 07:30",
                "(+1 more modules)");
    }

    @Test
    void theNewestRecordOfAnIdWins() throws IOException {
        Path store = tempDir.resolve("dupes");
        write(store.resolve("milestones/a.json"),
                "{'id':'0a1b2c3d','module':'m','timestamp':'2026-10-01T10:00:00Z','passing':true}");
        write(store.resolve("milestones/b.json"),
                "{'id':'0a1b2c3d','module':'m','timestamp':'2026-10-02T10:00:00Z','passing':false}");
        MilestoneInsights source = new MilestoneInsights(store, null, CONFIG);

        assertEquals("Tests, all time: 1 run of 1 module, 0.0% green; 1 module failing at its latest run",
                ask(source, "tests").getHeadline());
        assertEquals("Test run 0a1b2c3d (m), 10-02 10:00: FAIL", ask(source, "show run 0a1b2c3d").getHeadline());
    }

    @Test
    void oneDirectoryForBothStoresIsReadOnce() throws IOException {
        // A chat started in the home directory: the project store is the home store.
        Path home = tempDir.resolve("home");
        write(home.resolve("milestones/0a1b2c3d.json"),
                "{'id':'0a1b2c3d','module':'m','timestamp':'2026-10-01T10:00:00Z','passing':true}");
        write(home.resolve("milestones/FooTest.json"),
                "{'id':'FooTest','module':'m','timestamp':'2026-10-01T11:00:00Z','passing':false}");
        write(home.resolve("milestones/broken.json"), "[");

        InsightReport report = ask(new MilestoneInsights(home, home.resolve("sub/.."), CONFIG), "tests");

        assertEquals("Tests, all time: 1 run of 1 module, 100.0% green; every module passed its latest run; "
                + "1 test class, 0 passing", report.getHeadline());
        // Results merge by id either way; the unreadable file shows the directory was read once.
        assertLines(report, "1 milestone file could not be parsed, such as "
                + home.toAbsolutePath().normalize().resolve("milestones/broken.json") + ".");
    }

    @Test
    void aMissingOrUnconfiguredStoreIsNamed() throws IOException {
        Path a = tempDir.resolve("a").toAbsolutePath().normalize();
        Path b = tempDir.resolve("b").toAbsolutePath().normalize();

        InsightReport report = ask(new MilestoneInsights(a, b, CONFIG), "tests");
        assertEquals("Tests, all time: no test results recorded", report.getHeadline());
        assertLines(report, "No test milestones in " + a.resolve("milestones") + " or " + b.resolve("milestones")
                + "; the test_milestone tool records them.");
        assertNull(report.getChart());

        assertLines(ask(new MilestoneInsights(a, a.resolve("sub/.."), CONFIG), "tests"),
                "No test milestones in " + a.resolve("milestones") + "; the test_milestone tool records them.");
        InsightsConfig warned = CONFIG.toBuilder().warning("Could not read insights.json").build();
        assertEquals("Tests, all time: no test results recorded\n\nNo test-milestone store is configured.\n"
                + "Could not read insights.json\n", ask(new MilestoneInsights(null, null, warned), "tests").getText());
    }

    @Test
    void aResultIsReadFromEitherLayout() {
        // A sweep result: written in local time, no module, counts already renamed by the reader.
        MilestoneInsights.Result sweep = MilestoneInsights.result(Map.of("id", "BarTest",
                "timestamp", "2026-10-03T07:30:00", "passing", true, "totalTests", 3, "passed", 3),
                ZoneId.of("America/New_York"));
        assertEquals(Instant.parse("2026-10-03T11:30:00Z"), sweep.at());
        assertFalse(sweep.run());
        assertEquals(MilestoneInsights.NO_MODULE, sweep.module());
        assertEquals("3/3", sweep.tests());

        MilestoneInsights.Result run = result(Map.of("id", "0a1b2c3d", "timestamp", AT, "module", "m",
                "passing", "TRUE", "totalTests", "12", "passed", "11", "commitShort", "abc1234"));
        assertTrue(run.run());
        assertTrue(run.passing());
        assertEquals("11/12", run.tests());
        // Without a full commit, the recorded short one is all there is.
        assertEquals("abc1234", run.commit());

        assertEquals("0123456", result(Map.of("id", "x", "timestamp", AT, "commit", "0123456789abcdef",
                "commitShort", "blind12")).commit());
        assertEquals("?/12", result(Map.of("id", "x", "timestamp", AT, "totalTests", 12)).tests());
        assertEquals("-", result(Map.of("id", "x", "timestamp", AT)).tests());
        assertFalse(result(Map.of("id", "x", "timestamp", AT, "passing", "yes")).passing());
        assertEquals(MilestoneInsights.NO_MODULE, result(Map.of("id", "x", "timestamp", AT, "module", " ")).module());
        assertFalse(result(Map.of("id", "0A1B2C3D", "timestamp", AT)).run());

        assertNull(result(Map.of("timestamp", AT)));
        assertNull(result(Map.of("id", " ", "timestamp", AT)));
        assertNull(result(Map.of("id", "x")));
        assertNull(result(Map.of("id", "x", "timestamp", "yesterday")));
    }

    @Test
    void thePanelShowsTheMostRecentlyWrittenMilestoneOfEitherStore() throws IOException {
        MilestoneInsights source = stores(CONFIG);
        Path runs = projectStore().resolve(TestMilestoneReader.MILESTONES_DIR);
        Path classes = tempDir.resolve("global").resolve(TestMilestoneReader.MILESTONES_DIR);
        modified(runs.resolve("broken.json"), "2026-09-30T00:00:00Z");
        modified(runs.resolve("incomplete.json"), "2026-09-30T00:00:00Z");
        modified(runs.resolve("0a1b2c3d.json"), "2026-10-01T10:00:00Z");
        modified(runs.resolve("0a1b2c3e.json"), "2026-10-02T10:00:00Z");
        modified(classes.resolve("FooTest.json"), "2026-10-02T11:00:00Z");
        modified(classes.resolve("BarTest.json"), "2026-10-03T07:30:00Z");
        modified(runs.resolve("0a1b2c3f.json"), "2026-10-03T08:00:00Z");

        assertEquals(Panel.Line.of("Tests: kompile-cli-common 40/40 pass · 4h ago"), source.panelLine(panelQuery()));

        // A failed run, rewritten last, shows its notes; its age is the run's own time, not the write.
        modified(runs.resolve("0a1b2c3e.json"), "2026-10-03T09:00:00Z");
        assertEquals(new Panel.Line("Tests: kompile-cli-main 118/120 FAIL · 1d ago", "↳ two failures", false),
                source.panelLine(panelQuery()));

        // A test class names itself and its module.
        modified(classes.resolve("FooTest.json"), "2026-10-03T10:00:00Z");
        assertEquals(new Panel.Line("Tests: FooTest (kompile-cli-main) 4/5 FAIL · 1d ago", "↳ testBar failed", false),
                source.panelLine(panelQuery()));
    }

    @Test
    void thePanelNamesARunWithoutAModuleAndATestClassByItsSimpleName() throws IOException {
        Path store = tempDir.resolve("store");
        Path dir = store.resolve(TestMilestoneReader.MILESTONES_DIR);
        MilestoneInsights source = new MilestoneInsights(store, null, CONFIG);

        write(dir.resolve("a.json"), "{'id':'0a1b2c3d','timestamp':'2026-10-03T11:00:00Z','passing':true}");
        modified(dir.resolve("a.json"), "2026-10-03T11:00:00Z");
        assertEquals(Panel.Line.of("Tests: run 0a1b2c3d pass · 1h ago"), source.panelLine(panelQuery()));

        // Blank notes are no detail.
        write(dir.resolve("b.json"), "{'id':'ai.kompile.cli.FooIT','timestamp':'2026-10-03T11:30:00Z',"
                + "'passing':false,'notes':' '}");
        modified(dir.resolve("b.json"), "2026-10-03T11:30:00Z");
        assertEquals(Panel.Line.of("Tests: FooIT FAIL · 30m ago"), source.panelLine(panelQuery()));
    }

    @Test
    void thePanelFallsBackPastUnreadableFilesButTriesOnlyTheNewestFive() throws IOException {
        Path store = tempDir.resolve("store");
        Path dir = store.resolve(TestMilestoneReader.MILESTONES_DIR);
        MilestoneInsights source = new MilestoneInsights(store, null, CONFIG);
        write(dir.resolve("0a1b2c3d.json"), "{'id':'0a1b2c3d','module':'m','timestamp':'2026-10-03T10:00:00Z',"
                + "'passing':true,'totalTests':2,'passed':2}");
        modified(dir.resolve("0a1b2c3d.json"), "2026-10-03T10:00:00Z");
        write(dir.resolve("incomplete.json"), "{'module':'m','passing':true}");
        modified(dir.resolve("incomplete.json"), "2026-10-03T10:05:00Z");
        write(dir.resolve("broken1.json"), "{'id':");
        modified(dir.resolve("broken1.json"), "2026-10-03T10:10:00Z");

        assertEquals(Panel.Line.of("Tests: m 2/2 pass · 2h ago"), source.panelLine(panelQuery()));

        for (int i = 2; i <= 4; i++) {
            write(dir.resolve("broken" + i + ".json"), "{'id':");
            modified(dir.resolve("broken" + i + ".json"), "2026-10-03T10:" + (i * 10) + ":00Z");
        }
        // The readable run is now sixth newest.
        assertEquals(Panel.Line.of("Tests: the newest milestone files cannot be read, such as broken4.json"),
                source.panelLine(panelQuery()));
    }

    @Test
    void thePanelRereadsAFileOnlyWhenItsSizeOrWriteTimeChanges() throws IOException {
        Path store = tempDir.resolve("store");
        Path file = store.resolve(TestMilestoneReader.MILESTONES_DIR).resolve("0a1b2c3d.json");
        MilestoneInsights source = new MilestoneInsights(store, null, CONFIG);
        String milestone = "{'id':'0a1b2c3d','module':'m','timestamp':'2026-10-03T10:00:00Z','passing':true}";
        write(file, milestone);
        modified(file, "2026-10-03T10:00:00Z");

        assertEquals(Panel.Line.of("Tests: m pass · 2h ago"), source.panelLine(panelQuery()));

        // Same size, same write time: the parsed result is reused.
        write(file, "x".repeat(milestone.length()));
        modified(file, "2026-10-03T10:00:00Z");
        assertEquals(Panel.Line.of("Tests: m pass · 2h ago"), source.panelLine(panelQuery()));

        modified(file, "2026-10-03T10:01:00Z");
        assertEquals(Panel.Line.of("Tests: the newest milestone files cannot be read, such as 0a1b2c3d.json"),
                source.panelLine(panelQuery()));
    }

    @Test
    void withoutMilestonesThePanelSaysSo() throws IOException {
        assertEquals(Panel.Line.of("Tests: no milestones recorded"),
                new MilestoneInsights(null, null, CONFIG).panelLine(panelQuery()));

        Path store = tempDir.resolve("store");
        Files.createDirectories(store.resolve(TestMilestoneReader.MILESTONES_DIR));
        assertEquals(Panel.Line.of("Tests: no milestones recorded"),
                new MilestoneInsights(store, null, CONFIG).panelLine(panelQuery()));
    }

    @Test
    void thePanelWatchesEachStoresMilestonesOrTheStoreUntilTheyExist() throws IOException {
        Path project = projectStore();
        Path global = tempDir.resolve("global");
        MilestoneInsights source = new MilestoneInsights(project, global, CONFIG);

        assertEquals(List.of(), source.panelWatches(panelQuery()));

        Files.createDirectories(project);
        Files.createDirectories(global.resolve(TestMilestoneReader.MILESTONES_DIR));
        assertEquals(List.of(new Panel.Watch(project.toAbsolutePath().normalize(), TestMilestoneReader.MILESTONES_DIR),
                new Panel.Watch(global.toAbsolutePath().normalize().resolve(TestMilestoneReader.MILESTONES_DIR), null)),
                source.panelWatches(panelQuery()));
    }
}
