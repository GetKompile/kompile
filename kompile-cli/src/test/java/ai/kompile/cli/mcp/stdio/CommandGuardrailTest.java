/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.mcp.stdio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CommandGuardrail} — command filtering for spawned subagent processes.
 */
class CommandGuardrailTest {

    @TempDir
    Path tempDir;

    // ── Default rules ────────────────────────────────────────────────────

    @Test
    void defaultRulesDenyDestructiveGitCommands() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        // Should deny
        assertDenied(guardrail, "Run git reset --hard HEAD~1 to undo the last commit");
        assertDenied(guardrail, "Use git checkout -- src/main/java/Foo.java to revert changes");
        assertDenied(guardrail, "git clean -fd to remove untracked files");
        assertDenied(guardrail, "git push --force to overwrite remote");
    }

    @Test
    void defaultRulesDenyCcacheClear() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        assertDenied(guardrail, "Run ccache -C to clear the cache");
        assertDenied(guardrail, "Execute ccache --clear before building");
    }

    @Test
    void defaultRulesDenyDirectMake() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        assertDenied(guardrail, "Run make -j8 to compile the project");
        assertDenied(guardrail, "Execute make clean && make");
    }

    @Test
    void defaultRulesDenyTailOnLogs() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        assertDenied(guardrail, "tail -30 build.log to check output");
        assertDenied(guardrail, "Run tail -f /tmp/test.log for progress");
        assertDenied(guardrail, "Check with tail output.txt");
    }

    @Test
    void defaultRulesDenyProcessKilling() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        assertDenied(guardrail, "killall java to stop all running processes");
        assertDenied(guardrail, "pkill java to terminate");
        assertDenied(guardrail, "pkill -f java to match all");
    }

    @Test
    void defaultRulesDenyLdPreload() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        assertDenied(guardrail, "LD_PRELOAD=/usr/lib/libjemalloc.so mvn test");
    }

    @Test
    void defaultRulesDenyInlineExportBeforeMvn() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        assertDenied(guardrail, "export JAVA_HOME=/usr/lib/jvm/java-17 && mvn test");
    }

    @Test
    void defaultRulesAllowSafeCommands() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        assertAllowed(guardrail, "mvn clean install -DskipTests");
        assertAllowed(guardrail, "git status");
        assertAllowed(guardrail, "git add src/main/java/Foo.java");
        assertAllowed(guardrail, "git commit -m 'fix bug'");
        assertAllowed(guardrail, "git push origin feature-branch");
        assertAllowed(guardrail, "cat build.log");
    }

    @Test
    void defaultRulesWarnOnGitStash() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());

        CommandGuardrail.CheckResult result = guardrail.check("git stash to save changes temporarily");
        assertTrue(result.isAllowed()); // warn, not deny
        assertFalse(result.getWarnings().isEmpty());
    }

    // ── Custom rules ─────────────────────────────────────────────────────

    @Test
    void customLiteralRuleDenies() {
        List<CommandGuardrail.GuardrailRule> rules = List.of(
            new CommandGuardrail.GuardrailRule("rm -rf /", "Never delete root", CommandGuardrail.Action.DENY, false)
        );
        CommandGuardrail guardrail = CommandGuardrail.withRules(rules);

        assertDenied(guardrail, "Run rm -rf / to clean everything");
        assertAllowed(guardrail, "rm -rf build/");
    }

    @Test
    void customRegexRuleDenies() {
        List<CommandGuardrail.GuardrailRule> rules = List.of(
            new CommandGuardrail.GuardrailRule("sudo\\s+", "No sudo allowed", CommandGuardrail.Action.DENY, true)
        );
        CommandGuardrail guardrail = CommandGuardrail.withRules(rules);

        assertDenied(guardrail, "sudo apt-get install something");
        assertAllowed(guardrail, "Install without elevated privileges");
    }

    @Test
    void warnRuleDoesNotBlock() {
        List<CommandGuardrail.GuardrailRule> rules = List.of(
            new CommandGuardrail.GuardrailRule("docker", "Docker usage noted", CommandGuardrail.Action.WARN, false)
        );
        CommandGuardrail guardrail = CommandGuardrail.withRules(rules);

        CommandGuardrail.CheckResult result = guardrail.check("Run docker build .");
        assertTrue(result.isAllowed());
        assertEquals(1, result.getWarnings().size());
    }

    @Test
    void mixedDenyAndWarnRules() {
        List<CommandGuardrail.GuardrailRule> rules = List.of(
            new CommandGuardrail.GuardrailRule("rm -rf", "Dangerous delete", CommandGuardrail.Action.DENY, false),
            new CommandGuardrail.GuardrailRule("docker", "Docker noted", CommandGuardrail.Action.WARN, false)
        );
        CommandGuardrail guardrail = CommandGuardrail.withRules(rules);

        // Both match — deny takes precedence
        CommandGuardrail.CheckResult result = guardrail.check("docker run rm -rf /data");
        assertFalse(result.isAllowed());
        assertEquals(1, result.getViolations().size());
        assertEquals(1, result.getWarnings().size());
    }

    // ── Disabled guardrail ───────────────────────────────────────────────

    @Test
    void disabledGuardrailAllowsEverything() {
        CommandGuardrail guardrail = CommandGuardrail.disabled();
        assertFalse(guardrail.isEnabled());

        assertAllowed(guardrail, "git reset --hard");
        assertAllowed(guardrail, "rm -rf /");
        assertAllowed(guardrail, "killall java");
    }

    @Test
    void nullPromptAllowed() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());
        assertTrue(guardrail.check(null).isAllowed());
    }

    // ── Config file loading ──────────────────────────────────────────────

    @Test
    void loadFromProjectConfig() throws Exception {
        Path configDir = tempDir.resolve(".kompile").resolve("config");
        Files.createDirectories(configDir);
        Path configFile = configDir.resolve("command-guardrails.json");

        String json = """
            {
              "enabled": true,
              "rules": [
                {"pattern": "drop table", "description": "No SQL drops", "action": "DENY", "isRegex": false}
              ]
            }
            """;
        Files.writeString(configFile, json);

        CommandGuardrail guardrail = CommandGuardrail.load(tempDir);
        assertTrue(guardrail.isEnabled());
        assertEquals(1, guardrail.getRules().size());
        assertDenied(guardrail, "Run drop table users");
    }

    @Test
    void loadFallsBackToDefaultsWhenNoConfig() {
        CommandGuardrail guardrail = CommandGuardrail.load(tempDir);
        assertTrue(guardrail.isEnabled());
        assertFalse(guardrail.getRules().isEmpty());
    }

    // ── Prompt injection ─────────────────────────────────────────────────

    @Test
    void buildPromptInjectionIncludesDenyRules() {
        CommandGuardrail guardrail = CommandGuardrail.withRules(CommandGuardrail.defaultRules());
        String injection = guardrail.buildPromptInjection();

        assertFalse(injection.isEmpty());
        assertTrue(injection.contains("Command Guardrails"));
        assertTrue(injection.contains("BANNED"));
        assertTrue(injection.contains("git reset --hard"));
    }

    @Test
    void disabledGuardrailReturnsEmptyInjection() {
        CommandGuardrail guardrail = CommandGuardrail.disabled();
        assertEquals("", guardrail.buildPromptInjection());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void assertDenied(CommandGuardrail guardrail, String prompt) {
        CommandGuardrail.CheckResult result = guardrail.check(prompt);
        assertFalse(result.isAllowed(), "Expected DENIED for: " + prompt);
        assertFalse(result.getViolations().isEmpty(), "Expected violations for: " + prompt);
    }

    private void assertAllowed(CommandGuardrail guardrail, String prompt) {
        CommandGuardrail.CheckResult result = guardrail.check(prompt);
        assertTrue(result.isAllowed(), "Expected ALLOWED for: " + prompt
                + " but got violations: " + result.getViolations());
    }
}
