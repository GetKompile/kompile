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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerJudgeReadOnlyGitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void broadGitReminderCannotBlockHostClassifiedReadOnlyInspection() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        judge.setReminderSupplier(() ->
                "Plan before changes. Git operations, especially resets, are not allowed.");

        EnforcerToolCallDecision decision = judge.evaluateToolCall(
                "bash", "{\"command\":\"git diff --check\"}",
                new EnforcerPolicy("Use safe, relevant tools.", 2, false));

        assertTrue(decision.isAllowed());
        assertEquals(0, backend.calls.get(),
                "the probabilistic judge must not reclassify a host-proven read-only Git command");
    }

    @Test
    void logAndShowAreInspectionsButRevertAndMixedCommandsNeedReview() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        EnforcerPolicy policy = new EnforcerPolicy("Review mutations", 2, false);
        for (String command : java.util.List.of("git log --oneline -5", "git show HEAD",
                "git --no-pager show HEAD", "git -C /tmp/repo log -1")) {
            assertTrue(judge.evaluateToolCall("bash", objectMapper.writeValueAsString(
                    java.util.Map.of("command", command)), policy).isAllowed(), command);
        }
        assertEquals(0, backend.calls.get());
        for (String command : java.util.List.of("git revert HEAD", "git log; git revert HEAD",
                "git log\nrm -rf build-cache", "git show HEAD && rm obsolete.txt")) {
            assertFalse(judge.evaluateToolCall("bash", objectMapper.writeValueAsString(
                    java.util.Map.of("command", command)), policy).isAllowed(), command);
        }
        assertEquals(4, backend.calls.get());
    }

    @Test
    void mutatingGitAndExplicitCommandBansStillBlock() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, objectMapper);
        judge.setReminderSupplier(() -> "Git mutations are prohibited.");

        EnforcerToolCallDecision mutation = judge.evaluateToolCall(
                "bash", "{\"command\":\"git commit -m test\"}",
                new EnforcerPolicy("Use safe, relevant tools.", 2, false));
        assertFalse(mutation.isAllowed());
        assertEquals(1, backend.calls.get(), "mutations must continue through normal policy review");

        EnforcerToolCallDecision explicitBan = judge.evaluateToolCall(
                "bash", "{\"command\":\"git diff --check\"}",
                new EnforcerPolicy("BAN_CMD: git diff", 2, false));
        assertFalse(explicitBan.isAllowed());
        assertEquals(1, backend.calls.get(), "a deterministic explicit ban must win without an LLM call");
    }

    private static final class BlockingBackend implements JudgeBackend {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String generate(String userPrompt, String systemPrompt) {
            calls.incrementAndGet();
            return "{\"action\":\"BLOCK\",\"reason\":\"blocked by fixture\","
                    + "\"violations\":[\"blocked\"],\"correction_prompt\":\"revise\"}";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
