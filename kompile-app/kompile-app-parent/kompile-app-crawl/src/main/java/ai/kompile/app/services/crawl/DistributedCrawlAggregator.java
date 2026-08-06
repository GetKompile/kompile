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

package ai.kompile.app.services.crawl;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.LlmCallRecord;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.PipelineStepSnapshot;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.ProgressSnapshot;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.RetryEvent;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.StageEvent;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.TuningDecision;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Merges the per-worker {@link ProgressSnapshot}s a {@link DistributedCrawlSession} has collected into ONE
 * aggregate snapshot, so a distributed crawl renders in the unified step monitor as a single job: summed
 * counters, a worker-tagged union of pipeline steps, and merged rolling event / retry / tuning / LLM lists.
 *
 * <p>The aggregate is itself a {@link ProgressSnapshot} so the existing SSE pipeline and the shared
 * {@code crawl-step-monitor} consume it with zero shape changes (the SSE event's snapshot field is a
 * {@code ProgressSnapshot} everywhere). Pure (no Spring state) → unit-testable.</p>
 */
@Component
public class DistributedCrawlAggregator {

    public ProgressSnapshot aggregate(DistributedCrawlSession session) {
        ProgressSnapshot.ProgressSnapshotBuilder b = ProgressSnapshot.builder()
                .jobId("distributed-" + session.getSessionId())
                .name(session.getOriginalRequest() != null ? session.getOriginalRequest().getName() : null)
                .status(mapStatus(session.getStatus()))
                .startedAt(session.getStartedAt())
                .completedAt(session.getCompletedAt());

        // Deterministic worker order (by parsed worker index) so step grouping is stable across ticks.
        List<DistributedCrawlSession.WorkerInfo> workers = new ArrayList<>(session.getWorkers().values());
        workers.sort(Comparator.comparingInt(w -> workerIndex(w.getWorkerId())));

        List<PipelineStepSnapshot> steps = new ArrayList<>();
        List<StageEvent> events = new ArrayList<>();
        List<RetryEvent> retries = new ArrayList<>();
        List<TuningDecision> tuning = new ArrayList<>();
        List<LlmCallRecord> llmCalls = new ArrayList<>();

        long docsDiscovered = 0, docsLoaded = 0, chunksCreated = 0, chunksProcessed = 0;
        long graphChunksProcessed = 0, graphChunksTotal = 0, chunksEmbedded = 0, docsIndexed = 0;
        long entities = 0, rels = 0, errors = 0, inTok = 0, outTok = 0, costX100 = 0;
        long llmTotal = 0, llmOk = 0, llmFail = 0, retriedBatches = 0, retriedItems = 0;
        long elapsedMs = 0;
        int memMax = 0;
        long progressSum = 0;
        int progressN = 0;
        String currentPhase = null;

        for (DistributedCrawlSession.WorkerInfo w : workers) {
            ProgressSnapshot s = w.getLatestSnapshot();
            if (s == null) {
                continue;
            }
            int idx = workerIndex(w.getWorkerId());
            if (s.getPipelineSteps() != null) {
                for (PipelineStepSnapshot ps : s.getPipelineSteps()) {
                    steps.add(tagStep(ps, idx));
                }
            }
            // Tag phase/stage with the worker prefix so the step monitor attributes every event,
            // retry, tuning decision and decomposed LLM call to the right worker's step.
            if (s.getRecentEvents() != null) {
                for (StageEvent e : s.getRecentEvents()) events.add(tagEvent(e, idx));
            }
            if (s.getRecentRetryEvents() != null) {
                for (RetryEvent r : s.getRecentRetryEvents()) retries.add(tagRetry(r, idx));
            }
            if (s.getRecentTuningDecisions() != null) {
                for (TuningDecision d : s.getRecentTuningDecisions()) tuning.add(tagTuning(d, idx));
            }
            if (s.getRecentLlmCalls() != null) {
                for (LlmCallRecord call : s.getRecentLlmCalls()) {
                    llmCalls.add(tagLlmCall(call, idx));
                }
            }

            docsDiscovered += s.getDocumentsDiscovered();
            docsLoaded += s.getDocumentsLoaded();
            chunksCreated += s.getChunksCreated();
            chunksProcessed += s.getChunksProcessed();
            graphChunksProcessed += s.getGraphChunksProcessed();
            graphChunksTotal += s.getGraphChunksTotal();
            chunksEmbedded += s.getChunksEmbedded();
            docsIndexed += s.getDocumentsIndexed();
            entities += s.getEntitiesExtracted();
            rels += s.getRelationshipsExtracted();
            errors += s.getErrorCount();
            inTok += s.getTotalInputTokens();
            outTok += s.getTotalOutputTokens();
            costX100 += s.getEstimatedCostCentsX100();
            llmTotal += s.getLlmCallsTotal();
            llmOk += s.getLlmCallsSucceeded();
            llmFail += s.getLlmCallsFailed();
            retriedBatches += s.getRetriedBatches();
            retriedItems += s.getRetriedItems();
            elapsedMs = Math.max(elapsedMs, s.getElapsedMs());
            memMax = Math.max(memMax, s.getMemoryUsagePercent());
            progressSum += s.getProgressPercent();
            progressN++;
            if (s.getCurrentPhase() != null) {
                currentPhase = s.getCurrentPhase(); // last non-null in worker order
            }
        }

        // Keep the newest N of each rolling list in chronological (ascending) order — the monitor shows the
        // tail (slice:-N), so ascending + drop-oldest preserves "latest" display semantics.
        keepNewest(events, 120, StageEvent::getTimestamp);
        keepNewest(retries, 40, RetryEvent::getTimestamp);
        keepNewest(tuning, 60, TuningDecision::getTimestamp);
        keepNewest(llmCalls, 80, LlmCallRecord::getTimestamp);

        return b
                .documentsDiscovered((int) docsDiscovered)
                .documentsLoaded((int) docsLoaded)
                .chunksCreated((int) chunksCreated)
                .chunksProcessed((int) chunksProcessed)
                .graphChunksProcessed((int) graphChunksProcessed)
                .graphChunksTotal((int) graphChunksTotal)
                .chunksEmbedded((int) chunksEmbedded)
                .documentsIndexed((int) docsIndexed)
                .entitiesExtracted((int) entities)
                .relationshipsExtracted((int) rels)
                .errorCount((int) errors)
                .totalInputTokens(inTok)
                .totalOutputTokens(outTok)
                .estimatedCostCentsX100(costX100)
                .llmCallsTotal(llmTotal)
                .llmCallsSucceeded(llmOk)
                .llmCallsFailed(llmFail)
                .retriedBatches(retriedBatches)
                .retriedItems(retriedItems)
                .elapsedMs(elapsedMs)
                .memoryUsagePercent(memMax)
                .progressPercent(progressN > 0 ? (int) (progressSum / progressN) : 0)
                .currentPhase(currentPhase)
                .pipelineSteps(steps.isEmpty() ? null : steps)
                .recentEvents(events.isEmpty() ? null : events)
                .recentRetryEvents(retries.isEmpty() ? null : retries)
                .recentTuningDecisions(tuning.isEmpty() ? null : tuning)
                .recentLlmCalls(llmCalls.isEmpty() ? null : llmCalls)
                .build();
    }

