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
import ai.kompile.app.subprocess.ManagedSubprocessLauncher;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.KgeTrainingExecutor;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.core.reasoning.ReasoningLearningExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
@ConditionalOnProperty(
        name = "kompile.learning.subprocess.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LearningSubprocessLauncher extends ManagedSubprocessLauncher
        implements KgeTrainingExecutor, ReasoningLearningExecutor {

    private static final String SUBPROCESS_ID = "learning";
    private static final String MAIN_CLASS =
            "ai.kompile.app.learning.subprocess.LearningSubprocessMain";

    @Value("${kompile.learning.subprocess.heap-mb:8192}")
    private int heapMb;

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
        return heapMb;
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

        // Dynamic: derive from MemAvailable.
        long memAvailableMb = -1L;
        if (resourceTelemetryService != null) {
            try {
                memAvailableMb = resourceTelemetryService.latest().memAvailableMb();
            } catch (Exception e) {
                log.debug("[learning] ResourceTelemetryService.latest() failed: {}", e.getMessage());
            }
        }

        if (memAvailableMb > 0) {
            long computed = (long) (memAvailableMb * nativeCapFraction);
            // Apply floor
            computed = Math.max(computed, nativeCapFloorMb);
            // Apply ceiling (0 = no ceiling)
            if (nativeCapCeilingMb > 0) {
                computed = Math.min(computed, nativeCapCeilingMb);
            }
            log.info("[learning] native cap: {} MB (MemAvailable={} MB × fraction={}, floor={}, ceiling={})",
                    computed, memAvailableMb, nativeCapFraction, nativeCapFloorMb,
                    nativeCapCeilingMb > 0 ? nativeCapCeilingMb + " MB" : "none");
            return computed;
        }

        // Fallback: 4 × heap (same as base class default).
        long fallback = (long) heapMb * 4L;
        log.warn("[learning] MemAvailable unavailable (ResourceTelemetryService absent or not yet polled); "
                + "falling back to 4×heap = {} MB. "
                + "Set kompile.learning.subprocess.max-physical-mb to override.", fallback);
        return fallback;
    }

    @Override
    protected String getStructuredMessagePrefix() {
        return LearningSubprocessMessage.MESSAGE_PREFIX;
    }

    /**
     * Ensure the learning subprocess JVM always carries ND4J CPU-backend flags.
     *
     * <p>When the parent was started via {@code run-cpu.sh} with
     * {@code -Dnd4j.backend.priority=CPU} and {@code -Dnd4j.multibackend.enabled=false},
     * {@link ai.kompile.app.subprocess.SubprocessEnvironmentPropagator#buildSystemPropertyFlags()}
     * already propagates those flags (both start with the {@code "nd4j."} prefix and
     * {@code org.nd4j.backend.multi.auto} starts with {@code "org.nd4j."}).
     *
     * <p><b>The gap this fixes</b>: {@code buildSystemPropertyFlags()} only emits flags
     * that are <em>already set</em> in the parent's {@link System#getProperties()}. If the
     * parent was started without them (e.g. in tests or a bare {@code java -jar} run),
     * the subprocess inherits BackendManager's default device-priority order of
     * {@code [CUDA_GPU, ROCM_GPU, METAL_GPU, TPU, CPU]}. On a no-GPU host
     * {@code JCublasBackend.canRun()} calls {@code cudaGetDeviceCount}, gets 0 devices,
     * and throws {@code RuntimeException("No CUDA devices were found")}, crashing
     * {@code Nd4j.&lt;clinit&gt;} before any training starts.
     *
     * <p><b>Dynamic + safe</b>: each property is read from the parent first; the CPU-safe
     * default is only used when the parent has no value. Because JVM processes
     * {@code -D} flags left-to-right (later values overwrite), {@code getExtraJvmArgs()}
     * is only called here for properties the parent does NOT already hold — so
     * {@code buildSystemPropertyFlags()} (which runs first in the command-line) never
     * produces a duplicate that we would mistakenly override. GPU runs where the parent
     * holds {@code nd4j.backend.priority=CUDA_GPU,CPU} continue to work unchanged.
     *
     * <p>{@code CUDA_VISIBLE_DEVICES} is propagated as an env var by
     * {@link ai.kompile.app.subprocess.SubprocessEnvironmentPropagator#propagateToEnvironment}
     * and does not need a -D flag.
     */
    @Override
    protected List<String> getExtraJvmArgs() {
        List<String> args = new ArrayList<>();

        // nd4j.backend.priority — BackendManager.PROP_PRIORITY.
        // Controls the DeviceType priority list in BackendManager.loadSystemProperties().
        // Only inject when the parent does not already carry this property; the parent's
        // value is forwarded first by buildSystemPropertyFlags() and would otherwise win
        // because -D is processed left-to-right (last value wins on hotspot).
        if (System.getProperty("nd4j.backend.priority") == null) {
            args.add("-Dnd4j.backend.priority=CPU");
            log.debug("[learning] nd4j.backend.priority not set in parent — defaulting subprocess to CPU");
        }

        // nd4j.multibackend.enabled — Kompile DeviceAwareOpExecutioner gate.
        // Prevents CUDA executioner dispatch (and SIGSEGV) when no GPU is present.
        if (System.getProperty("nd4j.multibackend.enabled") == null) {
            args.add("-Dnd4j.multibackend.enabled=false");
            log.debug("[learning] nd4j.multibackend.enabled not set in parent — defaulting subprocess to false");
        }

        // org.nd4j.backend.multi.auto — BackendManager auto-enable multi-backend.
        // When false BackendManager.autoInitialize() skips MultiBackendNativeOpsHolder,
        // avoiding a second CUDA probe that would crash on a no-GPU host.
        if (System.getProperty("org.nd4j.backend.multi.auto") == null) {
            args.add("-Dorg.nd4j.backend.multi.auto=false");
            log.debug("[learning] org.nd4j.backend.multi.auto not set in parent — defaulting subprocess to false");
        }

        return args;
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
        String runId = "learning-" + factSheetId + "-" + System.currentTimeMillis();
        log.info("Starting out-of-process KGE training: runId={}, algorithm={}, triples={}, " +
                        "warmStartEntities={}, warmStartRelations={}",
                runId, algorithm, triples.size(),
                !priorEmbeddings.isEmpty(), !priorRelationEmbeddings.isEmpty());

        Path argsFile = null;
        Path triplesFile = null;
        Path outputFile = null;
        Path warmStartFile = null;
        try {
            triplesFile = Files.createTempFile("kompile-kge-triples-", ".json");
            Files.writeString(triplesFile, objectMapper.writeValueAsString(triples), StandardCharsets.UTF_8);
            outputFile = Files.createTempFile("kompile-kge-out-", ".json");

            // Serialize prior entity and relation embeddings to a temp file if warm-starting.
            // Both maps are written into the same JSON file under "entities" and "relations".
            // An empty map for either key tells the subprocess to cold-start that component.
            String warmStartPath = null;
            if (!priorEmbeddings.isEmpty() || !priorRelationEmbeddings.isEmpty()) {
                warmStartFile = Files.createTempFile("kompile-kge-warmstart-", ".json");
                Map<String, float[]> entityVectors = priorEmbeddings.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    INDArray arr = e.getValue();
                                    float[] floats = new float[(int) arr.length()];
                                    for (int i = 0; i < floats.length; i++) floats[i] = arr.getFloat(i);
                                    return floats;
                                }));
                Map<String, float[]> relationVectors = priorRelationEmbeddings.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    INDArray arr = e.getValue();
                                    float[] floats = new float[(int) arr.length()];
                                    for (int i = 0; i < floats.length; i++) floats[i] = arr.getFloat(i);
                                    return floats;
                                }));
                Map<String, Object> warmStartJson = new HashMap<>();
                warmStartJson.put("entities", entityVectors);
                warmStartJson.put("relations", relationVectors);
                Files.writeString(warmStartFile, objectMapper.writeValueAsString(warmStartJson),
                        StandardCharsets.UTF_8);
                warmStartPath = warmStartFile.toString();
                log.info("[KGE {}] Wrote warm-start file: {} entity vectors, {} relation vectors to {}",
                        runId, entityVectors.size(), relationVectors.size(), warmStartPath);
            }

            LearningSubprocessArgs subArgs = new LearningSubprocessArgs(
                    factSheetId, algorithmKey(algorithm), config.embeddingDim(), config.epochs(),
                    config.learningRate(), config.margin(), config.negativeSamples(),
                    config.normalizeEntities(), config.batchSize(),
                    triplesFile.toString(), outputFile.toString(), warmStartPath);
            argsFile = subArgs.writeToTempFile();

            CountDownLatch doneLatch = new CountDownLatch(1);
            AtomicReference<KgeTrainingResult> resultRef = new AtomicReference<>();
            // Set true the instant a Completed message arrives — BEFORE the (slow) embeddings load —
            // so the process-exit handler can't race ahead and report a bogus "no completion message".
            AtomicBoolean completedReceived = new AtomicBoolean(false);
            AtomicLong hb = new AtomicLong(System.currentTimeMillis());
            lastHeartbeatByRun.put(runId, hb);

            Path finalOutputFile = outputFile;
            Path finalArgs = argsFile, finalTriples = triplesFile, finalWarmStart = warmStartFile;
            ManagedRun run = startProcess(runId, crawlJobId, List.of(argsFile.toString()),
                    payload -> handleKgeMessage(payload, runId, crawlJobId, finalOutputFile,
                            resultRef, completedReceived, doneLatch, hb, callback));

            run.process().onExit().thenRun(() -> {
                // Only treat the exit as a failure if NO Completed message was received. The Completed
                // handler parses the embeddings file and builds ~N INDArrays (hundreds of ms) BEFORE it
                // sets resultRef, and the subprocess exits the moment it sends Completed — so without the
                // completedReceived guard a fast exit clobbers a genuine success with a bogus failure.
                if (!completedReceived.get() && resultRef.compareAndSet(null,
                        KgeTrainingResult.failure("Subprocess exited without a completion message"))) {
                    doneLatch.countDown();
                }
                lastHeartbeatByRun.remove(runId);
                finishRun(runId);
                cleanupQuiet(finalArgs, finalTriples, finalOutputFile, finalWarmStart);
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
            int maxEpochs, double learningRate, ReasoningLearningExecutor.ProgressCallback callback) {

        String runId = "psl-" + factSheetId + "-" + System.currentTimeMillis();
        log.info("Starting out-of-process PSL weight learning: runId={}, factSheetId={}", runId, factSheetId);
        Path argsFile = null, inputFile = null;
        try {
            PslLearningInput input = new PslLearningInput(
                    ruleTexts, observedAtoms, targetAtoms, groundTruthLabels, programKey, weightStoreDirPath);
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
                log.info("[KGE {}] Completed: loss={}, entities={}, relations={}",
                        runId, c.finalLoss(), c.entities(), c.relations());
                // Mark completion BEFORE the slow embeddings load (see onExit) so a fast subprocess
                // exit cannot race in and overwrite this genuine success with a bogus failure.
                completedReceived.set(true);
                try {
                    resultRef.set(KgeTrainingResult.success(loadEmbeddingsShim(outputFile), c.finalLoss()));
                } catch (Exception loadEx) {
                    // Training succeeded but the embeddings file could not be read back — report THAT
                    // accurately rather than the misleading "exited without a completion message".
                    log.error("[KGE {}] training completed but loading embeddings failed: {}",
                            runId, loadEx.getMessage());
                    resultRef.set(KgeTrainingResult.failure("Embeddings load failed: " + loadEx.getMessage()));
                }
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

    private KGEmbeddingModel loadEmbeddingsShim(Path outputFile) {
        try {
            Map<String, Object> raw = objectMapper.readValue(outputFile.toFile(),
                    new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            Map<String, List<Number>> entityRaw = (Map<String, List<Number>>) raw.get("entities");
            @SuppressWarnings("unchecked")
            Map<String, List<Number>> relationRaw = (Map<String, List<Number>>) raw.get("relations");
            return new DeserializedEmbeddingShim(toINDArrayMap(entityRaw), toINDArrayMap(relationRaw));
        } catch (Exception e) {
            log.error("Failed to load embeddings from {}: {}", outputFile, e.getMessage());
            throw new RuntimeException("Cannot load embeddings: " + e.getMessage(), e);
        }
    }

    private static Map<String, INDArray> toINDArrayMap(Map<String, List<Number>> raw) {
        if (raw == null) return Collections.emptyMap();
        Map<String, INDArray> result = new HashMap<>(raw.size() * 2);
        for (Map.Entry<String, List<Number>> entry : raw.entrySet()) {
            List<Number> nums = entry.getValue();
            float[] floats = new float[nums.size()];
            for (int i = 0; i < nums.size(); i++) {
                floats[i] = nums.get(i).floatValue();
            }
            result.put(entry.getKey(), Nd4j.create(floats));
        }
        return result;
    }

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
