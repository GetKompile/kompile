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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the fix for the concurrency concern behind WP9: the realtime JSONL tap (poll thread) and
 * the turn-gate (main thread) share one {@link EnforcerJudge}, whose backend (esp. a persistent
 * subprocess) is single-flight. {@code EnforcerJudge}'s evaluators are {@code synchronized}, so
 * concurrent callers must never be inside {@code backend.generate} at the same time.
 */
class EnforcerJudgeConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Backend that records the maximum number of threads simultaneously inside generate(). */
    private static final class ConcurrencyProbeBackend implements JudgeBackend {
        final AtomicInteger inFlight = new AtomicInteger(0);
        final AtomicInteger maxInFlight = new AtomicInteger(0);

        @Override
        public String generate(String userPrompt, String systemPrompt) throws Exception {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20); // widen the window so any real overlap is observed
            } finally {
                inFlight.decrementAndGet();
            }
            // A benign "pass"/"allow" JSON both decision parsers accept.
            return "{\"compliant\":true,\"stop\":false,\"action\":\"ALLOW\",\"violations\":[]}";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentEvaluatorsNeverOverlapInsideBackend() throws Exception {
        ConcurrencyProbeBackend backend = new ConcurrencyProbeBackend();
        EnforcerJudge judge = new EnforcerJudge(backend, MAPPER);
        EnforcerPolicy policy = new EnforcerPolicy("BAN: rm -rf", 3, false);
        EnforcerConversationContext ctx = EnforcerConversationContext.empty();

        int threads = 6;
        int callsPerThread = 15;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int c = 0; c < callsPerThread; c++) {
                        if (id % 2 == 0) {
                            judge.evaluatePartialOutput("u", "partial output " + c, policy, ctx);
                        } else {
                            judge.evaluateToolCall("tool" + c, "{}", policy, ctx);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        assertTrue(done.await(25, TimeUnit.SECONDS), "all evaluator threads must finish");
        assertEquals(0, errors.get(), "no evaluator call may throw");
        assertEquals(1, backend.maxInFlight.get(),
                "the synchronized judge must keep the single-flight backend to one concurrent call, was "
                        + backend.maxInFlight.get());
    }
}
