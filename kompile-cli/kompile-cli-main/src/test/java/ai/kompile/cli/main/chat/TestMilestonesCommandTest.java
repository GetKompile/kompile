/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.insights.TestMilestoneReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /test-milestones} against a temporary store: session scoping from the recorder's stamp,
 * both sources (harness capture and agent records) shown and filterable, bounded rows, and a
 * coverage line that names every cut.
 */
@ai.kompile.cli.main.chat.testing.TemporaryUserHome
class TestMilestonesCommandTest {

    private static final String SESSION = "sess-1234";
    private static final String OTHER = "sess-9999";

    @TempDir
    Path project;

    private TestMilestonesCommand command() {
        return new TestMilestonesCommand(project);
    }

    /** One milestone file, as either writer records it; fields absent when null. */
    private void write(String id, String sessionId, String source, String outcome, Integer total,
                       Integer passed, Integer failed, String module) throws Exception {
        writeAt(id, sessionId, source, outcome, total, passed, failed, module, "2026-10-20T10:00:00Z");
    }

    private void writeAt(String id, String sessionId, String source, String outcome, Integer total,
                         Integer passed, Integer failed, String module, String timestamp) throws Exception {
        Path milestones = Files.createDirectories(TestMilestoneReader.storeDir(project)
                .resolve(TestMilestoneReader.MILESTONES_DIR));
        StringBuilder json = new StringBuilder("{");
        json.append("\"id\":\"").append(id).append('"');
        if (sessionId != null) json.append(",\"sessionId\":\"").append(sessionId).append('"');
        if (source != null) json.append(",\"source\":\"").append(source).append('"');
        if (outcome != null) {
            json.append(",\"outcome\":\"").append(outcome).append('"');
            if ("passed".equals(outcome) || "failed".equals(outcome)) {
                json.append(",\"passing\":").append("passed".equals(outcome));
            }
        }
        if (total != null) json.append(",\"totalTests\":").append(total);
        if (passed != null) json.append(",\"passed\":").append(passed);
        if (failed != null) json.append(",\"failed\":").append(failed);
        if (module != null) json.append(",\"module\":\"").append(module).append('"');
        json.append(",\"timestamp\":\"").append(timestamp).append("\"}");
        Files.writeString(milestones.resolve(id + ".json"), json.toString());
    }

    @Test
    void showsHarnessAndAgentRecordsForTheCurrentSession() throws Exception {
        write("a1b2c3d4", SESSION, "harness", "passed", 12, 12, 0, "kompile-cli-main");
        write("agentrec1", SESSION, null, "passed", 5, 5, 0, "core");
        write("e5f6a7b8", OTHER, "harness", "failed", 3, 2, 1, "kompile-cli-main");

        String report = command().show("", SESSION);
        assertTrue(report.contains("Scope: session " + SESSION), report);
        assertTrue(report.contains("auto"), report);
        assertTrue(report.contains("agent"), report);
        assertTrue(report.contains("a1b2c3d4"), report);
        assertTrue(report.contains("agentrec1"), report);
        assertFalse(report.contains("e5f6a7b8"), "other session's record must not appear: " + report);
        assertTrue(report.startsWith("Scope:"), report);
        assertTrue(report.contains("Coverage: 2 matched, 2 shown"), report);
    }

    @Test
    void unstampedRecordsAreExcludedFromASessionScopeButNotHidden() throws Exception {
        write("nostamp1", null, null, "failed", 2, 1, 1, "legacy");

        String report = command().show("", SESSION);
        assertFalse(report.contains("nostamp1"), report);
        assertTrue(report.contains("without a session stamp excluded"), report);
        assertTrue(report.contains("use 'all'"), report);

        String all = command().show("all", SESSION);
        assertTrue(all.contains("nostamp1"), all);
        assertTrue(all.contains("Scope: all sessions"), all);
    }

    @Test
    void sourceFilterSplitsTheTwoWriters() throws Exception {
        write("a1b2c3d4", SESSION, "harness", "passed", 12, 12, 0, "mod");
        write("agentrec1", SESSION, null, "passed", 5, 5, 0, "mod");

        String auto = command().show("source:auto", SESSION);
        assertTrue(auto.contains("a1b2c3d4"), auto);
        assertFalse(auto.contains("agentrec1"), auto);
        String agent = command().show("source:agent", SESSION);
        assertTrue(agent.contains("agentrec1"), agent);
        assertFalse(agent.contains("a1b2c3d4"), agent);
    }

    @Test
    void limitsBoundsRowsAndSaysWhatWasCut() throws Exception {
        writeAt("aaaa0001", SESSION, "harness", "passed", 1, 1, 0, "m", "2026-10-20T09:00:00Z");
        writeAt("aaaa0002", SESSION, "harness", "passed", 1, 1, 0, "m", "2026-10-20T10:00:00Z");
        writeAt("aaaa0003", SESSION, "harness", "passed", 1, 1, 0, "m", "2026-10-20T11:00:00Z");

        String report = command().show("2", SESSION);
        assertTrue(report.contains("Coverage: 3 matched, 2 shown (limit 2)"), report);
        assertTrue(report.contains("3 matched milestones shown"), report);
        assertFalse(report.contains("aaaa0001"), "oldest row must be the cut one: " + report);
    }

