package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.TestMilestoneReader;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.PassthroughStreamParser;
import ai.kompile.cli.main.chat.TranscriptLogScope;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class TestMilestoneCaptureTest {
    @TempDir Path root;

    @Test void invalidCaptureConfigDoesNotFailUnrelatedCommandsButStillFlagsExplicitTests() throws Exception {
        configure("{\"excludeCommands\":[\"(\"]}");
        ToolResult result = bash("pwd");
        assertFalse(result.isError(), result.getOutput());
        assertEquals(0, result.getMetadata().get("exitCode"));
        assertFalse(result.getMetadata().containsKey("testCaptureWarnings"));
        List<String> captureFailures = new ArrayList<>();
        assertNull(TestMilestoneCapture.start("mvn compile", root, null, null, captureFailures::add));
        assertNull(TestMilestoneCapture.start("bash compile.sh", root, null, null, captureFailures::add));
        assertTrue(captureFailures.isEmpty(), "Diagnostics for a non-test command are not capture failures");
        assertNull(TestMilestoneCapture.start("pytest", root, null, null, captureFailures::add));
        assertEquals(1, captureFailures.size());
        assertTrue(captureFailures.get(0).contains("Automatic test capture disabled"));
        assertTrue(milestones().isEmpty());
    }

    @Test void successfulCommandCannotSilentlyLoseItsMilestone() throws Exception {
        Path transcript = transcript("capture-failure");
        Files.createDirectories(root.resolve(".kompile/test-milestones"));
        Files.writeString(root.resolve(".kompile/test-milestones/milestones"), "not a directory");
        Files.writeString(root.resolve("checks.sh"), "#!/bin/sh\nprintf '2 tests executed: 2 passed, 0 failed\\n'\n");
        try (var scope = TranscriptLogScope.openIsolated("capture-failure", root, false);
             var binding = scope.bind()) {
            ToolResult result = bash("bash checks.sh");
            assertTrue(result.isError(), result.getOutput());
            assertEquals(0, result.getMetadata().get("exitCode"), "Do not rewrite the actual command exit code");
            assertNotNull(result.getMetadata().get("testCaptureWarnings"));
            assertFalse(result.getMetadata().containsKey("testMilestoneId"));
        }
        assertTrue(Files.readString(transcript).contains("Test milestone capture warning:"));
        assertFalse(Files.readString(transcript).contains("Automatic test milestone recorded:"));
    }

    @Test void foregroundAutomaticallyCapturesWithoutAnAgentToolCall() throws Exception {
        Files.writeString(root.resolve("checks.sh"), "#!/bin/sh\n[ 2 -eq 2 ] || exit 1\nprintf '2 tests executed: 2 passed, 0 failed\\n'\n");
        ToolResult result = bash("bash checks.sh");
        assertFalse(result.isError(), result.getOutput());
        assertNotNull(result.getMetadata().get("testMilestoneId"));
        var milestone = milestones().get(0);
        assertEquals("harness", milestone.get("source"));
        assertEquals("passed", milestone.get("outcome"));
        assertEquals(2, milestone.get("totalTests"));
        assertEquals(true, milestone.get("passing"));
        assertNotNull(milestone.get("executionId"));
    }
    @Test void observesTheTailEvenWhenCanonicalBashOutputIsTruncated() throws Exception {
        Files.writeString(root.resolve("checks.sh"), "#!/bin/sh\ni=0; while [ $i -lt 1500 ]; do printf 'long diagnostic line to exceed canonical output limit\\n'; i=$((i+1)); done\nprintf '1 tests executed: 1 passed, 0 failed\\n'\n");
        ToolResult result = bash("bash checks.sh");
        assertEquals(true, result.getMetadata().get("truncated"));
        assertEquals(false, result.getMetadata().get(ToolResult.OUTPUT_STREAMED_METADATA));
        assertEquals("passed", milestones().get(0).get("outcome"));
    }
    @Test void backgroundCompletionCapturesOnceWithoutPolling() throws Exception {
        Files.writeString(root.resolve("checks.sh"), "#!/bin/sh\nprintf '2 tests executed: 1 passed, 1 failed\\n'; exit 1\n");
        try (var manager = new BackgroundProcessManager("capture-test", root)) {
            CountDownLatch exited = new CountDownLatch(1);
            manager.addExitListener(entry -> exited.countDown());
            var entry = manager.launch("bash checks.sh", "checks", root);
            assertTrue(exited.await(10, TimeUnit.SECONDS));
            assertEquals("failed", milestones().get(0).get("outcome"));
            assertEquals(1, milestones().size());
            manager.get(entry.getId());
            manager.get(entry.getId());
            assertEquals(1, milestones().size());
        }
    }
    @Test void existingRegisteredModuleNamesAreUsedForDirectoriesAndMavenSelections() throws Exception {
        Path config = root.resolve(".kompile/test-milestones/config.json");
        Files.createDirectories(config.getParent());
        Files.createDirectories(root.resolve("platform-tests"));
        Files.writeString(config, "{\"modules\":{\"dsp-validation\":{\"path\":\"platform-tests\"}}}");
        try (var run = start("cd platform-tests && pytest")) {
            run.accept("2 passed in 0.1s"); run.finish(0, false, false, null);
        }
        try (var run = start("mvn -pl :platform-tests test")) {
            run.accept("Tests run: 2, Failures: 0, Errors: 0, Skipped: 0"); run.finish(0, false, false, null);
        }
        assertEquals(2, milestones().size());
        assertTrue(milestones().stream().allMatch(m -> "dsp-validation".equals(m.get("module"))));
    }
    @Test void repeatedRunsRemainDistinctButRepeatedCompletionDoesNotDuplicate() throws Exception {
        for (int i = 0; i < 2; i++) {
            try (var run = start("pytest")) {
                assertNotNull(run);
                run.accept("2 passed in 0.1s");
                assertNotNull(run.finish(0, false, false, null));
                assertNull(run.finish(0, false, false, null));
            }
        }
        assertEquals(2, milestones().size());
    }
    @Test void interruptedAndTimedOutRunsCannotBecomeLastKnownGood() throws Exception {
        try (var run = start("pytest")) {
            run.accept("2 passed in 0.1s");
            run.finish(-1, true, false, null);
        }
        var saved = milestones().get(0);
        assertEquals("timed_out", saved.get("outcome"));
        assertFalse(saved.containsKey("passing"));
        assertFalse(TestMilestoneReader.isConclusive(saved));
    }
    @Test void delegatedSubagentCapturesCarryTheSubagentOrigin() throws Exception {
        try (var run = TestMilestoneCapture.start("pytest", root, "child-session", "child-execution", "subagent", text -> fail(text))) {
            assertNotNull(run);
            run.accept("2 passed in 0.1s");
            run.finish(0, false, false, null);
        }
        // Directory order is unspecified; select the delegated run by the session it stamped.
        var delegated = milestones().stream()
                .filter(m -> "child-session".equals(m.get("sessionId"))).findFirst().orElseThrow();
        assertEquals("subagent", delegated.get("origin"));
        assertEquals("child-session", delegated.get("sessionId"));
        // An untagged sibling run must not inherit the delegated origin.
        try (var run = start("pytest")) {
            run.accept("1 tests executed: 1 passed, 0 failed");
            run.finish(0, false, false, null);
        }
        var siblings = milestones().stream()
                .filter(m -> !"child-session".equals(m.get("sessionId"))).toList();
        assertEquals(1, siblings.size());
        assertFalse(siblings.get(0).containsKey("origin"));
    }
    @Test void supervisedChildBashTagsItsMilestonesAsSubagentWork() throws Exception {
        Files.writeString(root.resolve("checks.sh"), "#!/bin/sh\nprintf '3 tests executed: 3 passed, 0 failed\\n'\n");
        var context = new ToolContext("native-child-session", null, null, root, null);
        context.setAutoApproveAll(true);
        context.markSupervisedChild();
        ToolResult result = new BashTool().execute(
                JsonUtils.standardMapper().createObjectNode().put("command", "bash checks.sh"), context);
        assertFalse(result.isError(), result.getOutput());
        var saved = milestones().get(0);
        assertEquals("subagent", saved.get("origin"));
        assertEquals("native-child-session", saved.get("sessionId"));
    }
    @Test void compileDoesNotImportAnOldPassingReport() throws Exception {
        report("<testsuite tests='2'><testcase name='a'/><testcase name='b'/></testsuite>");
        assertNull(start("mvn compile"));
        assertNull(start("mvn test -DskipTests"));
        try (var run = start("mvn test")) { run.finish(1, false, false, null); }
        assertEquals("execution_error", milestones().get(0).get("outcome"));
        assertFalse(milestones().get(0).containsKey("passing"));
    }
    @Test void freshReportsCanSupplyCountsWhenTerminalOutputIsQuiet() throws Exception {
        try (var run = start("mvn test")) {
            report("<testsuite tests='3'><testcase name='a'/><testcase name='b'><failure/></testcase><testcase name='c'><skipped/></testcase></testsuite>");
            run.finish(1, false, false, null);
        }
        var saved = milestones().get(0);
        assertEquals("failed", saved.get("outcome"));
        assertEquals(3, saved.get("totalTests"));
        assertEquals(1, saved.get("failed"));
        assertEquals("fresh-structured-reports", saved.get("basis"));
        assertNotNull(saved.get("reportReferences"));
    }
    @Test void concurrentlySharedReportPathsNeverAttributeOneRunsReportsToAnother() throws Exception {
        try (var first = start("mvn test"); var second = start("mvn test")) {
            report("<testsuite tests='1'><testcase name='a'/></testsuite>");
            first.finish(0, false, false, null);
            second.finish(0, false, false, null);
        }
        assertEquals(2, milestones().size());
        assertTrue(milestones().stream().allMatch(m -> !m.containsKey("passing") && m.containsKey("reportsIgnored")));
    }
    @Test void globalDefaultsHaveProjectOverridesAndInvalidConfigurationFailsClosed() throws Exception {
        Path global = Path.of(System.getProperty("user.home"), ".kompile/test-milestones/config.json");
        Files.createDirectories(global.getParent());
        Files.writeString(global, "{\"autoCapture\":{\"enabled\":false}}");
        try {
        assertNull(start("pytest"));
        configure("{\"enabled\":true,\"disabledFrameworks\":[\"pytest\"]}");
        assertNull(start("pytest"));
        configure("{\"enabled\":true,\"excludeCommands\":[\"integration\"]}");
        assertNull(start("pytest integration"));
        assertNotNull(startAndClose("pytest unit"));
        configure("{\"excludeCommands\":[\"(\"]}");
        List<String> warnings = new ArrayList<>();
        assertNull(TestMilestoneCapture.start("pytest", root, null, null, warnings::add));
        assertFalse(warnings.isEmpty());
        } finally {
            Files.deleteIfExists(global); // The class-scoped temporary home is shared by these methods.
        }
    }
    @Test void globalConfigurationDoesNotMakeHomeTheProjectRootForEveryChild() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Path config = home.resolve(".kompile/test-milestones/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"autoCapture\":{\"enabled\":false}}");
        try {
            Path child = Files.createDirectories(home.resolve("unregistered-project"));
            assertEquals(child, TestCaptureConfig.root(child));
        } finally { Files.deleteIfExists(config); }
    }
    @Test void atomicWriterDoesNotReplaceAnExistingRun() throws Exception {
        var milestone = new LinkedHashMap<String, Object>(Map.of("id", "abcdef12", "passing", true));
        Path file = TestMilestoneStore.save(root, milestone, null);
        milestone.put("passing", false);
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> TestMilestoneStore.save(root, milestone, null));
        assertTrue(JsonUtils.standardMapper().readTree(file.toFile()).path("passing").asBoolean());
    }
    @Test void customRegexRulesAndFallbackSwitchAreEffective() throws Exception {
        configure("{\"fallbackEnabled\":false,\"customRules\":[{\"commandRegex\":\"validate-special\","
                + "\"summaryRegex\":\"DONE (?<total>[0-9]+) (?<passed>[0-9]+) (?<failed>[0-9]+)\"}]}");
        try (var fallback = start("./ordinary-checks")) {
            fallback.accept("2 tests executed: 2 passed, 0 failed");
            assertNull(fallback.finish(0, false, false, null));
        }
        try (var run = start("./validate-special")) {
            run.accept("DONE 3 2 1");
            run.finish(1, false, false, null);
        }
        assertEquals("custom", milestones().get(0).get("framework"));
        assertEquals("failed", milestones().get(0).get("outcome"));
    }
    @Test void providerCallsAreCorrelatedAndMcpToolsDoNotDoubleCapture() throws Exception {
        try (var capture = new ProviderTestCapture(root)) {
            capture.start("one", "Bash", "{\"command\":\"pytest\"}");
            capture.start("two", "bash", "{\"command\":\"cargo test\"}");
            capture.start("mcp", "mcp__kompile__bash", "{\"command\":\"pytest\"}");
            capture.complete("two", "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.0s", 0, false);
            capture.complete("one", "2 failed in 0.1s", 1, false);
            capture.complete("one", "2 failed in 0.1s", 1, false);
            capture.complete("mcp", "99 passed in 0.1s", 0, false);
        }
        assertEquals(2, milestones().size());
        assertEquals(Set.of("pytest", "rust"), new HashSet<>(milestones().stream().map(m -> m.get("framework")).toList()));
    }
    @Test void codexProviderAdapterUsesFullFinalOutputNotDuplicateDeltas() throws Exception {
        var parser = new PassthroughStreamParser();
        parser.enableTestCapture(root);
        parser.parseCodexLine("{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\",\"id\":\"x\",\"command\":\"pytest\"}}");
        parser.parseCodexLine("{\"type\":\"item.updated\",\"item\":{\"type\":\"command_execution\",\"id\":\"x\",\"aggregated_output\":\"2 passed in 0.1s\\n\"}}");
        parser.parseCodexLine("{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\",\"id\":\"x\",\"aggregated_output\":\"2 passed in 0.1s\\n\",\"exit_code\":0}}");
        parser.closeTestCapture();
        assertEquals(1, milestones().size());
        assertEquals(2, milestones().get(0).get("totalTests"));
    }
    @Test void foregroundNoticeIsTranscriptMetadataWithoutModelOutputOrExtraResume() throws Exception {
        Files.writeString(root.resolve("checks.sh"), "#!/bin/sh\nprintf '2 tests executed: 2 passed, 0 failed\\n'\n");
        ChatHistory history = new ChatHistory("capture-transcript");
        history.open(null, "coder", false, root);
        history.logUserMessage("Run the checks");
        var turns = history.readTurns();
        try (var scope = TranscriptLogScope.openIsolated("capture-transcript", root, false);
             var binding = scope.bind()) {
            ToolResult result = bash("bash checks.sh");
            String id = result.getMetadata().get("testMilestoneId").toString();
            String transcript = history.readTranscript();
            assertTrue(transcript.contains("[system] Automatic test milestone recorded: id=" + id), transcript);
            assertTrue(transcript.contains("outcome=passed"));
            assertTrue(transcript.contains("totalTests=2 passed=2 failed=0 errors=0 skipped=0"));
            assertEquals(turns, history.readTurns()); // No fabricated LLM turn or replay input.
            assertFalse(result.getOutput().contains("Automatic test milestone"));
            history.logAssistantMessage("Done", 0, 0);
            assertTrue(history.readTranscript().contains("< Done"));
            assertFalse(history.readTranscript().contains("[resumed"));
        } finally { history.close(); }
    }
    @Test void backgroundCompletionWritesOneTranscriptNoticeWithoutPolling() throws Exception {
        Path transcript = transcript("background-transcript");
        Files.writeString(root.resolve("checks.sh"), "#!/bin/sh\nprintf '2 tests executed: 1 passed, 1 failed\\n'; exit 1\n");
        try (var scope = TranscriptLogScope.openIsolated("background-transcript", root, false);
             var binding = scope.bind();
             var manager = new BackgroundProcessManager("background-transcript", root)) {
            CountDownLatch exited = new CountDownLatch(1);
            manager.addExitListener(entry -> exited.countDown());
            var entry = manager.launch("bash checks.sh", "checks", root);
            assertTrue(exited.await(10, TimeUnit.SECONDS));
            manager.get(entry.getId());
            String text = Files.readString(transcript);
            assertEquals(1, text.lines().filter(line -> line.startsWith("[system] Automatic test milestone")).count());
            assertTrue(text.contains("outcome=failed"));
            assertTrue(text.contains("passed=1 failed=1"));
        }
    }
    @Test void providerCompletionDoesNotDuplicateTranscriptNotices() throws Exception {
        Path transcript = transcript("provider-transcript");
        try (var scope = TranscriptLogScope.openIsolated("provider-transcript", root, false);
             var binding = scope.bind();
             var capture = new ProviderTestCapture(root)) {
            capture.start("one", "Bash", "{\"command\":\"pytest\"}");
            capture.start("mcp", "mcp__kompile__bash", "{\"command\":\"pytest\"}");
            capture.complete("one", "2 passed in 0.1s", 0, false);
            capture.complete("one", "2 passed in 0.1s", 0, false);
            capture.complete("mcp", "99 passed in 0.1s", 0, false);
        }
        assertEquals(1, Files.readAllLines(transcript).stream()
                .filter(line -> line.startsWith("[system] Automatic test milestone")).count());
    }
    @Test void completionUsesLaunchingTranscriptEvenAfterScopeSwitchAndClose() throws Exception {
        Path owner = transcript("launch-owner");
        Path other = transcript("other-chat");
        TestMilestoneCapture run;
        try (var scope = TranscriptLogScope.openIsolated("launch-owner", root, false);
             var binding = scope.bind()) {
            run = start("pytest");
        }
        try (var scope = TranscriptLogScope.openIsolated("other-chat", root, false);
             var binding = scope.bind(); run) {
            run.accept("2 passed in 0.1s");
            assertNotNull(run.finish(0, false, false, null));
            assertNull(run.finish(0, false, false, null));
        }
        assertEquals(1, Files.readAllLines(owner).stream()
                .filter(line -> line.startsWith("[system] Automatic test milestone")).count());
        assertFalse(Files.readString(other).contains("Automatic test milestone"));
    }
    @Test void inconclusiveNoticeDoesNotClaimPassingAndBuildOnlyHasNoNotice() throws Exception {
        Path transcript = transcript("neutral-transcript");
        try (var scope = TranscriptLogScope.openIsolated("neutral-transcript", root, false);
             var binding = scope.bind()) {
            assertNull(start("mvn compile"));
            try (var build = start("./build.sh")) { assertNull(build.finish(0, false, false, null)); }
            assertFalse(Files.readString(transcript).contains("Automatic test milestone"));
            try (var run = start("pytest")) {
                run.accept("2 passed in 0.1s");
                run.finish(-1, true, false, null);
            }
        }
        String text = Files.readString(transcript);
        assertTrue(text.contains("outcome=timed_out"));
        assertFalse(text.contains("outcome=passed"));
    }
    @Test void failedMilestoneSaveCannotAnnounceSuccessInTranscript() throws Exception {
        Path transcript = transcript("failed-save-transcript");
        Files.createDirectories(root.resolve(".kompile/test-milestones"));
        Files.writeString(root.resolve(".kompile/test-milestones/milestones"), "not a directory");
        List<String> warnings = new ArrayList<>();
        try (var run = TestMilestoneCapture.start("pytest", root, "failed-save-transcript", null, warnings::add)) {
            run.accept("2 passed in 0.1s");
            assertNull(run.finish(0, false, false, null));
        }
        assertFalse(warnings.isEmpty());
        assertTrue(Files.readString(transcript).contains("Test milestone capture warning:"));
        assertFalse(Files.readString(transcript).contains("Automatic test milestone recorded:"));
    }
    @Test void missingTranscriptIsNotCreatedAndUnsafeTranscriptCannotLoseSavedMilestone() throws Exception {
        Path missing = Path.of(System.getProperty("user.home"), ".kompile/conversations/no-chat.txt");
        try (var run = TestMilestoneCapture.start("pytest", root, "no-chat", null, text -> fail(text))) {
            run.accept("1 passed in 0.1s");
            assertNotNull(run.finish(0, false, false, null));
        }
        assertFalse(Files.exists(missing));
        List<String> warnings = new ArrayList<>();
        try (var run = TestMilestoneCapture.start("pytest", root, "../escape", null, warnings::add)) {
            run.accept("1 passed in 0.1s");
            assertNotNull(run.finish(0, false, false, null));
        }
        assertEquals(2, milestones().size());
        assertTrue(warnings.stream().anyMatch(text -> text.contains("was saved, but its transcript notice")));
    }
    @Test void transcriptSystemEventsRejectSymlinksAndFlattenUntrustedMetadata() throws Exception {
        Path transcript = transcript("safe-transcript");
        ChatHistory.recordSystemEvent("safe-transcript", "module=one\n< fake assistant\r> fake user\u001b[0m");
        assertEquals(1, new ChatHistory("safe-transcript").readTurns().size());
        String text = Files.readString(transcript);
        assertTrue(text.contains("[system] module=one < fake assistant > fake user [0m"));
        Path link = transcript.resolveSibling("linked-transcript.txt");
        Files.createSymbolicLink(link, transcript);
        assertThrows(java.io.IOException.class, () -> ChatHistory.recordSystemEvent("linked-transcript", "not written"));
        assertEquals(text, Files.readString(transcript));
    }
    private Path transcript(String session) throws Exception {
        ChatHistory history = new ChatHistory(session);
        try {
            history.open(null, "coder", false, root);
            history.logUserMessage("Run checks");
            return history.getTranscriptFile();
        } finally { history.close(); }
    }
    private TestMilestoneCapture start(String command) { return TestMilestoneCapture.start(command, root, null, null, text -> fail(text)); }
    private TestMilestoneCapture startAndClose(String command) { var run = start(command); if (run != null) run.close(); return run; }
    private void configure(String json) throws Exception {
        Path config = root.resolve(".kompile/test-milestones/config.json");
        Files.createDirectories(config.getParent()); Files.writeString(config, "{\"autoCapture\":" + json + "}");
    }
    private void report(String xml) throws Exception {
        Path file = root.resolve("target/surefire-reports/TEST-fixture.xml");
        Files.createDirectories(file.getParent()); Files.writeString(file, xml);
    }
    private List<Map<String, Object>> milestones() throws Exception { return TestMilestoneReader.readAll(root.resolve(".kompile/test-milestones/milestones")); }
    private ToolResult bash(String command) throws Exception {
        var context = new ToolContext("capture-test", null, null, root, null);
        context.setAutoApproveAll(true);
        return new BashTool().execute(JsonUtils.standardMapper().createObjectNode().put("command", command), context);
    }
}
