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

package ai.kompile.cli.main.chat.enforcer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeControlTest {

    @TempDir
    Path sessionsDir;

    @Test
    void guidancePersistsAcrossInstancesAndSessions() {
        JudgeControl first = new JudgeControl("judge-ctl-a", sessionsDir);
        assertFalse(first.hasGuidance());

        first.setGuidance("File edits in src/ are approved; never block on imports.");
        assertTrue(first.hasGuidance());

        JudgeControl second = new JudgeControl("judge-ctl-a", sessionsDir);
        assertEquals("File edits in src/ are approved; never block on imports.",
                second.getGuidance(), "guidance must survive a restart");

        second.clearGuidance();
        JudgeControl third = new JudgeControl("judge-ctl-a", sessionsDir);
        assertFalse(third.hasGuidance());
    }

    @Test
    void beginTurnConsumesOneShotOverrideExactlyOnce() {
        JudgeControl control = new JudgeControl("judge-ctl-b", sessionsDir);
        control.setGuidance("prefer junit asserts");
        control.setOverrideNext(true);

        JudgeControl.TurnSnapshot armed = control.beginTurn();
        assertTrue(armed.enabled());
        assertTrue(armed.reportOnly(), "armed override applies to this turn");
        assertTrue(armed.hasGuidance());
        assertEquals("prefer junit asserts", armed.guidance());

        JudgeControl.TurnSnapshot consumed = control.beginTurn();
        assertFalse(consumed.reportOnly(),
                "override is one-shot: the next turn must be fully enforced again");
        assertTrue(consumed.hasGuidance(), "guidance is durable across turns");
        assertFalse(control.isOverrideNextSet());
    }

    @Test
    void disarmBeforeTurnRestoresFullEnforcement() {
        JudgeControl control = new JudgeControl("judge-ctl-c", sessionsDir);
        control.setOverrideNext(true);
        control.setOverrideNext(false);

        assertFalse(control.beginTurn().reportOnly());
    }

    @Test
    void blankGuidanceIsNormalizedToAbsent() {
        JudgeControl control = new JudgeControl("judge-ctl-d", sessionsDir);
        control.setGuidance("   \n  \t ");
        assertFalse(control.hasGuidance());
        assertFalse(control.beginTurn().hasGuidance());
    }

    @Test
    void sessionSwitchIsCapturedPerTurnAndNeverPersisted() {
        JudgeControl control = new JudgeControl("judge-ctl-enabled", sessionsDir);
        control.setOverrideNext(true);
        control.setEnabled(false);

        JudgeControl.TurnSnapshot disabled = control.beginTurn();
        assertFalse(disabled.enabled());
        assertFalse(disabled.reportOnly(), "disabling clears a stale one-shot override");

        JudgeControl restarted = new JudgeControl("judge-ctl-enabled", sessionsDir);
        assertTrue(restarted.isEnabled(), "session off must not silently survive a restart");
    }

    @Test
    void commandApprovalIsExactTurnScopedAndNotPersistent() {
        JudgeControl control = new JudgeControl("approval", sessionsDir);
        control.approveCommandNext("rm -rf build-cache");
        assertEquals("", new JudgeControl("approval", sessionsDir).getApprovedCommandNext());
        JudgeControl.TurnSnapshot turn = control.beginTurn();
        assertTrue(turn.approvesCommand("bash", "{\"command\":\"rm -rf build-cache\"}"));
        assertFalse(turn.approvesCommand("bash", "{\"command\":\"rm -rf build-cache; rm other\"}"));
        assertFalse(turn.approvesCommand("process", "{\"command\":\"rm -rf build-cache\"}"));
        assertFalse(turn.approvesCommand("bash", "{\"command\":\"rm -rf other\"}"));
        assertTrue(turn.guidance().contains("explicitly approved"));
        assertEquals("", control.beginTurn().approvedCommand());
        assertFalse(control.hasGuidance(), "approval must not become durable guidance");
        control.approveCommandNext("rm -rf build-cache");
        control.setEnabled(false);
        assertEquals("", control.beginTurn().approvedCommand());
        control.approveCommandNext("rm -rf build-cache");
        control.approveCommandNext("");
        assertEquals("", control.beginTurn().approvedCommand());
    }

    @Test
    void tokenPatternsGeneralizeArgumentsButNotShellStructure() throws Exception {
        JudgeControl control = new JudgeControl("pattern", sessionsDir);
        control.approvePatternNext("git log **");
        JudgeControl.TurnSnapshot turn = control.beginTurn();
        for (String command : java.util.List.of("git log", "git  log --oneline -20", "git log --format='author name'")) {
            assertTrue(turn.approvesCommand("bash", new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(java.util.Map.of("command", command))), command);
        }
        for (String command : java.util.List.of("git revert HEAD", "git log; rm other", "git log && git revert HEAD",
                "git log | bash", "git log > out", "git log $(touch file)", "git log\nrm other",
                "git log `touch file`", "git log *", "git\u2003log", "git log --format='unterminated")) {
            assertFalse(turn.approvesCommand("bash", new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(java.util.Map.of("command", command))), command);
        }
        assertEquals("", control.beginTurn().approvedCommand());
        assertFalse(new JudgeControl("pattern", sessionsDir).isApprovalPatternNext());
    }

    @Test
    void argumentPatternsCannotWidenPathsOrAddArguments() {
        CommandApprovalPattern pattern = new CommandApprovalPattern("rm -rf target/cache-*");
        assertTrue(pattern.matches("rm -rf target/cache-123"));
        assertTrue(pattern.matches("rm  -rf 'target/cache-test run'"));
        assertFalse(pattern.matches("rm -rf target/cache-123 other"));
        assertFalse(pattern.matches("rm -rf target/cache-123/nested"));
        assertFalse(pattern.matches("rm -rf target/cache-123/../../other"));
        assertFalse(new CommandApprovalPattern("rm -rf target/*").matches("rm -rf target/.."));
        assertFalse(pattern.matches("sudo rm -rf target/cache-123"));
        assertFalse(pattern.matches("rm -rf target/cache-*"));
        for (String invalid : java.util.List.of("", "'' **", "**", "g* log **", "git ** log", "git log; rm *", "FOO=bar git log **")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new CommandApprovalPattern(invalid), invalid);
        }
    }

    @Test
    void invalidPatternDoesNotReplaceApprovalAndExactModeResetsIt() {
        JudgeControl control = new JudgeControl("pattern-reset", sessionsDir);
        control.approvePatternNext("git log **");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> control.approvePatternNext("*"));
        assertEquals("git log **", control.getApprovedCommandNext());
        control.approveCommandNext("git show HEAD");
        assertFalse(control.isApprovalPatternNext());
        assertTrue(control.beginTurn().approvesCommand("bash", "{\"command\":\"git show HEAD\"}"));
        control.approvePatternNext("git log **");
        control.setEnabled(false);
        assertFalse(control.isApprovalPatternNext());
    }

    @Test
    void loadResolvesUnderTheStandardSessionsRoot() {
        JudgeControl control = JudgeControl.load("judge-ctl-root-check");
        assertTrue(control.getFile().toString()
                .contains(java.nio.file.Paths.get(".kompile", "sessions").toString()));
        assertTrue(control.getFile().getFileName().toString().equals("judge-control.json"));
    }
}
