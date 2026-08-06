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
package ai.kompile.app.services.subprocess;

import ai.kompile.app.learning.subprocess.LearningSubprocessArgs;
import ai.kompile.app.learning.subprocess.LearningSubprocessMessage;
import ai.kompile.app.learning.subprocess.LearningSubprocessMessage.Completed;
import ai.kompile.app.learning.subprocess.LearningSubprocessMessage.Failed;
import ai.kompile.app.learning.subprocess.LearningSubprocessMessage.Heartbeat;
import ai.kompile.app.learning.subprocess.LearningSubprocessMessage.Progress;
import ai.kompile.app.learning.subprocess.MebnLearningInput;
import ai.kompile.app.learning.subprocess.PslLearningInput;
import ai.kompile.app.learning.subprocess.ReasoningLearningSubprocessArgs;
import ai.kompile.app.services.ResourceTelemetryService;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KgeTrainingExecutor;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.core.reasoning.ReasoningLearningExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.linalg.api.ndarray.INDArray;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Out-of-process KGE / PSL / MEBN learning launcher.
 *
 * <p>Implements {@link KgeTrainingExecutor} and {@link ReasoningLearningExecutor} so the
 * knowledge-graph services delegate expensive ND4J/SameDiff training to a separate JVM —
 * keeping the main app alive (and its memory bounded) even if a run OOMs.</p>
 *
 * <p><b>Built on {@link ManagedSubprocessLauncher}</b>: the base owns the JVM command
 * (heap + JavaCPP native cap + uber-jar classpath), process spawn, {@code SubprocessRegistry}
 * registration, the stdout/stderr reader threads, live-log streaming to the
 * {@code SubprocessLogBus}, and {@link #requestRestart}. This subclass keeps only the
 * learning protocol: it parses {@code LEARNING_MSG:} lines via a
 * {@link StructuredLineHandler} and resolves a per-run result future, using
 * {@link Process#onExit()} so the future resolves on <em>any</em> exit (normal, killed by
 * the watchdog, or crashed).</p>
 *
 * <p><b>Enabled only when {@code kompile.learning.subprocess.enabled=true}.</b></p>
 */
@Service
// On by default with no property required; runtime enable/disable is managed config, not a property file.
public class LearningSubprocessLauncher extends ManagedSubprocessLauncher
        implements KgeTrainingExecutor, ReasoningLearningExecutor {

    private static final String SUBPROCESS_ID = "learning";
    private static final String MAIN_CLASS =
            "ai.kompile.app.learning.subprocess.LearningSubprocessMain";

    /** Default heap for the learning subprocess (8 g); overridden per-type via the SubprocessConfigService JSON. */
    private static final int DEFAULT_HEAP_MB = 8192;

    /** UI-controllable managed-config (subprocess-ingest-config.json → subprocessTypes.learning). */
    @Autowired(required = false)
    private SubprocessConfigService subprocessConfig;

    /**
     * Hard override for the JavaCPP native cap in MB.
     * When {@code > 0}, this value is used directly (no dynamic computation).
     * When {@code 0} (the default), the cap is computed dynamically from host
     * MemAvailable × {@code native-cap-fraction}, bounded by floor/ceiling below.
     */
    @Value("${kompile.learning.subprocess.max-physical-mb:0}")
    private int maxPhysicalMbConfig;

    /**
     * Fraction of host MemAvailable to allocate as the learning subprocess native cap.
     * Default 0.75 (75 %): leaves 25 % for the OS, main app, and other subprocesses.
     * Only used when {@code max-physical-mb} is 0 (dynamic mode).
     */
    @Value("${kompile.learning.subprocess.native-cap-fraction:0.75}")
    private double nativeCapFraction;

    /**
     * Minimum native cap in MB (safety floor).
     * Even if MemAvailable × fraction would be lower, this floor applies.
     * Default 4096 MB (4 GB) — enough for shared libs + small graphs.
     */
    @Value("${kompile.learning.subprocess.native-cap-floor-mb:4096}")
    private long nativeCapFloorMb;

    /**
     * Maximum native cap in MB (safety ceiling).
     * Even if MemAvailable × fraction would be higher, this ceiling applies.
     * Default 0 (no ceiling). Set to e.g. 65536 to cap at 64 GB on very large hosts.
     */
    @Value("${kompile.learning.subprocess.native-cap-ceiling-mb:0}")
    private long nativeCapCeilingMb;

    @Value("${kompile.learning.subprocess.stale-timeout-ms:180000}")
    private long staleTimeoutMs;

    @Value("${kompile.learning.subprocess.timeout-ms:3600000}")
    private long jobTimeoutMs;

    /** Used to read MemAvailable for dynamic native-cap computation. Optional: absent outside app-main. */
    @Autowired(required = false)
    private ResourceTelemetryService resourceTelemetryService;

    /** Source of the declared heavy-memory budget (#3) that also caps this subprocess. Optional. */
    @Autowired(required = false)
    private ResourceSchedulerConfigService resourceSchedulerConfigService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Per-run liveness for the heartbeat stale-watchdog (process tracking lives in the base). */
    private final ConcurrentHashMap<String, AtomicLong> lastHeartbeatByRun = new ConcurrentHashMap<>();

    // ── ManagedSubprocessLauncher configuration ───────────────────────────────

    @Override
    public String getSubprocessId() {
        return SUBPROCESS_ID;
    }

    @Override
    protected String getTypeLabel() {
        return "learning";
    }

    @Override
    protected String getMainClass() {
        return MAIN_CLASS;
    }

    @Override
    protected int getHeapMb() {
        return subprocessConfig != null
                ? parseHeapMb(subprocessConfig.heapSizeForType("learning", "8g"), DEFAULT_HEAP_MB)
                : DEFAULT_HEAP_MB;
    }

    /** Parse a heap-size string ({@code "8g"}, {@code "4096m"}, {@code "2048"}) to MB. */
    private static int parseHeapMb(String heap, int defaultMb) {
        if (heap == null || heap.isBlank()) return defaultMb;
        String h = heap.trim().toLowerCase();
        try {
            if (h.endsWith("g")) return (int) (Double.parseDouble(h.substring(0, h.length() - 1)) * 1024);
            if (h.endsWith("m")) return (int) Double.parseDouble(h.substring(0, h.length() - 1));
            return Integer.parseInt(h);
        } catch (NumberFormatException e) {
            return defaultMb;
        }
    }

    /**
     * Dynamic native-memory cap for the learning subprocess's JavaCPP allocator.
     *
     * <p>When {@code kompile.learning.subprocess.max-physical-mb > 0} that value is
     * used verbatim (manual override, no dynamic computation).</p>
     *
     * <p>Otherwise the cap is derived from host {@code MemAvailable} (Linux
     * {@code /proc/meminfo}) via {@link ResourceTelemetryService#latest()}, scaled by
     * {@code native-cap-fraction} and bounded by the configured floor and ceiling.
     * Falls back to {@code 4 × heap} when telemetry is unavailable (non-Linux / bean
     * absent), which mirrors the base-class behaviour but is logged prominently so the
     * operator knows the fallback fired.</p>
     *
     * <p>The property {@code org.bytedeco.javacpp.maxphysicalbytes} controls how much
     * physical RSS the subprocess JVM is allowed to consume in total — shared libs
     * (libnd4j, BLAS) alone can occupy 20–28 GB on large installations, so a hardcoded
     * 32 GB cap was only 4 GB above the shared-lib baseline, leaving no headroom for
     * actual training data and causing the OOM seen at
     * {@code EmbeddingInitializer.uniformTransE} on 9 k-entity graphs.</p>
     */
    @Override
    protected long getMaxPhysicalMb() {
        // Manual override wins unconditionally.
        if (maxPhysicalMbConfig > 0) {
            log.debug("[learning] native cap: manual override {} MB", maxPhysicalMbConfig);
            return maxPhysicalMbConfig;
        }

        // The budget this run declares to the HeavyMemoryCoordinator (#3). Capping the subprocess at
        // (at most) its declared footprint stops it from greedily claiming a fraction of ALL free RAM
        // at spawn on large hosts — which would defeat the coordinator's reservation and block other
        // admitted heavy ops. 0 when managed config is unavailable.
        long declaredBudgetMb = declaredNativeBudgetMb();

        // Dynamic ceiling from live MemAvailable — protects small hosts from over-claiming even when
        // the declared budget is larger than the RAM actually free right now. -1 when unavailable.
        long dynamicMb = -1L;
        if (resourceTelemetryService != null) {
            try {
                long memAvailableMb = resourceTelemetryService.latest().memAvailableMb();
                if (memAvailableMb > 0) {
                    dynamicMb = (long) (memAvailableMb * nativeCapFraction);
                }
            } catch (Exception e) {
                log.debug("[learning] ResourceTelemetryService.latest() failed: {}", e.getMessage());
            }
        }

        // Effective cap = the smaller of the declared budget and the dynamic MemAvailable ceiling
        // (whichever are known), so we never exceed what we declared NOR what the host can spare.
        long cap;
        if (declaredBudgetMb > 0 && dynamicMb > 0) {
            cap = Math.min(declaredBudgetMb, dynamicMb);
        } else if (declaredBudgetMb > 0) {
            cap = declaredBudgetMb;
        } else if (dynamicMb > 0) {
            cap = dynamicMb;
        } else {
            // Fallback: 4 × heap (same as base class default).
            long fallback = (long) getHeapMb() * 4L;
            log.warn("[learning] neither declared budget nor MemAvailable available "
                    + "(ResourceSchedulerConfigService/ResourceTelemetryService absent or not yet polled); "
                    + "falling back to 4×heap = {} MB. "
                    + "Set kompile.learning.subprocess.max-physical-mb to override.", fallback);
            return fallback;
        }

        // Apply floor then ceiling.
        cap = Math.max(cap, nativeCapFloorMb);
        if (nativeCapCeilingMb > 0) {
            cap = Math.min(cap, nativeCapCeilingMb);
        }
        log.info("[learning] native cap: {} MB (declaredBudget={} MB, dynamicCeiling={} MB [MemAvailable×{}], floor={}, ceiling={})",
                cap, declaredBudgetMb, dynamicMb, nativeCapFraction, nativeCapFloorMb,
                nativeCapCeilingMb > 0 ? nativeCapCeilingMb + " MB" : "none");
        return cap;
    }

    /**
     * The native budget this learning run declares to the {@code HeavyMemoryCoordinator} (#3), in MB
     * — the {@code "kge-training"} entry of {@code heavyMemoryOpEstimatesMb}. Returns 0 when the
     * managed config is unavailable, in which case only the dynamic MemAvailable ceiling applies.
     */
    private long declaredNativeBudgetMb() {
        if (resourceSchedulerConfigService == null) {
            return 0L;
        }
        try {
            Map<String, Long> estimates =
                    resourceSchedulerConfigService.getConfiguration().getHeavyMemoryOpEstimatesMb();
            if (estimates == null) {
                return 0L;
            }
            Long budget = estimates.get("kge-training");
            return budget != null && budget > 0 ? budget : 0L;
        } catch (Exception e) {
            log.debug("[learning] could not read declared heavy-memory budget: {}", e.getMessage());
            return 0L;
        }
    }

    @Override
    protected String getStructuredMessagePrefix() {
        return LearningSubprocessMessage.MESSAGE_PREFIX;
    }

    /**
     * Backend selection is handled centrally via
     * {@link ManagedSubprocessLauncher#getBackendPreference()} plus scheduler placement.
     * Multi-backend enablement is intentionally inherited from parent/runtime config and is
     * not hardcoded at this launcher level.
     */
    /**
     * KGE / SameDiff training defaults to the CPU backend (preserving the long-standing default) so a
     * post-crawl training pass never contends for GPU memory with the live embedding lane, and never
     * crashes loading a CUDA context on a no-GPU host. Declared via the device-abstract
     * backend-preference mechanism — the base emits the real {@code org.nd4j.*.priority} selection
     * flags. (GPU-accelerated KGE is a deliberate policy choice for later, not a device env-var hack.)
     */
    @Override
    protected BackendPreference getBackendPreference() {
        return BackendPreference.CPU;
    }

    @Override
    protected List<String> getExtraJvmArgs() {
        return List.of();
    }

    // ── KgeTrainingExecutor ───────────────────────────────────────────────────

    @Override
    public KgeTrainingResult trainOutOfProcess(Long factSheetId,
                                               KGEmbeddingAlgorithm algorithm,
                                               KGEmbeddingConfig config,
                                               List<Triple> triples) {
        return trainOutOfProcess(null, factSheetId, algorithm, config, triples,
                Collections.emptyMap(), null);
    }

    @Override
    public KgeTrainingResult trainOutOfProcess(String crawlJobId,
                                               Long factSheetId,
                                               KGEmbeddingAlgorithm algorithm,
                                               KGEmbeddingConfig config,
                                               List<Triple> triples,
                                               KgeTrainingExecutor.ProgressCallback callback) {
        return trainOutOfProcess(crawlJobId, factSheetId, algorithm, config, triples,
                Collections.emptyMap(), callback);
    }

    /**
     * Warm-start-capable overload: if {@code priorEmbeddings} is non-empty, serializes them
     * to a temp file in the same JSON shape as the output file and passes its path to the
     * subprocess via {@link LearningSubprocessArgs#warmStartEmbeddingsPath()}.
     *
     * <p>Delegates to the full relation-aware overload with an empty relation map.</p>
     */
    @Override
    public KgeTrainingResult trainOutOfProcess(String crawlJobId,
                                               Long factSheetId,
                                               KGEmbeddingAlgorithm algorithm,
                                               KGEmbeddingConfig config,
                                               List<Triple> triples,
                                               Map<String, INDArray> priorEmbeddings,
                                               KgeTrainingExecutor.ProgressCallback callback) {
        return trainOutOfProcess(crawlJobId, factSheetId, algorithm, config, triples,
                priorEmbeddings, Collections.emptyMap(), callback);
    }

    /**
     * Full warm-start overload: serializes both entity and relation prior embeddings to the
     * warm-start temp file so the subprocess can seed BOTH TransE/RotatE entity and relation
     * matrices from prior training runs rather than random init.
     */
    @Override
    public KgeTrainingResult trainOutOfProcess(String crawlJobId,
                                               Long factSheetId,
                                               KGEmbeddingAlgorithm algorithm,
                                               KGEmbeddingConfig config,
                                               List<Triple> triples,
                                               Map<String, INDArray> priorEmbeddings,
                                               Map<String, INDArray> priorRelationEmbeddings,
                                               KgeTrainingExecutor.ProgressCallback callback) {
        return trainOutOfProcess(crawlJobId, factSheetId, algorithm, config, triples,
                priorEmbeddings, priorRelationEmbeddings, null, callback);
    }

    @Override
    public KgeTrainingResult trainOutOfProcess(String crawlJobId,
                                               Long factSheetId,
                                               KGEmbeddingAlgorithm algorithm,
                                               KGEmbeddingConfig config,
                                               List<Triple> triples,
                                               Path serializedWarmStartPath,
                                               KgeTrainingExecutor.ProgressCallback callback) {
        return trainOutOfProcess(crawlJobId, factSheetId, algorithm, config, triples,
                Collections.emptyMap(), Collections.emptyMap(), serializedWarmStartPath, callback);
    }

    private KgeTrainingResult trainOutOfProcess(String crawlJobId,
                                                Long factSheetId,
                                                KGEmbeddingAlgorithm algorithm,
                                                KGEmbeddingConfig config,
                                                List<Triple> triples,
                                                Map<String, INDArray> priorEmbeddings,
                                                Map<String, INDArray> priorRelationEmbeddings,
                                                Path serializedWarmStartPath,
                                                KgeTrainingExecutor.ProgressCallback callback) {
        String runId = "learning-" + factSheetId + "-" + System.currentTimeMillis();
        log.info("Starting out-of-process KGE training: runId={}, algorithm={}, triples={}, " +
                        "warmStartEntities={}, warmStartRelations={}, serializedWarmStart={}",
                runId, algorithm, triples.size(),
                !priorEmbeddings.isEmpty(), !priorRelationEmbeddings.isEmpty(), serializedWarmStartPath != null);

        Path argsFile = null;
        Path triplesFile = null;
        Path outputFile = null;
        Path warmStartFile = null;
        try {
            triplesFile = Files.createTempFile("kompile-kge-triples-", ".json");
            Files.writeString(triplesFile, objectMapper.writeValueAsString(triples), StandardCharsets.UTF_8);
            outputFile = Files.createTempFile("kompile-kge-out-", ".json");

            // Prefer a pre-serialized warm-start artifact exported by the graph adapter. The
            // map-based branch remains for tests/legacy callers, but production app-main should
            // avoid rehydrating persisted embeddings into INDArray maps just to serialize them again.
            String warmStartPath = null;
            if (serializedWarmStartPath != null) {
                warmStartFile = serializedWarmStartPath;
                warmStartPath = serializedWarmStartPath.toString();
                log.info("[KGE {}] Using serialized warm-start file {}", runId, warmStartPath);
            } else if (!priorEmbeddings.isEmpty() || !priorRelationEmbeddings.isEmpty()) {
                log.warn("[KGE {}] Ignoring INDArray warm-start maps in app-main; callers should use "
                        + "the serialized warm-start overload", runId);
            }

            LearningSubprocessArgs subArgs = new LearningSubprocessArgs(
                    factSheetId, algorithmKey(algorithm), config.embeddingDim(), config.epochs(),
                    config.learningRate(), config.margin(), config.negativeSamples(),
                    config.normalizeEntities(), config.batchSize(),
                    triplesFile.toString(), outputFile.toString(), warmStartPath);
            argsFile = subArgs.writeToTempFile();

            CountDownLatch doneLatch = new CountDownLatch(1);
            AtomicReference<KgeTrainingResult> resultRef = new AtomicReference<>();
            // Set true the instant a Completed message arrives so the process-exit handler can't
            // race ahead and report a bogus "no completion message".
            AtomicBoolean completedReceived = new AtomicBoolean(false);
            AtomicLong hb = new AtomicLong(System.currentTimeMillis());
            lastHeartbeatByRun.put(runId, hb);

            Path finalOutputFile = outputFile;
            Path finalArgs = argsFile, finalTriples = triplesFile, finalWarmStart = warmStartFile;
            ManagedRun run = startProcess(runId, crawlJobId, List.of(argsFile.toString()),
                    payload -> handleKgeMessage(payload, runId, crawlJobId, finalOutputFile,
                            resultRef, completedReceived, doneLatch, hb, callback));

            run.process().onExit().thenRun(() -> {
                // Only treat the exit as a failure if NO Completed message was received. The
                // subprocess exits soon after sending Completed; the guard prevents a fast exit from
                // clobbering a genuine success with a bogus failure.
                if (!completedReceived.get() && resultRef.compareAndSet(null,
                        KgeTrainingResult.failure("Subprocess exited without a completion message"))) {
                    doneLatch.countDown();
                }
                lastHeartbeatByRun.remove(runId);
                finishRun(runId);
                if (completedReceived.get()) {
                    // The caller owns the embeddings artifact on success and deletes it after write-back.
                    cleanupQuiet(finalArgs, finalTriples, finalWarmStart);
                } else {
                    cleanupQuiet(finalArgs, finalTriples, finalOutputFile, finalWarmStart);
                }
            });

            if (!doneLatch.await(jobTimeoutMs, TimeUnit.MILLISECONDS)) {
                stop(runId, "timeout after " + jobTimeoutMs + "ms");
                return KgeTrainingResult.failure("Subprocess timed out after " + jobTimeoutMs + "ms");
            }
            return resultRef.get() != null ? resultRef.get()
                    : KgeTrainingResult.failure("No result received from subprocess");

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cleanupQuiet(argsFile, triplesFile, outputFile, warmStartFile);
            return KgeTrainingResult.failure("Interrupted: " + ie.getMessage());
        } catch (Exception e) {
            log.error("Failed to launch KGE subprocess for fact sheet {}", factSheetId, e);
            cleanupQuiet(argsFile, triplesFile, outputFile, warmStartFile);
            return KgeTrainingResult.failure("Launch error: " + e.getMessage());
        }
    }

    // ── ReasoningLearningExecutor — PSL ───────────────────────────────────────

    @Override
    public ReasoningLearningExecutor.LearningResult runPslLearning(
            String crawlJobId, long factSheetId, List<String> ruleTexts,
            Map<String, Double> observedAtoms, List<String> targetAtoms,
            Map<String, Double> groundTruthLabels, String programKey, String weightStoreDirPath,
            int maxEpochs, double learningRate, double tolerance, int batchSize, long seed,
            double weightPriorStrength, double weightPriorMean, double[] perRuleMeans,
            ReasoningLearningExecutor.ProgressCallback callback) {

        String runId = "psl-" + factSheetId + "-" + System.currentTimeMillis();
        log.info("Starting out-of-process PSL weight learning: runId={}, factSheetId={}", runId, factSheetId);
        Path argsFile = null, inputFile = null;
        try {
            PslLearningInput input = new PslLearningInput(
                    ruleTexts, observedAtoms, targetAtoms, groundTruthLabels, programKey, weightStoreDirPath,
                    tolerance, batchSize, seed, weightPriorStrength, weightPriorMean, perRuleMeans);
            inputFile = input.writeToFile();
            ReasoningLearningSubprocessArgs subArgs = new ReasoningLearningSubprocessArgs(
                    factSheetId, "PSL", inputFile.toString(), weightStoreDirPath, maxEpochs, learningRate);
            argsFile = subArgs.writeToTempFile();
            return runReasoning(runId, crawlJobId, argsFile, inputFile, callback, "PSL");
        } catch (Exception e) {
            log.error("Failed to launch PSL subprocess for factSheet {}", factSheetId, e);
            cleanupQuiet(argsFile, inputFile);
            return ReasoningLearningExecutor.LearningResult.failure("Launch error: " + e.getMessage());
        }
    }

    // ── ReasoningLearningExecutor — MEBN ──────────────────────────────────────

    @Override
    public ReasoningLearningExecutor.LearningResult runMebnLearning(
            String crawlJobId, long factSheetId, List<String> edgeKeys, List<Double> currentStrengths,
            double[][] pParentMatrix, double[][] targetMatrix, String mebnWeightsOutputPath,
            int maxEpochs, double learningRate, ReasoningLearningExecutor.ProgressCallback callback) {

        String runId = "mebn-" + factSheetId + "-" + System.currentTimeMillis();
        log.info("Starting out-of-process MEBN strength learning: runId={}, factSheetId={}", runId, factSheetId);
        Path argsFile = null, inputFile = null;
        try {
            MebnLearningInput input = new MebnLearningInput(
                    edgeKeys, currentStrengths, pParentMatrix, targetMatrix, mebnWeightsOutputPath);
            inputFile = input.writeToFile();
            ReasoningLearningSubprocessArgs subArgs = new ReasoningLearningSubprocessArgs(
                    factSheetId, "MEBN", inputFile.toString(), mebnWeightsOutputPath, maxEpochs, learningRate);
            argsFile = subArgs.writeToTempFile();
            return runReasoning(runId, crawlJobId, argsFile, inputFile, callback, "MEBN");
        } catch (Exception e) {
            log.error("Failed to launch MEBN subprocess for factSheet {}", factSheetId, e);
            cleanupQuiet(argsFile, inputFile);
            return ReasoningLearningExecutor.LearningResult.failure("Launch error: " + e.getMessage());
        }
    }

    /** Shared PSL/MEBN run: start, parse {@code LEARNING_MSG:} lines, resolve the result on exit. */
    private ReasoningLearningExecutor.LearningResult runReasoning(
            String runId, String crawlJobId, Path argsFile, Path inputFile,
            ReasoningLearningExecutor.ProgressCallback callback, String label) {
        try {
            CountDownLatch doneLatch = new CountDownLatch(1);
            AtomicReference<ReasoningLearningExecutor.LearningResult> resultRef = new AtomicReference<>();
            AtomicLong hb = new AtomicLong(System.currentTimeMillis());
            lastHeartbeatByRun.put(runId, hb);

            Path finalArgs = argsFile, finalInput = inputFile;
            ManagedRun run = startProcess(runId, crawlJobId, List.of(argsFile.toString()),
                    payload -> handleReasoningMessage(payload, runId, crawlJobId, label,
                            callback, resultRef, doneLatch, hb));

            run.process().onExit().thenRun(() -> {
                if (resultRef.compareAndSet(null, ReasoningLearningExecutor.LearningResult.failure(
                        label + " subprocess exited without a completion message"))) {
                    doneLatch.countDown();
                }
                lastHeartbeatByRun.remove(runId);
                finishRun(runId);
                cleanupQuiet(finalArgs, finalInput);
            });

            if (!doneLatch.await(jobTimeoutMs, TimeUnit.MILLISECONDS)) {
                stop(runId, label + " timeout after " + jobTimeoutMs + "ms");
                return ReasoningLearningExecutor.LearningResult.failure(
                        label + " subprocess timed out after " + jobTimeoutMs + "ms");
            }
            return resultRef.get() != null ? resultRef.get()
                    : ReasoningLearningExecutor.LearningResult.failure("No result from subprocess");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cleanupQuiet(argsFile, inputFile);
            return ReasoningLearningExecutor.LearningResult.failure("Interrupted: " + ie.getMessage());
        } catch (Exception e) {
            log.error("Failed to run {} subprocess", label, e);
            cleanupQuiet(argsFile, inputFile);
            return ReasoningLearningExecutor.LearningResult.failure("Error: " + e.getMessage());
        }
    }

    // ── structured-message handlers ───────────────────────────────────────────

    private void handleKgeMessage(String json, String runId, String crawlJobId, Path outputFile,
                                  AtomicReference<KgeTrainingResult> resultRef, AtomicBoolean completedReceived,
                                  CountDownLatch doneLatch,
                                  AtomicLong hb, KgeTrainingExecutor.ProgressCallback callback) {
        try {
            LearningSubprocessMessage msg = objectMapper.readValue(json, LearningSubprocessMessage.class);
            if (msg instanceof Heartbeat h) {
                hb.set(h.timestampMs());
            } else if (msg instanceof Progress p) {
                log.info("[KGE {}] epoch {}/{} loss={}", runId, p.epoch(), p.totalEpochs(),
                        String.format("%.4f", p.loss()));
                if (callback != null && crawlJobId != null) {
                    try { callback.onProgress(crawlJobId, p.epoch(), p.totalEpochs(), p.loss()); }
                    catch (Exception ex) { log.debug("[KGE {}] callback error: {}", runId, ex.getMessage()); }
                }
            } else if (msg instanceof Completed c) {
                log.info("[KGE {}] Completed: loss={}, entities={}, relations={}, output={}",
                        runId, c.finalLoss(), c.entities(), c.relations(), outputFile);
                completedReceived.set(true);
                resultRef.set(KgeTrainingResult.success(outputFile, c.entities(), c.relations(), c.finalLoss()));
                doneLatch.countDown();
            } else if (msg instanceof Failed f) {
                log.warn("[KGE {}] Failed: {}", runId, f.reason());
                resultRef.set(KgeTrainingResult.failure(f.reason()));
                doneLatch.countDown();
            }
        } catch (Exception e) {
            log.debug("[KGE {}] cannot parse '{}': {}", runId, json, e.getMessage());
        }
    }

    private void handleReasoningMessage(String json, String runId, String crawlJobId, String label,
                                        ReasoningLearningExecutor.ProgressCallback callback,
                                        AtomicReference<ReasoningLearningExecutor.LearningResult> resultRef,
                                        CountDownLatch doneLatch, AtomicLong hb) {
        try {
            LearningSubprocessMessage msg = objectMapper.readValue(json, LearningSubprocessMessage.class);
            if (msg instanceof Heartbeat h) {
                hb.set(h.timestampMs());
            } else if (msg instanceof Progress p) {
                log.info("[{} {}] epoch {}/{} loss={}", label, runId, p.epoch(), p.totalEpochs(),
                        String.format("%.4f", p.loss()));
                if (callback != null && crawlJobId != null) {
                    try { callback.onProgress(crawlJobId, p.epoch(), p.totalEpochs(), p.loss()); }
                    catch (Exception ex) { log.debug("[{} {}] callback error: {}", label, runId, ex.getMessage()); }
                }
            } else if (msg instanceof Completed c) {
                log.info("[{} {}] Completed: loss={}", label, runId, c.finalLoss());
                resultRef.set(ReasoningLearningExecutor.LearningResult.success(c.finalLoss(), c.entities()));
                doneLatch.countDown();
            } else if (msg instanceof Failed f) {
                log.warn("[{} {}] Failed: {}", label, runId, f.reason());
                resultRef.set(ReasoningLearningExecutor.LearningResult.failure(f.reason()));
                doneLatch.countDown();
            }
        } catch (Exception e) {
            log.debug("[{} {}] cannot parse '{}': {}", label, runId, json, e.getMessage());
        }
    }

    // ── heartbeat stale-watchdog (process kill handled by base.stop → onExit) ─

    @Scheduled(fixedDelayString = "${kompile.learning.subprocess.stale-check-ms:30000}")
    public void checkForStaleProcesses() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AtomicLong> e : lastHeartbeatByRun.entrySet()) {
            if (now - e.getValue().get() > staleTimeoutMs) {
                log.warn("Learning run {} stale (no heartbeat for {}ms) — stopping", e.getKey(), staleTimeoutMs);
                stop(e.getKey(), "stale (no heartbeat)");
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String algorithmKey(KGEmbeddingAlgorithm algorithm) {
        return switch (algorithm) {
            case ROTATE -> "KGE_ROTATE";
            case TRANSE -> "KGE_TRANSE";
        };
    }

    private static void cleanupQuiet(Path... files) {
        for (Path f : files) {
            if (f != null) {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { }
            }
        }
    }
}
