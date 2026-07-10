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
package ai.kompile.core.reasoning;

import java.util.List;
import java.util.Map;

/**
 * Seam for out-of-process PSL weight learning and MEBN edge-strength learning.
 *
 * <p>This interface lives in {@code kompile-app-core} so that
 * {@code IncrementalReasoningOrchestrator} in {@code kompile-knowledge-graph} can optionally
 * delegate the compute-heavy weight-learning steps to an out-of-process launcher without
 * depending on {@code kompile-app-main}.</p>
 *
 * <p>The concrete implementation ({@link ai.kompile.app.services.subprocess.LearningSubprocessLauncher})
 * lives in {@code kompile-app-main} and is wired by the app-main Spring context via
 * {@code @Autowired(required = false)} in {@code IncrementalReasoningOrchestrator}. When absent
 * the in-JVM path is used unchanged.</p>
 *
 * <h3>Contract</h3>
 * <ol>
 *   <li>The caller provides primitive data (rule strings, atom maps, tensor matrices).
 *       The implementation serialises them to temp files, launches the subprocess, and
 *       reports progress via the callback.</li>
 *   <li>PSL: the subprocess writes the updated weights to the {@code weightStoreDirPath}
 *       under the {@code programKey}. The caller is responsible for reading them back via
 *       its {@code FileWeightStore} / {@code DualStoreGroundingFactory}.</li>
 *   <li>MEBN: the subprocess writes updated edge strengths to {@code mebnWeightsOutputPath}
 *       (the {@code mebn-weights.json} path). The caller re-applies via
 *       {@code MebnWeightPersistenceAdapter.load()} after return.</li>
 *   <li>If learning fails, {@link LearningResult#success()} is {@code false} and the caller
 *       falls through to the existing in-JVM path unchanged.</li>
 * </ol>
 *
 * <h3>Memory isolation</h3>
 * <p>Both PSL ({@link ai.kompile.graph.reasoning.learning.StructuredPerceptronLearner}) and MEBN
 * ({@link ai.kompile.graph.reasoning.learning.SameDiffMebnStrengthLearner}) run inside the managed
 * learning subprocess JVM. The subprocess is launched with
 * {@code -Dorg.bytedeco.javacpp.maxphysicalbytes} so that SameDiff's ND4J native memory (which
 * allocates a new SameDiff computation graph per epoch) is bounded and the RSS watchdog can kill
 * a runaway subprocess without taking down the main app.</p>
 */
public interface ReasoningLearningExecutor {

    /**
     * Callback invoked on each PSL iteration / MEBN epoch progress update.
     * Implementations must be thread-safe (called from the stdout-reader thread).
     */
    @FunctionalInterface
    interface ProgressCallback {
        /**
         * Called for each progress update from the learning subprocess.
         *
         * @param crawlJobId  the owning crawl job ID (for keying UI events)
         * @param epoch       current epoch / iteration (1-based)
         * @param totalEpochs total epochs configured
         * @param loss        current training loss
         */
        void onProgress(String crawlJobId, int epoch, int totalEpochs, double loss);
    }

    /**
     * Result of an out-of-process learning run.
     *
     * @param success      whether learning completed without error
     * @param finalLoss    final training loss (0.0 on failure)
     * @param iterations   number of iterations / epochs completed
     * @param errorMessage error description (null on success)
     */
    record LearningResult(boolean success,
                          double finalLoss,
                          int iterations,
                          String errorMessage) {

        /** Convenience factory for a successful run. */
        public static LearningResult success(double finalLoss, int iterations) {
            return new LearningResult(true, finalLoss, iterations, null);
        }

        /** Convenience factory for a failed run. */
        public static LearningResult failure(String reason) {
            return new LearningResult(false, 0.0, 0, reason);
        }
    }

