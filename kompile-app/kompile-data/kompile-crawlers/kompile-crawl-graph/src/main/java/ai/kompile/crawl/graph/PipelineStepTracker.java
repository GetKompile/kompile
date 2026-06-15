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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Pure state-machine logic for pipeline step tracking.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 *
 * <p>All methods operate on {@link UnifiedCrawlJob.PipelineStepProgress} objects
 * stored on the job — no I/O, no external dependencies.</p>
 */
@Component
class PipelineStepTracker {

    /** Whether a GraphConstructor is configured — affects stepType() output. */
    private volatile boolean graphConstructorPresent;

    void setGraphConstructorPresent(boolean present) {
        this.graphConstructorPresent = present;
    }

    void initializePipelineSteps(UnifiedCrawlJob job) {
        if (job == null) {
            return;
        }
        for (String phase : List.of(
                "LOADING",
                "DISCOVERING",
                "CONVERTING",
                "ROUTING",
                "GRAPH_PREP",
                "CHUNKING",
                "GRAPH_EXTRACTION",
                "SURFACING",
                "ENTITY_RESOLUTION",
                "EDGE_COMPUTATION",
                "VECTOR_INDEXING",
                "ENRICHMENT")) {
            ensurePipelineStep(job, phase);
        }
        updatePipelineStep(job, "LOADING", UnifiedCrawlJob.PipelineStepStatus.PENDING,
                0, 0, 0, 0, 0, 0, null, "Waiting for crawl slot");
    }

    UnifiedCrawlJob.PipelineStepProgress ensurePipelineStep(UnifiedCrawlJob job, String phase) {
        String stepId = normalizeStepId(phase);
        List<UnifiedCrawlJob.PipelineStepProgress> steps = job.getPipelineSteps();
        // CopyOnWriteArrayList is thread-safe for reads. Steps are only ever added
        // (never removed), so a duplicate add under a race is harmless — the first
        // match will always be found on subsequent lookups.
        for (UnifiedCrawlJob.PipelineStepProgress step : steps) {
            if (stepId.equals(step.getStepId())) {
                return step;
            }
        }
        UnifiedCrawlJob.PipelineStepProgress step = UnifiedCrawlJob.PipelineStepProgress.builder()
                .stepId(stepId)
                .displayName(stepDisplayName(phase))
                .stepType(stepType(phase))
                .lastUpdatedAt(Instant.now())
                .build();
        steps.add(step);
        return step;
    }

    void updatePipelineStepFromCounters(UnifiedCrawlJob job, String phase, String message, String details) {
        if (job == null || phase == null) {
            return;
        }
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        UnifiedCrawlJob.PipelineStepStatus current = step.getStatus().get();
        if (current == UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                || current == UnifiedCrawlJob.PipelineStepStatus.FAILED
                || current == UnifiedCrawlJob.PipelineStepStatus.CANCELLED
                || current == UnifiedCrawlJob.PipelineStepStatus.SKIPPED
                || current == UnifiedCrawlJob.PipelineStepStatus.DEFERRED) {
            return;
        }
        if (step.getStartedAt() == null) {
            step.setStartedAt(Instant.now());
        }
        step.getStatus().set("MEMORY_BACKPRESSURE".equals(job.getCurrentBatchStep().get())
                ? UnifiedCrawlJob.PipelineStepStatus.BACKPRESSURE
                : UnifiedCrawlJob.PipelineStepStatus.RUNNING);
        step.getMessage().set(message != null ? message : details);
        step.getCurrentItem().set(job.getCurrentFile().get());
        step.getCurrentBatchSize().set(job.getCurrentBatchSize().get());
        step.setLastUpdatedAt(Instant.now());

        refreshStepCounters(job, normalizeStepId(phase), step);
    }

