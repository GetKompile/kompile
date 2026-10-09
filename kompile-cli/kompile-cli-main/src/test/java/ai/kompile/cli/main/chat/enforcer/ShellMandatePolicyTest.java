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
import ai.kompile.cli.main.chat.tools.BashTool;
import ai.kompile.cli.main.chat.tools.ProcessManagementTool;
import ai.kompile.cli.main.chat.tools.ToolResult;
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
    @DisplayName("Native memory mutations must use Kompile memory")
    class NativeMemoryMutations {
        @ParameterizedTest
        @ValueSource(strings = {"Write", "Edit", "MultiEdit", "NotebookEdit", "mcp__kompile__write",
                "mcp__kompile__edit", "mcp__kompile__edit_batch", "mcp__kompile__edit_patch", "patch"})
        void fileMutationToolsRejectManagedMemory(String tool) {
            EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs(tool,
                    "{\"file_path\":\".claude/projects/-repo/memory/MEMORY.md\"}");
            assertNotNull(decision);
            assertFalse(decision.isAllowed());
            assertTrue(decision.getCorrectionPrompt().contains("mcp__kompile__memory"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"edits", "patches"})
        void batchMutationInspectsEveryTarget(String field) {
            EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs(
                    "mcp__kompile__edit_batch", "{\"" + field + "\":[{\"file_path\":\"ordinary.md\"},"
                            + "{\"file_path\":\".claude/projects/-repo/memory/feedback.md\"}]}");
            assertNotNull(decision);
            assertFalse(decision.isAllowed());
        }

        @Test
        void memoryToolIsNotConfusedWithFileMutation() {
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs("mcp__kompile__memory",
                    "{\"action\":\"write\",\"file\":\".claude/memory/MEMORY.md\",\"content\":\"note\"}"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"invalid", "null", "[]", "{\"file_path\":\"\\u0000\"}"})
        void uninspectableNativeMutationFailsClosed(String args) {
            EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs("Write", args);
            assertNotNull(decision);
            assertFalse(decision.isAllowed());
        }

        @Test
        void disabledSessionJudgeCannotBypassMemoryGate(@TempDir Path sessionsDir) {
            JudgeControl control = new JudgeControl("memory-hook", sessionsDir);
            control.setEnabled(false);
            EnforcerToolCallDecision decision = EnforcerToolCallGuard.evaluateSession(null, "Write",
                    Map.of("file_path", ".claude/projects/-repo/memory/MEMORY.md"), control, new ObjectMapper());
            assertFalse(decision.isAllowed());
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
    @DisplayName("Sed is blocked on files, streams, and stdin — use dedicated tools")
    class SedBan {
        @ParameterizedTest
        @ValueSource(strings = {
                "sed -n '1,20p' build.log",
                "sed '/ERROR/!d' build.log",
                "mvn test 2>&1 | sed -n '/ERROR/p'",
                "printf '%s\\n' hello | sed 's/hello/world/'",
                "sed -n '1,20p' < build.log",
                "sed -n '/ERROR/p' -",
                "sed -n '/ERROR/p' <<< 'ERROR inline'",
                "sed -n '/ERROR/p' <<'EOF'\nERROR inline\nEOF",
                "/usr/bin/sed -n '1p' build.log",
                "\"sed\" -n '1p' build.log",
                "'/usr/bin/sed' -n '1p' build.log",
                "env LC_ALL=C sed -n '/ERROR/p' < build.log",
                "sudo sed -n '1p' build.log",
                "timeout 5 sed -n '1p' build.log",
                "nohup sed -n '1p' build.log",
                "command sed -n '1p' build.log",
                "true && sed -n '1p' build.log",
                "false || sed -n '1p' build.log",
                "true; sed -n '1p' build.log",
                "if true; then sed -n '1p' build.log; fi",
                "if sed -n '1p' build.log; then true; fi",
                "if false; then true; else sed -n '1p' build.log; fi",
                "! sed -n '1p' build.log",
                "echo $(printf hello | sed -n '1p')",
                "echo `printf hello | sed -n '1p'`",
                "bash -c 'printf hello | sed -n 1p'",
                "env FLAG=1 /bin/sh -lc 'sed -n 1p < build.log'"
        })
        void blockedForms(String command) {
            assertBlocked("bash", command);
            assertBlocked("process", command);
        }

        @ParameterizedTest
        @ValueSource(strings = {"bash", "mcp__kompile__bash", "process", "mcp__kompile__process"})
        void serializedToolCallsRouteToGrep(String tool) {
            EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs(
                    tool, "{\"command\":\"mvn test | sed -n '/ERROR/p'\"}");
            assertNotNull(decision);
            assertFalse(decision.isAllowed());
            assertTrue(decision.getViolations().get(0).contains("`grep` tool"));
            assertTrue(decision.getCorrectionPrompt().contains("never shell `sed`"));
        }

        @Test
        void inPlaceRewritesStillRouteToEdit() {
            EnforcerToolCallDecision decision = eval("bash", "sed -i 's/old/new/' file.txt");
            assertNotNull(decision);
            assertTrue(decision.getViolations().get(0).contains("`edit` tool"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "echo sed",
                "printf '%s' 'sed -n 1p build.log'",
                "mvn test -Dlabel=sed",
                "bash -c 'echo sed'",
                "if true; then echo sed; fi",
                "if true; then printf '%s' 'sed -n 1p build.log'; fi",
                "mvn test | awk '/ERROR/'",
                "ps aux | awk '{print $2}'"
        })
        void textMentionsAndOtherStreamFiltersStayAllowed(String command) {
            assertAllowed("bash", command);
        }
    }

    @Nested
    @DisplayName("Sed, cat and grep-family calls cannot bypass dedicated tools")
    class SearchBan {
        @ParameterizedTest
        @ValueSource(strings = {
                "%s",
                "cd project && %s",
                "cd project&&%s",
                "cd 'project directory'; %s",
                "cd project & %s",
                "cd project&%s",
                "cd project || %s",
                "(cd project && %s)",
                "cd project && { %s; }",
                "cd project && \\\n%s",
                "cd project && sudo -n %s",
                "cd project && sudo -u root %s",
                "cd project && /usr/bin/env -u FLAG %s",
                "cd project && /usr/bin/timeout --signal TERM 1.5s %s",
                "cd project && command -- %s",
                "cd project && nice -n 5 %s",
                "cd project && stdbuf -o L %s",
                "'bash' -euc 'cd project && %s'",
                "printf hello | %s",
                "printf hello |& %s",
                "cd project && %s < input.txt",
                "cd project && %s <<< hello",
                "echo $(cd project && %s)",
                "echo `cd project && %s`"
        })
        void allCommandFormsAreBlocked(String form) throws Exception {
            for (String command : new String[]{"sed -n 1p", "grep MATCH", "egrep MATCH", "fgrep MATCH",
                    "rg MATCH", "ag MATCH", "ack MATCH", "cat"}) {
                String shell = form.formatted(command);
                String input = JsonUtils.standardMapper().writeValueAsString(Map.of("command", shell));
                for (String tool : new String[]{"bash", "mcp__kompile__bash", "process", "mcp__kompile__process"}) {
                    EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs(tool, input);
                    assertNotNull(decision, shell);
                    assertFalse(decision.isAllowed(), shell);
                    assertTrue(decision.getCorrectionPrompt().contains("`grep` tool"), shell);
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "cd project && s'e'd -n 1p input.txt",
                "cd project && \"s\"ed -n 1p input.txt",
                "cd project && se\\d -n 1p input.txt",
                "cd project && gr'e'p MATCH input.txt",
                "cd project && \"gr\"ep MATCH input.txt",
                "cd project && /usr/bin/s'e'd -n 1p input.txt",
                "bash -c 'cd project && '\"sed -n 1p input.txt\"",
                "cd project && c'a't < input.txt",
                "cd project && /bin/cat -",
                "cd project && cat <<'EOF'\nhello\nEOF",
                "cd project && grep MATCH -",
                "cd project && grep MATCH <<'EOF'\nhello\nEOF"
        })
        void quotedNamesAndStdinAreStillBlocked(String command) {
            assertBlocked("bash", command);
            assertBlocked("process", command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "cd project && echo sed grep rg",
                "cd project && printf '%s' 'sed -n 1p input.txt'",
                "printf '%s' 'cd project && grep MATCH input.txt'",
                "printf '%s' 'cd project & sed -n 1p input.txt'",
                "cd project && command -v sed",
                "cd project && command -v grep",
                "cd project && mvn test 2>&1",
                "cd project & printf hello",
                "bash -c 'printf \"%s\" hello'"
        })
        void mentionsAndSystemCommandsRemainAllowed(String command) {
            assertAllowed("bash", command);
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
                "mvn test",
                "mvn -q test 2>&1",
                "ps aux",
                "ps aux | awk '{print $2}'",
                "git log --oneline -5",
                "jcmd 1 Thread.print",
                "echo hello",
                "printf '%s\\n' a b",
                "docker logs c 2>&1",
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

        @ParameterizedTest
        @ValueSource(strings = {
                "cd project && grep -rn MATCH src/",
                "cd 'project directory'&&grep MATCH src/Main.java",
                "cd project && /usr/bin/grep MATCH src/Main.java",
                "cd project && command grep MATCH src/Main.java",
                "bash -lc 'cd project && grep MATCH src/Main.java'",
                "cd project && \\\ngrep MATCH src/Main.java",
                "cd project && gr\\\nep MATCH src/Main.java",
                "cd project && \"gr\\\nep\" MATCH src/Main.java",
                "cd project && \\\n env LC_ALL=C grep MATCH src/Main.java",
                "bash -lc 'cd project && \\\ngrep MATCH src/Main.java'"
        })
        void directoryChangeDoesNotHideFileSearch(String command) throws Exception {
            for (String tool : new String[]{"bash", "mcp__kompile__bash", "process", "mcp__kompile__process"}) {
                String input = JsonUtils.standardMapper().writeValueAsString(Map.of("command", command));
                EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs(tool, input);
                assertNotNull(decision, command);
                assertFalse(decision.isAllowed(), command);
                assertTrue(decision.getViolations().get(0).contains("`grep` tool"), command);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "cd project && mvn test | awk '/ERROR/'",
                "cd project && \\\n mvn test | awk '/ERROR/'",
                "cd project && printf '%s' 'grep MATCH src/'",
                "cd project && mvn test"
        })
        void directoryChangesAndStreamOnlyFiltersStayAllowed(String command) {
            assertAllowed("bash", command);
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
            assertAllowed("bash", "mvn test 2>&1 | awk '/ERROR/'; git status");
        }

        @Test
        void quotedPipeDoesNotSplitSegments() {
            // A quoted '|' belongs to a sed script / grep pattern, not the pipeline.
            assertAllowed("bash", "mvn test | awk '/foo|bar/'");
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
            String args = "{\"command\":\"mvn test\",\"description\":\"run tests\"}";
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
    @DisplayName("Shell loops are blocked, including until without sleep")
    class ShellLoops {
        @ParameterizedTest
        @ValueSource(strings = {
                "until test -f ready; do :; done",
                "until false; do true; done",
                "until ! kill -0 $pid 2>/dev/null; do :; done",
                "\\\n until false; do :; done",
                "until [ -e ready ]; do sleep 1; done",
                "until test -f ready\ndo\n  :\ndone",
                "while true; do :; done",
                "for item in a b; do true; done",
                "for ((i=0; i<3; i++)); do true; done",
                "select item in a b; do break; done",
                "true && until false; do :; done",
                "if true; then until false; do :; done; fi",
                "(until false; do :; done)",
                "{ until false; do :; done; }",
                "bash -c 'until false; do :; done'",
                "env FLAG=1 /bin/sh -lc 'until false; do :; done'",
                "echo $(until false; do :; done)",
                "echo `until false; do :; done`"
        })
        void blockedForms(String command) {
            assertBlocked("bash", command);
            assertBlocked("mcp__kompile__process", command);
            assertTrue(ShellMandatePolicy.containsShellLoop(command));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "echo until while for select",
                "printf '%s' 'until false; do :; done'",
                "printf '%s' \"while true; do :; done\"",
                "mvn test -Dlabel=until",
                "bash -c 'echo until'",
                "git status --short"
        })
        void loopWordsInArgumentsAreAllowed(String command) {
            assertAllowed("bash", command);
            assertFalse(ShellMandatePolicy.containsShellLoop(command));
        }

        @ParameterizedTest
        @ValueSource(strings = {"monitor", "status", "output", "stream"})
        void processMonitoringRemainsAllowed(String action) {
            assertNull(ShellMandatePolicy.evaluateFromSerializedArgs(
                    "mcp__kompile__process", "{\"action\":\"" + action + "\",\"process_id\":\"build\"}"));
        }

        @Test
        void serializedUntilLoopRoutesToHostMonitoring() {
            EnforcerToolCallDecision decision = ShellMandatePolicy.evaluateFromSerializedArgs(
                    "mcp__kompile__bash", "{\"command\":\"until false; do :; done\"}");
            assertNotNull(decision);
            assertTrue(decision.getViolations().get(0).contains("until"));
            assertTrue(decision.getCorrectionPrompt().contains("action=monitor"));
        }
    }

    @Nested
    @DisplayName("Sleep is banned — waiting must go through process monitors")
    class SleepBan {
        @ParameterizedTest
        @ValueSource(strings = {
                "sleep 30",
                "sleep 0.5",
                "sleep 1h30m",
                "sleep 500ms",
                "sleep 30s && mvn test",
                "mvn test; sleep 5; git status",
                "nohup sleep 60 &",
                "timeout 10 sleep 5",
                "sleep 5 | cat",
                "usleep 1000",
                "bash -c 'sleep 5'",
                "echo $(sleep 1)",
                "at now + 5 minutes -f job.sh",
                "at 09:30 -f job.sh"
        })
        void blockedForms(String command) {
            assertBlocked("bash", command);
        }

        @Test
        void violationPointsAtProcessMonitors() {
            EnforcerToolCallDecision decision = eval("bash", "sleep 30");
            assertTrue(decision.getViolations().get(0).contains("process"),
                    () -> decision.blockMessage());
        }

        @Test
        void plainAtWithoutTimeSpecPasses() {
            assertAllowed("bash", "at -l");
        }

        @Test
        void correctionPromptNamesMonitorsAndPolling() {
            EnforcerToolCallDecision decision = eval("bash", "sleep 30");
            assertTrue(decision.getCorrectionPrompt().contains("action=monitor"),
                    decision::getCorrectionPrompt);
        }

        @Test
        void similarlyNamedBinariesAreNotOverBlocked() {
            assertAllowed("bash", "sleepless daemon");
        }
    }

    @Nested
    @DisplayName("Stream slicing with head/tail is banned — page via dedicated paths")
    class StreamSlicers {
        @ParameterizedTest
        @ValueSource(strings = {
                "mvn test 2>&1 | tail -30",
                "mvn test | head -20",
                "git log --oneline -10 | head -5",
                "git log | head -20",
                "find . | head",
                "ls | tail -5",
                "jcmd 1 Thread.print | tail -50",
                "echo hi | head -1",
                "git diff | head -40",
                "git log --oneline | head -5 | wc -l"
        })
        void blockedForms(String command) {
            assertBlocked("bash", command);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "mvn test",                                  // builds remain legal
                "ps aux | awk '{print $2}'",                 // awk filter unchanged
                "git log --oneline -5",                      // native limit flag
                "git status | wc -l",                        // non-slicing filter unchanged
                "head -1 <<< \"$var\"",                      // inlined text, not fishing a stream
                "tail -3 -",                                 // explicit stdin source
        })
        void allowedForms(String command) {
            assertAllowed("bash", command);
        }

        @Test
        void violationNamesThePagingPaths() {
            EnforcerToolCallDecision decision = eval("bash", "mvn test 2>&1 | tail -30");
            String message = decision.getViolations().get(0);
            assertTrue(message.contains("fetch_result") && message.contains("tail_lines"), message);
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

        @ParameterizedTest
        @ValueSource(strings = {
                "mvn test | sed -n '/ERROR/p'",
                "cd project && sed -n 1p src/Main.java",
                "cd project & sed -n 1p src/Main.java",
                "cd project && sudo -n sed -n 1p src/Main.java",
                "cd project && s'e'd -n 1p src/Main.java",
                "cd project && mvn test | grep ERROR",
                "cd project && grep MATCH src/Main.java",
                "cd project && \\\ngrep MATCH src/Main.java"
        })
        void shellSearchIsBlockedAcrossEnforcerLanesWithoutAJudge(String command) throws Exception {
            String input = JsonUtils.standardMapper().writeValueAsString(Map.of("command", command));
            EnforcerPolicy policy = new EnforcerPolicy("", 1, false);
            try (EnforcerToolCallGuard guard = guardWithRules("Use tools relevant to the request.")) {
                assertFalse(guard.evaluate("bash", Map.of("command", command)).isAllowed());
                assertFalse(guard.evaluate("process", Map.of("action", "launch", "command", command)).isAllowed());
                assertTrue(guard.describe().contains("lazy"), "hard block must not build the judge");
            }
            KeywordEnforcerEvaluator evaluator = new KeywordEnforcerEvaluator(java.util.List.of(), "");
            assertFalse(evaluator.evaluateToolCall("bash", input, policy).isAllowed());
            JudgeBackend unavailable = new JudgeBackend() {
                @Override
                public String generate(String userPrompt, String systemPrompt) {
                    throw new AssertionError("shell search must be blocked before calling the judge");
                }

                @Override
                public boolean isAvailable() {
                    return false;
                }
            };
            EnforcerJudge judge = new EnforcerJudge(unavailable, JsonUtils.standardMapper());
            try {
                assertFalse(judge.evaluateToolCall("bash", input, policy).isAllowed());
            } finally {
                judge.close();
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "printf hello | sed -n '1p'",
                "cd project && sed -n 1p src/Main.java",
                "cd project & sed -n 1p src/Main.java",
                "cd project && sudo -n sed -n 1p src/Main.java",
                "cd project && s'e'd -n 1p src/Main.java",
                "cd project && mvn test | grep ERROR",
                "cd project && grep MATCH src/Main.java",
                "cd project && \\\ngrep MATCH src/Main.java"
        })
        void executionBoundariesRejectShellSearchWithoutAnEnforcerOrPermissions(String command) throws Exception {
            var params = JsonUtils.standardMapper().createObjectNode();
            params.put("command", command);
            // Null contexts prove rejection happens before permission checks or process execution.
            ToolResult bash = new BashTool().execute(params, null);
            assertTrue(bash.isError());
            assertTrue(bash.getOutput().contains("`grep` tool"));

            params.put("action", "launch");
            ToolResult process = new ProcessManagementTool(null).execute(params, null);
            assertTrue(process.isError());
            assertTrue(process.getOutput().contains("`grep` tool"));
        }

        @Test
        void guardBlocksSleepWithoutJudgeOrRules() throws Exception {
            try (EnforcerToolCallGuard guard = guardWithRules("Use tools relevant to the request.")) {
                assertFalse(guard.evaluate("bash", Map.of("command", "sleep 30")).isAllowed(),
                        "sleep must be hard-blocked by the deterministic mandate");
            }
        }

        @Test
        void guardBlocksUntilLoopWithoutStartingJudge() throws Exception {
            try (EnforcerToolCallGuard guard = guardWithRules("Use tools relevant to the request.")) {
                assertFalse(guard.evaluate("bash",
                        Map.of("command", "until false; do :; done")).isAllowed());
                assertTrue(guard.describe().contains("lazy"));
            }
            KeywordEnforcerEvaluator evaluator = new KeywordEnforcerEvaluator(java.util.List.of(), "");
            assertFalse(evaluator.evaluateToolCall("bash",
                    "{\"command\":\"until false; do :; done\"}",
                    new EnforcerPolicy("", 1, false)).isAllowed());
        }

        @Test
        void guardAllowsCompliantCommandsAfterInspectingTheirArguments() throws Exception {
            try (EnforcerToolCallGuard guard = guardWithInMemoryAllowJudge(
                    "Use tools relevant to the request.")) {
                assertTrue(guard.evaluate("bash", Map.of("command", "mvn test")).isAllowed());
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
        void inactiveJudgeDoesNotDisableShellMandate() throws Exception {
            ObjectMapper om = JsonUtils.standardMapper();
            EnforcerRuntimePolicy runtimePolicy = EnforcerRuntimePolicy.create(wd,
                    new EnforcerPolicy("rules", 1, false), new HarnessConfig(), om);
            runtimePolicy.setEnabled(false, om);
            try (EnforcerToolCallGuard guard = new EnforcerToolCallGuard(om, runtimePolicy)) {
                assertFalse(guard.isActive());
                assertFalse(guard.evaluate("bash", Map.of("command", "cat pom.xml")).isAllowed());
                assertFalse(guard.evaluate("bash", Map.of("cmd", "cd project && sed -n 1p input.txt")).isAllowed());
                assertFalse(guard.evaluate("bash", Map.of("command", "until false; do :; done")).isAllowed());
                assertFalse(guard.evaluate("mcp__kompile__process", Map.of(
                        "action", "launch", "command", "until false; do :; done")).isAllowed());
                assertTrue(guard.evaluate("mcp__kompile__process", Map.of(
                        "action", "monitor", "process_id", "build")).isAllowed());
                assertTrue(guard.evaluate("bash", Map.of("command", "mvn test")).isAllowed());
                EnforcerToolCallDecision memory = guard.evaluate("Write", Map.of(
                        "file_path", ".claude/projects/-repo/memory/MEMORY.md", "content", "note"));
                assertFalse(memory.isAllowed());
                assertTrue(memory.getCorrectionPrompt().contains("mcp__kompile__memory"));
                assertTrue(guard.evaluate("mcp__kompile__memory", Map.of(
                        "action", "save", "content", "note")).isAllowed());
                assertTrue(guard.describe().contains("lazy"));
            }
        }

        @Test
        void keywordLaneAlsoEnforcesMandate() {
            KeywordEnforcerEvaluator evaluator = new KeywordEnforcerEvaluator(java.util.List.of(), "");
            EnforcerPolicy policy = new EnforcerPolicy("", 1, false);
            assertFalse(evaluator.evaluateToolCall("bash",
                    "{\"command\":\"grep -rn TODO src/\"}", policy).isAllowed());
            assertFalse(evaluator.evaluateToolCall("bash",
                    "{\"command\":\"ps aux | grep java\"}", policy).isAllowed());
            assertFalse(evaluator.evaluateToolCall("Edit",
                    "{\"file_path\":\".claude/projects/-repo/memory/feedback.md\",\"new_string\":\"note\"}",
                    policy).isAllowed());
        }
    }
}
