/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.admission;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrency and lifecycle contracts for the parallel shadow admission branch. */
class ParallelAdmissionCoordinatorConcurrencyTest {

    @Test
    void graphTimeoutCancelsTheUnderlyingEvaluation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        GraphAdmissionEvaluator slowGraph = request -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return GraphAdmissionResult.defer("unexpected completion");
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return GraphAdmissionResult.failed("graph evaluation interrupted", "interrupted");
            }
        };

        try {
            AdmissionComparison comparison = new ParallelAdmissionCoordinator(
                    AdmissionMode.SHADOW_COMPARE, slowGraph, executor, Duration.ofMillis(25))
                    .evaluate(request(), ignored -> AdmissionDecision.REUSE);

            assertTrue(started.await(100, TimeUnit.MILLISECONDS), "graph branch never started");
            assertTrue(comparison.graphResult().degraded());
            assertTrue(interrupted.await(100, TimeUnit.MILLISECONDS),
                    "timing out the future must interrupt/cancel the graph evaluation");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void llmAndGraphBranchesOverlapOnAParallelExecutor() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch graphStarted = new CountDownLatch(1);
        CountDownLatch bothStarted = new CountDownLatch(2);

        try {
            AdmissionComparison comparison = new ParallelAdmissionCoordinator(
                    AdmissionMode.SHADOW_COMPARE,
                    request -> {
                        graphStarted.countDown();
                        bothStarted.countDown();
                        if (!await(bothStarted)) {
                            return GraphAdmissionResult.failed("parallel start timeout", "branches did not overlap");
                        }
                        return GraphAdmissionResult.defer("parallel graph result");
                    }, executor, Duration.ofSeconds(2))
                    .evaluate(request(), ignored -> {
                        llmStarted.countDown();
                        bothStarted.countDown();
                        if (!await(bothStarted)) {
                            return AdmissionDecision.DEFER;
                        }
                        return AdmissionDecision.REUSE;
                    });

            assertTrue(llmStarted.getCount() == 0, "LLM branch did not start");
            assertTrue(graphStarted.getCount() == 0, "graph branch did not start");
            assertEquals(AdmissionDecision.REUSE, comparison.authoritativeDecision());
            assertEquals(AdmissionDecision.DEFER, comparison.graphResult().decision());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private AdmissionRequest request() {
        AdmissionCandidate candidate = new AdmissionCandidate("candidate", "person:candidate");
        return new AdmissionRequest("group", candidate, List.of(candidate),
                "snapshot:concurrency", "policy:v1", new UnifiedGraph());
    }

    private boolean await(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