    void refreshStepCounters(UnifiedCrawlJob job,
                             String stepId,
                             UnifiedCrawlJob.PipelineStepProgress step) {
        switch (stepId) {
            case "LOADING" -> {
                List<UnifiedCrawlJob.SourceProgress> sourceProgress = job.getSourceProgress();
                int total = sourceProgress != null ? sourceProgress.size() : 0;
                int completed = 0;
                if (sourceProgress != null) {
                    for (UnifiedCrawlJob.SourceProgress sp : sourceProgress) {
                        UnifiedCrawlJob.Status s = sp.getStatus();
                        if (s == UnifiedCrawlJob.Status.COMPLETED
                                || s == UnifiedCrawlJob.Status.FAILED
                                || s == UnifiedCrawlJob.Status.CANCELLED) {
                            completed++;
                        }
                    }
                }
                step.getTotalItems().set(total);
                step.getCompletedItems().set(completed);
                step.getFailedItems().set(job.getErrorCount().get());
                step.getProgressPercent().set(percent(completed, total));
            }
            case "GRAPH_EXTRACTION" -> {
                // Read graph chunk counters directly from job atomics (read-only, no
                // side-effect normalization — the orchestrator handles clamping via
                // normalizeGraphChunksProcessed when updating the counters).
                int total = Math.max(0, job.getGraphChunksTotal().get());
                int raw = Math.max(0, job.getGraphChunksProcessed().get());
                int completed = total > 0 ? Math.min(total, raw) : raw;
                step.getTotalItems().set(total);
                step.getCompletedItems().set(completed);
                step.getFailedItems().set(job.getErrorCount().get());
                step.getProgressPercent().set(percent(completed, total));
            }
            case "VECTOR_INDEXING" -> {
                int total = job.getChunksQueuedForEmbedding().get();
                int completed = Math.max(job.getChunksEmbedded().get(), job.getDocumentsIndexed().get());
                step.getTotalItems().set(total);
                step.getCompletedItems().set(completed);
                step.getTotalBatches().set(job.getVectorBatchesTotal().get());
                step.getCompletedBatches().set(job.getVectorBatchesCompleted().get());
                step.getProgressPercent().set(percent(completed, total));
            }
            case "CHUNKING" -> {
                int total = job.getDocumentsLoaded().get();
                int completed = step.getCompletedItems().get();
                step.getTotalItems().set(total);
                step.getProgressPercent().set(percent(completed, total));
            }
            default -> {
                int total = Math.max(1, step.getTotalItems().get());
                int completed = Math.min(total, step.getCompletedItems().get());
                step.getProgressPercent().set(percent(completed, total));
            }
        }
    }

    void updatePipelineStep(UnifiedCrawlJob job,
                            String phase,
                            UnifiedCrawlJob.PipelineStepStatus status,
                            int completedItems,
                            int totalItems,
                            int failedItems,
                            int completedBatches,
                            int totalBatches,
                            int currentBatchSize,
                            String currentItem,
                            String message) {
        if (job == null || phase == null) {
            return;
        }
        applyPipelineStepUpdate(ensurePipelineStep(job, phase), status,
                completedItems, totalItems, failedItems,
                completedBatches, totalBatches, currentBatchSize,
                currentItem, message);
    }