    /**
     * Run PSL weight learning out-of-process.
     *
     * <p>The implementation serialises the PSL rules, observed atoms, target keys, and labels to a
     * temp file, launches the subprocess, which runs
     * {@link ai.kompile.graph.reasoning.learning.StructuredPerceptronLearner} for {@code maxEpochs}
     * and writes updated weights to a {@link ai.kompile.graph.reasoning.learning.FileWeightStore}
     * at {@code weightStoreDirPath} under {@code programKey}. The caller reads the new weights from
     * the same store path and applies them to the in-memory program.</p>
     *
     * @param crawlJobId       owning crawl job ID (for UI progress events); may be null
     * @param factSheetId      fact sheet being re-grounded
     * @param ruleTexts        PSL rule strings (each parseable via {@code PslRule.parse()})
     * @param observedAtoms    ground atom key → observed value (evidence)
     * @param targetAtoms      ground atom keys that are inference targets
     * @param groundTruthLabels atom key → label in [0,1] used for weight gradient
     * @param programKey       the FileWeightStore program key for the updated weights
     * @param weightStoreDirPath path to the FileWeightStore base directory
     * @param maxEpochs        number of learning epochs (often 1 for online learning)
     * @param learningRate     SGD step size
     * @param tolerance        convergence threshold on max weight update
     * @param batchSize        PSL ground-rule mini-batch size; 0 means full-batch
     * @param seed             deterministic mini-batch seed
     * @param weightPriorStrength MAP prior strength; 0 disables prior regularization
     * @param weightPriorMean  scalar fallback prior mean
     * @param perRuleMeans     optional per-rule prior means for band-aware regularization
     * @param callback         per-iteration progress callback; may be null
     * @return learning result with final loss
     */
    LearningResult runPslLearning(String crawlJobId,
                                  long factSheetId,
                                  List<String> ruleTexts,
                                  Map<String, Double> observedAtoms,
                                  List<String> targetAtoms,
                                  Map<String, Double> groundTruthLabels,
                                  String programKey,
                                  String weightStoreDirPath,
                                  int maxEpochs,
                                  double learningRate,
                                  double tolerance,
                                  int batchSize,
                                  long seed,
                                  double weightPriorStrength,
                                  double weightPriorMean,
                                  double[] perRuleMeans,
                                  ProgressCallback callback);

    /**
     * Run MEBN edge-strength learning out-of-process.
     *
     * <p>The implementation serialises the pre-computed tensor batch ({@code pParentMatrix} and
     * {@code targetMatrix}) along with the current edge strengths to a temp file, launches the
     * subprocess, which runs {@link ai.kompile.graph.reasoning.learning.SameDiffMebnStrengthLearner}
     * for {@code maxEpochs} (creating a SameDiff computation graph per epoch — the memory-risky
     * native operation), and writes updated edge strengths to {@code mebnWeightsOutputPath}
     * (the {@code mebn-weights.json} file). The caller re-applies via
     * {@code MebnWeightPersistenceAdapter.load()} after return.</p>
     *
     * <p>The subprocess JVM is bounded by {@code -Dorg.bytedeco.javacpp.maxphysicalbytes} so that
     * SameDiff's per-epoch native allocations cannot overflow the system. If memory is exceeded
     * the subprocess exits and this method returns {@link LearningResult#failure}.</p>
     *
     * @param crawlJobId         owning crawl job ID (for UI progress events); may be null
     * @param factSheetId        fact sheet being re-grounded
     * @param edgeKeys           ordered list of edge identifiers in format {@code "fragName:parent->child"}
     * @param currentStrengths   current strength values (same order as edgeKeys)
     * @param pParentMatrix      [E × M] parent-posterior values (pre-computed: E observations, M edges)
     * @param targetMatrix       [E × M] child-target values (same shape)
     * @param mebnWeightsOutputPath path where the subprocess writes the updated strengths JSON
     * @param maxEpochs          gradient-descent epochs (often 1 for online learning)
     * @param learningRate       SGD step size
     * @param callback           per-epoch progress callback; may be null
     * @return learning result with final loss
     */
    LearningResult runMebnLearning(String crawlJobId,
                                   long factSheetId,
                                   List<String> edgeKeys,
                                   List<Double> currentStrengths,
                                   double[][] pParentMatrix,
                                   double[][] targetMatrix,
                                   String mebnWeightsOutputPath,
                                   int maxEpochs,
                                   double learningRate,
                                   ProgressCallback callback);
}
