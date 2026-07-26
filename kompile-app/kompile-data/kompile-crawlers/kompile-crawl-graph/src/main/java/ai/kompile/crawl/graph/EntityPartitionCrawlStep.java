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

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.PartitionPassConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.partition.DiscoveryPolicy;
import ai.kompile.core.graphrag.partition.PartitionStore;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import ai.kompile.core.graphrag.partition.reuse.ExtractionReuse;
import ai.kompile.core.graphrag.partition.staging.GraphCommitSink;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.PartitionRequest;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedRunAllResult;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedRunResult;
import ai.kompile.crawl.graph.partition.PartitionChunkExtractor;
import ai.kompile.crawl.graph.partition.PartitionChunkTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The crawl's entity-partition pass: a durable, per-subject coverage claim over what this run read.
 *
 * <p>Extraction alone answers "what did each chunk say". A partition answers a different question —
 * "for this entity, which chunks did we look at, which are still outstanding, and under what policy
 * was that judged complete" — and it writes that answer down so the next run can resume it instead
 * of re-deriving it. This step is where the two meet: the partitions are discovered from the graph
 * extraction just produced, and every chunk they admit is re-read through the crawl's own
 * extraction, staged, and committed once per partition.</p>
 *
 * <h3>Why it runs where it runs</h3>
 * <p>Late, after entity resolution and after vector indexing has landed. Grouping reads the
 * entities the graph actually ended up with, and the retrieval discovery channels are only
 * meaningful against an index that contains this run's chunks. When either is missing the pass
 * still runs — it just runs a policy that records what it could not look at, rather than one that
 * quietly looked in fewer places.</p>
 *
 * <h3>It never fails the crawl</h3>
 * <p>Everything the crawl was asked for is already written by the time this runs. A partition pass
 * that falls over is a missing coverage claim, not a lost graph, so it is reported on its own step
 * and the pipeline carries on.</p>
 */
@Component
public class EntityPartitionCrawlStep {

    private static final Logger log = LoggerFactory.getLogger(EntityPartitionCrawlStep.class);

    /** Canonical pipeline step id; matches {@link CrawlPipelineStepRegistry}. */
    public static final String STEP_ID = "ENTITY_PARTITIONS";

    /** What the pass did, for a caller (or a test) that wants more than the tracker's step state. */
    public enum Status {
        /** A gate said no; {@code detail} says which one. */
        SKIPPED,
        /** The graph held no entities to partition. */
        NOTHING_TO_PARTITION,
        /** Partitions ran; {@code result} carries per-subject coverage, failures and reuse. */
        RAN,
        /** The job was cancelled part-way; the partitions that finished are committed. */
        CANCELLED,
        /**
         * The pass produced no coverage — it fell over, or every subject in it did. The crawl's
         * graph is unaffected either way.
         */
        FAILED
    }

    /**
     * @param status what happened
     * @param detail the same sentence that was reported on the pipeline step
     * @param result the staged run, or null when no partition ran
     */
    public record Outcome(Status status, String detail, StagedRunAllResult result) {

        static Outcome of(Status status, String detail) {
            return new Outcome(status, detail, null);
        }
    }

    private final EntityPartitionCrawlService partitions;
    private final PartitionGraphCommitter committer;
    private final PartitionStore store;
    private final GraphExtractionOrchestrator extraction;
    private final PipelineStepTracker steps;
    private final CrawlDocumentTracker events;

    EntityPartitionCrawlStep(EntityPartitionCrawlService partitions,
                             PartitionGraphCommitter committer,
                             PartitionStore store,
                             GraphExtractionOrchestrator extraction,
                             PipelineStepTracker steps,
                             CrawlDocumentTracker events) {
        this.partitions = Objects.requireNonNull(partitions, "the entity partition service");
        this.committer = Objects.requireNonNull(committer, "a partition graph committer");
        this.store = Objects.requireNonNull(store, "a partition store to record coverage in");
        this.extraction = Objects.requireNonNull(extraction, "the crawl's graph extraction");
        this.steps = Objects.requireNonNull(steps, "the pipeline step tracker");
        this.events = Objects.requireNonNull(events, "the crawl event tracker");
    }