    @Test
    void badArgumentsPrintUsageWithoutThrowing() {
        String tooBig = command().show("500", SESSION);
        assertTrue(tooBig.contains("Usage: /test-milestones"), tooBig);
        assertTrue(tooBig.contains("Error:"), tooBig);
        String unknown = command().show("wat:1", SESSION);
        assertTrue(unknown.contains("Unknown selector 'wat:'"), unknown);
        String both = command().show("all session:" + OTHER, SESSION);
        assertTrue(both.contains("exclusive"), both);
        String badSource = command().show("source:nope", SESSION);
        assertTrue(badSource.contains("source must be auto or agent"), badSource);
        String dup = command().show("1 2", SESSION);
        assertTrue(dup.contains("Duplicate count"), dup);
    }

    @Test
    void emptyStoreIsHonestAndExplainsWhereRecordsComeFrom() {
        String report = command().show("", SESSION);
        assertTrue(report.contains("No milestones match this scope."), report);
        assertTrue(report.contains("Coverage: 0 matched"), report);
        assertTrue(report.contains("test_milestone"), report);
    }

    @Test
    void malformedFilesAreSkippedAndDisclosed() throws Exception {
        Path milestones = Files.createDirectories(TestMilestoneReader.storeDir(project)
                .resolve(TestMilestoneReader.MILESTONES_DIR));
        Files.writeString(milestones.resolve("broken.json"), "{not json");
        write("a1b2c3d4", SESSION, "harness", "passed", 1, 1, 0, "m");

        String report = command().show("", SESSION);
        assertTrue(report.contains("a1b2c3d4"), report);
        assertTrue(report.contains("unreadable milestone files skipped: 1"), report);
        assertTrue(report.contains("broken.json"), report);
    }

    @Test
    void moduleFilterNarrowsToTheNamedModule() throws Exception {
        write("a1b2c3d4", SESSION, "harness", "passed", 1, 1, 0, "mod-a");
        write("b2c3d4e5", SESSION, "harness", "failed", 2, 1, 1, "mod-b");

        String report = command().show("module:mod-b", SESSION);
        assertTrue(report.contains("b2c3d4e5"), report);
        assertFalse(report.contains("a1b2c3d4"), report);
    }

    @Test
    void nonConclusiveOutcomesRenderVerbatimNotAsPassFail() throws Exception {
        write("a1b2c3d4", SESSION, "harness", "timed_out", null, null, null, "m");

        String report = command().show("", SESSION);
        assertTrue(report.contains("TIMED_OUT"), report);
        assertFalse(report.contains("| PASS"), report);
    }

    @Test
    void countsReadFromBothLayoutsIncludingObservations() {
        assertEquals("auto", TestMilestonesCommand.source(Map.of("source", "harness")));
        assertEquals("auto", TestMilestonesCommand.source(Map.of("executionId", "x")));
        assertEquals("agent", TestMilestonesCommand.source(Map.of("id", "abc12345")));
        assertEquals("PASS", TestMilestonesCommand.status(Map.of("passing", true)));
        assertEquals("FAIL", TestMilestonesCommand.status(Map.of("passing", false)));
        assertEquals("12/12 f0", TestMilestonesCommand.totals(
                Map.of("totalTests", 12, "passed", 12, "failed", 0)));
        assertEquals("7/6 f1 s1", TestMilestonesCommand.totals(
                Map.of("totalTests", 7, "passed", 6, "failed", 1, "skipped", 1)));
        assertEquals("—", TestMilestonesCommand.totals(Map.of("outcome", "unknown")));
        assertEquals("4/3 f1", TestMilestonesCommand.totals(Map.of("observations",
                Map.of("total", 4, "passed", 3, "failed", 1, "skipped", 0))));
    }

    @Test
    void missingSessionIdCannotDefaultToAScope() {
        String report = command().show("", null);
        assertTrue(report.contains("No session id is available"), report);
        assertTrue(report.contains("'all'"), report);
    }

    @Test
    void usageRequestReportsUsageWithoutStoreAccess() {
        String help = command().show("help", null);
        assertTrue(help.contains("Usage: /test-milestones"), help);
        assertFalse(help.contains("Error:"), "bare 'help' is not an error: " + help);
        assertFalse(help.contains("Scope:"), "no store scan for a usage request: " + help);
    }

    @Test
    void slashCommandRoutesThroughTheRouterAndPrintsTheReport() throws Exception {
        write("a1b2c3d4", "router-session", "harness", "passed", 3, 3, 0, "m");
        var repl = org.mockito.Mockito.mock(ChatRepl.class);
        org.mockito.Mockito.when(repl.getWorkingDirectory()).thenReturn(project);
        var permissions = org.mockito.Mockito.mock(ai.kompile.cli.main.chat.permission.PermissionService.class);
        var loop = org.mockito.Mockito.mock(ai.kompile.cli.main.chat.agent.AgenticChatLoop.class);
        org.mockito.Mockito.when(loop.getCurrentAgentConfig()).thenReturn(
                org.mockito.Mockito.mock(ai.kompile.cli.main.chat.agent.AgentConfig.class));
        var router = new ChatCommandRouter(repl, null, null, null, null,
                ai.kompile.cli.common.util.JsonUtils.standardMapper(), "router-session", true,
                null, null, null, null, null, null, null, null,
                permissions, loop, null, null, null, List.of(), null);
        var previous = System.out;
        var output = new java.io.ByteArrayOutputStream();
        try (var captured = new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(captured);
            assertTrue(router.handleSlashCommand("/test-milestones"));
        } finally {
            System.setOut(previous);
        }
        String printed = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(printed.contains("Scope: session router-session"), printed);
        assertTrue(printed.contains("a1b2c3d4"), printed);
        assertTrue(ChatCommandCatalog.isBuiltin("test-milestones"));
    }
}