    /** New step copy whose id/displayName are prefixed with the worker so the monitor groups by worker. */
    private static PipelineStepSnapshot tagStep(PipelineStepSnapshot s, int idx) {
        return PipelineStepSnapshot.builder()
                .stepId("w" + idx + ":" + s.getStepId())
                .displayName("[W" + idx + "] " + (s.getDisplayName() != null ? s.getDisplayName() : s.getStepId()))
                .stepType(s.getStepType())
                .status(s.getStatus())
                .progressPercent(s.getProgressPercent())
                .totalItems(s.getTotalItems())
                .completedItems(s.getCompletedItems())
                .failedItems(s.getFailedItems())
                .activeTasks(s.getActiveTasks())
                .totalBatches(s.getTotalBatches())
                .completedBatches(s.getCompletedBatches())
                .currentBatchSize(s.getCurrentBatchSize())
                .currentItem(s.getCurrentItem())
                .message(s.getMessage())
                .startedAt(s.getStartedAt())
                .completedAt(s.getCompletedAt())
                .lastUpdatedAt(s.getLastUpdatedAt())
                .elapsedMs(s.getElapsedMs())
                .build();
    }

    private static LlmCallRecord tagLlmCall(LlmCallRecord call, int idx) {
        String phase = call.getPhase();
        return LlmCallRecord.builder()
                .timestamp(call.getTimestamp())
                .backendId(call.getBackendId())
                .taskType(call.getTaskType())
                .phase(phase == null || phase.isBlank() ? phase : "w" + idx + ":" + phase)
                .passId(call.getPassId())
                .passInvocation(call.getPassInvocation())
                .taskId(call.getTaskId())
                .partitionId(call.getPartitionId())
                .chunkId(call.getChunkId())
                .corpusSnapshotId(call.getCorpusSnapshotId())
                .graphRevision(call.getGraphRevision())
                .graphEntities(call.getGraphEntities())
                .graphRelationships(call.getGraphRelationships())
                .latencyMs(call.getLatencyMs())
                .inputTokens(call.getInputTokens())
                .outputTokens(call.getOutputTokens())
                .success(call.isSuccess())
                .timedOut(call.isTimedOut())
                .rateLimited(call.isRateLimited())
                .circuitBroken(call.isCircuitBroken())
                .errorCategory(call.getErrorCategory())
                .errorMessage(call.getErrorMessage())
                .promptChars(call.getPromptChars())
                .responseChars(call.getResponseChars())
                .promptText(call.getPromptText())
                .responseText(call.getResponseText())
                .build();
    }