    /**
     * Runs one partition per entity group of {@code job}'s fact sheet.
     *
     * @param job      the crawl whose graph the partitions are discovered from and written back to
     * @param stepPlan the resolved step plan; the pass is opt-out like any other modular step
     * @param chunks   every chunk this run produced, as the source of chunk <em>text</em>
     * @param config   extraction config, passed through to the same extraction the crawl uses
     */
    public Outcome run(UnifiedCrawlJob job, CrawlStepPlan stepPlan, List<Document> chunks,
                       GraphExtractionConfig config) {
        if (job == null) {
            return Outcome.of(Status.SKIPPED, "no job");
        }
        if (stepPlan != null && !stepPlan.isRun(STEP_ID)) {
            return skip(job, "Entity partitions skipped by step plan");
        }
        if (!committer.canWrite()) {
            // Worth saying rather than silently skipping: with no graph behind it the pass would
            // read every chunk it could find, report every partition complete, and store nothing.
            return skip(job, "Entity partitions skipped: no knowledge graph is configured to "
                    + "write them into");
        }
        if (!extraction.hasGraphConstructor()) {
            return skip(job, "Entity partitions skipped: this deployment has no graph constructor "
                    + "to re-read a partition's chunks with");
        }
        Long factSheetId = job.getRequest() == null ? null : job.getRequest().getFactSheetId();
        if (factSheetId == null) {
            return skip(job, "Entity partitions skipped: the job has no fact sheet to scope "
                    + "coverage to");
        }
        Map<String, Document> index = PartitionChunkTexts.index(chunks);
        if (index.isEmpty()) {
            return skip(job, "Entity partitions skipped: this run holds no chunk text for a "
                    + "partition to read");
        }

        try {
            return partition(job, factSheetId, index, chunks, config);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            steps.failPipelineStep(job, STEP_ID, "Entity partitions failed: " + detail);
            events.recordEvent(job, STEP_ID, "ERROR", "Entity partition pass failed", detail);
            log.warn("[Job {}] Entity partition pass failed: {}", job.getJobId(), detail, e);
            return Outcome.of(Status.FAILED, detail);
        }
    }

    private Outcome partition(UnifiedCrawlJob job, Long factSheetId, Map<String, Document> index,
                              List<Document> chunks, GraphExtractionConfig config) {
        PartitionPassConfig settings = config == null
                ? PartitionPassConfig.defaults() : config.getPartition();
        GroupingPolicy grouping = settings.groupingPolicy();
        List<PartitionRequest> requests = partitions.groupedRequests(factSheetId, grouping,
                discoveryPolicy(job, settings));
        if (settings.hasBatchCaps()) {
            requests = requests.stream()
                    .map(request -> request.withBatching(
                            settings.getMaxItemsPerBatch() > 0
                                    ? settings.getMaxItemsPerBatch() : request.maxItemsPerBatch(),
                            settings.getTargetCostPerBatch()))
                    .toList();
        }
        if (requests.isEmpty()) {
            String detail = "No entity groups to partition";
            steps.completePipelineStep(job, STEP_ID, 0, detail);
            events.recordEvent(job, STEP_ID, "INFO", detail,
                    "fact sheet " + factSheetId + " has no entities");
            return Outcome.of(Status.NOTHING_TO_PARTITION, detail);
        }

        int total = requests.size();
        // Job-level phase, not just the per-step tracker: every progress surface that renders a
        // single "what is this crawl doing now" label reads getCurrentPhase(), and a pass that takes
        // minutes per group would otherwise keep displaying the phase that preceded it. Set here
        // rather than at the top of run() so a skipped pass never claims the phase. Same direct-set
        // pattern the other extracted helpers use (VectorIndexingHelper, GraphExtractionOrchestrator).
        job.getCurrentPhase().set(STEP_ID);
        steps.updatePipelineStep(job, STEP_ID, UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                0, total, 0, -1, -1, -1, null,
                "Partitioning " + total + " entity group(s) over " + index.size() + " chunk key(s)");
        // The grouping version travels with the event because it is what the resulting claims are
        // relative to: two runs under different versions grouped differently and are not
        // comparable, and that is invisible unless it is said.
        events.recordEvent(job, STEP_ID, "INFO", "Entity partitions starting",
                total + " group(s), " + index.size() + " chunk key(s), grouping "
                        + grouping.version());

        GraphCommitSink sink = committer.sinkFor(job, config,
                GraphExtractionOrchestrator.filterAndConvertDocs(chunks, job.getJobId()));
        PartitionChunkExtractor extractor = PartitionChunkExtractor.over(index,
                doc -> extraction.extractChunkGraph(doc, config, job));

        // One ledger across every partition: the overlap between partitions is the point of
        // partitioning, and without sharing it a chunk naming two subjects is extracted twice.
        ExtractionReuse reuse = ExtractionReuse.forRun();
        Map<String, StagedRunResult> runs = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();

        // Deliberately one request at a time rather than runAllStaged: a partition pass over a
        // large fact sheet is minutes of extraction, and a crawl that was cancelled should stop
        // between subjects instead of at the end of them.
        for (PartitionRequest request : requests) {
            if (job.isCancellationRequested()) {
                String detail = "Cancelled after " + runs.size() + " of " + total + " partition(s)";
                steps.updatePipelineStep(job, STEP_ID,
                        UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                        runs.size(), total, failed.size(), -1, -1, -1, null, detail);
                events.recordEvent(job, STEP_ID, "WARN", "Entity partitions cancelled", detail);
                return new Outcome(Status.CANCELLED, detail,
                        new StagedRunAllResult(runs, failed, reuse.stats()));
            }
            try {
                StagedRunResult ran = partitions.runStaged(request.withChunks(index), store,
                        extractor, sink, reuse);
                runs.put(request.subject(), ran);
                log.info("[Job {}] Partition {}: {}", job.getJobId(), request.subject(),
                        ran.describe());
            } catch (RuntimeException e) {
                // One subject failing is not the others failing — but it is also not a covered
                // subject, so it is named here and counted against the step.
                failed.add(request.subject());
                String detail = e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage();
                log.warn("[Job {}] Partition {} failed: {}", job.getJobId(), request.subject(),
                        detail);
                events.recordEvent(job, STEP_ID, "WARN",
                        "Partition failed for " + request.subject(), detail);
            }
            steps.updatePipelineStep(job, STEP_ID, UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    runs.size(), total, failed.size(), -1, -1, -1, request.subject(),
                    "Partition " + (runs.size() + failed.size()) + " of " + total);
        }

        StagedRunAllResult result = new StagedRunAllResult(runs, failed, reuse.stats());
        String detail = result.describe();
        if (runs.isEmpty()) {
            // Nothing was covered. Calling that RAN would contradict the step this just marked
            // FAILED; the result still travels, so a caller can see which subjects went down.
            steps.failPipelineStep(job, STEP_ID, "Entity partitions failed: " + detail);
            events.recordEvent(job, STEP_ID, "ERROR", "Every entity partition failed", detail);
            log.warn("[Job {}] Entity partitions: {}", job.getJobId(), detail);
            return new Outcome(Status.FAILED, detail, result);
        }
        steps.completePipelineStep(job, STEP_ID, runs.size(), detail);
        events.recordEvent(job, STEP_ID, failed.isEmpty() ? "INFO" : "WARN",
                "Entity partitions complete", detail);
        log.info("[Job {}] Entity partitions: {}", job.getJobId(), detail);
        return new Outcome(Status.RAN, detail, result);
    }

