/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.pipelines.steps.samediff.trainer;

public final class SameDiffTrainerConstants {
    private SameDiffTrainerConstants() {}

    public static final String RUNNER_FQCN = "ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerRunner";

    // Parameter Keys (matching names in the schema.json)
    public static final String PARAM_MODEL_URI = "modelUri";
    public static final String PARAM_L1 = "l1";
    public static final String PARAM_L2 = "l2";
    public static final String PARAM_WEIGHT_DECAY_COEFFICIENT = "weightDecayCoefficient";
    public static final String PARAM_WEIGHT_DECAY_APPLY_LR = "weightDecayApplyLearningRate";
    public static final String PARAM_MODEL_SAVE_OUTPUT_PATH = "modelSaveOutputPath";
    public static final String PARAM_LOSS_VARIABLES = "lossVariables";
    public static final String PARAM_INPUT_FEATURES = "inputFeatures";
    public static final String PARAM_LABELS = "labels";
    public static final String PARAM_TARGET_VARIABLES = "targetVariables";
    public static final String PARAM_UPDATER_CONFIG = "updaterConfig"; // Changed from IUpdater object
    public static final String PARAM_INITIAL_LOSS_TYPE = "initialLossType";
    public static final String PARAM_LOSS_FUNCTION = "lossFunction";
    public static final String PARAM_DEBUG_MODE = "debugMode";
    public static final String PARAM_VERBOSE_MODE = "verboseMode";
}