    private static StageEvent tagEvent(StageEvent e, int idx) {
        return StageEvent.builder()
                .timestamp(e.getTimestamp())
                .phase("w" + idx + ":" + e.getPhase())
                .level(e.getLevel())
                .message(e.getMessage())
                .details(e.getDetails())
                .progressPercent(e.getProgressPercent())
                .build();
    }

    private static RetryEvent tagRetry(RetryEvent r, int idx) {
        return RetryEvent.builder()
                .timestamp(r.getTimestamp())
                .stage("w" + idx + ":" + r.getStage())
                .attempt(r.getAttempt())
                .maxAttempts(r.getMaxAttempts())
                .itemCount(r.getItemCount())
                .originalBatchSize(r.getOriginalBatchSize())
                .reducedBatchSize(r.getReducedBatchSize())
                .failureReason(r.getFailureReason())
                .backendId(r.getBackendId())
                .fallbackBackendId(r.getFallbackBackendId())
                .backoffMs(r.getBackoffMs())
                .succeeded(r.isSucceeded())
                .sentToDeadLetter(r.isSentToDeadLetter())
                .build();
    }

    private static TuningDecision tagTuning(TuningDecision d, int idx) {
        return TuningDecision.builder()
                .timestamp(d.getTimestamp())
                .stage("w" + idx + ":" + d.getStage())
                .oldValue(d.getOldValue())
                .newValue(d.getNewValue())
                .direction(d.getDirection())
                .reason(d.getReason())
                .detail(d.getDetail())
                .memoryPercent(d.getMemoryPercent())
                .build();
    }

    /** Sort ascending by timestamp and keep only the newest {@code max} entries. */
    private static <T> void keepNewest(List<T> list, int max, Function<T, Instant> ts) {
        list.sort(Comparator.comparing(x -> {
            Instant i = ts.apply(x);
            return i != null ? i : Instant.EPOCH;
        }));
        if (list.size() > max) {
            list.subList(0, list.size() - max).clear();
        }
    }

    /** Parse the numeric worker index from a {@code <session>-worker-N[-rK]} id; 0 when absent. */
    public static int workerIndex(String workerId) {
        if (workerId == null) {
            return 0;
        }
        int p = workerId.lastIndexOf("-worker-");
        if (p < 0) {
            return 0;
        }
        int i = p + "-worker-".length();
        int n = 0;
        boolean any = false;
        while (i < workerId.length() && Character.isDigit(workerId.charAt(i))) {
            n = n * 10 + (workerId.charAt(i) - '0');
            i++;
            any = true;
        }
        return any ? n : 0;
    }

    private static UnifiedCrawlJob.Status mapStatus(DistributedCrawlSession.Status s) {
        if (s == null) {
            return UnifiedCrawlJob.Status.PENDING;
        }
        return switch (s) {
            case DISPATCHING -> UnifiedCrawlJob.Status.PENDING;
            case RUNNING -> UnifiedCrawlJob.Status.RUNNING;
            case COMPLETED, PARTIALLY_COMPLETED -> UnifiedCrawlJob.Status.COMPLETED;
            case FAILED -> UnifiedCrawlJob.Status.FAILED;
            case CANCELLED -> UnifiedCrawlJob.Status.CANCELLED;
        };
    }
}
