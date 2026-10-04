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
import java.util.List;

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
    void userDecisionsAreRecordedOnceEachInTheSessionJudgementLog() {
        JudgeControl control = new JudgeControl("judge-ctl-log", sessionsDir);
        control.initEnabled(false);
        control.initEnabled(true);
        control.setGuidance("prefer junit asserts");
        control.setGuidance("prefer junit asserts");
        control.setOverrideNext(true);
        control.setOverrideNext(true);
        control.clearOverrideNext();
        control.approveCommandNext("rm -rf build-cache");
        control.approvePatternNext("git log **");
        control.approveCommandNext("");
        control.setOverrideNext(true);
        control.setEnabled(false);
        control.setEnabled(false);
        control.clearGuidance();

        List<JudgementRecord> records = readLog("judge-ctl-log");
        assertEquals(List.of("GUIDANCE_SET", "OVERRIDE_ARMED", "OVERRIDE_DISARMED", "APPROVAL_SET",
                        "APPROVAL_SET", "APPROVAL_CLEARED", "OVERRIDE_ARMED", "JUDGE_DISABLED", "GUIDANCE_CLEARED"),
                records.stream().map(JudgementRecord::getStatus).toList(),
                "startup defaults and repeated no-op calls must not be recorded");
        for (JudgementRecord record : records) {
            assertEquals("CONTROL", record.getPhase());
            assertEquals("user", record.getJudgeMode());
            assertEquals("judge-ctl-log", record.getSessionId());
            assertTrue(record.isCompliant());
            assertFalse(record.isStop());
        }
        assertTrue(records.get(3).getReasoning().endsWith("Exact bash command approved for the next turn: rm -rf build-cache"));
        assertTrue(records.get(4).getReasoning().endsWith("Bash token pattern approved for the next turn: git log **"));
        assertTrue(records.get(5).getReasoning().contains("bash token pattern approval cancelled: git log **"));
        assertTrue(records.get(7).getReasoning().endsWith("discarded the pending report-only override"));
    }

    @Test
    void decisionsThatLetWorkThroughAreRecordedAsOverrides() {
        JudgeControl control = new JudgeControl("judge-ctl-override", sessionsDir);
        control.approvePatternNext("git log **");
        JudgeControl.TurnSnapshot turn = control.beginTurn();
        control.recordApprovalUsed(turn, "bash", "{\"command\":\"git log -5\"}");
        control.recordReportOnlyOverride(EnforcerDecision.stop(List.of("deleted tests"), "tests must stay"));
        control.recordReportOnlyOverride("edit", "{\"file_path\":\"a\"}", EnforcerToolCallDecision.block("outside scope"));
        control.recordReportOnlyOverride(EnforcerDecision.fail(List.of("long"), "", "r".repeat(5_000)));

        List<JudgementRecord> overrides = readLog("judge-ctl-override").stream()
                .filter(r -> "OVERRIDE".equals(r.getPhase()))
                .toList();
        assertEquals(4, overrides.size());
        overrides.forEach(r -> assertEquals("user", r.getJudgeMode()));

        JudgementRecord approved = overrides.get(0);
        assertEquals("APPROVED", approved.getStatus());
        assertTrue(approved.isCompliant());
        assertEquals("bash", approved.getToolName());
        assertEquals("{\"command\":\"git log -5\"}", approved.getAgentOutputExcerpt());
        assertTrue(approved.getReasoning().endsWith("bash token pattern approval let this call through without judge review: git log **"));

        JudgementRecord stoppedTurn = overrides.get(1);
        assertEquals("OVERRIDDEN", stoppedTurn.getStatus());
        assertFalse(stoppedTurn.isCompliant(), "the neutralized verdict stays visible");
        assertFalse(stoppedTurn.isStop(), "the override let the turn finish");
        assertEquals("critical", stoppedTurn.getSeverity());
        assertEquals(List.of("deleted tests"), stoppedTurn.getViolations());
        assertTrue(stoppedTurn.getReasoning().endsWith("would have stopped it: tests must stay"));

        JudgementRecord blockedCall = overrides.get(2);
        assertEquals("OVERRIDDEN", blockedCall.getStatus());
        assertEquals("edit", blockedCall.getToolName());
        assertEquals("{\"file_path\":\"a\"}", blockedCall.getAgentOutputExcerpt());
        assertEquals(List.of("outside scope"), blockedCall.getViolations());
        assertTrue(blockedCall.getReasoning().endsWith("would have blocked it: outside scope"));

        String corrected = overrides.get(3).getReasoning();
        assertTrue(corrected.contains("would have corrected it: rrr"));
        assertTrue(corrected.length() < 1_300 && corrected.endsWith("more)"), "reasoning is clamped like the excerpts");
    }

    private List<JudgementRecord> readLog(String sessionId) {
        return JudgementLog.readFile(sessionsDir.resolve(sessionId).resolve(JudgementLog.FILE_NAME));
    }

    @Test
    void loadResolvesUnderTheStandardSessionsRoot() {
        JudgeControl control = JudgeControl.load("judge-ctl-root-check");
        assertTrue(control.getFile().toString()
                .contains(java.nio.file.Paths.get(".kompile", "sessions").toString()));
        assertTrue(control.getFile().getFileName().toString().equals("judge-control.json"));
    }
}