    /**
     * Core step-update logic — accepts a pre-resolved step to avoid redundant
     * {@link #ensurePipelineStep} linear scans when the caller already has the step.
     */
    void applyPipelineStepUpdate(UnifiedCrawlJob.PipelineStepProgress step,
                                 UnifiedCrawlJob.PipelineStepStatus status,
                                 int completedItems,
                                 int totalItems,
                                 int failedItems,
                                 int completedBatches,
                                 int totalBatches,
                                 int currentBatchSize,
                                 String currentItem,
                                 String message) {
        Instant now = Instant.now();
        if (status == UnifiedCrawlJob.PipelineStepStatus.RUNNING && step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        if (status == UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                || status == UnifiedCrawlJob.PipelineStepStatus.FAILED
                || status == UnifiedCrawlJob.PipelineStepStatus.CANCELLED
                || status == UnifiedCrawlJob.PipelineStepStatus.SKIPPED
                || status == UnifiedCrawlJob.PipelineStepStatus.DEFERRED) {
            if (step.getStartedAt() == null) {
                step.setStartedAt(now);
            }
            step.setCompletedAt(now);
        }
        step.getStatus().set(status);
        if (totalItems >= 0) step.getTotalItems().set(totalItems);
        if (completedItems >= 0) step.getCompletedItems().set(completedItems);
        if (failedItems >= 0) step.getFailedItems().set(failedItems);
        if (totalBatches >= 0) step.getTotalBatches().set(totalBatches);
        if (completedBatches >= 0) step.getCompletedBatches().set(completedBatches);
        if (currentBatchSize >= 0) step.getCurrentBatchSize().set(currentBatchSize);
        step.getCurrentItem().set(currentItem);
        step.getMessage().set(message);
        step.setLastUpdatedAt(now);
        int total = step.getTotalItems().get();
        int done = step.getCompletedItems().get();
        step.getProgressPercent().set(status == UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                ? 100
                : percent(done, total));
    }

    void completePipelineStep(UnifiedCrawlJob job, String phase, int completedItems, String message) {
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        int total = Math.max(step.getTotalItems().get(), completedItems);
        applyPipelineStepUpdate(step, UnifiedCrawlJob.PipelineStepStatus.COMPLETED,
                completedItems, total, step.getFailedItems().get(),
                step.getCompletedBatches().get(), step.getTotalBatches().get(),
                0, null, message);
    }

    void failPipelineStep(UnifiedCrawlJob job, String phase, String message) {
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        applyPipelineStepUpdate(step, UnifiedCrawlJob.PipelineStepStatus.FAILED,
                step.getCompletedItems().get(), Math.max(1, step.getTotalItems().get()),
                step.getFailedItems().incrementAndGet(), step.getCompletedBatches().get(),
                step.getTotalBatches().get(), step.getCurrentBatchSize().get(),
                step.getCurrentItem().get(), message);
    }

    void skipPipelineStep(UnifiedCrawlJob job, String phase, String message) {
        updatePipelineStep(job, phase, UnifiedCrawlJob.PipelineStepStatus.SKIPPED,
                0, 0, 0, 0, 0, 0, null, message);
    }

    void incrementPipelineStep(UnifiedCrawlJob job,
                               String phase,
                               int completedItemsDelta,
                               int completedBatchesDelta,
                               String message) {
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
        int completedItems = completedItemsDelta > 0
                ? step.getCompletedItems().addAndGet(completedItemsDelta)
                : step.getCompletedItems().get();
        int completedBatches = completedBatchesDelta > 0
                ? step.getCompletedBatches().addAndGet(completedBatchesDelta)
                : step.getCompletedBatches().get();
        int totalItems = step.getTotalItems().get();
        UnifiedCrawlJob.PipelineStepStatus status =
                "GRAPH_PREP".equals(step.getStepId()) && totalItems > 0 && completedItems >= totalItems
                        ? UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                        : UnifiedCrawlJob.PipelineStepStatus.RUNNING;
        applyPipelineStepUpdate(step, status,
                completedItems, step.getTotalItems().get(), step.getFailedItems().get(),
                completedBatches, step.getTotalBatches().get(), step.getCurrentBatchSize().get(),
                step.getCurrentItem().get(), message);
    }

    int percent(int completed, int total) {
        if (total <= 0) {
            return completed > 0 ? 100 : 0;
        }
        return Math.max(0, Math.min(100, (int) Math.round((completed * 100.0) / total)));
    }

    String normalizeStepId(String phase) {
        if ("EMBEDDING".equals(phase) || "INDEXING".equals(phase)) {
            return "VECTOR_INDEXING";
        }
        return phase != null ? phase : "UNKNOWN";
    }

    String stepDisplayName(String phase) {
        return switch (normalizeStepId(phase)) {
            case "LOADING" -> "Source Loading";
            case "DISCOVERING" -> "Source Discovery";
            case "CONVERTING" -> "Text Conversion";
            case "ROUTING" -> "Content Routing";
            case "GRAPH_PREP" -> "Rule Graph Prep";
            case "CHUNKING" -> "Chunking";
            case "GRAPH_EXTRACTION" -> "Graph Extraction";
            case "SURFACING" -> "Crawl Surface";
            case "ENTITY_RESOLUTION" -> "Entity Resolution";
            case "EDGE_COMPUTATION" -> "Graph Edge Cleanup";
            case "VECTOR_INDEXING" -> "Embedding & Vector Index";
            case "ENRICHMENT" -> "Post-Crawl Enrichment";
            default -> humanizePhase(phase);
        };
    }

    String stepType(String phase) {
        return switch (normalizeStepId(phase)) {
            case "LOADING", "DISCOVERING" -> "IO";
            case "CONVERTING", "ROUTING", "CHUNKING" -> "CPU";
            case "GRAPH_PREP", "SURFACING", "ENTITY_RESOLUTION", "EDGE_COMPUTATION" -> "GRAPH";
            case "GRAPH_EXTRACTION" -> graphConstructorPresent ? "GRAPH_CONSTRUCTOR" : "LLM";
            case "VECTOR_INDEXING" -> "EMBEDDING";
            case "ENRICHMENT" -> "ENRICHMENT";
            default -> "PIPELINE";
        };
    }

    static String humanizePhase(String phase) {
        if (phase == null) return "post-processing";
        return phase.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
