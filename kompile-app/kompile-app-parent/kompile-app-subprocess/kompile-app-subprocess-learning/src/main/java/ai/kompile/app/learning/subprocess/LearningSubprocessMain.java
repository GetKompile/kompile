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
package ai.kompile.app.learning.subprocess;

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.app.subprocess.SubprocessMemoryWatchdog;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.TrainingProgress;
import ai.kompile.core.kgembedding.TrainingResult;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.graph.reasoning.learning.FileWeightStore;
import ai.kompile.graph.reasoning.learning.ProjectedGradientOptimizer;
import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.learning.SameDiffMebnStrengthLearner;
import ai.kompile.graph.reasoning.learning.StructuredPerceptronLearner;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import ai.kompile.knowledgegraph.embedding.impl.RotatEModel;
import ai.kompile.knowledgegraph.embedding.impl.TransEModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacpp.Pointer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the out-of-process KGE training subprocess.
 *
 * <p>Called by {@link LearningSubprocessLauncher} as:
 * <pre>{@code
 *   java -Xmx<N>m -cp <classpath> \
 *       ai.kompile.app.learning.subprocess.LearningSubprocessMain <args-file>
 * }</pre>
 *
 * <h3>Stdout / Stderr split</h3>
 * Immediately on startup {@code System.out} is redirected to {@code System.err}
 * so that all Spring / ND4J log noise goes to stderr. Structured
 * {@link LearningSubprocessMessage} lines are written only to the original
 * {@code PrintStream}, which the parent launcher reads line-by-line.
 *
 * <h3>Entity-ID consistency</h3>
 * The triples are serialised by the parent and written to {@code triplesFilePath}
 * as a JSON array of {@code {head,relation,tail}} objects using exactly the same
 * entity keys the adapter produced. The subprocess reads them back verbatim, trains,
 * and writes the resulting embedding vectors keyed by those same IDs, so the
 * write-back shim in the launcher maps them 1-to-1 back into the store.
 */
public class LearningSubprocessMain {

    /** In-JVM heap/off-heap/GPU watchdog; started from the args {@code memoryWatchdog} section. */
    private static volatile SubprocessMemoryWatchdog memoryWatchdog;
    /** Heartbeat interval sent to the parent so it can detect stale processes. */
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Log a native-memory snapshot to stderr (captured by the parent into the per-job log) so we can
     * pinpoint WHICH phase grows memory toward the {@code maxphysicalbytes} cap — distinguishing
     * JavaCPP off-heap (ND4J arrays) from JVM heap (e.g. an over-large triples list) from total RSS.
     * The OOM is reported at whatever allocation crosses the cap, so this per-phase trace is the only
     * reliable way to find the real source. Never throws — instrumentation must not break the job.
     */
    private static void probeMem(String phase) {
        try {
            long rssMb = Pointer.physicalBytes() >> 20;          // total process resident set (heap+offheap+libs)
            long offHeapMb = Pointer.totalBytes() >> 20;          // JavaCPP-tracked off-heap (ND4J)
            long capMb = Pointer.maxPhysicalBytes() >> 20;        // the maxphysicalbytes ceiling
            Runtime rt = Runtime.getRuntime();
            long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
            long heapMaxMb = rt.maxMemory() >> 20;
            System.err.printf(
                    "[NATIVE-MEM] phase=%-34s rssMB=%d offHeapMB=%d capMB=%d heapUsedMB=%d heapMaxMB=%d%n",
                    phase, rssMb, offHeapMb, capMb, heapUsedMb, heapMaxMb);
            System.err.flush();
        } catch (Throwable ignored) {
            // A memory probe must never destabilise the learning job.
        }
    }

