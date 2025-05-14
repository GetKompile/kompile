/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.steps.samediff.trainer;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.NDArray;
import ai.kompile.pipelines.framework.core.config.ConfigAccessor;
import ai.kompile.pipelines.framework.core.config.SchemaRegistry;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.steps.samediff.utils.SameDiffDataUtils;
import ai.kompile.pipelines.util.URIUtils; // Assuming this utility class is now available

import org.nd4j.autodiff.loss.LossReduce;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.TrainingConfig;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.MultiDataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.IUpdater;
import org.nd4j.linalg.learning.config.NoOp;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.io.File;
import java.io.IOException;
import java.util.*;


public class SameDiffTrainerRunner implements PipelineStepRunner {

    private SameDiff sd;
    // Removed: private TrainingConfig trainingConfig; // TrainingConfig is set directly on sd
    private List<String> inputFeatures;
    private List<String> labels;
    private String modelSaveOutputPath;
    private File modelFileRef;

    private boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        StepSchema schema = SchemaRegistry.getInstance().getSchema(stepConfig.runnerClassName())
                .orElseThrow(() -> new IllegalStateException(
                        "No schema found for runner: " + stepConfig.runnerClassName()));
        ConfigAccessor config = new ConfigAccessor(stepConfig.getParameters(), schema);

        String modelUriString = config.getString(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_MODEL_URI);
        this.modelSaveOutputPath = config.getString(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_MODEL_SAVE_OUTPUT_PATH, null);
        this.inputFeatures = config.getStringList(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_INPUT_FEATURES);
        this.labels = config.getStringList(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_LABELS);
        List<String> targetVariables = config.getStringList(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_TARGET_VARIABLES);
        List<String> lossVariables = config.getStringList(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_LOSS_VARIABLES, new ArrayList<>());

        Data updaterConfigData = config.getData(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_UPDATER_CONFIG);
        double l1 = config.getDouble(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_L1, 0.0);
        double l2 = config.getDouble(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_L2, 0.0);
        double weightDecayCoefficient = config.getDouble(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_WEIGHT_DECAY_COEFFICIENT, 0.0);
        boolean weightDecayApplyLearningRate = config.getBoolean(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_WEIGHT_DECAY_APPLY_LR, true);
        String initialLossTypeStr = config.getString(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_INITIAL_LOSS_TYPE, "FLOAT");
        String lossFunctionStr = config.getString(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_LOSS_FUNCTION, null);
        boolean debugMode = config.getBoolean(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_DEBUG_MODE, false);
        boolean verboseMode = config.getBoolean(ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_VERBOSE_MODE, false);

        Objects.requireNonNull(modelUriString, "Parameter '" + ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_MODEL_URI + "' is required.");
        Objects.requireNonNull(inputFeatures, "Parameter '" + ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_INPUT_FEATURES + "' is required.");
        Objects.requireNonNull(labels, "Parameter '" + ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_LABELS + "' is required.");
        Objects.requireNonNull(targetVariables, "Parameter '" + ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_TARGET_VARIABLES + "' is required.");
        Objects.requireNonNull(updaterConfigData, "Parameter '" + ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_UPDATER_CONFIG + "' is required.");

