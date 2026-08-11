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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the existing LLM admission decision beside an optional graph policy.
 *
 * <p>In SHADOW_COMPARE the two callbacks receive the same immutable request and execute on the
 * supplied executor. Graph failures and timeouts are converted to a degraded DEFER result; the LLM
 * callback still determines the authoritative action. In LLM_ONLY the graph callback is never invoked.</p>
 */
public final class ParallelAdmissionCoordinator {

    private final AdmissionMode mode;
    private final GraphAdmissionEvaluator graphEvaluator;
    private final Executor executor;
    private final Duration graphTimeout;

    public ParallelAdmissionCoordinator(AdmissionMode mode, GraphAdmissionEvaluator graphEvaluator) {
        this(mode, graphEvaluator, ForkJoinPool.commonPool(), null);
    }

    public ParallelAdmissionCoordinator(AdmissionMode mode,
                                        GraphAdmissionEvaluator graphEvaluator,
                                        Executor executor,
                                        Duration graphTimeout) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.graphEvaluator = graphEvaluator;
        this.executor = Objects.requireNonNull(executor, "executor");
        if (graphTimeout != null && (graphTimeout.isZero() || graphTimeout.isNegative())) {
            throw new IllegalArgumentException("graphTimeout must be positive");
        }
        this.graphTimeout = graphTimeout;
    }

    public AdmissionComparison evaluate(AdmissionRequest request,
                                         LlmAdmissionEvaluator llmEvaluator) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(llmEvaluator, "llmEvaluator");

        if (mode == AdmissionMode.LLM_ONLY) {
            AdmissionDecision llmDecision = requireDecision(llmEvaluator.evaluate(request));
            return comparison(request, llmDecision, GraphAdmissionResult.notEvaluated(), 0L);
        }

        CompletableFuture<AdmissionDecision> llmFuture = CompletableFuture.supplyAsync(
                () -> requireDecision(llmEvaluator.evaluate(request)), executor);
        GraphTask graphTask = submitGraph(request);
        CompletableFuture<GraphCall> graphFuture = graphTask.future();
        if (graphTimeout != null) {
            graphFuture = graphFuture.orTimeout(graphTimeout.toNanos(), TimeUnit.NANOSECONDS);
            graphFuture.whenComplete((ignored, error) -> {
                if (isTimeout(error)) {
                    graphTask.cancel();
                }
            });
        }
        graphFuture = graphFuture.exceptionally(error ->
                GraphCall.failed("graph evaluation did not complete",
                        rootMessage(error)));

        AdmissionDecision llmDecision = llmFuture.join();
        GraphCall graphCall = graphFuture.join();
        return comparison(request, llmDecision, graphCall.result(), graphCall.durationNanos());
    }

    private GraphTask submitGraph(AdmissionRequest request) {
        CompletableFuture<GraphCall> future = new CompletableFuture<>();
        AtomicReference<Future<?>> handle = new AtomicReference<>();
        Runnable task = () -> {
            try {
                future.complete(evaluateGraph(request));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        };
        if (executor instanceof ExecutorService service) {
            handle.set(service.submit(task));
        } else {
            executor.execute(task);
        }
        return new GraphTask(future, handle);
    }

    private GraphCall evaluateGraph(AdmissionRequest request) {
        long start = System.nanoTime();
        if (request.graph() == null) {
            return GraphCall.failed("graph snapshot is missing", "missing graph snapshot", start);
        }
        if (graphEvaluator == null) {
            return new GraphCall(GraphAdmissionResult.failed(
                    "graph evaluator is not configured", "missing graph evaluator"), 0L);
        }
        try {
            GraphAdmissionResult result = graphEvaluator.evaluate(request);
            if (result == null) {
                return GraphCall.failed("graph evaluator returned no result",
                        "null graph admission result", start);
            }
            return new GraphCall(result, elapsed(start));
        } catch (RuntimeException error) {
            return GraphCall.failed("graph evaluator failed", rootMessage(error), start);
        }
    }

    private AdmissionComparison comparison(AdmissionRequest request,
                                            AdmissionDecision llmDecision,
                                            GraphAdmissionResult graphResult,
                                            long durationNanos) {
        return new AdmissionComparison(
                request.decisionGroupId(),
                request.candidate().candidateId(),
                request.candidate().canonicalKey(),
                request.snapshotId(),
                request.policyVersion(),
                request.ballotFingerprint(),
                mode,
                llmDecision,
                graphResult,
                llmDecision,
                durationNanos);
    }

    private static AdmissionDecision requireDecision(AdmissionDecision decision) {
        return Objects.requireNonNull(decision, "admission evaluator returned null");
    }

    private static long elapsed(long start) {
        return Math.max(0L, System.nanoTime() - start);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        if (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record GraphTask(CompletableFuture<GraphCall> future,
                             AtomicReference<Future<?>> handle) {
        private void cancel() {
            Future<?> task = handle.get();
            if (task != null) {
                task.cancel(true);
            }
        }
    }

    private record GraphCall(GraphAdmissionResult result, long durationNanos) {
        private GraphCall {
            Objects.requireNonNull(result, "result");
            if (durationNanos < 0L) {
                throw new IllegalArgumentException("durationNanos must not be negative");
            }
        }

        private static GraphCall failed(String reason, String error) {
            return new GraphCall(GraphAdmissionResult.failed(reason, error), 0L);
        }

        private static GraphCall failed(String reason, String error, long start) {
            return new GraphCall(GraphAdmissionResult.failed(reason, error), elapsed(start));
        }
    }
}
