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

package ai.kompile.cli.main.chat.harness;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilientJudgeBackendTest {

    /** A judge backend whose generate() behavior is supplied per test. */
    static final class StubBackend implements JudgeBackend {
        private final String name;
        private final Supplier<String> behavior;

        StubBackend(String name, Supplier<String> behavior) {
            this.name = name;
            this.behavior = behavior;
        }

        @Override
        public String generate(String userPrompt, String systemPrompt) {
            return behavior.get();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return name;
        }
    }

    @Test
    void swapsToBackupOnErrorSentinel() throws Exception {
        JudgeBackend primary = new StubBackend("primary", () -> "[Error: boom]");
        JudgeBackend backup = new StubBackend("backup", () -> "{\"compliant\":true}");
        ResilientJudgeBackend resilient = new ResilientJudgeBackend(primary, List.of(backup), 0, 1000);

        assertEquals("{\"compliant\":true}", resilient.generate("u", "s"));
        assertFalse(resilient.getSwapHistory().isEmpty(), "a swap should have been recorded");
        resilient.close();
    }

    @Test
    void swapsToBackupOnException() throws Exception {
        JudgeBackend primary = new StubBackend("primary", () -> {
            throw new RuntimeException("network down");
        });
        JudgeBackend backup = new StubBackend("backup", () -> "ok-json");
        ResilientJudgeBackend resilient = new ResilientJudgeBackend(primary, List.of(backup), 0, 1000);

        assertEquals("ok-json", resilient.generate("u", "s"));
        resilient.close();
    }

    @Test
    void deadlineTriggersSwap() throws Exception {
        JudgeBackend slow = new StubBackend("slow", () -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return "too-late";
        });
        JudgeBackend fast = new StubBackend("fast", () -> "fast-json");
        ResilientJudgeBackend resilient = new ResilientJudgeBackend(slow, List.of(fast), 150, 1000);

        assertEquals("fast-json", resilient.generate("u", "s"));
        resilient.close();
    }

    @Test
    void allBackendsFailReturnsErrorSentinel() throws Exception {
        JudgeBackend a = new StubBackend("a", () -> "[Error: a]");
        JudgeBackend b = new StubBackend("b", () -> "[LLM API error 429: rate limited]");
        ResilientJudgeBackend resilient = new ResilientJudgeBackend(a, List.of(b), 0, 1000);

        String out = resilient.generate("u", "s");
        assertTrue(ResilientJudgeBackend.isErrorResponse(out), "exhausted chain returns an error sentinel");
        resilient.close();
    }

    @Test
    void isErrorResponseDetectsSentinels() {
        assertTrue(ResilientJudgeBackend.isErrorResponse(null));
        assertTrue(ResilientJudgeBackend.isErrorResponse("   "));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[Error: x]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[LLM API error 500: y]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[Anthropic API error 529: overloaded]"));
        assertFalse(ResilientJudgeBackend.isErrorResponse("{\"compliant\":true}"));
    }
}
