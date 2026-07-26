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

package ai.kompile.core.graphrag.partition.reuse;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the ledger owes a run: the answer an earlier read produced, an honest count of what that
 * saved, and the freedom to forget without ever being wrong.
 */
class ExtractionLedgerTest {

    private static final String PROFILE = "lfm2.5/entities-v3";

    private static ExtractionKey key(String text) {
        return ExtractionKey.ofText(text, PROFILE).orElseThrow();
    }

    private static Graph graphOf(String entityTitle) {
        Entity entity = new Entity();
        entity.setId(entityTitle);
        entity.setTitle(entityTitle);
        return Graph.builder().entities(List.of(entity)).relationships(List.of()).build();
    }

    @Nested
    @DisplayName("Remembering")
    class Remembering {

        @Test
        @DisplayName("work nobody has done is not remembered")
        void unknownWorkIsNotRemembered() {
            assertTrue(ExtractionLedger.inMemory().lookup(key("Acme employs Alice.")).isEmpty());
        }

        @Test
        @DisplayName("what a read produced comes back, with the chunk that was actually read")
        void whatAReadProducedComesBack() {
            ExtractionLedger ledger = ExtractionLedger.inMemory();
            Graph produced = graphOf("Acme");
            ledger.record(key("Acme employs Alice."), "c1", produced);

            ExtractionLedger.Reuse hit =
                    ledger.lookup(key("Acme employs Alice.")).orElseThrow();

            assertSame(produced, hit.result());
            assertEquals("c1", hit.firstChunkId());
            assertTrue(hit.describe().contains("c1"), hit.describe());
        }

        @Test
        @DisplayName("\"this text yields nothing\" is an answer worth keeping")
        void nothingExtractedIsStillAnAnswer() {
            // Re-asking a question whose answer is "nothing" costs exactly as much as re-asking
            // any other, so an empty result is remembered rather than treated as a miss.
            ExtractionLedger ledger = ExtractionLedger.inMemory();
            ledger.record(key("A page of headers."), "c1", null);

            Optional<ExtractionLedger.Reuse> hit = ledger.lookup(key("A page of headers."));

            assertTrue(hit.isPresent());
            assertNull(hit.orElseThrow().result());
        }

        @Test
        @DisplayName("a second reading of the same text replaces the first")
        void recordingAgainReplaces() {
            ExtractionLedger ledger = ExtractionLedger.inMemory();
            ledger.record(key("Acme employs Alice."), "c1", graphOf("Acme"));
            Graph fresher = graphOf("Acme Corp");
            ledger.record(key("Acme employs Alice."), "c2", fresher);

            ExtractionLedger.Reuse hit = ledger.lookup(key("Acme employs Alice.")).orElseThrow();

            assertSame(fresher, hit.result());
            assertEquals("c2", hit.firstChunkId());
        }

        @Test
        @DisplayName("nothing is recorded against no key")
        void aNullKeyIsIgnored() {
            ExtractionLedger ledger = ExtractionLedger.inMemory();
            ledger.record(null, "c1", graphOf("Acme"));

            assertTrue(ledger.lookup(null).isEmpty());
            assertEquals(0, ledger.stats().total());
        }
    }

    @Nested
    @DisplayName("Counting")
    class Counting {

        @Test
        @DisplayName("each hand-out is counted, so an audit sees what the sharing saved")
        void everyReuseIsCounted() {
            ExtractionLedger ledger = ExtractionLedger.inMemory();
            ledger.record(key("Acme employs Alice."), "c1", graphOf("Acme"));

            // The count includes the hand-out you are holding: an answer in your hands has been
            // reused once, not zero times.
            assertEquals(1, ledger.lookup(key("Acme employs Alice.")).orElseThrow().timesReused());
            assertEquals(2, ledger.lookup(key("Acme employs Alice.")).orElseThrow().timesReused());
            assertEquals(3, ledger.lookup(key("Acme employs Alice.")).orElseThrow().timesReused());
            assertEquals(new ExtractionLedger.Stats(1, 3), ledger.stats());
        }

        @Test
        @DisplayName("a miss is not counted as anything")
        void aMissIsNotCounted() {
            ExtractionLedger ledger = ExtractionLedger.inMemory();
            ledger.lookup(key("Acme employs Alice."));

            assertEquals(new ExtractionLedger.Stats(0, 0), ledger.stats());
            assertEquals(0.0, ledger.stats().reuseRate(), 1e-9);
        }

        @Test
        @DisplayName("the rate is the share of work that did not have to be done")
        void theRateIsTheShareSaved() {
            ExtractionLedger.Stats stats = new ExtractionLedger.Stats(1, 3);

            assertEquals(4, stats.total());
            assertEquals(0.75, stats.reuseRate(), 1e-9);
            assertTrue(stats.describe().contains("reused=3"), stats.describe());
        }
    }

    @Nested
    @DisplayName("Forgetting")
    class Forgetting {

        @Test
        @DisplayName("a full ledger drops the oldest rather than growing without end")
        void aFullLedgerDropsTheOldest() {
            // Safe by construction: a miss costs one extraction that had already been done, and
            // can never produce a wrong answer.
            ExtractionLedger ledger = ExtractionLedger.inMemory(2);
            ledger.record(key("one"), "c1", graphOf("One"));
            ledger.record(key("two"), "c2", graphOf("Two"));
            ledger.record(key("three"), "c3", graphOf("Three"));

            assertTrue(ledger.lookup(key("one")).isEmpty());
            assertTrue(ledger.lookup(key("two")).isPresent());
            assertTrue(ledger.lookup(key("three")).isPresent());
        }

        @Test
        @DisplayName("what keeps being used keeps being kept")
        void whatIsUsedIsKept() {
            ExtractionLedger ledger = ExtractionLedger.inMemory(2);
            ledger.record(key("one"), "c1", graphOf("One"));
            ledger.record(key("two"), "c2", graphOf("Two"));
            ledger.lookup(key("one"));
            ledger.record(key("three"), "c3", graphOf("Three"));

            assertTrue(ledger.lookup(key("one")).isPresent());
            assertTrue(ledger.lookup(key("two")).isEmpty());
        }

        @Test
        @DisplayName("a ledger with no room still works")
        void aLedgerWithNoRoomStillWorks() {
            ExtractionLedger ledger = ExtractionLedger.inMemory(0);
            ledger.record(key("one"), "c1", graphOf("One"));

            assertTrue(ledger.lookup(key("one")).isPresent());
        }

        @Test
        @DisplayName("the default bound is stated, not guessed at by callers")
        void theDefaultBoundIsStated() {
            assertTrue(ExtractionLedger.DEFAULT_MAX_ENTRIES > 0);
        }
    }

    @Nested
    @DisplayName("Sharing between threads")
    class Sharing {

        @Test
        @DisplayName("one ledger serves every partition in a run without losing count")
        void concurrentReusesAreAllCounted() throws Exception {
            // A crawl parallelises inside a mini-batch, so the counters are shared state. If they
            // are not guarded, this loses increments.
            ExtractionLedger ledger = ExtractionLedger.inMemory();
            ledger.record(key("Acme employs Alice."), "c1", graphOf("Acme"));

            int threads = 4;
            int perThread = 250;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            ledger.lookup(key("Acme employs Alice."));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
            start.countDown();

            assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
            assertEquals(new ExtractionLedger.Stats(1, threads * perThread), ledger.stats());
        }
    }
}