    public static void main(String[] args) {
        NativeLibraryResolver.bootstrapOrThrow();
        // 1. Capture original stdout BEFORE anything else writes to it
        PrintStream originalStdout = System.out;
        // 2. Redirect System.out → System.err so ND4J/log noise never pollutes the
        //    structured message channel.
        System.setOut(System.err);

        LearningSubprocessProgressReporter reporter =
                new LearningSubprocessProgressReporter(originalStdout);

        if (args.length < 1) {
            reporter.reportFailed("Usage: LearningSubprocessMain <args-file>");
            System.exit(1);
        }

        // 3. Detect whether the args file is for KGE or PSL/MEBN reasoning learning.
        // We do this by peeking at the "algorithm" field: KGE values start with "KGE_",
        // PSL/MEBN values are exactly "PSL" or "MEBN".  We read the file as a generic map
        // first to avoid double-parsing.
        Map<String, Object> rawArgs;
        try {
            rawArgs = MAPPER.readValue(Paths.get(args[0]).toFile(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            reporter.reportFailed("Cannot read args file: " + e.getMessage());
            System.exit(1);
            return;
        }

        String operationField = rawArgs.get("operation") != null
                ? String.valueOf(rawArgs.get("operation")).toUpperCase()
                : "";
        String algorithmField = rawArgs.get("algorithm") != null
                ? String.valueOf(rawArgs.get("algorithm")).toUpperCase()
                : "";

        reporter.startHeartbeat(HEARTBEAT_INTERVAL_MS);

        // Start the in-JVM watchdog (if the parent sent thresholds) BEFORE training so the
        // load/alloc phases are covered too. kill=0 (or no section) keeps legacy behaviour.
        startMemoryWatchdog(rawArgs);

        int exitCode = 0;
        try {
            if (PortableGraphLearningSubprocessArgs.OPERATION.equals(operationField)) {
                PortableGraphLearningSubprocessArgs portableArgs =
                        MAPPER.convertValue(rawArgs, PortableGraphLearningSubprocessArgs.class);
                exitCode = runPortableGraphLearning(portableArgs, reporter);
            } else if ("PSL".equals(algorithmField)) {
                ReasoningLearningSubprocessArgs reasoningArgs =
                        MAPPER.convertValue(rawArgs, ReasoningLearningSubprocessArgs.class);
                exitCode = runPslLearning(reasoningArgs, reporter);
            } else if ("MEBN".equals(algorithmField)) {
                ReasoningLearningSubprocessArgs reasoningArgs =
                        MAPPER.convertValue(rawArgs, ReasoningLearningSubprocessArgs.class);
                exitCode = runMebnLearning(reasoningArgs, reporter);
            } else {
                // KGE path (KGE_TRANSE / KGE_ROTATE) — original behaviour
                LearningSubprocessArgs learningArgs =
                        MAPPER.convertValue(rawArgs, LearningSubprocessArgs.class);
                exitCode = runTraining(learningArgs, reporter);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String detail = root.getMessage() == null || root.getMessage().isBlank()
                    ? root.getClass().getName()
                    : root.getClass().getName() + ": " + root.getMessage();
            reporter.reportFailed("Unhandled " + t.getClass().getName() + " (root=" + detail + ")");
            exitCode = 1;
        } finally {
            reporter.stopHeartbeat();
            closeMemoryWatchdog();
        }
        System.exit(exitCode);
    }

    /**
     * Start the in-JVM {@link SubprocessMemoryWatchdog} from the optional
     * {@code memoryWatchdog} args section: {@code {heapStopPercent, heapCriticalPercent,
     * heapKillPercent, checkIntervalMs, offHeapStopPercent, offHeapCriticalPercent,
     * offHeapKillPercent, gpuStopPercent, gpuCriticalPercent, gpuKillPercent}}.
     * A {@code heapKillPercent <= 0} (or a missing section) disables the watchdog.
     */
    private static void startMemoryWatchdog(Map<String, Object> rawArgs) {
        try {
            Object section = rawArgs.get("memoryWatchdog");
            if (!(section instanceof Map<?, ?> cfg)) {
                return; // Parent did not send thresholds — legacy behaviour, no watchdog.
            }
            int heapStop = intArg(cfg.get("heapStopPercent"), 80);
            int heapCritical = intArg(cfg.get("heapCriticalPercent"), 90);
            int heapKill = intArg(cfg.get("heapKillPercent"), 95);
            long intervalMs = longArg(cfg.get("checkIntervalMs"), 2_000L);
            int offHeapStop = intArg(cfg.get("offHeapStopPercent"), 80);
            int offHeapCritical = intArg(cfg.get("offHeapCriticalPercent"), 90);
            int offHeapKill = intArg(cfg.get("offHeapKillPercent"), 95);
            int gpuStop = intArg(cfg.get("gpuStopPercent"), 75);
            int gpuCritical = intArg(cfg.get("gpuCriticalPercent"), 85);
            int gpuKill = intArg(cfg.get("gpuKillPercent"), 92);
            if (heapKill <= 0) {
                System.err.println("[NATIVE-MEM] watchdog disabled (heapKillPercent<=0)");
                return;
            }
            memoryWatchdog = new SubprocessMemoryWatchdog(
                    heapStop, heapCritical, heapKill, intervalMs,
                    gpuStop, gpuCritical, gpuKill,
                    offHeapStop, offHeapCritical, offHeapKill);
            memoryWatchdog.start();
            System.err.printf(
                    "[NATIVE-MEM] watchdog active: heap stop=%d%%/crit=%d%%/kill=%d%%; "
                            + "off-heap %d/%d/%d; interval=%dms%n",
                    heapStop, heapCritical, heapKill,
                    offHeapStop, offHeapCritical, offHeapKill, intervalMs);
            System.err.flush();
        } catch (Throwable t) {
            // A watchdog failure must never break the learning job.
            memoryWatchdog = null;
            System.err.println("[NATIVE-MEM] watchdog start failed (non-fatal): " + t.getMessage());
        }
    }

    private static int intArg(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longArg(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void closeMemoryWatchdog() {
        SubprocessMemoryWatchdog watchdog = memoryWatchdog;
        memoryWatchdog = null;
        if (watchdog != null) {
            try {
                watchdog.close();
            } catch (Throwable ignored) {
                // Shutdown is best-effort.
            }
        }
    }

    private static int runPortableGraphLearning(
            PortableGraphLearningSubprocessArgs args,
            LearningSubprocessProgressReporter reporter) {
        try {
            probeMem("PORTABLE_GRAPH_LEARNING:before-load");
            PortableGraphLearningJob.Result result = PortableGraphLearningJob.run(args);
            probeMem("PORTABLE_GRAPH_LEARNING:after-save");
            reporter.reportCompleted(result.embedding().finalLoss(),
                    result.outputPath().toString(), result.graph().entityCount(),
                    result.graph().relationCount());
            return 0;
        } catch (OutOfMemoryError oom) {
            probeMem("PORTABLE_GRAPH_LEARNING:at-OOM");
            reporter.reportFailed("OOM during portable graph learning: " + oom.getMessage());
            return 1;
        } catch (Exception e) {
            reporter.reportFailed("Portable graph learning failed: " + e.getMessage());
            return 1;
        }
    }

    // ── training ─────────────────────────────────────────────────────────────

    private static int runTraining(LearningSubprocessArgs args,
                                   LearningSubprocessProgressReporter reporter) {
        // Read triples from the serialised JSON file
        List<Triple> triples;
        try {
            triples = MAPPER.readValue(
                    Paths.get(args.triplesFilePath()).toFile(),
                    new TypeReference<List<Triple>>() {});
        } catch (IOException e) {
            reporter.reportFailed("Cannot read triples file: " + e.getMessage());
            return 1;
        }

        if (triples.isEmpty()) {
            reporter.reportFailed("Triples file is empty; nothing to train on");
            return 1;
        }
        probeMem("KGE:after-triples-load(triples=" + triples.size() + ")");

        // Build config from args (progressCallback wired here to emit PROGRESS messages)
        KGEmbeddingConfig config = KGEmbeddingConfig.builder()
                .embeddingDim(args.embeddingDim())
                .epochs(args.epochs())
                .learningRate(args.learningRate())
                .margin(args.margin())
                .negativeSamples(args.negativeSamples())
                .normalizeEntities(args.normalizeEntities())
                .batchSize(args.batchSize())
                .progressCallback((TrainingProgress p) ->
                        reporter.reportProgress(p.epoch(), p.totalEpochs(), p.loss()))
                .build();

        // Instantiate model
        KGEmbeddingModel model = createModel(args.algorithm());

        // ── Warm-start: import prior embeddings if the caller provided them ────
        // When warmStartEmbeddingsPath is set, load the JSON and call importEntityEmbeddings /
        // importRelationEmbeddings BEFORE train() so the model seeds from prior vectors rather
        // than random init. train() detects this via the populated entityEmbeddings field and
        // skips uniformTransE for already-known entities.
        if (args.warmStartEmbeddingsPath() != null && !args.warmStartEmbeddingsPath().isBlank()) {
            Path wsPath = Paths.get(args.warmStartEmbeddingsPath());
            if (Files.exists(wsPath)) {
                try {
                    Map<String, Object> wsRaw = MAPPER.readValue(wsPath.toFile(),
                            new TypeReference<Map<String, Object>>() {});
                    @SuppressWarnings("unchecked")
                    Map<String, List<Number>> entityRaw =
                            (Map<String, List<Number>>) wsRaw.get("entities");
                    @SuppressWarnings("unchecked")
                    Map<String, List<Number>> relationRaw =
                            (Map<String, List<Number>>) wsRaw.get("relations");

                    Map<String, INDArray> entityVecs = toINDArrayMap(entityRaw);
                    Map<String, INDArray> relationVecs = toINDArrayMap(relationRaw);

                    if (!entityVecs.isEmpty()) {
                        // Dim-mismatch guard: if the persisted dim differs from the current config,
                        // log a warning and skip warm-start to avoid a shape error in importEntityEmbeddings.
                        int persistedDim = entityVecs.values().iterator().next().columns();
                        if (persistedDim != args.embeddingDim()) {
                            System.err.printf(
                                    "[KGE subprocess] Warm-start dim mismatch: persisted=%d config=%d — " +
                                    "ignoring prior embeddings, cold-starting%n", persistedDim, args.embeddingDim());
                        } else {
                            model.importEntityEmbeddings(entityVecs);
                            if (!relationVecs.isEmpty()) {
                                model.importRelationEmbeddings(relationVecs);
                            }
                            System.err.printf(
                                    "[KGE subprocess] Warm-start: imported %d entity, %d relation vectors " +
                                    "(dim=%d); running %d incremental epochs%n",
                                    entityVecs.size(), relationVecs.size(),
                                    args.embeddingDim(), args.epochs());
                            probeMem("KGE:after-warm-start-import(entities=" + entityVecs.size()
                                    + ",relations=" + relationVecs.size() + ")");
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("[KGE subprocess] Could not load warm-start file %s: %s — cold-starting%n",
                            wsPath, e.getMessage());
                }
            } else {
                System.err.printf("[KGE subprocess] Warm-start file not found: %s — cold-starting%n", wsPath);
            }
        }

        // Train
        probeMem("KGE:before-train(dim=" + args.embeddingDim() + ",batch=" + args.batchSize()
                + ",neg=" + args.negativeSamples() + ",epochs=" + args.epochs() + ")");
        TrainingResult result;
        try {
            result = model.train(triples, config);
        } catch (OutOfMemoryError oom) {
            probeMem("KGE:at-OOM");
            reporter.reportFailed("OOM during training: " + oom.getMessage());
            return 1;
        } catch (Exception e) {
            reporter.reportFailed("Training exception: " + e.getMessage());
            return 1;
        }

        if (!result.success()) {
            reporter.reportFailed(result.errorMessage() != null
                    ? result.errorMessage() : "Training returned failure");
            return 1;
        }

        // Write embeddings output JSON  { entities: {id: [floats]}, relations: {id: [floats]} }
        Path outputPath = Paths.get(args.outputEmbeddingsPath());
        try {
            writeEmbeddings(model, outputPath);
        } catch (IOException e) {
            reporter.reportFailed("Cannot write embeddings: " + e.getMessage());
            return 1;
        }

        reporter.reportCompleted(
                result.finalLoss(),
                outputPath.toString(),
                model.getEntityCount(),
                model.getRelationCount());
        return 0;
    }

    // ── PSL weight learning ───────────────────────────────────────────────────

    /**
     * Run one mini-batch PSL weight learning step in this subprocess.
     *
     * <p>Reads the {@link PslLearningInput} from {@code args.inputFilePath()}, reconstructs the
     * {@link PslProgram} from rule texts + atom declarations, runs
     * {@link StructuredPerceptronLearner#learn} for {@code args.maxEpochs()} epochs on the
     * ground-truth labels, then writes the updated weights to a {@link FileWeightStore} at the
     * parent's resolved base directory. Per-epoch progress is reported via the reporter.</p>
     */
    private static int runPslLearning(ReasoningLearningSubprocessArgs args,
                                      LearningSubprocessProgressReporter reporter) {
        PslLearningInput input;
        try {
            input = PslLearningInput.readFromFile(Paths.get(args.inputFilePath()));
        } catch (IOException e) {
            reporter.reportFailed("Cannot read PSL input file: " + e.getMessage());
            return 1;
        }

        // Reconstruct the PslProgram from the serialised snapshot.
        PslProgram program = new PslProgram();
        for (String ruleText : input.ruleTexts()) {
            try {
                program.addRule(ruleText);
            } catch (Exception e) {
                // Non-fatal: skip malformed rule, log to stderr
                System.err.println("[PSL subprocess] Skipping unparseable rule '"
                        + ruleText + "': " + e.getMessage());
            }
        }
        // Restore observed atoms.
        if (input.observedAtoms() != null) {
            for (Map.Entry<String, Double> e : input.observedAtoms().entrySet()) {
                String key = e.getKey();
                // key format: "predicate(arg1,arg2)"
                int lp = key.indexOf('(');
                if (lp > 0 && key.endsWith(")")) {
                    String pred = key.substring(0, lp);
                    String argsStr = key.substring(lp + 1, key.length() - 1);
                    String[] argsArr = argsStr.isEmpty() ? new String[0] : argsStr.split(",");
                    program.observe(pred, e.getValue(), argsArr);
                }
            }
        }
        // Restore target atoms.
        if (input.targetAtoms() != null) {
            for (String key : input.targetAtoms()) {
                int lp = key.indexOf('(');
                if (lp > 0 && key.endsWith(")")) {
                    String pred = key.substring(0, lp);
                    String argsStr = key.substring(lp + 1, key.length() - 1);
                    String[] argsArr = argsStr.isEmpty() ? new String[0] : argsStr.split(",");
                    program.target(pred, argsArr);
                }
            }
        }

        if (program.rules().isEmpty()) {
            reporter.reportFailed("PSL program has no rules; nothing to learn");
            return 1;
        }
        if (input.groundTruthLabels() == null || input.groundTruthLabels().isEmpty()) {
            reporter.reportFailed("PSL ground-truth labels are empty; nothing to learn");
            return 1;
        }

        // Build the learner with the same managed options used by the in-JVM fallback path.
        double tolerance = input.tolerance() > 0.0 ? input.tolerance() : 1e-4;
        int batchSize = Math.max(0, input.batchSize());
        long seed = input.seed() != 0L ? input.seed() : 1234L;
        double priorStrength = Math.max(0.0, input.weightPriorStrength());
        double priorMean = Math.max(0.0, input.weightPriorMean());
        StructuredPerceptronLearner learner =
                new StructuredPerceptronLearner(args.learningRate(), tolerance, batchSize, seed,
                        priorStrength, priorMean, input.perRuleMeans());
        PslWeightLearningService svc = new PslWeightLearningService(learner, args.maxEpochs());

        // Run the learning step.
        List<PslRule> updatedRules;
        double finalLoss = 0.0;
        try {
            // Emit iteration 0 before starting.
            reporter.reportProgress(0, args.maxEpochs(), 0.0);

            updatedRules = svc.learn(program, input.groundTruthLabels());

            // Compute a representative final loss: mean absolute weight delta from defaults.
            List<PslRule> before = program.rules();
            double sumDelta = 0.0;
            int n = Math.min(before.size(), updatedRules.size());
            for (int i = 0; i < n; i++) {
                sumDelta += Math.abs(updatedRules.get(i).weight() - before.get(i).weight());
            }
            finalLoss = n > 0 ? sumDelta / n : 0.0;

            // Emit final progress.
            reporter.reportProgress(args.maxEpochs(), args.maxEpochs(), finalLoss);
        } catch (OutOfMemoryError oom) {
            reporter.reportFailed("OOM during PSL learning: " + oom.getMessage());
            return 1;
        } catch (Exception e) {
            reporter.reportFailed("PSL learning exception: " + e.getMessage());
            return 1;
        }

        // Write the updated weights to a FileWeightStore at the given path.
        // The weight output path is the BASE directory for the store; the programKey
        // identifies the versioned file inside it.
        try {
            Path storeBase = Paths.get(input.weightOutputPath());
            Files.createDirectories(storeBase);
            FileWeightStore store = new FileWeightStore(storeBase);

            // Serialize as Map<String, Double> (rule display → weight).
            Map<String, Double> weightMap = new LinkedHashMap<>();
            for (PslRule r : updatedRules) {
                weightMap.put(r.toString(), r.weight());
            }
            store.save(input.programKey(), weightMap);
        } catch (Exception e) {
            reporter.reportFailed("Cannot write PSL weights: " + e.getMessage());
            return 1;
        }

        reporter.reportCompleted(finalLoss, input.weightOutputPath(),
                updatedRules.size(), 0);
        return 0;
    }

    // ── MEBN strength learning ────────────────────────────────────────────────

    /**
     * Run one MEBN edge-strength gradient step in this subprocess using SameDiff autodiff.
     *
     * <p>Reads the {@link MebnLearningInput} from {@code args.inputFilePath()}, reconstructs the
     * {@code [E × M]} tensor batch from the serialised {@code double[][]} matrices, runs
     * {@link SameDiffMebnStrengthLearner#sdGradient} + {@link ProjectedGradientOptimizer} for
     * {@code args.maxEpochs()} steps, and writes the updated strengths as JSON to
     * {@code args.outputWeightsFilePath()}. Per-epoch progress is reported via the reporter.</p>
     *
     * <p><b>Memory note</b>: SameDiff creates a new computation graph per {@code sdGradient()} call.
     * The subprocess JVM is bounded by {@code -Dorg.bytedeco.javacpp.maxphysicalbytes} (set by the
     * launcher) so native memory growth is capped and the main app survives an OOM here.</p>
     */
    private static int runMebnLearning(ReasoningLearningSubprocessArgs args,
                                       LearningSubprocessProgressReporter reporter) {
        MebnLearningInput input;
        try {
            input = MebnLearningInput.readFromFile(Paths.get(args.inputFilePath()));
        } catch (IOException e) {
            reporter.reportFailed("Cannot read MEBN input file: " + e.getMessage());
            return 1;
        }

        List<String> edgeKeys = input.edgeKeys();
        List<Double> currentStrengthsList = input.currentStrengths();
        double[][] pParentMatrix = input.pParentMatrix();
        double[][] targetMatrix  = input.targetMatrix();

        if (edgeKeys == null || edgeKeys.isEmpty()) {
            reporter.reportFailed("MEBN input has no edge keys");
            return 1;
        }
        if (pParentMatrix == null || pParentMatrix.length == 0) {
            reporter.reportFailed("MEBN input has empty tensor batch (no observations matched edges)");
            return 1;
        }

        int M = edgeKeys.size();
        int E = pParentMatrix.length;

        // Reconstruct current strengths array.
        double[] strengths = new double[M];
        for (int i = 0; i < M; i++) {
            strengths[i] = (currentStrengthsList != null && i < currentStrengthsList.size())
                    ? currentStrengthsList.get(i) : 0.5;
        }

        // Build the ND4J tensor batch from the plain-Java matrices.
        INDArray pParND = Nd4j.create(pParentMatrix).castTo(DataType.DOUBLE);
        INDArray tgtND  = Nd4j.create(targetMatrix).castTo(DataType.DOUBLE);
        SameDiffMebnStrengthLearner.TensorBatch batch =
                new SameDiffMebnStrengthLearner.TensorBatch(pParND, tgtND, pParentMatrix, targetMatrix);

        ProjectedGradientOptimizer optimizer = new ProjectedGradientOptimizer(
                args.learningRate(), 0.0, ProjectedGradientOptimizer.unitInterval());

        double finalLoss = 0.0;
        try {
            for (int epoch = 0; epoch < Math.max(1, args.maxEpochs()); epoch++) {
                double loss = SameDiffMebnStrengthLearner.computeLoss(strengths, batch);
                if (loss < 1e-9) {
                    finalLoss = loss;
                    reporter.reportProgress(epoch + 1, args.maxEpochs(), loss);
                    break;
                }
                double[] gradient = SameDiffMebnStrengthLearner.sdGradient(strengths, batch);
                optimizer.step(strengths, gradient);
                finalLoss = SameDiffMebnStrengthLearner.computeLoss(strengths, batch);
                reporter.reportProgress(epoch + 1, args.maxEpochs(), finalLoss);
            }
        } catch (OutOfMemoryError oom) {
            reporter.reportFailed("OOM during MEBN SameDiff learning: " + oom.getMessage());
            return 1;
        } catch (Exception e) {
            reporter.reportFailed("MEBN learning exception: " + e.getMessage());
            return 1;
        }

        // Write the updated strengths as JSON: { "fragName:parent->child": strength, ... }
        // The format matches what MebnWeightPersistenceAdapter/MebnWeightSerializer expects.
        // We write a flat JSON map keyed by the edge keys received from the main JVM.
        try {
            Path outputPath = Paths.get(args.outputWeightsFilePath());
            Files.createDirectories(outputPath.getParent());
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < M; i++) {
                if (i > 0) sb.append(',');
                // escape the edge key (may contain '|', ':', '->', which are safe in JSON values)
                sb.append('"')
                  .append(escapeJson(edgeKeys.get(i)))
                  .append("\":")
                  .append(String.format(java.util.Locale.ROOT, "%.17g", strengths[i]));
            }
            sb.append('}');
            Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            reporter.reportFailed("Cannot write MEBN weights: " + e.getMessage());
            return 1;
        }

        reporter.reportCompleted(finalLoss, args.outputWeightsFilePath(), M, 0);
        return 0;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static KGEmbeddingModel createModel(String algorithm) {
        String alg = algorithm.toUpperCase();
        if ("KGE_ROTATE".equals(alg) || "ROTATE".equals(alg)) {
            return new RotatEModel();
        }
        return new TransEModel();   // KGE_TRANSE, TRANSE, or unrecognised
    }

    /**
     * Writes the trained embeddings to a JSON file in the format expected by
     * {@link LearningSubprocessLauncher}'s write-back shim:
     * <pre>{@code
     * {
     *   "entities":  { "<entityId>": [f1, f2, ...], ... },
     *   "relations": { "<relationType>": [f1, f2, ...], ... }
     * }
     * }</pre>
     *
     * <p>The entity and relation keys are EXACTLY the IDs produced by the adapter's
     * {@code extractTriples()} — the launcher wrote them verbatim into the triples
     * file, and the model's vocabulary was built from those same strings, so no
     * mapping step is needed.</p>
     */
    private static void writeEmbeddings(KGEmbeddingModel model, Path outputPath) throws IOException {
        Map<String, float[]> entityVectors = toFloatArrayMap(
                model.getEntityIdsInEmbeddingOrder(), model.getEntityEmbeddingMatrix());
        Map<String, float[]> relationVectors = toFloatArrayMap(
                model.getRelationTypesInEmbeddingOrder(), model.getRelationEmbeddingMatrix());

        Map<String, Object> output = new HashMap<>();
        output.put("entities", entityVectors);
        output.put("relations", relationVectors);

        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath,
                MAPPER.writeValueAsString(output),
                StandardCharsets.UTF_8);
    }

    /** Converts a map of {@code String → List<Number>} (from JSON) to {@code String → INDArray}. */
    private static Map<String, INDArray> toINDArrayMap(Map<String, List<Number>> raw) {
        if (raw == null || raw.isEmpty()) return new HashMap<>();
        Map<String, INDArray> result = new HashMap<>(raw.size() * 2);
        for (Map.Entry<String, List<Number>> entry : raw.entrySet()) {
            List<Number> nums = entry.getValue();
            if (nums == null || nums.isEmpty()) continue;
            float[] floats = new float[nums.size()];
            for (int i = 0; i < nums.size(); i++) {
                floats[i] = nums.get(i).floatValue();
            }
            result.put(entry.getKey(), Nd4j.create(floats));
        }
        return result;
    }

    private static Map<String, float[]> toFloatArrayMap(List<String> rowKeys, INDArray matrix) {
        int expectedSize = rowKeys == null ? 0 : rowKeys.size();
        Map<String, float[]> result = new LinkedHashMap<>(Math.max(16, expectedSize * 2));
        if (rowKeys == null || rowKeys.isEmpty() || matrix == null) {
            return result;
        }

        int rows = Math.toIntExact(matrix.rows());
        int cols = Math.toIntExact(matrix.columns());
        int offset = Math.toIntExact(matrix.offset());
        int rowCount = Math.min(rowKeys.size(), rows);
        float[] flat = matrix.data().asFloat();
        for (int row = 0; row < rowCount; row++) {
            String key = rowKeys.get(row);
            if (key == null || key.isBlank()) {
                continue;
            }
            float[] vector = new float[cols];
            System.arraycopy(flat, offset + row * cols, vector, 0, cols);
            result.put(key, vector);
        }
        return result;
    }
}
