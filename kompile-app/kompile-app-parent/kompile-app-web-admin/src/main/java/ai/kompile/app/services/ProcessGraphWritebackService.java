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

package ai.kompile.app.services;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.service.ProcessGraphCallback;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Writes process execution results back to the knowledge graph as entities and edges.
 * Creates PROCESS_RUN and STEP_EXECUTION entity nodes linked to any graph nodes
 * referenced during execution, enabling full provenance tracing from KG entities
 * through to the process outputs that consumed them.
 *
 * <h3>Fail-safe contract</h3>
 * All writes are async to avoid blocking the process engine. Failures are never
 * propagated to the caller. If the KnowledgeGraphService is unavailable, each batch
 * is retried up to {@value #MAX_RETRY_ATTEMPTS} times with exponential backoff
 * (delays: {@value #RETRY_DELAY_1_SECONDS}s, {@value #RETRY_DELAY_2_SECONDS}s,
 * {@value #RETRY_DELAY_3_SECONDS}s). After terminal failure the payload is appended
 * to a durable JSONL dead-letter file so it can be replayed later via
 * {@link #replayDeadLetters()} or the REST surface at
 * {@code /api/process/writeback/dead-letters}.
 *
 * <h3>Startup replay</h3>
 * On {@link ApplicationReadyEvent}, any entries in the dead-letter file are
 * replayed once; successes are removed, failures remain for manual retry.
 *
 * <h3>Dead-letter disabled path</h3>
 * When {@code kompile.data.dir} is not configured (unit-test contexts), durability
 * is disabled: in-memory retry still runs, but terminal failures are logged at WARN
 * and dropped instead of written to disk.
 */
@Service
@ConditionalOnBean(KnowledgeGraphService.class)
public class ProcessGraphWritebackService implements ProcessGraphCallback {

    private static final Logger log = LoggerFactory.getLogger(ProcessGraphWritebackService.class);

    // ── Retry policy ────────────────────────────────────────────────────────────
    static final int MAX_RETRY_ATTEMPTS = 3;
    static final long RETRY_DELAY_1_SECONDS = 1L;
    static final long RETRY_DELAY_2_SECONDS = 5L;
    static final long RETRY_DELAY_3_SECONDS = 25L;
    private static final long[] RETRY_DELAYS_SECONDS =
            { RETRY_DELAY_1_SECONDS, RETRY_DELAY_2_SECONDS, RETRY_DELAY_3_SECONDS };

    // ── Dependencies ─────────────────────────────────────────────────────────────

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ProcessEngineService processEngineService;

    /** Scheduler used for retry delays. Lazily created if not injected. */
    private volatile ThreadPoolTaskScheduler retryScheduler;

    /** Dead-letter store — enabled only when dataDir is configured. */
    private volatile ProcessWritebackDeadLetterStore deadLetterStore;

    /**
     * Explicit test-seam path: when set (via constructor or {@link #setProjectDataDir}),
     * overrides Spring's {@code kompile.data.dir} injection.
     */
    private Path projectDataDir;

    @Value("${kompile.data.dir:#{null}}")
    private String dataDirProperty;

    // ── Constructors ──────────────────────────────────────────────────────────────

    @Autowired
    public ProcessGraphWritebackService(KnowledgeGraphService knowledgeGraphService,
                                        ObjectMapper objectMapper) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.objectMapper = objectMapper;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ProcessGraphWritebackService() {
        this.objectMapper = JsonUtils.standardMapper();
    }

    /**
     * Test-seam constructor: use a specific data dir for the dead-letter store.
     * Retains Spring injection for dependencies; dataDir wins over any property.
     */
    public ProcessGraphWritebackService(KnowledgeGraphService knowledgeGraphService,
                                        ObjectMapper objectMapper,
                                        Path projectDataDir) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.objectMapper = objectMapper;
        this.projectDataDir = projectDataDir;
    }

    /** Test seam: set the data directory after construction (e.g. from @TempDir). */
    public void setProjectDataDir(@Nullable Path projectDataDir) {
        this.projectDataDir = projectDataDir;
        this.deadLetterStore = null; // force re-initialisation
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    /**
     * After application boot, replay any persisted dead-letter entries.
     * Runs asynchronously so it does not delay startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onApplicationReady() {
        replayDeadLetters();
    }

    // ── ProcessGraphCallback ───────────────────────────────────────────────────────

    @Override
    @Async
    public void onStepCompleted(WorkflowRun run, StepExecution step) {
        if (step.getGraphNodeIds() == null || step.getGraphNodeIds().isEmpty()) {
            return;
        }
        executeWithRetry(
                () -> performStepWrite(run, step),
                () -> buildStepDeadLetter(run, step),
                "step execution step-exec:" + run.getId() + "/" + step.getStepId(),
                1);
    }

    @Override
    @Async
    public void onRunCompleted(WorkflowRun run) {
        executeWithRetry(
                () -> performRunWrite(run),
                () -> buildRunDeadLetter(run),
                "process run process-run:" + run.getId(),
                1);
    }

    // ── Public replay API (also called from REST controller) ─────────────────────

    /**
     * Replay all dead-letter entries synchronously.
     * Returns a summary map with keys {@code total}, {@code succeeded}, {@code failed}.
     * This is public and non-async so the REST controller can call it and return results.
     */
    public Map<String, Object> replayDeadLetters() {
        ProcessWritebackDeadLetterStore store = deadLetterStore();
        List<ProcessWritebackDeadLetterStore.DeadLetterEntry> entries = store.readAll();
        int total = entries.size();
        if (total == 0) {
            log.info("ProcessWriteback startup replay: dead-letter store is empty");
            return Map.of("total", 0, "succeeded", 0, "failed", 0);
        }

        List<String> succeeded = new ArrayList<>();
        AtomicInteger failedCount = new AtomicInteger(0);

        for (ProcessWritebackDeadLetterStore.DeadLetterEntry entry : entries) {
            try {
                replayEntry(entry);
                succeeded.add(entry.id);
                log.debug("ProcessWriteback replay: succeeded for entry id={} type={} runId={}",
                        entry.id, entry.callbackType, entry.runId);
            } catch (Exception ex) {
                failedCount.incrementAndGet();
                log.warn("ProcessWriteback replay: failed for entry id={} type={} runId={}: {}",
                        entry.id, entry.callbackType, entry.runId, ex.getMessage());
            }
        }

        int removed = store.removeSucceeded(succeeded);
        log.info("ProcessWriteback replay complete: total={} succeeded={} failed={} removed={}",
                total, succeeded.size(), failedCount.get(), removed);
        return Map.of("total", total, "succeeded", succeeded.size(), "failed", failedCount.get());
    }

    /**
     * Returns the dead-letter store (initialised lazily so it respects the test-seam path
     * set via {@link #setProjectDataDir} after construction).
     */
    public ProcessWritebackDeadLetterStore deadLetterStore() {
        if (deadLetterStore == null) {
            synchronized (this) {
                if (deadLetterStore == null) {
                    deadLetterStore = new ProcessWritebackDeadLetterStore(
                            resolveDataDir(), objectMapper);
                }
            }
        }
        return deadLetterStore;
    }

    // ── Retry engine ─────────────────────────────────────────────────────────────

    /**
     * Execute {@code action} inline; on failure schedule retries with backoff.
     * The caller is never blocked and never receives an exception.
     *
     * @param action       the writeback batch to attempt
     * @param deadLetterFn supplier of the dead-letter entry (called only on terminal failure)
     * @param context      log label
     * @param attemptNumber 1-based attempt index (1 = first inline attempt)
     */
    private void executeWithRetry(
            Runnable action,
            java.util.function.Supplier<ProcessWritebackDeadLetterStore.DeadLetterEntry> deadLetterFn,
            String context,
            int attemptNumber) {
        if (knowledgeGraphService == null) {
            // KG service bean absent — skip to dead-letter immediately
            deadLetter(deadLetterFn, context, attemptNumber, "KnowledgeGraphService not available");
            return;
        }
        try {
            action.run();
            if (attemptNumber > 1) {
                log.info("ProcessWriteback retry succeeded on attempt {} for {}", attemptNumber, context);
            }
        } catch (Exception ex) {
            if (attemptNumber <= MAX_RETRY_ATTEMPTS) {
                long delaySecs = RETRY_DELAYS_SECONDS[attemptNumber - 1];
                log.warn("ProcessWriteback attempt {}/{} failed for {} (retry in {}s): {}",
                        attemptNumber, MAX_RETRY_ATTEMPTS, context, delaySecs, ex.getMessage());
                scheduler().schedule(
                        () -> executeWithRetry(action, deadLetterFn, context, attemptNumber + 1),
                        Instant.now().plus(Duration.ofSeconds(delaySecs)));
            } else {
                log.warn("ProcessWriteback terminal failure for {} after {} attempts: {}",
                        context, attemptNumber - 1, ex.getMessage());
                deadLetter(deadLetterFn, context, attemptNumber, ex.getMessage());
            }
        }
    }

    private void deadLetter(
            java.util.function.Supplier<ProcessWritebackDeadLetterStore.DeadLetterEntry> deadLetterFn,
            String context, int attemptNumber, String failureSummary) {
        try {
            ProcessWritebackDeadLetterStore.DeadLetterEntry entry = deadLetterFn.get();
            if (entry != null) {
                entry.failureSummary = failureSummary;
                entry.attemptNumber = attemptNumber;
                deadLetterStore().append(entry);
            }
        } catch (Exception ex) {
            log.error("ProcessWriteback: failed to build dead-letter entry for {}: {}", context, ex.getMessage());
        }
    }

    // ── Actual write logic (extracted so replay can call them too) ───────────────

    private void performStepWrite(WorkflowRun run, StepExecution step) {
        String stepExtId = "step-exec:" + run.getId() + "/" + step.getStepId();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("entity_subtype", "step_execution");
        meta.put("runId", run.getId());
        meta.put("stepId", step.getStepId());
        meta.put("stepName", step.getStepName());
        meta.put("status", step.getStatus().name());
        if (step.getExecutedBy() != null) meta.put("executedBy", step.getExecutedBy());
        if (step.getStartedAt() != null) meta.put("startedAt", step.getStartedAt().toString());
        if (step.getCompletedAt() != null) meta.put("completedAt", step.getCompletedAt().toString());
        if (step.getInputHash() != null) meta.put("inputHash", step.getInputHash());
        if (step.getOutputHash() != null) meta.put("outputHash", step.getOutputHash());
        if (step.getError() != null) meta.put("error", step.getError());

        String title = step.getStepName() + " (" + step.getStatus().name().toLowerCase() + ")";
        GraphNode stepNode = knowledgeGraphService.createNode(
                NodeLevel.ENTITY, stepExtId, title,
                "Step execution: " + step.getStepName() + " in run " + run.getId(),
                meta);
        List<KnowledgeGraphService.EdgeSpec> edgeSpecs = new ArrayList<>();

        for (String graphNodeId : step.getGraphNodeIds()) {
            edgeSpecs.add(edgeSpec(
                    stepNode.getNodeId(), graphNodeId,
                    EdgeType.USER_DEFINED, 0.9,
                    "EXECUTED_ON",
                    "Step '" + step.getStepName() + "' executed on referenced graph data"));
        }

        if (step.getExecutedBy() != null && step.getExecutedBy().contains(":")) {
            try {
                String executor = step.getExecutedBy();
                String executorType = executor.substring(0, executor.indexOf(':'));
                String executorName = executor.substring(executor.indexOf(':') + 1);
                String toolExtId = "executor:" + executorType + ":" + executorName;

                Optional<GraphNode> toolNodeOpt = knowledgeGraphService.getNodeByExternalId(
                        toolExtId, NodeLevel.ENTITY);
                GraphNode toolNode;
                if (toolNodeOpt.isPresent()) {
                    toolNode = toolNodeOpt.get();
                } else {
                    Map<String, Object> toolMeta = new LinkedHashMap<>();
                    toolMeta.put("entity_subtype", executorType + "_executor");
                    toolMeta.put("executorType", executorType);
                    toolMeta.put("executorName", executorName);
                    toolNode = knowledgeGraphService.createNode(
                            NodeLevel.ENTITY, toolExtId,
                            executorType.substring(0, 1).toUpperCase() + executorType.substring(1)
                                    + ": " + executorName,
                            executorType + " executor used by workflow steps",
                            toolMeta);
                }
                edgeSpecs.add(edgeSpec(
                        stepNode.getNodeId(), toolNode.getNodeId(),
                        EdgeType.USER_DEFINED, 0.8,
                        "USES_TOOL",
                        "Step '" + step.getStepName() + "' used " + executor));
            } catch (Exception e) {
                log.debug("Failed to create USES_TOOL edge for {}: {}", step.getExecutedBy(), e.getMessage());
            }
        }

        if (step.getStatus() == StepExecutionStatus.COMPLETED && step.getOutputs() != null
                && !step.getOutputs().isEmpty()) {
            try {
                String outputSummary = summarizeOutputs(step.getOutputs());
                if (outputSummary != null) {
                    knowledgeGraphService.updateNode(stepNode.getNodeId(), null, outputSummary, null);

                    String outputExtId = "step-output:" + run.getId() + "/" + step.getStepId();
                    Map<String, Object> outputMeta = new LinkedHashMap<>();
                    outputMeta.put("entity_subtype", "step_output");
                    outputMeta.put("runId", run.getId());
                    outputMeta.put("stepId", step.getStepId());
                    outputMeta.put("outputKeys", String.join(",", step.getOutputs().keySet()));
                    if (step.getOutputHash() != null) outputMeta.put("outputHash", step.getOutputHash());

                    GraphNode outputNode = knowledgeGraphService.createNode(
                            NodeLevel.ENTITY, outputExtId,
                            "Output: " + step.getStepName(),
                            outputSummary, outputMeta);

                    edgeSpecs.add(edgeSpec(
                            stepNode.getNodeId(), outputNode.getNodeId(),
                            EdgeType.USER_DEFINED, 1.0,
                            "PRODUCED_OUTPUT",
                            "Step '" + step.getStepName() + "' produced output"));
                }
            } catch (Exception e) {
                log.debug("Failed to create OUTPUT entity for step {}: {}", stepExtId, e.getMessage());
            }
        }

        if (step.getStatus() == StepExecutionStatus.FAILED && step.getError() != null
                && !step.getError().isBlank()) {
            try {
                String errorExtId = "step-error:" + run.getId() + "/" + step.getStepId();
                Map<String, Object> errorMeta = new LinkedHashMap<>();
                errorMeta.put("entity_subtype", "step_error");
                errorMeta.put("runId", run.getId());
                errorMeta.put("stepId", step.getStepId());
                errorMeta.put("errorMessage", step.getError());

                GraphNode errorNode = knowledgeGraphService.createNode(
                        NodeLevel.ENTITY, errorExtId,
                        "Error: " + step.getStepName(),
                        step.getError(), errorMeta);

                edgeSpecs.add(edgeSpec(
                        stepNode.getNodeId(), errorNode.getNodeId(),
                        EdgeType.USER_DEFINED, 1.0,
                        "PRODUCED_ERROR",
                        "Step '" + step.getStepName() + "' failed: " + step.getError()));
            } catch (Exception e) {
                log.debug("Failed to create ERROR entity for step {}: {}", stepExtId, e.getMessage());
            }
        }

        createEdgesBatch(edgeSpecs, "step execution " + stepExtId);

        log.debug("Wrote step execution {} to KG (linked to {} graph nodes)",
                stepExtId, step.getGraphNodeIds().size());
    }

    private void performRunWrite(WorkflowRun run) {
        String runExtId = "process-run:" + run.getId();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("entity_subtype", "process_run");
        meta.put("processDefinitionId", run.getProcessDefinitionId());
        meta.put("status", run.getStatus().name());
        if (run.getStartedAt() != null) meta.put("startedAt", run.getStartedAt().toString());
        if (run.getCompletedAt() != null) meta.put("completedAt", run.getCompletedAt().toString());

        String title = "Process Run: " + run.getProcessDefinitionId() + " (" + run.getStatus().name().toLowerCase() + ")";
        GraphNode runNode = knowledgeGraphService.createNode(
                NodeLevel.ENTITY, runExtId, title,
                "Workflow run " + run.getId(), meta);
        List<KnowledgeGraphService.EdgeSpec> edgeSpecs = new ArrayList<>();

        if (run.getProcessDefinitionId() != null) {
            String procDefExtId = "process-def:" + run.getProcessDefinitionId();
            Optional<GraphNode> procDefOpt = knowledgeGraphService.getNodeByExternalId(
                    procDefExtId, NodeLevel.ENTITY);
            if (procDefOpt.isEmpty()) {
                String processName = resolveProcessDefinitionName(run.getProcessDefinitionId());
                Map<String, Object> defMeta = new LinkedHashMap<>();
                defMeta.put("entity_subtype", "process_definition");
                defMeta.put("processDefinitionId", run.getProcessDefinitionId());
                GraphNode defNode = knowledgeGraphService.createNode(
                        NodeLevel.ENTITY, procDefExtId,
                        processName,
                        "Process definition: " + processName, defMeta);
                procDefOpt = Optional.of(defNode);
            }
            edgeSpecs.add(edgeSpec(
                    runNode.getNodeId(), procDefOpt.get().getNodeId(),
                    EdgeType.USER_DEFINED, 1.0,
                    "INSTANCE_OF",
                    "Process run is an instance of " + run.getProcessDefinitionId()));
        }

        if (run.getStepExecutions() != null) {
            List<KnowledgeGraphService.ExternalNodeLookup> stepLookups = new ArrayList<>();
            for (StepExecution step : run.getStepExecutions()) {
                stepLookups.add(new KnowledgeGraphService.ExternalNodeLookup(
                        stepExecutionExternalId(run, step), NodeLevel.ENTITY, null));
            }
            Map<String, GraphNode> stepNodesById = new LinkedHashMap<>();
            for (GraphNode node : knowledgeGraphService.getNodesByExternalIds(stepLookups)) {
                if (node != null && node.getNodeId() != null) {
                    stepNodesById.putIfAbsent(node.getNodeId(), node);
                }
            }
            for (StepExecution step : run.getStepExecutions()) {
                GraphNode stepNode = stepNodesById.get(externalNodeId(
                        NodeLevel.ENTITY, stepExecutionExternalId(run, step)));
                if (stepNode != null) {
                    edgeSpecs.add(edgeSpec(
                            runNode.getNodeId(), stepNode.getNodeId(),
                            EdgeType.CONTAINS, 1.0,
                            "CONTAINS_STEP",
                            "Run contains step: " + step.getStepName()));
                }
            }
        }

        Set<String> allGraphNodeIds = new LinkedHashSet<>();
        if (run.getGraphNodeIds() != null) allGraphNodeIds.addAll(run.getGraphNodeIds());
        if (run.getStepExecutions() != null) {
            for (StepExecution step : run.getStepExecutions()) {
                if (step.getGraphNodeIds() != null) allGraphNodeIds.addAll(step.getGraphNodeIds());
            }
        }
        for (String nodeId : allGraphNodeIds) {
            edgeSpecs.add(edgeSpec(
                    runNode.getNodeId(), nodeId,
                    EdgeType.USER_DEFINED, 0.7,
                    "REFERENCES_DATA",
                    "Process run referenced graph data"));
        }

        createEdgesBatch(edgeSpecs, "process run " + runExtId);

        log.debug("Wrote process run {} to KG (linked to {} graph nodes)",
                runExtId, allGraphNodeIds.size());
    }

    // ── Dead-letter replay ────────────────────────────────────────────────────────

    /**
     * Replay a single dead-letter entry synchronously. Throws on failure so the caller
     * can decide whether to keep or remove the entry.
     */
    private void replayEntry(ProcessWritebackDeadLetterStore.DeadLetterEntry e) {
        if ("STEP_COMPLETED".equals(e.callbackType)) {
            replayStepEntry(e);
        } else if ("RUN_COMPLETED".equals(e.callbackType)) {
            replayRunEntry(e);
        } else {
            log.warn("ProcessWriteback replay: unknown callbackType='{}' in entry id={}", e.callbackType, e.id);
        }
    }

    /**
     * Replay a STEP_COMPLETED dead-letter entry.
     * Reconstructs a minimal WorkflowRun+StepExecution from the saved fields
     * and re-runs performStepWrite.
     */
    private void replayStepEntry(ProcessWritebackDeadLetterStore.DeadLetterEntry e) {
        if (e.graphNodeIds == null || e.graphNodeIds.isEmpty()) {
            // Nothing to write (skipped originally, shouldn't be dead-lettered)
            return;
        }
        // Build a minimal synthetic StepExecution from the saved fields
        StepExecution.StepExecutionBuilder stepBuilder = StepExecution.builder()
                .stepId(e.stepId)
                .stepName(e.stepName)
                .status(e.stepStatus != null ? StepExecutionStatus.valueOf(e.stepStatus) : StepExecutionStatus.COMPLETED)
                .graphNodeIds(e.graphNodeIds);
        if (e.executedBy != null) stepBuilder.executedBy(e.executedBy);
        if (e.outputHash != null) stepBuilder.outputHash(e.outputHash);
        if (e.inputHash != null) stepBuilder.inputHash(e.inputHash);
        if (e.error != null) stepBuilder.error(e.error);
        StepExecution step = stepBuilder.build();

        WorkflowRun run = WorkflowRun.builder()
                .id(e.runId)
                .processDefinitionId(e.processDefinitionId)
                .status(ai.kompile.process.execution.RunStatus.COMPLETED)
                .build();

        performStepWrite(run, step);
    }

    /**
     * Replay a RUN_COMPLETED dead-letter entry.
     * Reconstructs a minimal WorkflowRun from the saved fields and re-runs performRunWrite.
     */
    private void replayRunEntry(ProcessWritebackDeadLetterStore.DeadLetterEntry e) {
        WorkflowRun.WorkflowRunBuilder runBuilder = WorkflowRun.builder()
                .id(e.runId)
                .processDefinitionId(e.processDefinitionId)
                .status(e.runStatus != null ? ai.kompile.process.execution.RunStatus.valueOf(e.runStatus)
                        : ai.kompile.process.execution.RunStatus.COMPLETED);
        if (e.graphNodeIds != null && !e.graphNodeIds.isEmpty()) {
            runBuilder.graphNodeIds(e.graphNodeIds);
        }
        // Step executions are not fully stored in the dead-letter (we only store graphNodeIds);
        // they will have been dead-lettered individually as STEP_COMPLETED entries.
        performRunWrite(runBuilder.build());
    }

    // ── Dead-letter entry builders ────────────────────────────────────────────────

    private ProcessWritebackDeadLetterStore.DeadLetterEntry buildStepDeadLetter(
            WorkflowRun run, StepExecution step) {
        String outputKeys = null;
        String outputSummary = null;
        if (step.getOutputs() != null && !step.getOutputs().isEmpty()) {
            outputKeys = String.join(",", step.getOutputs().keySet());
            outputSummary = summarizeOutputs(step.getOutputs());
        }
        return ProcessWritebackDeadLetterStore.DeadLetterEntry.forStep(
                run.getId(), step.getStepId(), run.getProcessDefinitionId(),
                step.getStepName(), step.getStatus() != null ? step.getStatus().name() : null,
                step.getGraphNodeIds(),
                step.getExecutedBy(), outputKeys, step.getOutputHash(), step.getInputHash(), step.getError(),
                outputSummary,
                null, 0); // failureSummary/attemptNumber filled in by deadLetter()
    }

    private ProcessWritebackDeadLetterStore.DeadLetterEntry buildRunDeadLetter(WorkflowRun run) {
        Set<String> allNodes = new LinkedHashSet<>();
        if (run.getGraphNodeIds() != null) allNodes.addAll(run.getGraphNodeIds());
        if (run.getStepExecutions() != null) {
            for (StepExecution s : run.getStepExecutions()) {
                if (s.getGraphNodeIds() != null) allNodes.addAll(s.getGraphNodeIds());
            }
        }
        return ProcessWritebackDeadLetterStore.DeadLetterEntry.forRun(
                run.getId(), run.getProcessDefinitionId(),
                run.getStatus() != null ? run.getStatus().name() : null,
                run.getStartedAt() != null ? run.getStartedAt().toString() : null,
                run.getCompletedAt() != null ? run.getCompletedAt().toString() : null,
                allNodes.isEmpty() ? null : new ArrayList<>(allNodes),
                null, 0); // filled in by deadLetter()
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────────

    private ThreadPoolTaskScheduler scheduler() {
        if (retryScheduler == null) {
            synchronized (this) {
                if (retryScheduler == null) {
                    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
                    s.setPoolSize(2);
                    s.setThreadNamePrefix("writeback-retry-");
                    s.setDaemon(true);
                    s.initialize();
                    retryScheduler = s;
                }
            }
        }
        return retryScheduler;
    }

    // ── Data-dir resolution (mirrors KbGroundingService pattern exactly) ─────────

    /**
     * Resolve the project data directory.
     * Priority: explicit test-seam path → Spring {@code kompile.data.dir} property →
     * {@code kompile.data.dir} JVM system property → {@code null} (dead-letter disabled).
     *
     * <p>Never falls back to CWD to prevent test contamination.</p>
     */
    @Nullable
    Path resolveDataDir() {
        if (projectDataDir != null) {
            return projectDataDir;
        }
        if (dataDirProperty != null && !dataDirProperty.isBlank()) {
            return Path.of(dataDirProperty);
        }
        String sysProp = System.getProperty("kompile.data.dir");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }
        return null; // dead-letter disabled
    }

    // ── KG write helpers (unchanged from original) ────────────────────────────────

    private static String stepExecutionExternalId(WorkflowRun run, StepExecution step) {
        return "step-exec:" + run.getId() + "/" + step.getStepId();
    }

    private static String externalNodeId(NodeLevel level, String externalId) {
        return level.name().toLowerCase(Locale.ROOT) + "_" + externalId;
    }

    private KnowledgeGraphService.EdgeSpec edgeSpec(String sourceNodeId, String targetNodeId,
                                                    EdgeType edgeType, double weight,
                                                    String label, String description) {
        return new KnowledgeGraphService.EdgeSpec(
                sourceNodeId, targetNodeId, edgeType, weight, description,
                label, null, EdgeProvenance.INFERRED, null);
    }

    private void createEdgesBatch(List<KnowledgeGraphService.EdgeSpec> edgeSpecs, String context) {
        if (edgeSpecs == null || edgeSpecs.isEmpty()) {
            return;
        }
        try {
            knowledgeGraphService.createEdgesBatch(edgeSpecs);
        } catch (Exception e) {
            log.debug("Failed to create process writeback edges for {}: {}", context, e.getMessage());
        }
    }

    private String summarizeOutputs(Map<String, Object> outputs) {
        if (outputs == null || outputs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Outputs: ");
        int count = 0;
        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            if (count > 0) sb.append(", ");
            sb.append(entry.getKey()).append("=");
            Object val = entry.getValue();
            if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else if (val instanceof String s) {
                sb.append(s.length() > 50 ? s.substring(0, 50) + "..." : s);
            } else {
                sb.append("[").append(val != null ? val.getClass().getSimpleName() : "null").append("]");
            }
            count++;
            if (count >= 5) { sb.append(", ..."); break; }
        }
        return count > 0 ? sb.toString() : null;
    }

    private String resolveProcessDefinitionName(String processDefinitionId) {
        if (processEngineService != null && processDefinitionId != null) {
            try {
                ProcessDefinition def = processEngineService.getProcess(processDefinitionId, -1);
                if (def != null && def.getName() != null && !def.getName().isBlank()) {
                    return def.getName();
                }
            } catch (Exception e) {
                log.debug("Could not resolve process name for {}: {}", processDefinitionId, e.getMessage());
            }
        }
        return "Process: " + processDefinitionId;
    }
}
