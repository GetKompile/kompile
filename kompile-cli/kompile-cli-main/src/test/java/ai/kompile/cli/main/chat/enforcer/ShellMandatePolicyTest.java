/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain an copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed as input to this software...
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the deterministic shell-mandate layer of harness tool-call validation: shell calls
 * that bypass dedicated file/search/memory tools are blocked; build/test/git pipelines that
 * do not perform shell file I/O stay allowed.
 */
class ShellMandatePolicyTest {

    private static EnforcerToolCallDecision eval(String tool, String command) {
        return ShellMandatePolicy.evaluateCommand(tool, command);
    }

    private static void assertBlocked(String tool, String command) {
        EnforcerToolCallDecision decision = eval(tool, command);
        assertNotNull(decision, "expected BLOCK for: " + tool + " " + command);
        assertFalse(decision.isAllowed(), "expected BLOCK for: " + tool + " " + command);
    }

    private static void assertAllowed(String tool, String command) {
        EnforcerToolCallDecision decision = eval(tool, command);
        assertNull(decision, "expected ALLOW for: " + tool + " " + command
                + (decision == null ? "" : " — got: " + decision.blockMessage()));
    }

    @Nested
    @DisplayName("Out of scope")
    class OutOfScope {
        @Test
        void nonShellToolsNeverMatch() {
            assertNull(ShellMandatePolicy.evaluateCommand("grep", "pattern src/**.java"));
            assertNull(ShellMandatePolicy.evaluateCommand("edit", "sed -i"));
            assertNull(ShellMandatePolicy.evaluateCommand("read", "cat file"));
            assertNull(ShellMandatePolicy.evaluateCommand("mcp__kompile__grep", "pattern x"));
        }

