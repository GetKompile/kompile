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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
    void structuredSchemaIsPreservedAcrossFallback() throws Exception {
        AtomicReference<JudgeBackend.JsonSchema> received = new AtomicReference<>();
        JudgeBackend primary = new StubBackend("primary", () -> "[Error: unavailable]");
        JudgeBackend backup = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                throw new AssertionError("structured fallback must not downgrade to raw generation");
            }

            @Override
            public String generateJson(
                    String userPrompt, String systemPrompt, JsonSchema outputSchema) {
                received.set(outputSchema);
                return "{\"compliant\":true}";
            }

            @Override public boolean isAvailable() { return true; }
            @Override public String describe() { return "backup"; }
        };
        ResilientJudgeBackend resilient = new ResilientJudgeBackend(
                primary, List.of(backup), 0, 1000);
        JudgeBackend.JsonSchema schema = new JudgeBackend.JsonSchema(
                "verdict", new ObjectMapper().createObjectNode().put("type", "object"), true);

        assertEquals("{\"compliant\":true}", resilient.generateJson("u", "s", schema));
        assertEquals("verdict", received.get().name());
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
    void interruptCancelsUnderlyingJudgeFutureAndPreservesCallerInterrupt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean workerInterrupted = new AtomicBoolean(false);
        JudgeBackend blocking = new StubBackend("blocking", () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
                return "too-late";
            } catch (InterruptedException interrupted) {
                workerInterrupted.set(true);
                throw new RuntimeException(interrupted);
            }
        });
        ResilientJudgeBackend resilient = new ResilientJudgeBackend(
                blocking, List.of(), 30_000, 1000);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean callerInterruptPreserved = new AtomicBoolean(false);
        Thread caller = new Thread(() -> {
            try {
                resilient.generate("u", "s");
            } catch (Throwable thrown) {
                failure.set(thrown);
                callerInterruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "judge-interrupt-test");

        caller.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2_000);

        assertFalse(caller.isAlive(), "interrupted judge caller must return promptly");
        assertTrue(failure.get() instanceof InterruptedException);
        assertTrue(callerInterruptPreserved.get());
        assertTrue(awaitTrue(workerInterrupted, 2_000),
                "interrupt must cancel the underlying judge future");
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
    void rateLimitedBackendIsUnavailableDuringCooldownAndRecoversAfterward() throws Exception {
        JudgeBackend primary = new StubBackend(
                "rate-limited", () -> "[OpenAI Codex API error 429: usage limit reached]");
        ResilientJudgeBackend resilient = new ResilientJudgeBackend(
                primary, List.of(), 0, 40);

        String response = resilient.generate("u", "s");

        assertTrue(ResilientJudgeBackend.isErrorResponse(response));
        assertFalse(resilient.isAvailable(), "cooldown must be reflected in judge health");
        assertTrue(resilient.failureReason().contains("429"));
        assertTrue(awaitTrue(resilient::isAvailable, 1_000),
                "judge health must recover when the bounded cooldown expires");
        resilient.close();
    }

    @Test
    void restartAndModifyDelegateWithoutShuttingDownDeadlineExecutor() throws Exception {
        AtomicBoolean restarted = new AtomicBoolean(false);
        AtomicReference<String> selection = new AtomicReference<>();
        JudgeBackend primary = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                return "ok";
            }
            @Override public boolean isAvailable() { return true; }
            @Override public void restart() { restarted.set(true); }
            @Override public boolean modify(String value) {
                selection.set(value);
                return true;
            }
        };
        ResilientJudgeBackend resilient = new ResilientJudgeBackend(
                primary, List.of(), 1_000, 1_000);

        resilient.restart();
        assertTrue(restarted.get());
        assertTrue(resilient.modify("claude"));
        assertEquals("claude", selection.get());
        assertEquals("ok", resilient.generate("u", "s"),
                "restart must leave the deadline executor usable");
        resilient.close();
    }

    @Test
    void isErrorResponseDetectsSentinels() {
        assertTrue(ResilientJudgeBackend.isErrorResponse(null));
        assertTrue(ResilientJudgeBackend.isErrorResponse("   "));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[Error: x]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[LLM API error 500: y]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[Anthropic API error 529: overloaded]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[OpenAI Codex API error 429: limited]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[OpenAI Responses API error 503: down]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[Radius API error 429: limited]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[Z.AI connection error: reset]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[Radius model is not present: x]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("[Error: all judge backends are in cooldown]"));
        assertTrue(ResilientJudgeBackend.isErrorResponse("You've hit your limit for the current session."));
        assertFalse(ResilientJudgeBackend.isErrorResponse("{\"compliant\":true}"));
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private static boolean awaitTrue(AtomicBoolean value, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!value.get() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return value.get();
    }
}
