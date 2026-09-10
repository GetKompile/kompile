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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerJudgementSupportTest {

    @Test
    void fallbackPolicyParse() {
        assertEquals(EnforcerFallbackPolicy.FAIL_OPEN, EnforcerFallbackPolicy.parse(null));
        assertEquals(EnforcerFallbackPolicy.FAIL_OPEN, EnforcerFallbackPolicy.parse(""));
        assertEquals(EnforcerFallbackPolicy.FAIL_OPEN, EnforcerFallbackPolicy.parse("fail_open"));
        assertEquals(EnforcerFallbackPolicy.FAIL_OPEN, EnforcerFallbackPolicy.parse("FAIL-OPEN"));
        assertEquals(EnforcerFallbackPolicy.FAIL_CLOSED, EnforcerFallbackPolicy.parse("closed"));
        assertEquals(EnforcerFallbackPolicy.DEGRADE_TO_KEYWORD, EnforcerFallbackPolicy.parse("keyword"));
        assertEquals(EnforcerFallbackPolicy.FAIL_OPEN, EnforcerFallbackPolicy.parse("nonsense"));
        assertEquals("fail_open", new EnforcerConfig().getJudgeFallbackPolicy());
    }

    @Test
    void invalidTurnJudgeJsonFailsOpen() {
        EnforcerDecision decision = EnforcerDecision.parse(new ObjectMapper(), "looks good");

        assertTrue(decision.isCompliant());
        assertFalse(decision.isStop());
    }

    @Test
    void malformedTurnJudgeJsonFailsOpen() {
        EnforcerDecision decision = EnforcerDecision.parse(
                new ObjectMapper(), "{\"compliant\":false");

        assertTrue(decision.isCompliant());
        assertFalse(decision.isStop());
    }

    @Test
    void vagueNoncompliantVerdictWithoutSpecificViolationFailsOpen() {
        EnforcerDecision decision = EnforcerDecision.parse(new ObjectMapper(), """
                {"compliant":false,"stop":false,"severity":"error","violations":[],
                 "correction_prompt":"Do something else","reasoning":"This seems unnecessary"}
                """);

        assertTrue(decision.isCompliant());
        assertTrue(decision.getReasoning().contains("specific rule violation"));
    }

    @Test
    void repairableVerdictWithoutCorrectionFailsOpen() {
        EnforcerDecision decision = EnforcerDecision.parse(new ObjectMapper(), """
                {"compliant":false,"stop":false,"severity":"error",
                 "violations":["Rule X was violated"],"correction_prompt":"",
                 "reasoning":"Rule X was violated"}
                """);

        assertTrue(decision.isCompliant());
        assertTrue(decision.getReasoning().contains("actionable correction"));
    }

    @Test
    void noncriticalStopIsDowngradedAndMustStillBeActionable() {
        EnforcerDecision decision = EnforcerDecision.parse(new ObjectMapper(), """
                {"compliant":false,"stop":true,"severity":"error",
                 "violations":["Repairable format mismatch"],"correction_prompt":"Use JSON",
                 "reasoning":"The format is repairable"}
                """);

        assertFalse(decision.isCompliant());
        assertFalse(decision.isStop());
        assertEquals("Use JSON", decision.getCorrectionPrompt());
    }

    @Test
    void enforcementEnabledDetection() {
        EnforcerConfig empty = new EnforcerConfig();
        assertFalse(empty.isEnforcementEnabled(), "a fresh config with no rules is not enforcing");

        EnforcerConfig keyword = new EnforcerConfig();
        keyword.setKeywordMode(true);
        assertTrue(keyword.isEnforcementEnabled());

        EnforcerConfig inline = new EnforcerConfig();
        inline.setInlineRules("Always be concise");
        assertTrue(inline.isEnforcementEnabled(), "an LLM-mode config with rules IS enforcing (the revived gate)");

        EnforcerConfig bans = new EnforcerConfig();
        bans.setBannedCommands(List.of("rm -rf"));
        assertTrue(bans.isEnforcementEnabled());
    }

    @Test
    void shouldActivateUsesProjectConfigWithoutStartupPrompt() {
        EnforcerConfig projectActive = new EnforcerConfig();
        projectActive.setKeywordMode(true); // a project config that would otherwise enforce

        // No startup prompt: an active project policy is enough unless this run has
        // an explicit legacy opt-out from the setup wizard.
        assertTrue(EnforcerConfig.shouldActivate(null, false, projectActive));
        assertTrue(EnforcerConfig.shouldActivate(Boolean.TRUE, false, projectActive));

        // An explicit session "N" must win over a project config on disk.
        assertFalse(EnforcerConfig.shouldActivate(Boolean.FALSE, false, projectActive),
                "a stale .kompile/enforcer-config.json must not override the session opt-out");

        // Explicit CLI rule flags (--rules/--rule-file) remain authoritative.
        assertTrue(EnforcerConfig.shouldActivate(Boolean.FALSE, true, projectActive));
        assertTrue(EnforcerConfig.shouldActivate(null, true, null));

        // Nothing on disk and no flags → nothing to enforce, regardless of the answer.
        assertFalse(EnforcerConfig.shouldActivate(null, false, null));
        assertFalse(EnforcerConfig.shouldActivate(Boolean.TRUE, false, new EnforcerConfig()),
                "an empty project config has nothing to enforce");
    }

    @Test
    void judgementLogRoundTrip(@TempDir Path tmp) {
        Path file = tmp.resolve("judgements.jsonl");
        JudgementLog log = new JudgementLog("sess-1", file);

        log.record(JudgementRecord.builder()
                .phase("JUDGE_TURN").attempt(1).judgeMode("llm").backend("stub")
                .compliant(false).stop(false).severity("error")
                .violations(List.of("used rm -rf")).reasoning("banned cmd")
                .judgeRawResponse("{\"compliant\":false}")
                .build());
        log.record(JudgementRecord.builder()
                .phase("RESULT").status("BLOCKED").compliant(false)
                .build());

        List<JudgementRecord> read = JudgementLog.readFile(file);
        assertEquals(2, read.size());
        assertEquals("JUDGE_TURN", read.get(0).getPhase());
        assertEquals("sess-1", read.get(0).getSessionId(), "sessionId is stamped by the log");
        assertNotNull(read.get(0).getTimestamp(), "timestamp is stamped by the log");
        assertEquals(List.of("used rm -rf"), read.get(0).getViolations());
        assertEquals("BLOCKED", read.get(1).getStatus());
    }
}