        @Test
        void blankAndNullCommands() {
            assertNull(eval("bash", null));
            assertNull(eval("bash", ""));
            assertNull(eval("bash", "   "));
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs("bash", "{\"timeout\":5}"));
        }
    }

    @Nested
    @DisplayName("In-place rewrites are always blocked")
    class InPlaceRewrites {
        @ParameterizedTest
        @ValueSource(strings = {
                "sed -i 's/old/new/g' file.txt",
                "sed -i.bak 's/a/b/' src/Main.java",
                "sed --in-place 's/a/b/' file",
                "sed -ie 's/a/b/' file",
                "perl -pi -e 's/old/new/g' file.txt",
                "perl -i -pe 's/a/b/' file",
                "perl -i.bak -p file",
                "awk -i inplace '{sub(/a/,\"b\")}1' file.txt",
                "gawk --include=inplace '{gsub(/x/,\"y\")}' f",
                "sudo sed -i 's/x/y/' /etc/hosts",
                "env FOO=1 sed -i 's/x/y/' file"
        })
        void blockedForms(String command) {
            assertBlocked("bash", command);
        }
    }

    @Nested
    @DisplayName("File-reading grep/cat/find/ls are blocked")
    class FileReaders {
        @ParameterizedTest
        @ValueSource(strings = {
                "grep -r pattern .",
                "grep -rn TODO src/",
                "rg pattern src/",
                "grep error logs.txt",
                "grep pattern --include='*.java' src",
                "grep -r pattern . | wc -l",
                "grep -rn x /home/agibsonccc/Documents/GitHub/kompile/src",
                "ag 'TODO' .",
                "ack foo lib/",
                "cat foo.txt",
                "cat a.txt b.txt",
                "cat src/main/java/Foo.java",
                "head -n 10 file.txt",
                "tail -f log.txt",
                "head -c 100 build.log",
                "tac file",
                "less big.log",
                "more notes.md",
                "find . -name '*.java'",
                "find src -type f -name '*.java'",
                "fd pattern",
                "locate pom.xml",
                "ls -la",
                "ls -la /tmp",
                "ls src/",
                "env LC_ALL=C grep -n foo bar.txt",
                "sudo tail -20 /var/log/syslog",
                "/bin/cat /etc/hostname",
                "/usr/bin/find . -maxdepth 2",
                // Reading a file and piping onward is the same violation in pipeline form:
                "cat build.log | grep error",
                "tail -n +1 file | grep -i x",
                "timeout 5 grep foo bar.txt | wc -l",
                "ls | grep java",
                "find . | head",
                "ls -la > listing.txt",
                "timeout 30 grep -r pattern src/",
                "grep \"-Dflag=x\" file.txt"
        })
        void blockedForms(String command) {
            assertBlocked("bash", command);
        }

        @Test
        void violationNamesTheReplacementTool() {
            EnforcerToolCallDecision decision = eval("bash", "grep -rn TODO src/");
            assertTrue(decision.getViolations().get(0).contains("`grep` tool"),
                    () -> decision.blockMessage());
        }
    }

    @Nested
    @DisplayName("Shell file writes are blocked")
    class FileWriters {
        @ParameterizedTest
        @ValueSource(strings = {
                "echo x >> app.log",
                "printf '%s\\n' x > output.txt",
                "mvn test > build.log 2>&1",
                "mvn test > /dev/null.log",
                "mvn test | tee build.log",
                "grep -v spam < raw.txt | tee filtered.txt",
                "cat <<'EOF' >> .kompile/memory/MEMORY.md\nnote\nEOF",
                "python3 -c \"from pathlib import Path; Path('.kompile/memory/note.md').write_text('x')\"",
                "node -e \"require('fs').writeFileSync('.codex/memory/note.md', 'x')\"",
                "touch created.txt",
                "truncate -s 0 output.txt",
                "patch < fix.diff",
                "sudo tee /etc/hosts"
        })
        void blockedForms(String command) {
            assertBlocked("bash", command);
        }

        @Test
        void backgroundProcessLaunchesUseTheSamePolicy() {
            assertBlocked("process", "echo note >> .kompile/memory/MEMORY.md");
            assertBlocked("mcp__kompile__process", "touch generated.txt");
            assertAllowed("process", "/home/agibsonccc/dev-apps/mvn/bin/mvn test");
        }

        @Test
        void violationRoutesManagedMemoryToMemoryTool() {
            EnforcerToolCallDecision decision = eval(
                    "bash", "cat <<'EOF' >> .kompile/memory/MEMORY.md\nnote\nEOF");
            assertTrue(decision.getCorrectionPrompt().contains("`memory` tool"),
                    decision::getCorrectionPrompt);
        }

        @Test
        void shellCannotReadManagedMemoryThroughAnInterpreterEither() {
            assertBlocked("bash",
                    "python3 -c \"print(open('.kompile/memory/MEMORY.md').read())\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rm obsolete.txt", "rm -rf build-cache", "rmdir empty-dir",
            "unlink obsolete-link", "mkdir generated", "cp source.txt destination.txt",
            "mv old.txt new.txt", "chmod +x script.sh", "chown user build-cache"})
    void filesystemAdministrationReachesRiskAndUserPolicyInsteadOfFakeReplacement(String command) {
        assertAllowed("bash", command);
        assertAllowed("process", command);
        assertFalse(ai.kompile.cli.main.chat.tools.BashTool.isReadOnlyCommand(command));
    }

    @Test
    void filesystemAdministrationCannotBypassManagedMemoryProtection() {
        assertBlocked("bash", "rm -rf .kompile/memory");
        assertBlocked("process", "mv .codex/memory notes");
    }

    @Nested
    @DisplayName("Piped streams, stdin sources, and null redirects stay allowed")
    class AllowedForms {
        @ParameterizedTest
        @ValueSource(strings = {
                "mvn test | grep ERROR",                     // project convention: consume build logs
                "mvn -q test 2>&1 | grep -i failure",
                "ps aux | grep java",
                "ps aux | grep java | grep -v grep",
                "ps aux | awk '{print $2}'",
                "git log --oneline -10 | head -5",
                "jcmd 1 Thread.print | grep RUNNABLE",
                "echo hello | grep hello",
                "printf '%s\\n' a b | grep a",
                "grep pattern -",                            // explicit stdin
                "grep pat - < input.txt",
                "grep foo < data.txt",
                "grep foo <<< 'some text'",
                "grep -e foo <<'EOF'\nbar\nEOF",
                "git log | grep Merge",
                "docker logs c 2>&1 | grep error",
                "nvidia-smi --query-compute-apps=pid --format=csv,noheader 2>/dev/null",
                "ps -o pid= -p 123 >/dev/null",
                "mvn test > '/dev/null' 2>&1",
                "git status 2>> /dev/null",
                "echo 'a > b'",
                "printf '%s\\n' 'x >> y'",
                "jq '.dependencies' package.json"             // jq reads its own file arg — not a banned reader
        })
        void allowedForms(String command) {
            assertAllowed("bash", command);
        }
    }

    @Nested
    @DisplayName("Chains and substitutions")
    class Chains {
        @Test
        void semicolonChainBlocksTheBannedPart() {
            assertBlocked("bash", "mvn -q test; grep -rn TODO src/");
        }

        @Test
        void andChainBlocksTheBannedPart() {
            assertBlocked("bash", "ls && grep pattern x.txt");
        }

        @Test
        void orFallbackCommandStillAnalyzed() {
            assertBlocked("bash", "mvn test || grep foo pom.xml");
        }

        @Test
        void pipesInsideSubstitutionsDoNotHideFileReads() {
            assertBlocked("bash", "echo $(grep -rn TODO src/)");
            assertBlocked("bash", "RESULT=$(grep pattern f.txt) && echo ok");
            assertBlocked("bash", "N=`grep -c x pom.xml`");
        }

        @Test
        void nonBannedSubstitutionPasses() {
            assertAllowed("bash", "echo $(git rev-parse HEAD)");
        }

        @Test
        void allowedChainWithPipeFilter() {
            assertAllowed("bash", "mvn test 2>&1 | grep ERROR; git status");
        }

        @Test
        void quotedPipeDoesNotSplitSegments() {
            // A quoted '|' belongs to a sed script / grep pattern, not the pipeline.
            assertAllowed("bash", "mvn test | grep -E 'foo|bar'");
            assertAllowed("bash", "echo 'a|b'");
        }

        @Test
        void quotedPipeAroundBannedCommandStillBlocksInPlace() {
            assertBlocked("bash", "sed -i 's/|/slash/' file.txt");
        }
    }

    @Nested
    @DisplayName("Serialized JSON argument extraction")
    class JsonArgs {
        @Test
        void extractsCommandFieldAndBlocks() {
            String args = "{\"command\":\"sed -i 's/a/b/' src/Foo.java\",\"timeout\":10}";
            EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs("bash", args);
            assertNotNull(decision);
            assertTrue(decision.getViolations().get(0).contains("edit"));
        }

        @Test
        void extractsCommandFieldAndAllows() {
            String args = "{\"command\":\"mvn test | grep ERROR\",\"description\":\"run tests\"}";
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs("bash", args));
        }

        @Test
        void handlesEscapedQuotesAndNewlinesInCommand() {
            String args = "{\"command\":\"grep -rn \\\"TODO\\\" src/\"}";
            assertNotNull(ShellMandatePolicy.evaluateFromSerializedArgs("bash", args));
        }

        @Test
        void unescapesUnicode() {
            String args = "{\"command\":\"grep caf\\u00e9 menu.txt\"}";
            EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs("bash", args);
            assertNotNull(decision);
            assertTrue(decision.getViolations().get(0).contains("caf\\u00e9")
                            || decision.getViolations().get(0).contains("café"),
                    () -> decision.blockMessage());
        }

        @Test
        void largeEscapedCommandDoesNotOverflowAndStillEvaluates() {
            String command = "echo " + "\"escaped\\\\value\" ".repeat(2_000);
            String args = JsonUtils.standardMapper().createObjectNode()
                    .put("command", command)
                    .toString();

            assertEquals(command, assertDoesNotThrow(
                    () -> ShellMandatePolicy.extractCommandFromJson(args)));
            assertNull(assertDoesNotThrow(
                    () -> ShellMandatePolicy.evaluateFromSerializedArgs("bash", args)));
        }

        @Test
        void malformedJsonFailsOpen() {
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs(
                    "bash", "{\"command\":\"unterminated"));
        }

        @Test
        void nonTextCommandValueFailsOpen() {
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs(
                    "bash", "{\"command\":[\"sed -i file\"]}"));
        }

        @Test
        void commandFieldPriorityDoesNotDependOnJsonOrder() {
            String args = "{\"bash_command\":\"cat fallback.txt\","
                    + "\"cmd\":\"sed -i file.txt\",\"command\":\"git status\"}";
            assertEquals("git status", ShellMandatePolicy.extractCommandFromJson(args));
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs("bash", args));
        }

        @Test
        void plainTextCommandInputRemainsSupported() {
            assertEquals("git status", ShellMandatePolicy.extractCommandFromJson("git status"));
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs("bash", "git status"));
        }

        @Test
        void namespacedToolNameIsCanonicalized() {
            String args = "{\"command\":\"cat pom.xml\"}";
            assertNotNull(ShellMandatePolicy.evaluateFromSerializedArgs("mcp__kompile__bash", args));
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs("mcp__kompile__grep", args));
            assertNotNull(ShellMandatePolicy.evaluateFromSerializedArgs(
                    "mcp__kompile__process", "{\"command\":\"tee note.txt\"}"));
        }
    }

    @Nested
    @DisplayName("Wiring")
    class Wiring {
        @TempDir
        Path wd;

        private EnforcerToolCallGuard guardWithRules(String rules) throws Exception {
            EnforcerPolicy policy = new EnforcerPolicy(rules, 1, false);
            HarnessConfig config = new HarnessConfig();
            config.setJudgeGlobalEnabled(true);
            return new EnforcerToolCallGuard(JsonUtils.standardMapper(),
                    EnforcerRuntimePolicy.create(wd, policy, config, JsonUtils.standardMapper()));
        }

        private EnforcerToolCallGuard guardWithInMemoryAllowJudge(String rules) throws Exception {
            ObjectMapper mapper = JsonUtils.standardMapper();
            EnforcerPolicy policy = new EnforcerPolicy(rules, 1, false);
            HarnessConfig config = new HarnessConfig();
            config.setJudgeGlobalEnabled(true);
            JudgeBackend backend = new JudgeBackend() {
                @Override
                public String generate(String userPrompt, String systemPrompt) {
                    return "{\"action\":\"ALLOW\",\"reason\":\"test fixture\"}";
                }

                @Override
                public boolean isAvailable() {
                    return true;
                }
            };
            return new EnforcerToolCallGuard(mapper,
                    EnforcerRuntimePolicy.create(wd, policy, config, mapper),
                    new EnforcerJudge(backend, mapper));
        }

        @Test
        void guardBlocksShellMandateWithoutJudgeOrRules() throws Exception {
            try (EnforcerToolCallGuard guard = guardWithRules("Use tools relevant to the request.")) {
                assertTrue(guard.describe().contains("lazy"), "no judge needed for a hard block");
                EnforcerToolCallDecision decision = guard.evaluate(
                        "bash", Map.of("command", "sed -i 's/a/b/' src/Foo.java"));
                assertFalse(decision.isAllowed());
                assertTrue(guard.describe().contains("lazy"), "hard block must not build the judge");
            }
        }

        @Test
        void guardAllowsCompliantCommandsAfterInspectingTheirArguments() throws Exception {
            try (EnforcerToolCallGuard guard = guardWithInMemoryAllowJudge(
                    "Use tools relevant to the request.")) {
                assertTrue(guard.evaluate("bash", Map.of("command", "mvn test | grep ERROR")).isAllowed());
                assertTrue(guard.evaluate("bash", Map.of("command",
                        "nvidia-smi --query-compute-apps=pid --format=csv,noheader 2>/dev/null")).isAllowed());
            }
        }

        @Test
        void guardAllowsReadOnlyGitWithoutStartingAProbabilisticJudge() throws Exception {
            try (EnforcerToolCallGuard guard = guardWithRules(
                    "Plan before changes. Git operations, especially resets, are not allowed.")) {
                EnforcerToolCallDecision decision = guard.evaluate(
                        "bash", Map.of("command", "git diff --check"));

                assertTrue(decision.isAllowed());
                assertTrue(guard.describe().contains("lazy"),
                        "host-classified read-only Git must not construct the LLM judge");
            }
        }

        @Test
        void guardInactiveMeansNoMandateBlock() throws Exception {
            ObjectMapper om = JsonUtils.standardMapper();
            EnforcerRuntimePolicy runtimePolicy = EnforcerRuntimePolicy.create(wd,
                    new EnforcerPolicy("rules", 1, false), new HarnessConfig(), om);
            runtimePolicy.setEnabled(false, om);
            try (EnforcerToolCallGuard guard = new EnforcerToolCallGuard(om, runtimePolicy)) {
                assertFalse(guard.isActive());
                assertTrue(guard.evaluate("bash", Map.of("command", "cat pom.xml")).isAllowed());
            }
        }

        @Test
        void keywordLaneAlsoEnforcesMandate() {
            KeywordEnforcerEvaluator evaluator = new KeywordEnforcerEvaluator(java.util.List.of(), "");
            EnforcerPolicy policy = new EnforcerPolicy("", 1, false);
            assertFalse(evaluator.evaluateToolCall("bash",
                    "{\"command\":\"grep -rn TODO src/\"}", policy).isAllowed());
            assertTrue(evaluator.evaluateToolCall("bash",
                    "{\"command\":\"ps aux | grep java\"}", policy).isAllowed());
        }
    }
}
