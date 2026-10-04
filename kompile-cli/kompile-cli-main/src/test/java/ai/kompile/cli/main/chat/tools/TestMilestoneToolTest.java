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
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code test_milestone} against its store in a temporary project: ids stay file names, a
 * project outside git records no git error text, and sweep and hand-written files read in the
 * tool's field names.
 */
@TemporaryUserHome
class TestMilestoneToolTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final TestMilestoneTool tool = new TestMilestoneTool();

    @TempDir
    Path project;

    @Test
    void idsCannotNameFilesOutsideTheMilestoneDirectory() throws Exception {
        Path store = TestMilestoneTool.storeDir(project);
        Files.createDirectories(store.resolve("milestones"));
        Path config = Files.writeString(store.resolve("config.json"), "{}");

        for (String json : List.of("{\"action\":\"get\",\"id\":\"../config\"}",
                "{\"action\":\"delete\",\"id\":\"../config\"}",
                "{\"action\":\"compare\",\"from_id\":\"../config\",\"to_id\":\"../config\"}")) {
            ToolResult result = run(json);
            assertTrue(result.isError(), json);
            assertTrue(result.getOutput().startsWith("Invalid milestone id '../config'"), result.getOutput());
        }
        assertTrue(Files.isRegularFile(config));
    }

    @Test
    void outsideGitNothingPrintsNullOrGitErrors() throws Exception {
        ToolResult recorded = run("{\"action\":\"record\",\"module\":\"core\",\"total_tests\":3,\"passed\":3}");
        assertFalse(recorded.isError(), recorded.getOutput());
        assertFalse(recorded.getOutput().contains("null"), recorded.getOutput());
        String id = String.valueOf(recorded.getMetadata().get("id"));
        assertTrue(id.matches("[0-9a-f]{8}"), id);
        Path file = TestMilestoneTool.storeDir(project).resolve("milestones").resolve(id + ".json");
        assertTrue(Files.isRegularFile(file), file.toString());

        JsonNode saved = mapper.readTree(file.toFile());
        // Null outside a repository; a temporary directory inside one records that repository's HEAD.
        String commit = saved.path("commit").textValue();
        assertTrue(commit == null || commit.matches("[0-9a-f]{40}"), "commit: " + commit);
        for (String field : List.of("branch", "commitMessage", "commitAuthor")) {
            assertFalse(saved.path(field).asText("").startsWith("fatal"), field + ": " + saved.path(field));
        }

        for (String action : List.of("list", "summary", "latest", "status")) {
            ToolResult result = run("{\"action\":\"" + action + "\"}");
            assertFalse(result.isError(), action + ": " + result.getOutput());
            assertFalse(result.getOutput().contains("null"), action + ": " + result.getOutput());
        }

        ToolResult failed = run("{\"action\":\"fail\",\"module\":\"core\",\"total_tests\":3,\"passed\":2,\"failed\":1}");
        assertFalse(failed.isError(), failed.getOutput());
        ToolResult compared = run("{\"action\":\"compare\",\"from_id\":\"" + id + "\",\"to_id\":\""
                + failed.getMetadata().get("id") + "\"}");
        assertFalse(compared.isError(), compared.getOutput());
        assertTrue(compared.getOutput().contains("REGRESSION detected"), compared.getOutput());
        assertFalse(compared.getOutput().contains("null"), compared.getOutput());

        ToolResult regression = run("{\"action\":\"add_regression\",\"test_name\":\"CoreTest#flaky\",\"module\":\"core\"}");
        assertFalse(regression.isError(), regression.getOutput());
        assertFalse(regression.getOutput().contains("null"), regression.getOutput());
        ToolResult status = run("{\"action\":\"status\"}");
        assertFalse(status.getOutput().contains("null"), status.getOutput());

        if (commit == null) {
            assertFalse(regression.getOutput().contains("Since:"), regression.getOutput());
            ToolResult check = run("{\"action\":\"check\"}");
            assertTrue(check.isError(), check.getOutput());
            assertEquals("No commit to check: pass 'commit', or run inside a git repository.", check.getOutput());
        }
    }

    @Test
    void sweepFilesReadInTheToolsFieldNames() throws Exception {
        Path milestones = Files.createDirectories(TestMilestoneTool.storeDir(project).resolve("milestones"));
        Files.writeString(milestones.resolve("JudgementLogTest.json"), "{\"id\":\"JudgementLogTest\","
                + "\"module\":\"kompile-cli-main\",\"passing\":true,\"total\":12,\"passed\":12,\"failed\":0,"
                + "\"commit\":\"" + COMMIT.substring(0, 10) + "\",\"tags\":\"sweep\","
                + "\"timestamp\":\"2026-06-11T13:10:00Z\"}");

        ToolResult got = run("{\"action\":\"get\",\"id\":\"JudgementLogTest\"}");
        assertFalse(got.isError(), got.getOutput());
        JsonNode milestone = mapper.readTree(got.getOutput());
        assertEquals(12, milestone.path("totalTests").asInt());
        assertEquals("0123456", milestone.path("commitShort").asText());

        // A sweep records ten hex digits; the full hash of the same commit still finds it.
        ToolResult check = run("{\"action\":\"check\",\"commit\":\"" + COMMIT + "\"}");
        assertFalse(check.isError(), check.getOutput());
        assertTrue(check.getOutput().startsWith("ID: JudgementLogTest | WORKING"), check.getOutput());
    }

    @Test
    void handWrittenFilesWithoutIdsOrDatesStillRead() throws Exception {
        Path milestones = Files.createDirectories(TestMilestoneTool.storeDir(project).resolve("milestones"));
        Files.writeString(milestones.resolve("notes.json"),
                "{\"module\":\"core\",\"passing\":true,\"total\":4,\"passed\":4}");

        for (String action : List.of("summary", "latest", "list")) {
            ToolResult result = run("{\"action\":\"" + action + "\"}");
            assertFalse(result.isError(), action + ": " + result.getOutput());
            assertFalse(result.getOutput().contains("null"), action + ": " + result.getOutput());
        }
        ToolResult summary = run("{\"action\":\"summary\"}");
        assertTrue(summary.getOutput().contains("core: (no commit, branch or date recorded)"), summary.getOutput());
    }

    @Test
    void countsAndTargetsThatAreNotNumbersAreLeftOut() throws Exception {
        Path store = TestMilestoneTool.storeDir(project);
        Path milestones = Files.createDirectories(store.resolve("milestones"));
        Files.writeString(store.resolve("config.json"), "{\"project\":\"demo\",\"targets\":{\"minPassRate\":\"95%\"}}");
        Files.writeString(milestones.resolve("before.json"), "{\"id\":\"before\",\"module\":\"core\",\"passing\":true,"
                + "\"total\":\"12\",\"passed\":\"12\",\"failed\":0,\"skipped\":\"1\",\"timestamp\":\"2026-06-11T13:10:00Z\"}");
        Files.writeString(milestones.resolve("after.json"), "{\"id\":\"after\",\"module\":\"core\",\"passing\":false,"
                + "\"total\":12,\"passed\":\"ten\",\"failed\":null,\"skipped\":2,\"timestamp\":\"2026-06-12T13:10:00Z\"}");

        ToolResult status = run("{\"action\":\"status\"}");
        assertFalse(status.isError(), status.getOutput());
        if (!status.getOutput().contains("Branch:")) {
            // Outside git both runs count; the latest has 12 tests and no pass count to read.
            assertTrue(status.getOutput().contains("Pass rate: 0.0%"), status.getOutput());
        }

        ToolResult compared = run("{\"action\":\"compare\",\"from_id\":\"before\",\"to_id\":\"after\"}");
        assertFalse(compared.isError(), compared.getOutput());
        assertTrue(compared.getOutput().contains("REGRESSION detected"), compared.getOutput());
        assertTrue(compared.getOutput().contains("Skipped: 1 -> 2 (+1)"), compared.getOutput());
        for (String label : List.of("Test count:", "Passed:", "Failed:")) {
            assertFalse(compared.getOutput().contains(label), compared.getOutput());
        }

        // The only target is not a number, so a new run gets no target check.
        ToolResult recorded = run("{\"action\":\"record\",\"module\":\"core\",\"total_tests\":3,\"passed\":3}");
        assertFalse(recorded.isError(), recorded.getOutput());
        assertFalse(recorded.getOutput().contains("Target check"), recorded.getOutput());
    }

    private ToolResult run(String json) throws Exception {
        ToolContext context = new ToolContext("milestone-session", null, null, project, null);
        context.setAutoApproveAll(true);
        return tool.execute(mapper.readTree(json), context);
    }
}