    /**
     * The full default policy only when the index this run wrote is actually searchable.
     *
     * <p>A vector store being configured is not the same as this run's chunks being in it: when
     * vector indexing was disabled, deferred or archived, retrieval would search a corpus that
     * predates the crawl and every partition would still claim the full policy's coverage. Falling
     * back to {@link EntityPartitionCrawlService#graphOnlyPolicy()} makes the gap part of the
     * record — it carries its own version, so the claim can never be compared to one that had the
     * index.</p>
     *
     * @param settings the configured pass; its own policy is narrowed the same way, so tuning the
     *                 pass never costs it this guard
     * @return null to keep each request's default policy, or the policy to run instead
     */
    private DiscoveryPolicy discoveryPolicy(UnifiedCrawlJob job, PartitionPassConfig settings) {
        DiscoveryPolicy configured = settings.discoveryOverride();
        if (partitions.hasVectorStore() && isCompleted(job, "VECTOR_INDEXING")) {
            return configured;
        }
        return EntityPartitionCrawlService.withoutVectorChannels(configured);
    }

    private static boolean isCompleted(UnifiedCrawlJob job, String stepId) {
        if (job.getPipelineSteps() == null) {
            return false;
        }
        for (UnifiedCrawlJob.PipelineStepProgress step : job.getPipelineSteps()) {
            if (stepId.equals(step.getStepId())) {
                return step.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.COMPLETED;
            }
        }
        return false;
    }

    private Outcome skip(UnifiedCrawlJob job, String detail) {
        steps.skipPipelineStep(job, STEP_ID, detail);
        events.recordEvent(job, STEP_ID, "INFO", "Entity partitions skipped", detail);
        log.info("[Job {}] {}", job.getJobId(), detail);
        return Outcome.of(Status.SKIPPED, detail);
    }
}
