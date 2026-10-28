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
import ai.kompile.cli.main.chat.harness.CliJudgeBackend;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
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
@TemporaryUserHome
class EnforcerJudgeConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Timeout(5)
    void ownedJudgeClosesOnceAndInterruptsBlockedWarmupBeforeTakingItsMonitor() throws Exception {
        CountDownLatch warming = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override public String generate(String user, String system) { return "{}"; }
            @Override public boolean isAvailable() { return true; }
            @Override public void warmUp(String system) {
                warming.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException cancelled) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        EnforcerJudge judge = new EnforcerJudge(backend, MAPPER);
        assertTrue(warming.await(2, TimeUnit.SECONDS));
        AutoCloseable owned = judge; // native adapter's instanceof AutoCloseable must own this backend
        owned.close();
        judge.close();
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(1, closes.get());
    }

    @Test
    @Timeout(8)
    void closingRealSingleShotWarmupTerminatesItsPid(@TempDir Path directory) throws Exception {
        Path binary = directory.resolve("judge-fixture");
        Path pidFile = directory.resolve("pid");
        Files.writeString(binary, "#!/bin/sh\nprintf '%s' $$ > '"
                + pidFile.toString().replace("'", "'\"'\"'") + "'\nexec sleep 30\n");
        assertTrue(binary.toFile().setExecutable(true));
        CliJudgeBackend backend = new CliJudgeBackend("fixture", directory);
        var binaryField = CliJudgeBackend.class.getDeclaredField("agentBinary");
        binaryField.setAccessible(true);
        binaryField.set(backend, binary.toString());
        EnforcerJudge judge = new EnforcerJudge(backend, MAPPER);
        var closer = Executors.newSingleThreadExecutor();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(pidFile), "warmup did not launch fixture");
            long pid = Long.parseLong(Files.readString(pidFile));
            closer.submit(judge::close).get(2, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "single-shot warmup process survived judge closure");
        } finally {
            judge.close();
            closer.shutdownNow();
            assertTrue(closer.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

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
