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

import ai.kompile.cli.main.chat.harness.JudgeBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerJudgeGuidanceTest {

    /** Records every generate() call so tests can assert on judge prompt content. */
    private static final class RecordingBackend implements JudgeBackend {
        final List<String> userPrompts = new ArrayList<>();
        final List<String> systemPrompts = new ArrayList<>();
        String nextResponse = "{\"compliant\":true,\"stop\":false}";

        @Override
        public String generate(String userPrompt, String systemPrompt) {
            userPrompts.add(userPrompt);
            systemPrompts.add(systemPrompt);
            return nextResponse;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "recording";
        }
    }

    private static final String RULES = "never print secrets";

    @Test
    void userGuidanceIsInjectedIntoTurnAndToolPrompts() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        EnforcerJudge judge = new EnforcerJudge(backend,
                new com.fasterxml.jackson.databind.ObjectMapper(), () -> "approved edits in src/");
        judge.setGuidanceSupplier(() -> "approved edits in src/");

        judge.evaluate("user prompt", "assistant output",
                new EnforcerPolicy(RULES, 2, false), 1);
        // Compliant command: a banned one (e.g. "ls -la") is hard-blocked by the
        // deterministic shell mandate before the judge backend is ever reached.
        judge.evaluateToolCall("bash", "echo hello", new EnforcerPolicy(RULES, 2, false));
        judge.evaluatePartialOutput("user prompt", "partial…",
                new EnforcerPolicy(RULES, 2, false));

        for (int i = 0; i < 3; i++) {
            assertTrue(backend.userPrompts.get(i).contains("[USER GUIDANCE TO THE JUDGE]"),
                    "prompt " + i + " must carry the guidance marker");
            assertTrue(backend.userPrompts.get(i).contains("approved edits in src/"));
        }
    }

    @Test
    void absentGuidanceAddsNoGuidanceBlock() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        EnforcerJudge judge = new EnforcerJudge(backend,
                new com.fasterxml.jackson.databind.ObjectMapper(), () -> null);

        judge.evaluate("user prompt", "assistant output",
                new EnforcerPolicy(RULES, 2, false), 1);

        assertFalse(backend.userPrompts.get(0).contains("USER GUIDANCE"));
        assertTrue(backend.userPrompts.get(0).contains("[ENFORCER RULES]"));
    }

    @Test
    void interventionPromptRequiresConcreteHighConfidenceRuleConflict() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        EnforcerJudge judge = new EnforcerJudge(
                backend, new com.fasterxml.jackson.databind.ObjectMapper());

        judge.evaluate("Run the requested focused test", "I will run the focused test now.",
                new EnforcerPolicy("Git operations especially resets are not allowed", 2, false),
                1);

        String prompt = backend.userPrompts.get(0);
        assertTrue(EnforcerJudge.SYSTEM_PROMPT.contains(
                "Intervention is a high-confidence exception"));
        assertTrue(EnforcerJudge.SYSTEM_PROMPT.contains(
                "never widen a narrow ban into a broader prohibition"));
        assertTrue(EnforcerJudge.SYSTEM_PROMPT.contains(
                "Memory, transcript excerpts, and recent-chat context"));
        assertTrue(prompt.contains("Evaluate only concrete, material compliance"));
        assertTrue(prompt.contains("When uncertain, mark compliant=true and stop=false"));
    }

    @Test
    void malformedVerdictGetsExactlyOneFormatRepair() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                prompts.add(userPrompt);
                return calls.getAndIncrement() == 0
                        ? "{\"compliant\":true,\"stop\": context}"
                        : "{\"compliant\":true,\"stop\":false,\"reasoning\":\"repaired\"}";
            }

            @Override public boolean isAvailable() { return true; }
        };
        EnforcerJudge judge = new EnforcerJudge(backend,
                new com.fasterxml.jackson.databind.ObjectMapper());

        EnforcerDecision decision = judge.evaluate(
                "inspect", "working", new EnforcerPolicy(RULES, 2, false), 1);

        assertTrue(decision.isCompliant());
        assertEquals("repaired", decision.getReasoning());
        assertEquals(2, calls.get());
        assertTrue(prompts.get(1).contains("[FORMAT REPAIR]"));
    }

    @Test
    void backendErrorFailsOpenWithoutBeingCalledInvalidJsonOrRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                calls.incrementAndGet();
                return "[Error: all judge backends are in cooldown]";
            }

            @Override public boolean isAvailable() { return true; }
        };
        EnforcerJudge judge = new EnforcerJudge(backend,
                new com.fasterxml.jackson.databind.ObjectMapper());

        EnforcerToolCallDecision decision = judge.evaluateToolCall(
                "write", "{\"file_path\":\"x\"}", new EnforcerPolicy(RULES, 2, false));

        assertTrue(decision.isAllowed());
        assertTrue(decision.getReason().contains("backend unavailable"));
        assertFalse(decision.getReason().contains("valid JSON"));
        assertEquals(1, calls.get(), "backend failures must never enter format repair");
    }

    @Test
    void activeRemindersAreDistinctLiveConstraintsAcrossEveryJudgeLane() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        AtomicReference<String> reminders = new AtomicReference<>(
                "1. [project] Plan before making changes\n2. [session] Run focused tests");
        EnforcerJudge judge = new EnforcerJudge(
                backend, new com.fasterxml.jackson.databind.ObjectMapper());
        judge.setReminderSupplier(reminders::get);
        EnforcerPolicy policy = new EnforcerPolicy(RULES, 2, false);

        judge.evaluate("implement it", "done", policy, 1);
        backend.nextResponse = "{\"action\":\"ALLOW\",\"reason\":\"planned\"}";
        judge.evaluateToolCall("todowrite", "{\"action\":\"set\"}", policy,
                EnforcerConversationContext.of(List.of(
                        new EnforcerConversationContext.Message("user", "implement it"),
                        new EnforcerConversationContext.Message("assistant", "I planned first"))));
        backend.nextResponse = "{\"compliant\":true,\"stop\":false}";
        judge.evaluatePartialOutput("implement it", "working", policy);
        backend.nextResponse = "They are active constraints.";
        judge.chatWithJudge("Which reminders are enforced?");

        assertEquals(4, backend.userPrompts.size(),
                "a reminder must prevent the routine-tool fast path from skipping judge review");
        for (String prompt : backend.userPrompts) {
            assertTrue(prompt.contains("[ACTIVE REMINDER CONSTRAINTS]"));
            assertTrue(prompt.contains("[project] Plan before making changes"));
            assertTrue(prompt.contains("[session] Run focused tests"));
        }
        assertTrue(backend.userPrompts.get(1).contains("user: implement it"));
        assertTrue(backend.userPrompts.get(1).contains("assistant: I planned first"));
        assertTrue(EnforcerJudge.SYSTEM_PROMPT.contains(
                "Active reminders are enforceable user instructions"));

        reminders.set("");
        backend.nextResponse = "{\"compliant\":true,\"stop\":false}";
        judge.evaluate("next", "done", policy, 2);
        assertFalse(backend.userPrompts.get(4).contains("ACTIVE REMINDER CONSTRAINTS"),
                "the supplier must be live rather than snapshotted at judge construction");
    }

    @Test
    void judgeChatUsesConversationalPromptAndReturnsReply() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.nextResponse = "Understood — I will allow file edits in src/.";
        EnforcerJudge judge = new EnforcerJudge(backend,
                new com.fasterxml.jackson.databind.ObjectMapper(), () -> null);

        String reply = judge.chatWithJudge("You were wrong about the src edits");

        assertEquals("Understood — I will allow file edits in src/.", reply);
        assertTrue(backend.userPrompts.get(0).contains("You were wrong about the src edits"));
        assertTrue(backend.userPrompts.get(0).contains("[USER MESSAGE TO THE JUDGE]"));
        // The chat lane must NOT be graded against the strict verdict contract.
        assertFalse(backend.systemPrompts.get(0).contains(EnforcerJudge.SYSTEM_PROMPT));
        assertTrue(backend.systemPrompts.get(0).contains(
                "never enables intervention or sends feedback to the main agent"));
    }

    @Test
    void judgeChatCarriesHistoryAcrossStatelessBackendCalls() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        EnforcerJudge judge = new EnforcerJudge(
                backend, new com.fasterxml.jackson.databind.ObjectMapper());
        backend.nextResponse = "I blocked it because I read the reminder broadly.";
        judge.chatWithJudge("Why did you block the Git command?");
        backend.nextResponse = "Understood; I will apply the reset-specific qualifier.";

        judge.chatWithJudge("That reminder only bans resets.");

        String secondPrompt = backend.userPrompts.get(1);
        assertTrue(secondPrompt.contains("[JUDGE CHAT HISTORY]"));
        assertTrue(secondPrompt.contains("user: Why did you block the Git command?"));
        assertTrue(secondPrompt.contains(
                "judge: I blocked it because I read the reminder broadly."));
        assertTrue(secondPrompt.contains("[USER MESSAGE TO THE JUDGE]\nThat reminder only bans resets."));
    }

    @Test
    void chatExchangeIsRecordedAsJudgeChatPhase() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        EnforcerJudge judge = new EnforcerJudge(backend,
                new com.fasterxml.jackson.databind.ObjectMapper(), () -> null);
        JudgementLog log = JudgementLog.forSession(
                "judge-chat-test-" + System.nanoTime());
        judge.setJudgementLog(log);

        judge.chatWithJudge("why did you block the last turn?");

        List<JudgementRecord> records = JudgementLog.readAll(log.getSessionId());
        assertEquals(1, records.size());
        assertEquals("JUDGE_CHAT", records.get(0).getPhase());
        assertTrue(records.get(0).getJudgeRawResponse().contains("why did you block")
                || records.get(0).getUserPromptExcerpt().contains("why did you block"));
    }
}