        if (inputFeatures.isEmpty()) {
            throw new IllegalArgumentException("Parameter '" + ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_INPUT_FEATURES + "' cannot be empty.");
        }
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("Parameter '" + ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_LABELS + "' cannot be empty.");
        }
        if (targetVariables.isEmpty()) {
            throw new IllegalArgumentException("Parameter '" + ai.kompile.pipelines.steps.samediff.trainer.SameDiffTrainerConstants.PARAM_TARGET_VARIABLES + "' cannot be empty.");
        }
        if (lossFunctionStr != null && (labels.size() != targetVariables.size())) {
            throw new IllegalArgumentException("When 'lossFunction' is specified, the number of 'labels' (" + labels.size()
                    + ") must match the number of 'targetVariables' (" + targetVariables.size() + ").");
        }
        if (lossFunctionStr != null && !lossVariables.isEmpty() && (lossVariables.size() != labels.size())) {
            throw new IllegalArgumentException("When 'lossFunction' and 'lossVariables' are specified, the number of 'lossVariables' (" + lossVariables.size()
                    + ") must match the number of 'labels' (" + labels.size() + ").");
        }

        DataType initialLossType = DataType.valueOf(initialLossTypeStr.toUpperCase());
        LossFunctions.LossFunction lossFunction = (lossFunctionStr != null) ?
                LossFunctions.LossFunction.valueOf(lossFunctionStr.toUpperCase()) : null;

        try {
            this.modelFileRef = URIUtils.resolveToTempOrLocalFile(modelUriString, context);
            if (!modelFileRef.exists()) {
                throw new IOException("SameDiff model file not found at resolved path: " + modelFileRef.getAbsolutePath());
            }
            sd = SameDiff.load(modelFileRef, true);
        } catch (Exception e) {
            throw new IOException("Failed to load SameDiff model from URI: " + modelUriString, e);
        }

        TrainingConfig.Builder builder = TrainingConfig.builder();
        builder.initialLossDataType(initialLossType);
        if (l1 > 0.0) builder.l1(l1);
        if (l2 > 0.0) builder.l2(l2);
        if (weightDecayCoefficient > 0.0) builder.weightDecay(weightDecayCoefficient, weightDecayApplyLearningRate);

        String updaterClassName = updaterConfigData.getString("updaterClass");
        Objects.requireNonNull(updaterClassName, "'updaterClass' field is required within updaterConfig.");
        IUpdater updater;
        try {
            updater = SameDiffDataUtils.configFromJson(updaterConfigData, (Class<IUpdater>) Class.forName("org.nd4j.linalg.learning.config." + updaterClassName));
            if (updater == null) {
                updater = new NoOp();
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown updaterClass specified in updaterConfig: " + updaterClassName, e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize updaterConfig: " + updaterConfigData, e);
        }
        builder.updater(updater);

        builder.dataSetFeatureMapping(this.inputFeatures.toArray(new String[0]));
        builder.dataSetLabelMapping(this.labels.toArray(new String[0]));

        if (lossFunction != null) {
            if (lossVariables.isEmpty()) {
                for (int i = 0; i < labels.size(); i++) {
                    lossVariables.add("loss_" + targetVariables.get(i)); // Default naming
                }
            }

            for (int i = 0; i < labels.size(); i++) {
                String labelVarName = labels.get(i);
                String targetVarName = targetVariables.get(i);
                String lossVarName = lossVariables.get(i);

                SDVariable labelSDVar = sd.getVariable(labelVarName);
                if (labelSDVar == null) {
                    // Shape will be inferred from data during fit if not set here
                    labelSDVar = sd.var(labelVarName, VariableType.PLACEHOLDER, null, initialLossType);
                }
                SDVariable targetSDVar = sd.getVariable(targetVarName);
                if (targetSDVar == null) {
                    throw new IllegalArgumentException("Target variable '" + targetVarName + "' specified in 'targetVariables' does not exist in the loaded SameDiff graph.");
                }
                addLossToGraph(sd, lossFunction, lossVarName, labelSDVar, targetSDVar);
            }
        }


        TrainingConfig builtTrainingConfig = builder.build();
        sd.setTrainingConfig(builtTrainingConfig);

        Nd4j.getExecutioner().enableDebugMode(debugMode);
        Nd4j.getExecutioner().enableVerboseMode(verboseMode);

        this.initialized = true;
    }

    private void addLossToGraph(SameDiff sd, LossFunctions.LossFunction lossType, String lossName, SDVariable labels, SDVariable predictions) {
        // Using null for weights, default LossReduce, and default specific params like epsilon/smoothing
        // as they are not currently configurable via the Kompile schema for this runner.
        // The SDLoss.java methods usually provide sensible defaults.
        switch (lossType) {
            case L1: // This is Mean Absolute Error
            case MEAN_ABSOLUTE_ERROR:
                sd.loss().absoluteDifference(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT);
                break;
            case L2: // Interpreting this as MSE for a loss function comparing labels and predictions
            case MSE:
            case SQUARED_LOSS:
                sd.loss().meanSquaredError(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT);
                break;
            case XENT: // Binary cross-entropy
                sd.loss().sigmoidCrossEntropy(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT, 0.0);
                break;
            case MCXENT: // Multi-class cross-entropy
                sd.loss().softmaxCrossEntropy(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT, 0.0);
                break;
            case HINGE:
                sd.loss().hingeLoss(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT);
                break;
            case SQUARED_HINGE:
                // Assuming sd.loss().squaredHingeLoss(...) exists and uses similar defaults.
                // If not, this would require "new SquaredHingeLossOp(...)"
                try {
                    // Attempt to call by reflection if not directly available, or assume it is.
                    // For robust code, one might check method existence or use direct op.
                    // This is a placeholder for how it might be called if available on sd.loss()
                    // sd.loss().squaredHingeLoss(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT);
                    // Fallback or error if not directly available:
                    // For now, let's assume it's like Hinge if not found
                    // This is a simplification and would need verification against nd4j API
                    sd.loss().hingeLoss(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT);
                } catch (NoSuchMethodError e) {
                    throw new IllegalArgumentException("SquaredHingeLoss is not easily available via sd.loss() helpers. Check ND4J API for direct op usage.");
                }
                break;
            case NEGATIVELOGLIKELIHOOD: // Often means log loss / binary cross entropy
                sd.loss().logLoss(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT, 0.0);
                break;
            case POISSON:
                sd.loss().logPoisson(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT, false);
                break;
            case SPARSE_MCXENT:
                sd.loss().sparseSoftmaxCrossEntropy(lossName, predictions, labels); // Note: SDLoss order is (logits, labels)
                break;
            case COSINE_PROXIMITY:
                // Cosine distance needs a dimension, typically the class dimension (e.g., 1 for [batch, classes])
                // This is a simplification; dimension might need to be configurable.
                sd.loss().cosineDistance(lossName, labels, predictions, null, LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT, 1);
                break;
            case KL_DIVERGENCE:
            case MEAN_ABSOLUTE_PERCENTAGE_ERROR:
            case MEAN_SQUARED_LOGARITHMIC_ERROR:
            case WASSERSTEIN: // Wasserstein requires specific network structures usually (critic/generator)
            default:
                throw new IllegalArgumentException("Unsupported or unimplemented LossFunction specified for automatic addition: " + lossType);
        }
        // Ensure the added variable is marked as a loss variable
        SDVariable addedLossVar = sd.getVariable(lossName);
        if (addedLossVar != null) {
            addedLossVar.markAsLoss();
        }
    }


    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!initialized || sd == null) {
            throw new IllegalStateException("SameDiffTrainerRunner is not initialized.");
        }
        Objects.requireNonNull(input, "Input Data cannot be null.");

        INDArray[] features = new INDArray[inputFeatures.size()];
        for (int i = 0; i < inputFeatures.size(); i++) {
            String featureKey = inputFeatures.get(i);
            if (!input.has(featureKey)) {
                throw new IllegalArgumentException("Input Data is missing required feature NDArray for key: '" + featureKey + "'. Available keys: " + input.keySet());
            }
            NDArray kompileFeature = input.getNDArray(featureKey);
            if (kompileFeature == null) {
                throw new IllegalArgumentException("Input Data contains null feature NDArray for key: '" + featureKey + "'.");
            }
            try {
                features[i] = SameDiffDataUtils.convertToINDArray(kompileFeature, featureKey);
            } catch (Exception e) {
                throw new RuntimeException("Error converting input feature NDArray '" + featureKey + "'", e);
            }
        }

        INDArray[] labelArrays = new INDArray[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            String labelKey = labels.get(i);
            if (!input.has(labelKey)) {
                throw new IllegalArgumentException("Input Data is missing required label NDArray for key: '" + labelKey + "'. Available keys: " + input.keySet());
            }
            NDArray kompileLabel = input.getNDArray(labelKey);
            if (kompileLabel == null) {
                throw new IllegalArgumentException("Input Data contains null label NDArray for key: '" + labelKey + "'.");
            }
            try {
                labelArrays[i] = SameDiffDataUtils.convertToINDArray(kompileLabel, labelKey);
            } catch (Exception e) {
                throw new RuntimeException("Error converting input label NDArray '" + labelKey + "'", e);
            }
        }

        MultiDataSet multiDataSet = new MultiDataSet(features, labelArrays);

        try {
            sd.fit(multiDataSet); // numEpochs parameter is not used by SameDiff.fit directly for single iteration.
            // Pipeline would call this step repeatedly if multiple epochs are desired.
        } catch (Exception e) {
            throw e;
        }

        if (modelSaveOutputPath != null && !modelSaveOutputPath.isEmpty()) {
            File saveFile = new File(modelSaveOutputPath);
            File parentDir = saveFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {

            }
            sd.save(saveFile, true);
        }
        return Data.empty();
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() throws Exception {
        sd = null;
        initialized = false;
        URIUtils.deleteTempFileQuietly(modelFileRef);
    }
}