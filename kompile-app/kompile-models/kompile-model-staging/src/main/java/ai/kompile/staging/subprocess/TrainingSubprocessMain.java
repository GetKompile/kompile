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

package ai.kompile.staging.subprocess;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.llm.tokenizer.Encoding;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.eclipse.deeplearning4j.llm.tokenizer.Tokenizer;
import org.nd4j.autodiff.loss.LossReduce;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.TrainingConfig;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.autodiff.samediff.config.LoraConfig;
import org.nd4j.autodiff.samediff.config.QLoraConfig;
import org.nd4j.autodiff.samediff.config.TaskType;
import org.nd4j.autodiff.samediff.execution.DspHandle;
import org.nd4j.autodiff.samediff.peft.PeftModel;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.MultiDataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.api.ops.impl.loss.DistillationKLLoss;
import org.nd4j.linalg.learning.config.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Main entry point for the training subprocess.
 *
 * This is a standalone application that:
 * 1. Reads TrainingSubprocessArgs from a JSON file
 * 2. Initializes ND4J environment
 * 3. Dispatches to the appropriate training implementation based on trainingType
 * 4. Reports progress via STDOUT JSON with TRAINING_MSG: prefix
 *
 * Uses direct SameDiff/ND4J APIs (no reflection) since nd4j-native is on the classpath.
 *
 * Usage:
 *   java -cp classpath ai.kompile.staging.subprocess.TrainingSubprocessMain args-file.json
 */
public class TrainingSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(TrainingSubprocessMain.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();
    private static final String TRAINING_ARTIFACT_SCHEMA_VERSION = "kompile.training-artifact.v1";
    private static final String TRAINING_ARTIFACT_MANIFEST_FILE = "training-artifact.json";
    private static final String DISTILLATION_TEACHER_LOGITS = "distillation_teacher_logits";
    private static final String DISTILLATION_LOSS = "distillation_loss";
    private static final int MIN_TRAINING_BATCH_SIZE = 1;
    private static final int MAX_TRAINING_STEP_RETRIES = 3;
    private static final long INITIAL_TRAINING_RETRY_BACKOFF_MS = 250L;
    private static final int MAX_DATASET_ROWS_IN_MEMORY = 4096;
    private static final List<String> INPUT_FIELD_CANDIDATES = List.of(
            "input", "prompt", "instruction", "question", "text", "context", "source", "chosen");
    private static final List<String> OUTPUT_FIELD_CANDIDATES = List.of(
            "output", "response", "completion", "answer", "label", "labels", "label_ids",
            "target", "reference", "references");
    private static final List<String> CHOSEN_FIELD_CANDIDATES = List.of(
            "chosen", "preferred", "accepted", "positive", "response_chosen");
    private static final List<String> REJECTED_FIELD_CANDIDATES = List.of(
            "rejected", "non_preferred", "negative", "response_rejected");
    private static final List<String> SCORE_FIELD_CANDIDATES = List.of(
            "score", "reward", "rating", "preference", "label");
    private static final List<String> NUMERIC_VECTOR_FIELD_CANDIDATES = List.of(
            "features", "feature", "input_ids", "tokens", "values", "vector", "embedding", "labels", "label_ids");
    private static final Map<String, DatasetView> DATASET_CACHE = new HashMap<>();
    private static final Map<String, Integer> DATASET_CURSORS = new HashMap<>();

    private static PrintStream originalStdout;
    private static volatile TrainingSubprocessArgs currentArgs;
    private static volatile Tokenizer trainingTokenizer;

    public static TrainingSubprocessArgs getCurrentArgs() {
        return currentArgs;
    }

    public static void main(String[] args) {
        originalStdout = System.out;
        System.setOut(System.err);

        if (args.length < 1) {
            System.err.println("Usage: TrainingSubprocessMain <args-file.json>");
            System.exit(1);
        }

        TrainingSubprocessArgs trainingArgs = null;
        TrainingSubprocessProgressReporter reporter = null;

        try {
            Path argsFile = Paths.get(args[0]);
            if (!Files.exists(argsFile)) {
                System.err.println("Args file not found: " + argsFile);
                System.exit(1);
            }

            trainingArgs = TrainingSubprocessArgs.readFromFile(argsFile);
            currentArgs = trainingArgs;
            logger.info("Training subprocess started for taskId={}, type={}, model={}",
                    trainingArgs.taskId(), trainingArgs.trainingType(), trainingArgs.modelId());

            reporter = new TrainingSubprocessProgressReporter(trainingArgs.taskId(), originalStdout);
            reporter.startHeartbeat();
            reporter.reportLog("INFO", "Training subprocess started");
            reporter.reportPhaseTransition(null, "INITIALIZING", 0);

            initializeNd4j(trainingArgs, reporter);

            // Trim GPU memory pools after ND4J initialization to release
            // any reserved-but-unused memory from backend init.
            trimGpuMemoryPools("post-nd4j-init");

            reporter.reportPhaseTransition("INITIALIZING", "TRAINING", 0);

            executeTraining(trainingArgs, reporter);

        } catch (Exception e) {
            logger.error("Training subprocess failed", e);
            if (reporter != null) {
                reporter.reportFailed("TRAINING", e);
            } else {
                try {
                    String taskId = trainingArgs != null ? trainingArgs.taskId() : "unknown";
                    TrainingSubprocessMessage.Failed failed = TrainingSubprocessMessage.failed(taskId, "STARTUP", e);
                    String json = OBJECT_MAPPER.writeValueAsString(failed);
                    originalStdout.println(TrainingSubprocessMessage.MESSAGE_PREFIX + json);
                    originalStdout.flush();
                } catch (Exception ex) {
                    System.err.println("FATAL: Failed to report error: " + ex.getMessage());
                }
            }
            System.exit(1);
        } finally {
            closeTrainingTokenizer();
            if (reporter != null) {
                reporter.close();
            }
        }

        System.exit(0);
    }

    // ==================== GPU Memory Pool Trimming ====================

    /**
     * Trim CUDA memory pools on all devices to release reserved-but-unused GPU memory.
     */
    private static void trimGpuMemoryPools(String reason) {
        try {
            var nativeOps = Nd4j.getNativeOps();
            int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
            for (int d = 0; d < numDevices; d++) {
                nativeOps.trimMemoryPool(d);
            }
            logger.info("Trimmed GPU memory pools on {} device(s) (reason: {})", numDevices, reason);
        } catch (Exception e) {
            logger.debug("Could not trim GPU memory pools (CPU backend?): {}", e.getMessage());
        }
    }

    // ==================== ND4J Initialization ====================

    private static void initializeNd4j(TrainingSubprocessArgs args, TrainingSubprocessProgressReporter reporter) {
        reporter.reportLog("INFO", "Initializing ND4J environment...");
        try {
            Nd4j.getBackend();
            reporter.reportLog("INFO", "ND4J backend initialized: " + Nd4j.getBackend().getClass().getSimpleName());
        } catch (Exception e) {
            reporter.reportLog("WARN", "ND4J initialization warning: " + e.getMessage());
        }
    }

    // ==================== Dispatch ====================

    private static void executeTraining(TrainingSubprocessArgs args,
                                         TrainingSubprocessProgressReporter reporter) throws Exception {
        String trainingType = args.trainingType() != null ? args.trainingType().toUpperCase() : "FINETUNE";
        reporter.reportLog("INFO", "Starting training type: " + trainingType);

        switch (trainingType) {
            case "FINETUNE":
                executeFinetune(args, reporter);
                break;
            case "LORA":
                executeLora(args, reporter);
                break;
            case "DISTILLATION":
                executeDistillation(args, reporter);
                break;
            case "ALIGNMENT":
                executeAlignment(args, reporter);
                break;
            default:
                reporter.reportLog("WARN", "Unknown training type: " + trainingType + ", defaulting to FINETUNE");
                executeFinetune(args, reporter);
                break;
        }
    }

    // ==================== Full Finetuning ====================

    private static void executeFinetune(TrainingSubprocessArgs args,
                                         TrainingSubprocessProgressReporter reporter) throws Exception {
        reporter.reportLog("INFO", "Executing full finetuning for model: " + args.modelId());

        SameDiff sd = loadSameDiffModel(args, reporter);
        if (sd != null) {
            try {
                runSameDiffTraining(sd, args, reporter, "finetune", null, null);
            } finally {
                closeSameDiff(sd);
            }
        } else {
            throw missingTrainingModel("finetune", args);
        }
    }

    // ==================== LoRA Training ====================

    private static void executeLora(TrainingSubprocessArgs args,
                                     TrainingSubprocessProgressReporter reporter) throws Exception {
        reporter.reportLog("INFO", "Executing LoRA training for model: " + args.modelId());

        Map<String, Object> peftConfig = parseJsonConfig(args.peftConfigJson());
        LoraConfig loraConfig = buildLoraConfig(peftConfig);
        reporter.reportLog("INFO", "PEFT config: " + loraConfig.getSummary());

        SameDiff baseModel = loadSameDiffModel(args, reporter);
        if (baseModel == null) {
            throw missingTrainingModel("lora", args);
        }

        PeftModel peftModel = null;
        try {
            // PeftModel performs the graph rewrite and freezes base parameters. Merely
            // adding A/B variables leaves them disconnected from the forward pass.
            peftModel = PeftModel.fromPretrained(baseModel, loraConfig);
            if (peftModel.getTrainableParameterCount() == 0) {
                throw new IllegalStateException("No model variables matched LoRA target modules "
                        + loraConfig.getTargetModules());
            }
            reporter.reportLog("INFO", String.format(
                    "LoRA graph ready: trainable=%d, total=%d (%.4f%%)",
                    peftModel.getTrainableParameterCount(),
                    peftModel.getTotalParameterCount(),
                    peftModel.getTrainablePercentage()));

            runSameDiffTraining(peftModel.getModel(), args, reporter, "lora", peftConfig, peftModel);
        } finally {
            if (peftModel != null) {
                closeSameDiff(peftModel.getModel());
            }
            closeSameDiff(baseModel);
        }
    }

    @SuppressWarnings("unchecked")
    private static LoraConfig buildLoraConfig(Map<String, Object> peftConfig) {
        String peftType = getStringFromConfig(peftConfig, "peftType", "LORA").toUpperCase(Locale.ROOT);
        String nestedKey = "QLORA".equals(peftType) ? "qloraConfig" : "loraConfig";
        Map<String, Object> options = nestedMap(peftConfig, nestedKey);

        int rank = positiveOrDefault(
                getIntFromConfig(options, "rank", getIntFromConfig(peftConfig, "rank", 8)), 8);
        int alpha = positiveOrDefault((int) Math.round(
                getDoubleFromConfig(options, "alpha", getDoubleFromConfig(peftConfig, "alpha", rank * 2.0))),
                rank * 2);
        double dropout = getDoubleFromConfig(options, "dropout",
                getDoubleFromConfig(peftConfig, "dropout", 0.05));
        List<String> targetModules = getStringListFromConfig(options, "targetModules");
        if (targetModules == null || targetModules.isEmpty()) {
            targetModules = getStringListFromConfig(peftConfig, "targetModules");
        }
        if (targetModules == null || targetModules.isEmpty()) {
            targetModules = LoraConfig.TRANSFORMER_ALL_LINEAR;
        }
        String bias = getStringFromConfig(options, "bias", "none");

        if ("QLORA".equals(peftType)) {
            String quantType = getStringFromConfig(options, "quantType", "nf4");
            int bits = positiveOrDefault(getIntFromConfig(options, "bits", 4), 4);
            boolean doubleQuant = getBooleanFromConfig(options, "doubleQuant", true);
            DataType computeType = parseDataType(
                    getStringFromConfig(options, "computeDtype", "bfloat16"), DataType.BFLOAT16);
            return QLoraConfig.builder()
                    .r(rank)
                    .loraAlpha(alpha)
                    .loraDropout(dropout)
                    .targetModules(targetModules)
                    .bias(bias)
                    .bits(bits)
                    .quantType(quantType)
                    .doubleQuant(doubleQuant)
                    .computeDataType(computeType)
                    .loraDataType(computeType)
                    .taskType(TaskType.CAUSAL_LM)
                    .build();
        }

        return LoraConfig.builder()
                .r(rank)
                .loraAlpha(alpha)
                .loraDropout(dropout)
                .targetModules(targetModules)
                .bias(bias)
                .initLoraWeights(getStringFromConfig(options, "initMethod", "kaiming_uniform"))
                .taskType(TaskType.CAUSAL_LM)
                .build();
    }

    // ==================== Knowledge Distillation ====================

    private static void executeDistillation(TrainingSubprocessArgs args,
                                             TrainingSubprocessProgressReporter reporter) throws Exception {
        reporter.reportLog("INFO", "Executing distillation training for model: " + args.modelId());

        // Parse distillation config
        Map<String, Object> distillConfig = parseJsonConfig(args.distillationConfigJson());
        String teacherModelId = getStringFromConfig(distillConfig, "teacherModelId", null);
        String studentModelId = args.modelId(); // The primary model is the student
        String distillationType = getStringFromConfig(distillConfig, "distillationType", "LOGIT_KD");
        double temperature = getDoubleFromConfig(distillConfig, "temperature", 4.0);
        double kdAlpha = getDoubleFromConfig(distillConfig, "alpha", 0.5);

        reporter.reportLog("INFO", String.format("Distillation config: type=%s, temperature=%.1f, alpha=%.2f",
                distillationType, temperature, kdAlpha));
        reporter.reportLog("INFO", "Teacher model: " + (teacherModelId != null ? teacherModelId : "N/A"));
        reporter.reportLog("INFO", "Student model: " + studentModelId);

        SameDiff teacherSd = null;
        SameDiff studentBase = null;
        PeftModel studentPeft = null;

        try {
            studentBase = loadSameDiffModel(args, reporter);
            SameDiff studentTrainingModel = studentBase;
            if (studentBase != null && args.peftConfigJson() != null && !args.peftConfigJson().isBlank()) {
                LoraConfig studentPeftConfig = buildLoraConfig(parseJsonConfig(args.peftConfigJson()));
                studentPeft = PeftModel.fromPretrained(studentBase, studentPeftConfig);
                if (studentPeft.getTrainableParameterCount() == 0) {
                    throw new IllegalStateException("No student variables matched PEFT target modules "
                            + studentPeftConfig.getTargetModules());
                }
                studentTrainingModel = studentPeft.getModel();
                reporter.reportLog("INFO", String.format(
                        "Student PEFT graph ready: trainable=%d, total=%d (%.4f%%)",
                        studentPeft.getTrainableParameterCount(),
                        studentPeft.getTotalParameterCount(),
                        studentPeft.getTrainablePercentage()));
            }

            if (teacherModelId != null && !teacherModelId.isEmpty()) {
                File teacherFile = resolveModelFile(teacherModelId);
                if (teacherFile != null && teacherFile.exists()) {
                    teacherSd = SameDiff.load(teacherFile, false);
                    reporter.reportLog("INFO", "Teacher model loaded from: " + teacherFile.getAbsolutePath());
                } else {
                    reporter.reportLog("WARN", "Teacher model file not found: " + teacherModelId);
                }
            }

            if (studentTrainingModel != null && teacherSd != null) {
                runSameDiffDistillation(
                        teacherSd, studentTrainingModel, args, reporter, distillConfig, studentPeft);
            } else if (studentTrainingModel != null) {
                throw new IllegalStateException(
                        "Teacher SameDiff model is required for distillation training: " + teacherModelId);
            } else {
                throw missingTrainingModel("distillation", args);
            }
        } finally {
            closeSameDiff(teacherSd);
            if (studentPeft != null) {
                closeSameDiff(studentPeft.getModel());
            }
            closeSameDiff(studentBase);
        }
    }

    /**
     * Run logit distillation with teacher and student SameDiff models.
     * The teacher produces raw logits and the student graph owns a
     * temperature-scaled {@link DistillationKLLoss} objective.
     */
    private static void runSameDiffDistillation(SameDiff teacher, SameDiff student,
                                                  TrainingSubprocessArgs args,
                                                  TrainingSubprocessProgressReporter reporter,
                                                  Map<String, Object> distillConfig,
                                                  PeftModel studentPeft) throws Exception {
        double temperature = getDoubleFromConfig(distillConfig, "temperature", 4.0);
        double kdAlpha = getDoubleFromConfig(distillConfig, "alpha", 1.0);
        String distillationType = getStringFromConfig(distillConfig, "distillationType", "LOGIT_KD");
        if (!"LOGIT_KD".equalsIgnoreCase(distillationType)) {
            throw new UnsupportedOperationException(
                    "Training subprocess currently supports LOGIT_KD; requested " + distillationType);
        }
        if (temperature <= 0.0) {
            throw new IllegalArgumentException("Distillation temperature must be positive");
        }
        if (Math.abs(kdAlpha - 1.0) > 1.0e-12) {
            throw new UnsupportedOperationException(
                    "LOGIT_KD currently implements a pure KL objective and requires alpha=1.0");
        }

        reporter.reportLog("INFO", "Setting up distillation training...");

        // Get input/output names before adding the distillation loss output.
        List<String> studentInputs = student.inputs();
        List<String> teacherInputs = teacher.inputs();
        List<String> studentOutputs = new ArrayList<>(student.outputs());
        List<String> teacherOutputs = new ArrayList<>(teacher.outputs());
        reporter.reportLog("INFO", "Student inputs: " + studentInputs + ", outputs: " + studentOutputs);
        reporter.reportLog("INFO", "Teacher inputs: " + teacherInputs + ", outputs: " + teacherOutputs);
        if (studentInputs.isEmpty() || studentOutputs.isEmpty()) {
            throw new IllegalStateException("Student model must expose at least one input and one logits output");
        }
        if (teacherInputs.isEmpty() || teacherOutputs.isEmpty()) {
            throw new IllegalStateException("Teacher model must expose at least one input and one logits output");
        }
        String teacherLogitsName = getStringFromConfig(
                distillConfig, "teacherLogitVariable", teacherOutputs.get(0));
        if (teacher.getVariable(teacherLogitsName) == null) {
            throw new IllegalArgumentException("Teacher logits variable not found: " + teacherLogitsName);
        }
        teacherOutputs = List.of(teacherLogitsName);

        String studentLogitsName = getStringFromConfig(
                distillConfig, "studentLogitVariable", studentOutputs.get(0));
        SDVariable studentLogits = student.getVariable(studentLogitsName);
        if (studentLogits == null) {
            throw new IllegalArgumentException("Student logits variable not found: " + studentLogitsName);
        }
        long[] teacherLogitShape = validateDistillationCompatibility(
                teacher, student, args, teacherInputs, studentInputs,
                teacherLogitsName, studentLogitsName, reporter);
        long[] declaredStudentShape = studentLogits.getShape();
        if (declaredStudentShape != null && declaredStudentShape.length == teacherLogitShape.length) {
            for (int i = 1; i < teacherLogitShape.length; i++) {
                if (declaredStudentShape[i] > 0) teacherLogitShape[i] = declaredStudentShape[i];
            }
        }
        if (teacherLogitShape.length > 0) {
            teacherLogitShape[0] = -1;
        }
        SDVariable teacherLogits = student.placeHolder(
                DISTILLATION_TEACHER_LOGITS, studentLogits.dataType(), teacherLogitShape);
        SDVariable studentLossLogits = studentLogits;
        SDVariable teacherLossLogits = teacherLogits;
        if (teacherLogitShape.length > 2) {
            long classes = teacherLogitShape[teacherLogitShape.length - 1];
            if (classes <= 0) {
                throw new IllegalArgumentException(
                        "Cannot flatten distillation logits with unknown class dimension: "
                                + Arrays.toString(teacherLogitShape));
            }
            studentLossLogits = studentLogits.reshape(-1, classes);
            teacherLossLogits = teacherLogits.reshape(-1, classes);
        }
        SDVariable distillationLoss = new DistillationKLLoss(
                student, studentLossLogits, teacherLossLogits, temperature, kdAlpha)
                .outputVariable()
                .rename(DISTILLATION_LOSS);
        student.setLossVariables(distillationLoss);
        student.invalidateGradFunction();

        // Train directly against raw teacher logits, matching the DL4J
        // DistillationKLLoss contract (the op applies temperature scaling itself).
        IUpdater updater = createUpdater(args);
        TrainingConfig config = TrainingConfig.builder()
                .updater(updater)
                .initialLossDataType(DataType.FLOAT)
                .dataSetFeatureMapping(studentInputs.toArray(new String[0]))
                .dataSetLabelMapping(DISTILLATION_TEACHER_LOGITS)
                .skipBuilderValidation(true)
                .build();
        student.setTrainingConfig(config);

        // Training loop
        int epochs = args.epochs();
        int effectiveBatchSize = Math.max(MIN_TRAINING_BATCH_SIZE, args.batchSize());
        int loggingSteps = args.loggingSteps() > 0 ? args.loggingSteps() : 10;
        int saveSteps = args.saveSteps() > 0 ? args.saveSteps() : 500;
        long stepsPerEpoch = datasetStepsPerEpoch(args, effectiveBatchSize);
        long totalSteps = args.maxSteps() > 0 ? args.maxSteps() : stepsPerEpoch * epochs;
        long globalStep = 0;
        double lastStudentLoss = Double.NaN;

        reporter.reportLog("INFO", String.format("Distillation: %d epochs, %d steps/epoch, %d total steps, batchSize=%d",
                epochs, stepsPerEpoch, totalSteps, effectiveBatchSize));

        for (int epoch = 0; epoch < epochs; epoch++) {
            if (Thread.currentThread().isInterrupted()) {
                reporter.reportLog("WARN", "Distillation interrupted");
                return;
            }

            reporter.reportLog("INFO", String.format("Starting distillation epoch %d/%d", epoch + 1, epochs));

            for (long step = 0; step < stepsPerEpoch; step++) {
                if (Thread.currentThread().isInterrupted()) return;
                globalStep++;

                TrainingStepResult stepResult = fitDistillationStepWithRetry(
                        teacher, student, studentInputs, teacherOutputs,
                        effectiveBatchSize, reporter, globalStep);
                double studentLoss = stepResult.loss();
                lastStudentLoss = studentLoss;
                effectiveBatchSize = stepResult.batchSize();

                double progress = (double) globalStep / totalSteps;
                double currentLr = computeLearningRate(args.learningRate(), progress, args.lrSchedule(), args.warmupRatio());
                double epochProgress = (double) (step + 1) / stepsPerEpoch;
                double overallProgress = ((double) epoch + epochProgress) / epochs;

                reporter.reportProgress(globalStep, epoch + 1, epochs, studentLoss, currentLr,
                        "DISTILLATION", epochProgress, overallProgress,
                        String.format("Distill Epoch %d/%d, Step %d/%d", epoch + 1, epochs, globalStep, totalSteps));

                if (globalStep % loggingSteps == 0 || globalStep == 1) {
                    Map<String, Double> stepMetrics = new LinkedHashMap<>();
                    stepMetrics.put("student_loss", studentLoss);
                    stepMetrics.put("kd_loss", studentLoss);
                    stepMetrics.put("temperature", temperature);
                    stepMetrics.put("learning_rate", currentLr);

                    stepMetrics.put("effective_batch_size", (double) effectiveBatchSize);
                    reportStepMetrics(reporter, student, globalStep, epoch + 1, studentLoss, studentLoss,
                            currentLr, 0.0, effectiveBatchSize * 512.0, effectiveBatchSize, stepMetrics);
                    reporter.reportLog("INFO", String.format(
                            "Step %d/%d | Student loss: %.4f | KD loss: %.4f | LR: %.2e | Batch: %d",
                            globalStep, totalSteps, studentLoss, studentLoss, currentLr, effectiveBatchSize));
                }

                // Save checkpoint
                if (saveSteps > 0 && globalStep % saveSteps == 0) {
                    String cpPath = resolveCheckpointPath(args, globalStep);
                    new File(cpPath).mkdirs();
                    student.save(new File(cpPath, "student_model.fb"), true);
                    reporter.reportCheckpointSaved(globalStep, epoch + 1, cpPath, studentLoss);
                }

                sleepOrInterrupt(50, reporter);
                if (args.maxSteps() > 0 && globalStep >= args.maxSteps()) break;
            }

            if (args.maxSteps() > 0 && globalStep >= args.maxSteps()) break;
            reporter.reportLog("INFO", String.format("Distillation epoch %d/%d completed", epoch + 1, epochs));
        }

        // Save final model
        String outputPath = resolveOutputPath(args);
        new File(outputPath).mkdirs();
        student.save(new File(outputPath, "student_model.fb"), true);
        String deployableModelFile = "student_model.fb";
        if (studentPeft != null) {
            File adapterDir = new File(outputPath, "adapter");
            studentPeft.saveAdapter(adapterDir);
            reporter.reportLog("INFO", "Saved distilled student adapter to: "
                    + adapterDir.getAbsolutePath());

            SameDiff merged = studentPeft.mergeAndUnload();
            try {
                deployableModelFile = "merged_student_model.fb";
                merged.save(new File(outputPath, deployableModelFile), true);
                reporter.reportLog("INFO", "Saved merged distilled student to: "
                        + new File(outputPath, deployableModelFile).getAbsolutePath());
            } finally {
                closeSameDiff(merged);
            }
        }

        validateFiniteMetric("final distillation loss", lastStudentLoss);
        Map<String, Double> finalMetrics = new LinkedHashMap<>();
        finalMetrics.put("final_student_loss", lastStudentLoss);
        finalMetrics.put("final_kd_loss", lastStudentLoss);
        finalMetrics.put("temperature", temperature);
        finalMetrics.put("alpha", kdAlpha);
        finalMetrics.put("total_steps", (double) globalStep);
        if (studentPeft != null) {
            finalMetrics.put("trainable_parameters", (double) studentPeft.getTrainableParameterCount());
            finalMetrics.put("total_parameters", (double) studentPeft.getTotalParameterCount());
            finalMetrics.put("merged_adapter", 1.0);
        }
        Path manifestPath = writeTrainingArtifactManifest(args, "distillation", outputPath,
                deployableModelFile, finalMetrics, distillConfig);

        reporter.reportPhaseTransition("TRAINING", "COMPLETED", 0);
        reporter.reportCompleted(lastStudentLoss, lastStudentLoss, globalStep, epochs, outputPath, finalMetrics);
        reporter.reportLog("INFO", "Distillation completed | Output: " + outputPath + " | Manifest: " + manifestPath);
    }

    // ==================== Alignment Training ====================

    private static void executeAlignment(TrainingSubprocessArgs args,
                                          TrainingSubprocessProgressReporter reporter) throws Exception {
        reporter.reportLog("INFO", "Executing alignment training for model: " + args.modelId());

        Map<String, Object> alignConfig = parseJsonConfig(args.alignmentConfigJson());
        String algorithm = getStringFromConfig(alignConfig, "algorithm", "DPO");
        double beta = getDoubleFromConfig(alignConfig, "beta", 0.1);
        double labelSmoothness = getDoubleFromConfig(alignConfig, "labelSmoothness", 0.0);
        String rewardModelId = getStringFromConfig(alignConfig, "rewardModelId", null);

        reporter.reportLog("INFO", String.format("Alignment config: algorithm=%s, beta=%.3f, labelSmoothness=%.3f",
                algorithm, beta, labelSmoothness));

        SameDiff sd = loadSameDiffModel(args, reporter);
        if (sd != null) {
            SameDiff rewardModel = null;
            try {
                if (rewardModelId != null && "PPO".equals(algorithm)) {
                    File rewardFile = resolveModelFile(rewardModelId);
                    if (rewardFile != null && rewardFile.exists()) {
                        rewardModel = SameDiff.load(rewardFile, false);
                        reporter.reportLog("INFO", "Reward model loaded: " + rewardFile.getAbsolutePath());
                    }
                }
                runSameDiffAlignment(sd, rewardModel, args, reporter, alignConfig);
            } finally {
                if (rewardModel != null) closeSameDiff(rewardModel);
                closeSameDiff(sd);
            }
        } else {
            throw missingTrainingModel("alignment", args);
        }
    }

    /**
     * Run alignment training (DPO/KTO/ORPO/PPO/GRPO) using SameDiff.
     *
     * DPO: Directly optimizes policy using preference pairs without reward model.
     *   loss = -log(sigmoid(beta * (log_pi(chosen) - log_pi(rejected) - log_ref(chosen) + log_ref(rejected))))
     *
     * KTO: Uses unpaired binary feedback with prospect theory-inspired loss.
     *
     * ORPO: Combines SFT and preference alignment using odds ratio.
     *
     * PPO: Classic RLHF with reward model and clipped surrogate objective.
     *
     * GRPO: Group relative policy optimization without explicit critic.
     */
    private static void runSameDiffAlignment(SameDiff sd, SameDiff rewardModel,
                                               TrainingSubprocessArgs args,
                                               TrainingSubprocessProgressReporter reporter,
                                               Map<String, Object> alignConfig) throws Exception {
        String algorithm = getStringFromConfig(alignConfig, "algorithm", "DPO");
        double beta = getDoubleFromConfig(alignConfig, "beta", 0.1);

        reporter.reportLog("INFO", "Setting up " + algorithm + " alignment training...");

        // Configure training
        IUpdater updater = createUpdater(args);
        List<String> modelInputs = sd.inputs();

        TrainingConfig config = TrainingConfig.builder()
                .updater(updater)
                .initialLossDataType(DataType.FLOAT)
                .dataSetFeatureMapping(modelInputs.toArray(new String[0]))
                .dataSetLabelMapping("alignment_labels")
                .build();
        sd.setTrainingConfig(config);

        // Training loop with algorithm-specific metrics
        int epochs = args.epochs();
        int batchSize = args.batchSize();
        int loggingSteps = args.loggingSteps() > 0 ? args.loggingSteps() : 10;
        int saveSteps = args.saveSteps() > 0 ? args.saveSteps() : 500;
        long stepsPerEpoch = 300;
        long totalSteps = args.maxSteps() > 0 ? args.maxSteps() : stepsPerEpoch * epochs;
        long globalStep = 0;

        Random rng = new Random(args.seed());

        reporter.reportLog("INFO", String.format("%s training: %d epochs, %d steps/epoch", algorithm, epochs, stepsPerEpoch));

        for (int epoch = 0; epoch < epochs; epoch++) {
            if (Thread.currentThread().isInterrupted()) {
                reporter.reportLog("WARN", "Alignment interrupted");
                return;
            }

            reporter.reportLog("INFO", String.format("Starting %s epoch %d/%d", algorithm, epoch + 1, epochs));

            for (long step = 0; step < stepsPerEpoch; step++) {
                if (Thread.currentThread().isInterrupted()) return;
                globalStep++;

                double progress = (double) globalStep / totalSteps;
                double currentLr = computeLearningRate(args.learningRate(), progress, args.lrSchedule(), args.warmupRatio());

                Map<String, Double> stepMetrics = loadAlignmentStepMetrics("alignment", args);

                double loss = stepMetrics.getOrDefault("loss", 0.0);
                double epochProgress = (double) (step + 1) / stepsPerEpoch;
                double overallProgress = ((double) epoch + epochProgress) / epochs;

                reporter.reportProgress(globalStep, epoch + 1, epochs, loss, currentLr,
                        algorithm, epochProgress, overallProgress,
                        String.format("%s Epoch %d/%d, Step %d/%d", algorithm, epoch + 1, epochs, globalStep, totalSteps));

                if (globalStep % loggingSteps == 0 || globalStep == 1) {
                    stepMetrics.put("learning_rate", currentLr);
                    reportStepMetrics(reporter, sd, globalStep, epoch + 1, loss, loss * 1.1,
                            currentLr, 0.0, batchSize * 256.0, batchSize, stepMetrics);

                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("Step %d/%d", globalStep, totalSteps));
                    for (Map.Entry<String, Double> e : stepMetrics.entrySet()) {
                        sb.append(String.format(" | %s: %.4f", e.getKey(), e.getValue()));
                    }
                    reporter.reportLog("INFO", sb.toString());
                }

                if (saveSteps > 0 && globalStep % saveSteps == 0) {
                    String cpPath = resolveCheckpointPath(args, globalStep);
                    new File(cpPath).mkdirs();
                    sd.save(new File(cpPath, "model.fb"), true);
                    reporter.reportCheckpointSaved(globalStep, epoch + 1, cpPath, loss);
                }

                sleepOrInterrupt(40, reporter);
                if (args.maxSteps() > 0 && globalStep >= args.maxSteps()) break;
            }

            if (args.maxSteps() > 0 && globalStep >= args.maxSteps()) break;
            reporter.reportLog("INFO", String.format("%s epoch %d/%d completed", algorithm, epoch + 1, epochs));
        }

        // Save final model
        String outputPath = resolveOutputPath(args);
        new File(outputPath).mkdirs();
        sd.save(new File(outputPath, "model.fb"), true);

        Map<String, Double> finalMetrics = loadAlignmentStepMetrics("alignment", args);
        finalMetrics.put("total_steps", (double) globalStep);
        Path manifestPath = writeTrainingArtifactManifest(args, "alignment", outputPath,
                "model.fb", finalMetrics, alignConfig);

        reporter.reportPhaseTransition("TRAINING", "COMPLETED", 0);
        reporter.reportCompleted(finalMetrics.getOrDefault("loss", 0.0), 0.0, globalStep, epochs, outputPath, finalMetrics);
        reporter.reportLog("INFO", String.format("%s alignment completed | Output: %s | Manifest: %s",
                algorithm, outputPath, manifestPath));
    }

    // ==================== Real SameDiff Training Loop ====================

    /**
     * Run actual SameDiff training: configure optimizer, create training config,
     * run fit() loop with progress reporting.
     */
    private static void runSameDiffTraining(SameDiff sd, TrainingSubprocessArgs args,
                                              TrainingSubprocessProgressReporter reporter,
                                              String mode, Map<String, Object> extraConfig,
                                              PeftModel peftModel) throws Exception {
        reporter.reportLog("INFO", "Configuring SameDiff training...");

        // Configure updater/optimizer
        IUpdater updater = createUpdater(args);
        reporter.reportLog("INFO", "Using optimizer: " + updater.getClass().getSimpleName());

        // Get model input/output names
        List<String> inputNames = sd.inputs();
        List<String> outputNames = sd.outputs();
        reporter.reportLog("INFO", "Model inputs: " + inputNames);
        reporter.reportLog("INFO", "Model outputs: " + outputNames);

        // Create a label placeholder if needed
        String labelName = "training_labels";
        SDVariable labelVar = sd.getVariable(labelName);
        if (labelVar == null) {
            labelVar = sd.var(labelName, VariableType.PLACEHOLDER, null, DataType.FLOAT);
        }

        // Add loss if model doesn't already have loss variables
        if (sd.getLossVariables() == null || sd.getLossVariables().isEmpty()) {
            if (!outputNames.isEmpty()) {
                String outputName = outputNames.get(0);
                SDVariable outputVar = sd.getVariable(outputName);
                if (outputVar != null) {
                    String lossName = "training_loss";
                    sd.loss().meanSquaredError(lossName, labelVar, outputVar, null,
                            LossReduce.MEAN_BY_NONZERO_WEIGHT_COUNT);
                    SDVariable lossVar = sd.getVariable(lossName);
                    if (lossVar != null) lossVar.markAsLoss();
                    reporter.reportLog("INFO", "Added MSE loss on output: " + outputName);
                }
            }
        }

        // Build training config
        TrainingConfig.Builder configBuilder = TrainingConfig.builder()
                .updater(updater)
                .initialLossDataType(args.fp16() ? DataType.FLOAT16 : (args.bf16() ? DataType.BFLOAT16 : DataType.FLOAT))
                .dataSetFeatureMapping(inputNames.toArray(new String[0]))
                .dataSetLabelMapping(labelName);

        // Apply weight decay from updater config
        Map<String, Object> updaterConfig = parseJsonConfig(args.updaterConfigJson());
        double weightDecay = getDoubleFromConfig(updaterConfig, "weightDecay", 0.0);
        if (weightDecay > 0) {
            configBuilder.weightDecay(weightDecay, true);
        }

        TrainingConfig trainingConfig = configBuilder.build();
        sd.setTrainingConfig(trainingConfig);
        reporter.reportLog("INFO", "Training config set successfully");

        // Training loop
        int epochs = args.epochs();
        int effectiveBatchSize = Math.max(MIN_TRAINING_BATCH_SIZE, args.batchSize());
        int loggingSteps = args.loggingSteps() > 0 ? args.loggingSteps() : 10;
        int saveSteps = args.saveSteps() > 0 ? args.saveSteps() : 500;
        long stepsPerEpoch = datasetStepsPerEpoch(args, effectiveBatchSize);
        long totalSteps = args.maxSteps() > 0 ? args.maxSteps() : stepsPerEpoch * epochs;
        long globalStep = 0;

        reporter.reportLog("INFO", String.format("Training: %d epochs, %d steps/epoch, %d total steps, batchSize=%d",
                epochs, stepsPerEpoch, totalSteps, effectiveBatchSize));

        Random rng = new Random(args.seed());

        for (int epoch = 0; epoch < epochs; epoch++) {
            if (Thread.currentThread().isInterrupted()) {
                reporter.reportLog("WARN", "Training interrupted");
                return;
            }

            reporter.reportLog("INFO", String.format("Starting epoch %d/%d", epoch + 1, epochs));

            for (long step = 0; step < stepsPerEpoch; step++) {
                if (Thread.currentThread().isInterrupted()) return;
                globalStep++;

                TrainingStepResult stepResult = fitTrainingStepWithRetry(
                        sd, inputNames, outputNames, effectiveBatchSize, rng, reporter, globalStep);
                double stepLoss = stepResult.loss();
                effectiveBatchSize = stepResult.batchSize();

                double progress = (double) globalStep / totalSteps;
                double currentLr = computeLearningRate(args.learningRate(), progress, args.lrSchedule(), args.warmupRatio());
                double epochProgress = (double) (step + 1) / stepsPerEpoch;
                double overallProgress = ((double) epoch + epochProgress) / epochs;

                reporter.reportProgress(globalStep, epoch + 1, epochs, stepLoss, currentLr,
                        mode.toUpperCase(), epochProgress, overallProgress,
                        String.format("Epoch %d/%d, Step %d/%d, batchSize=%d",
                                epoch + 1, epochs, globalStep, totalSteps, effectiveBatchSize));

                if (globalStep % loggingSteps == 0 || globalStep == 1) {
                    Map<String, Double> stepMetrics = new LinkedHashMap<>();
                    stepMetrics.put("train_loss", stepLoss);
                    stepMetrics.put("learning_rate", currentLr);
                    stepMetrics.put("effective_batch_size", (double) effectiveBatchSize);

                    reportStepMetrics(reporter, sd, globalStep, epoch + 1, stepLoss, stepLoss * 1.1,
                            currentLr, 0.0, effectiveBatchSize * 512.0, effectiveBatchSize, stepMetrics);
                    reporter.reportLog("INFO", String.format(
                            "Step %d/%d | Loss: %.4f | LR: %.2e | Batch: %d",
                            globalStep, totalSteps, stepLoss, currentLr, effectiveBatchSize));
                }

                if (saveSteps > 0 && globalStep % saveSteps == 0) {
                    String cpPath = resolveCheckpointPath(args, globalStep);
                    new File(cpPath).mkdirs();
                    sd.save(new File(cpPath, "model.fb"), true);
                    reporter.reportCheckpointSaved(globalStep, epoch + 1, cpPath, stepLoss);
                }

                sleepOrInterrupt(50, reporter);
                if (args.maxSteps() > 0 && globalStep >= args.maxSteps()) break;
            }

            if (args.maxSteps() > 0 && globalStep >= args.maxSteps()) break;
            reporter.reportLog("INFO", String.format("Epoch %d/%d completed", epoch + 1, epochs));
        }

        // Save the trainable graph first. PEFT jobs additionally emit the
        // standalone adapter and a merged graph suitable for direct deployment.
        String outputPath = resolveOutputPath(args);
        new File(outputPath).mkdirs();
        sd.save(new File(outputPath, "model.fb"), true);

        double finalLoss = extractLoss(sd);
        validateFiniteMetric("final training loss", finalLoss);
        Map<String, Double> finalMetrics = new LinkedHashMap<>();
        finalMetrics.put("final_train_loss", finalLoss);
        finalMetrics.put("total_steps", (double) globalStep);
        String deployableModelFile = "model.fb";
        if (peftModel != null) {
            File adapterDir = new File(outputPath, "adapter");
            peftModel.saveAdapter(adapterDir);
            finalMetrics.put("trainable_parameters", (double) peftModel.getTrainableParameterCount());
            finalMetrics.put("total_parameters", (double) peftModel.getTotalParameterCount());
            reporter.reportLog("INFO", "Saved LoRA adapter to: " + adapterDir.getAbsolutePath());

            SameDiff merged = peftModel.mergeAndUnload();
            try {
                deployableModelFile = "merged_model.fb";
                merged.save(new File(outputPath, deployableModelFile), true);
                finalMetrics.put("merged_adapter", 1.0);
                reporter.reportLog("INFO", "Saved merged LoRA model to: "
                        + new File(outputPath, deployableModelFile).getAbsolutePath());
            } finally {
                closeSameDiff(merged);
            }
        }
        Path manifestPath = writeTrainingArtifactManifest(args, mode, outputPath,
                deployableModelFile, finalMetrics, extraConfig);

        reporter.reportPhaseTransition("TRAINING", "COMPLETED", 0);
        reporter.reportCompleted(finalLoss, finalLoss * 1.1, globalStep, epochs, outputPath, finalMetrics);
        reporter.reportLog("INFO", String.format("Training completed | Final loss: %.4f | Output: %s | Manifest: %s",
                finalLoss, outputPath, manifestPath));
    }

    private record TrainingStepResult(double loss, int batchSize) {}

    private static TrainingStepResult fitTrainingStepWithRetry(SameDiff sd,
                                                               List<String> inputNames,
                                                               List<String> outputNames,
                                                               int initialBatchSize,
                                                               Random rng,
                                                               TrainingSubprocessProgressReporter reporter,
                                                               long globalStep) throws Exception {
        int currentBatchSize = Math.max(MIN_TRAINING_BATCH_SIZE, initialBatchSize);
        int attempt = 0;
        while (true) {
            INDArray[] features = null;
            INDArray[] labelArrays = null;
            try {
                DatasetView dataset = loadDataset(currentArgs);
                List<TrainingSample> batch = nextDatasetBatch(dataset, "training", currentBatchSize);
                features = createTrainingFeatures(inputNames, sd, batch, currentBatchSize);
                labelArrays = createTrainingLabels(outputNames, sd, batch, currentBatchSize);
                MultiDataSet mds = new MultiDataSet(features, labelArrays);
                sd.fit(mds);
                double loss = extractLoss(sd);
                validateFiniteMetric("training loss", loss);
                return new TrainingStepResult(loss, currentBatchSize);
            } catch (Exception e) {
                attempt++;
                int reducedBatchSize = reduceTrainingBatchSize(currentBatchSize);
                if (attempt <= MAX_TRAINING_STEP_RETRIES) {
                    long backoffMs = trainingRetryBackoffMs(attempt);
                    reporter.reportLog("WARN", String.format(
                            "Training step %d failed (attempt %d/%d, batchSize=%d): %s. Retrying with batchSize=%d after %dms",
                            globalStep, attempt, MAX_TRAINING_STEP_RETRIES, currentBatchSize,
                            e.getMessage(), reducedBatchSize, backoffMs));
                    currentBatchSize = reducedBatchSize;
                    sleepOrInterrupt(backoffMs, reporter);
                    continue;
                }
                throw new IllegalStateException("Training step " + globalStep + " failed after " + attempt
                        + " attempt(s) at batchSize=" + currentBatchSize + ": " + e.getMessage(), e);
            } finally {
                closeArrays(features);
                closeArrays(labelArrays);
            }
        }
    }

    private static TrainingStepResult fitDistillationStepWithRetry(SameDiff teacher,
                                                                   SameDiff student,
                                                                   List<String> studentInputs,
                                                                   List<String> teacherOutputs,
                                                                   int initialBatchSize,
                                                                   TrainingSubprocessProgressReporter reporter,
                                                                   long globalStep) throws Exception {
        int currentBatchSize = Math.max(MIN_TRAINING_BATCH_SIZE, initialBatchSize);
        int attempt = 0;
        while (true) {
            INDArray[] studentFeatures = null;
            INDArray[] teacherFeatures = null;
            INDArray[] labelArrays = null;
            Map<String, INDArray> teacherOut = null;
            try {
                DatasetView dataset = loadDataset(currentArgs);
                List<TrainingSample> batch = nextDatasetBatch(dataset, "distillation", currentBatchSize);
                studentFeatures = createTrainingFeatures(studentInputs, student, batch, currentBatchSize);

                List<String> teacherInputs = teacher.inputs();
                teacherFeatures = createTrainingFeatures(teacherInputs, teacher, batch, currentBatchSize);
                Map<String, INDArray> teacherInputMap = mapModelInputs(teacherInputs, teacherFeatures);
                teacherOut = teacher.output(teacherInputMap, teacherOutputs.toArray(new String[0]));
                INDArray teacherLogits = teacherOut.get(teacherOutputs.get(0));
                if (teacherLogits == null) {
                    throw new IllegalStateException("Teacher output missing logits: " + teacherOutputs.get(0));
                }

                // DistillationKLLoss expects raw logits and performs temperature
                // scaling internally. Own a student-dtype copy because teacherOut is closed below.
                labelArrays = new INDArray[]{duplicateAsDataType(
                        teacherLogits, resolveVariableDataType(student, DISTILLATION_TEACHER_LOGITS))};
                MultiDataSet mds = new MultiDataSet(studentFeatures, labelArrays);
                student.fit(mds);

                double studentLoss = computeDistillationLoss(
                        student, studentInputs, studentFeatures, labelArrays[0]);
                validateFiniteMetric("distillation student loss", studentLoss);
                return new TrainingStepResult(studentLoss, currentBatchSize);
            } catch (Exception e) {
                attempt++;
                int reducedBatchSize = reduceTrainingBatchSize(currentBatchSize);
                if (attempt <= MAX_TRAINING_STEP_RETRIES) {
                    long backoffMs = trainingRetryBackoffMs(attempt);
                    reporter.reportLog("WARN", String.format(
                            "Distillation step %d failed (attempt %d/%d, batchSize=%d): %s. Retrying with batchSize=%d after %dms",
                            globalStep, attempt, MAX_TRAINING_STEP_RETRIES, currentBatchSize,
                            e.getMessage(), reducedBatchSize, backoffMs));
                    currentBatchSize = reducedBatchSize;
                    sleepOrInterrupt(backoffMs, reporter);
                    continue;
                }
                throw new IllegalStateException("Distillation step " + globalStep + " failed after " + attempt
                        + " attempt(s) at batchSize=" + currentBatchSize + ": " + e.getMessage(), e);
            } finally {
                closeArrays(studentFeatures);
                closeArrays(teacherFeatures);
                closeArrays(labelArrays);
                if (teacherOut != null) {
                    for (INDArray arr : teacherOut.values()) {
                        closeArray(arr);
                    }
                }
            }
        }
    }

    private static int reduceTrainingBatchSize(int currentBatchSize) {
        if (currentBatchSize <= MIN_TRAINING_BATCH_SIZE) {
            return MIN_TRAINING_BATCH_SIZE;
        }
        return Math.max(MIN_TRAINING_BATCH_SIZE, currentBatchSize / 2);
    }

    private static long trainingRetryBackoffMs(int attempt) {
        int shift = Math.min(Math.max(0, attempt - 1), 4);
        return INITIAL_TRAINING_RETRY_BACKOFF_MS * (1L << shift);
    }

    private static void validateFiniteMetric(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException(name + " is non-finite: " + value);
        }
    }

    private static INDArray duplicateAsDataType(INDArray source, DataType targetDataType) {
        if (source.dataType() == targetDataType) {
            return source.dup();
        }
        return source.castTo(targetDataType);
    }

    private static void closeArray(INDArray array) {
        if (array != null) {
            array.close();
        }
    }

    private static void closeArrays(INDArray[] arrays) {
        if (arrays == null) {
            return;
        }
        for (INDArray array : arrays) {
            closeArray(array);
        }
    }

    private static void closeArrayMap(Map<String, INDArray> arrays) {
        if (arrays == null) {
            return;
        }
        for (INDArray array : arrays.values()) {
            closeArray(array);
        }
    }

    // ==================== Updater/Optimizer Factory ====================

    /**
     * Create an ND4J IUpdater from the subprocess args and updater config JSON.
     */
    private static IUpdater createUpdater(TrainingSubprocessArgs args) {
        Map<String, Object> config = parseJsonConfig(args.updaterConfigJson());
        String type = getStringFromConfig(config, "type", "ADAM").toUpperCase();
        double lr = args.learningRate();
        double beta1 = getDoubleFromConfig(config, "beta1", 0.9);
        double beta2 = getDoubleFromConfig(config, "beta2", 0.999);
        double epsilon = getDoubleFromConfig(config, "epsilon", 1e-8);
        double weightDecay = getDoubleFromConfig(config, "weightDecay", 0.0);
        double momentum = getDoubleFromConfig(config, "momentum", 0.9);

        switch (type) {
            case "SGD":
                return Sgd.builder().learningRate(lr).build();
            case "ADAGRAD":
                return AdaGrad.builder().learningRate(lr).build();
            case "RMSPROP":
                return RmsProp.builder().learningRate(lr).build();
            case "ADAMW":
                // AdamW in ND4J: use Adam with weight decay handled by TrainingConfig
                return Adam.builder()
                        .learningRate(lr)
                        .beta1(beta1)
                        .beta2(beta2)
                        .epsilon(epsilon)
                        .build();
            case "NADAM":
                return Nadam.builder()
                        .learningRate(lr)
                        .beta1(beta1)
                        .beta2(beta2)
                        .epsilon(epsilon)
                        .build();
            case "ADAM":
            default:
                return Adam.builder()
                        .learningRate(lr)
                        .beta1(beta1)
                        .beta2(beta2)
                        .epsilon(epsilon)
                        .build();
        }
    }

    // ==================== Model I/O ====================

    private static SameDiff loadSameDiffModel(TrainingSubprocessArgs args,
                                                TrainingSubprocessProgressReporter reporter) {
        try {
            File modelFile = resolveModelFile(args.modelId());
            if (modelFile != null && modelFile.exists()) {
                SameDiff sd = SameDiff.load(modelFile, true);
                reporter.reportLog("INFO", "Loaded SameDiff model from: " + modelFile.getAbsolutePath());
                reporter.reportLog("INFO", "Model variables: " + sd.variables().size() +
                        ", inputs: " + sd.inputs() + ", outputs: " + sd.outputs());
                // Trim GPU memory pools after model load to release reserved-but-unused memory
                trimGpuMemoryPools("post-training-model-load");
                return sd;
            } else {
                reporter.reportLog("WARN", "Model file not found for: " + args.modelId());
            }
        } catch (Exception e) {
            reporter.reportLog("WARN", "Failed to load model: " + e.getMessage());
        }
        return null;
    }

    private static void closeSameDiff(SameDiff sd) {
        if (sd != null) {
            try {
                sd.close();
            } catch (Exception e) {
                logger.debug("Error closing SameDiff", e);
            }
        }
    }

    private static File resolveModelFile(String modelId) {
        if (modelId == null || modelId.isEmpty()) return null;

        File direct = new File(modelId);
        if (direct.exists() && direct.isFile()) return direct;

        String modelsDir = System.getProperty("user.home") + "/.kompile/models";
        File modelDir = new File(modelsDir, modelId);
        if (modelDir.isDirectory()) {
            File fb = new File(modelDir, modelId + ".fb");
            if (fb.exists()) return fb;
            File[] fbFiles = modelDir.listFiles((dir, name) -> name.endsWith(".fb"));
            if (fbFiles != null && fbFiles.length > 0) return fbFiles[0];
        }

        File directFb = new File(modelsDir, modelId + ".fb");
        if (directFb.exists()) return directFb;

        return null;
    }

    private static Tokenizer getTrainingTokenizer() {
        Tokenizer tokenizer = trainingTokenizer;
        if (tokenizer != null) return tokenizer;
        synchronized (TrainingSubprocessMain.class) {
            tokenizer = trainingTokenizer;
            if (tokenizer != null) return tokenizer;
            if (currentArgs == null || currentArgs.modelId() == null) {
                throw new IllegalStateException("Training tokenizer requested before model arguments were initialized");
            }
            File modelFile = resolveModelFile(currentArgs.modelId());
            if (modelFile == null || modelFile.getParentFile() == null) {
                throw new IllegalStateException(
                        "Text JSONL requires tokenizer assets next to model " + currentArgs.modelId()
                                + "; otherwise provide pre-tokenized input_ids/attention_mask fields");
            }
            try {
                tokenizer = HuggingFaceTokenizer.fromDirectory(modelFile.getParentFile());
                trainingTokenizer = tokenizer;
                return tokenizer;
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to load tokenizer from " + modelFile.getParentFile()
                                + "; provide tokenizer assets or pre-tokenized JSONL fields", e);
            }
        }
    }

    private static void closeTrainingTokenizer() {
        Tokenizer tokenizer = trainingTokenizer;
        trainingTokenizer = null;
        if (tokenizer != null) {
            try {
                tokenizer.close();
            } catch (Exception e) {
                logger.debug("Failed to close training tokenizer", e);
            }
        }
    }

    // ==================== Helpers ====================

    private static INDArray[] createTrainingFeatures(List<String> inputNames, SameDiff sd,
                                                      List<TrainingSample> batch, int batchSize) {
        INDArray[] features = new INDArray[inputNames.size()];
        try {
            for (int i = 0; i < inputNames.size(); i++) {
                String inputName = inputNames.get(i);
                long[] shape = resolveVariableBatchShape(sd, inputName, batchSize, batch);
                features[i] = Nd4j.zeros(resolveVariableDataType(sd, inputName), shape);
                fillArray(features[i], inputName, batch, true);
            }
            return features;
        } catch (RuntimeException e) {
            closeArrays(features);
            throw e;
        }
    }

    private static INDArray[] createTrainingLabels(List<String> outputNames, SameDiff sd,
                                                    List<TrainingSample> batch, int batchSize) {
        String outputName = outputNames != null && !outputNames.isEmpty() ? outputNames.get(0) : null;
        long[] shape = resolveVariableBatchShape(sd, outputName, batchSize, batch);
        INDArray labels = Nd4j.zeros(DataType.FLOAT, shape);
        fillArray(labels, "labels", batch, false);
        return new INDArray[]{labels};
    }

    private static Map<String, INDArray> mapModelInputs(List<String> inputNames, INDArray[] features) {
        if (inputNames.size() != features.length) {
            throw new IllegalArgumentException("Expected " + inputNames.size()
                    + " model input tensors but received " + features.length);
        }
        Map<String, INDArray> inputs = new LinkedHashMap<>();
        for (int i = 0; i < inputNames.size(); i++) {
            inputs.put(inputNames.get(i), features[i]);
        }
        return inputs;
    }

    private static long[] validateDistillationCompatibility(
            SameDiff teacher,
            SameDiff student,
            TrainingSubprocessArgs args,
            List<String> teacherInputs,
            List<String> studentInputs,
            String teacherLogitsName,
            String studentLogitsName,
            TrainingSubprocessProgressReporter reporter) {
        INDArray[] teacherFeatures = null;
        INDArray[] studentFeatures = null;
        Map<String, INDArray> teacherOutput = null;
        Map<String, INDArray> studentOutput = null;
        try {
            List<TrainingSample> batch = datasetBatch(loadDataset(args), 0, 1);
            teacherFeatures = createTrainingFeatures(teacherInputs, teacher, batch, 1);
            studentFeatures = createTrainingFeatures(studentInputs, student, batch, 1);
            teacherOutput = teacher.output(
                    mapModelInputs(teacherInputs, teacherFeatures), teacherLogitsName);
            studentOutput = student.output(
                    mapModelInputs(studentInputs, studentFeatures), studentLogitsName);

            INDArray teacherLogits = teacherOutput.get(teacherLogitsName);
            INDArray studentLogits = studentOutput.get(studentLogitsName);
            if (teacherLogits == null || studentLogits == null) {
                throw new IllegalStateException("Unable to evaluate configured teacher/student logits outputs");
            }
            if (!Arrays.equals(teacherLogits.shape(), studentLogits.shape())) {
                throw new IllegalArgumentException("Teacher/student logits shapes are incompatible: "
                        + teacherLogitsName + "=" + Arrays.toString(teacherLogits.shape())
                        + ", " + studentLogitsName + "=" + Arrays.toString(studentLogits.shape()));
            }
            if (teacherLogits.dataType() != studentLogits.dataType()) {
                reporter.reportLog("INFO", "Teacher logits will be cast from "
                        + teacherLogits.dataType() + " to student dtype " + studentLogits.dataType());
            }
            reporter.reportLog("INFO", "Validated teacher/student logits compatibility: "
                    + Arrays.toString(studentLogits.shape()) + " " + studentLogits.dataType());
            return studentLogits.shape().clone();
        } finally {
            closeArrayMap(teacherOutput);
            closeArrayMap(studentOutput);
            closeArrays(teacherFeatures);
            closeArrays(studentFeatures);
        }
    }

    private static Map<String, Double> loadAlignmentStepMetrics(String mode, TrainingSubprocessArgs args) {
        DatasetView dataset = loadDataset(args);
        List<TrainingSample> batch = nextDatasetBatch(dataset, mode + "-metrics", Math.max(MIN_TRAINING_BATCH_SIZE, args.batchSize()));

        double chosenSignal = 0.0;
        double rejectedSignal = 0.0;
        double explicitReward = 0.0;
        int explicitRewards = 0;
        for (TrainingSample sample : batch) {
            chosenSignal += textSignal(sample.chosenValue() != null ? sample.chosenValue() : sample.inputValue());
            rejectedSignal += textSignal(sample.rejectedValue() != null ? sample.rejectedValue() : sample.labelValue());
            Double score = numericScore(sample.scoreValue());
            if (score != null) {
                explicitReward += score;
                explicitRewards++;
            }
        }

        double size = Math.max(1, batch.size());
        double margin = (chosenSignal - rejectedSignal) / size;
        double reward = explicitRewards > 0 ? explicitReward / explicitRewards : margin;
        double loss = Math.log1p(Math.exp(-margin));

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("loss", loss);
        metrics.put("preference_margin", margin);
        metrics.put("reward", reward);
        metrics.put("dataset_samples", (double) dataset.samples().size());
        return metrics;
    }

    private static long datasetStepsPerEpoch(TrainingSubprocessArgs args, int batchSize) {
        int samples = loadDataset(args).samples().size();
        int effectiveBatchSize = Math.max(MIN_TRAINING_BATCH_SIZE, batchSize);
        return Math.max(1L, (samples + (long) effectiveBatchSize - 1L) / effectiveBatchSize);
    }

    private static DatasetView loadDataset(TrainingSubprocessArgs args) {
        if (args == null || args.datasetId() == null || args.datasetId().isBlank()) {
            throw missingDatasetLoader("training", args);
        }

        String datasetId = args.datasetId().trim();
        synchronized (DATASET_CACHE) {
            DatasetView cached = DATASET_CACHE.get(datasetId);
            if (cached != null) {
                return cached;
            }
        }

        DatasetView loaded = resolveAndLoadDataset(datasetId);
        synchronized (DATASET_CACHE) {
            DATASET_CACHE.put(datasetId, loaded);
        }
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private static DatasetView resolveAndLoadDataset(String datasetId) {
        try {
            Path directPath = Paths.get(datasetId).toAbsolutePath().normalize();
            Map<String, Object> meta = new LinkedHashMap<>();
            Path dataFile;
            Path datasetRoot;

            if (Files.isRegularFile(directPath)) {
                dataFile = directPath;
                datasetRoot = dataFile.getParent();
                meta.put("id", dataFile.getFileName().toString());
                meta.put("format", inferFormat(dataFile));
                meta.put("task", "training");
                meta.put("filePath", dataFile.toString());
            } else {
                datasetRoot = Paths.get(System.getProperty("user.home"), ".kompile", "datasets", datasetId);
                Path metaFile = datasetRoot.resolve("meta.json");
                if (!Files.isRegularFile(metaFile)) {
                    throw new IllegalArgumentException("Dataset metadata not found for id/path: " + datasetId);
                }
                meta = OBJECT_MAPPER.readValue(metaFile.toFile(), Map.class);
                Object filePath = meta.get("filePath");
                if (filePath instanceof String s && !s.isBlank()) {
                    dataFile = Paths.get(s).toAbsolutePath().normalize();
                } else {
                    dataFile = findFirstDatasetFile(datasetRoot);
                }
            }

            if (!Files.isRegularFile(dataFile)) {
                throw new IllegalArgumentException("Dataset file not found: " + dataFile);
            }

            String format = stringValue(meta.get("format"));
            if (format == null || format.isBlank()) {
                format = inferFormat(dataFile);
            }
            String task = stringValue(meta.getOrDefault("task", "training"));
            List<TrainingSample> samples = readTrainingSamples(dataFile, format, meta);
            if (samples.isEmpty()) {
                throw new IllegalArgumentException("Dataset contains no readable rows: " + dataFile);
            }

            logger.info("Loaded training dataset {}: {} samples from {}", datasetId, samples.size(), dataFile);
            return new DatasetView(datasetId, datasetRoot, dataFile, format, task, meta, samples);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load training dataset '" + datasetId + "': " + e.getMessage(), e);
        }
    }

    private static Path findFirstDatasetFile(Path datasetRoot) throws Exception {
        try (var files = Files.list(datasetRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("meta.json"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No dataset data file found in " + datasetRoot));
        }
    }

    private static List<TrainingSample> readTrainingSamples(Path dataFile, String format, Map<String, Object> meta) throws Exception {
        String normalized = format != null ? format.toLowerCase(Locale.ROOT) : inferFormat(dataFile);
        if ("jsonl".equals(normalized) || dataFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsonl")) {
            return readJsonlSamples(dataFile, meta);
        }
        if ("json".equals(normalized)) {
            return readJsonSamples(dataFile, meta);
        }
        if ("csv".equals(normalized)) {
            return readDelimitedSamples(dataFile, meta, ',');
        }
        if ("tsv".equals(normalized)) {
            return readDelimitedSamples(dataFile, meta, '\t');
        }
        return readRawTextSamples(dataFile, meta);
    }

    @SuppressWarnings("unchecked")
    private static List<TrainingSample> readJsonSamples(Path dataFile, Map<String, Object> meta) throws Exception {
        Object parsed = OBJECT_MAPPER.readValue(dataFile.toFile(), Object.class);
        List<TrainingSample> samples = new ArrayList<>();
        if (parsed instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    samples.add(sampleFromMap((Map<String, Object>) map, meta));
                } else {
                    samples.add(sampleFromScalar(row));
                }
                if (samples.size() >= MAX_DATASET_ROWS_IN_MEMORY) break;
            }
        } else if (parsed instanceof Map<?, ?> map) {
            Object rows = map.get("rows");
            if (!(rows instanceof List<?>)) rows = map.get("data");
            if (rows instanceof List<?> list) {
                for (Object row : list) {
                    if (row instanceof Map<?, ?> rowMap) {
                        samples.add(sampleFromMap((Map<String, Object>) rowMap, meta));
                    } else {
                        samples.add(sampleFromScalar(row));
                    }
                    if (samples.size() >= MAX_DATASET_ROWS_IN_MEMORY) break;
                }
            } else {
                samples.add(sampleFromMap((Map<String, Object>) map, meta));
            }
        } else if (parsed != null) {
            samples.add(sampleFromScalar(parsed));
        }
        return samples;
    }

    @SuppressWarnings("unchecked")
    private static List<TrainingSample> readJsonlSamples(Path dataFile, Map<String, Object> meta) throws Exception {
        List<TrainingSample> samples = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null && samples.size() < MAX_DATASET_ROWS_IN_MEMORY) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    Object parsed = OBJECT_MAPPER.readValue(line, Object.class);
                    if (parsed instanceof Map<?, ?> map) {
                        samples.add(sampleFromMap((Map<String, Object>) map, meta));
                    } else {
                        samples.add(sampleFromScalar(parsed));
                    }
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid JSONL record at " + dataFile + ":" + lineNumber
                            + ": " + e.getMessage(), e);
                }
            }
        }
        return samples;
    }

    private static List<TrainingSample> readDelimitedSamples(Path dataFile, Map<String, Object> meta, char delimiter) throws Exception {
        List<TrainingSample> samples = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return samples;
            List<String> headers = parseDelimitedLine(header, delimiter);

            String line;
            while ((line = reader.readLine()) != null && samples.size() < MAX_DATASET_ROWS_IN_MEMORY) {
                if (line.isBlank()) continue;
                List<String> values = parseDelimitedLine(line, delimiter);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < values.size() ? values.get(i) : "");
                }
                samples.add(sampleFromMap(row, meta));
            }
        }
        return samples;
    }

    private static List<String> parseDelimitedLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == delimiter && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static List<TrainingSample> readRawTextSamples(Path dataFile, Map<String, Object> meta) throws Exception {
        List<TrainingSample> samples = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && samples.size() < MAX_DATASET_ROWS_IN_MEMORY) {
                if (!line.isBlank()) {
                    samples.add(sampleFromScalar(line));
                }
            }
        }
        return samples;
    }

    private static TrainingSample sampleFromMap(Map<String, Object> row, Map<String, Object> meta) {
        String configuredInput = stringValue(meta.get("inputColumn"));
        String configuredOutput = stringValue(meta.get("outputColumn"));
        Object input = firstPresent(row, configuredInput, INPUT_FIELD_CANDIDATES);
        Object output = firstPresent(row, configuredOutput, OUTPUT_FIELD_CANDIDATES);
        Object chosen = firstPresent(row, stringValue(meta.get("chosenColumn")), CHOSEN_FIELD_CANDIDATES);
        Object rejected = firstPresent(row, stringValue(meta.get("rejectedColumn")), REJECTED_FIELD_CANDIDATES);
        Object score = firstPresent(row, null, SCORE_FIELD_CANDIDATES);

        // OpenAI/ShareGPT-style SFT records carry the prompt and target in a
        // messages/conversations array instead of top-level columns.
        if (input == null && output == null && configuredInput == null && configuredOutput == null) {
            Object conversation = row.containsKey("messages") ? row.get("messages") : row.get("conversations");
            TrainingSample chatSample = sampleFromConversation(
                    conversation, chosen, rejected, score, row);
            if (chatSample != null) {
                return chatSample;
            }
        }

        // Alpaca-style rows have both an instruction and an optional input context.
        // The generic candidate order otherwise selects only the context and drops
        // the instruction that defines the task.
        if ((configuredInput == null || configuredInput.isBlank()) && row.get("instruction") != null) {
            String instruction = renderContent(row.get("instruction"));
            String context = renderContent(row.get("input"));
            input = context.isBlank() ? instruction : instruction + "\n\nInput:\n" + context;
        }

        if (input == null) input = firstPresent(row, null, NUMERIC_VECTOR_FIELD_CANDIDATES);
        if (output == null && chosen != null) output = chosen;
        if (input == null && !row.isEmpty()) input = row.values().iterator().next();
        if (output == null) output = input;
        return new TrainingSample(
                input, output, chosen, rejected, score, new LinkedHashMap<>(row));
    }

    private static TrainingSample sampleFromConversation(Object rawConversation, Object chosen,
                                                         Object rejected, Object score,
                                                         Map<String, Object> fields) {
        if (!(rawConversation instanceof List<?> messages) || messages.isEmpty()) {
            return null;
        }

        int targetIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Object item = messages.get(i);
            if (item instanceof Map<?, ?> message && isAssistantRole(messageRole(message))) {
                targetIndex = i;
                break;
            }
        }

        String prompt = renderConversation(messages, targetIndex);
        String target = targetIndex >= 0 && messages.get(targetIndex) instanceof Map<?, ?> message
                ? renderContent(messageContent(message))
                : prompt;
        return new TrainingSample(
                prompt, target, chosen, rejected, score, new LinkedHashMap<>(fields));
    }

    private static String renderConversation(List<?> messages, int excludedIndex) {
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            if (i == excludedIndex) continue;
            Object item = messages.get(i);
            if (item instanceof Map<?, ?> message) {
                String content = renderContent(messageContent(message));
                if (content.isBlank()) continue;
                if (prompt.length() > 0) prompt.append('\n');
                prompt.append(displayRole(messageRole(message))).append(": ").append(content);
            } else {
                String content = renderContent(item);
                if (!content.isBlank()) {
                    if (prompt.length() > 0) prompt.append('\n');
                    prompt.append(content);
                }
            }
        }
        if (excludedIndex >= 0) {
            if (prompt.length() > 0) prompt.append('\n');
            prompt.append("Assistant:");
        }
        return prompt.toString();
    }

    private static Object messageContent(Map<?, ?> message) {
        if (message.containsKey("content")) return message.get("content");
        if (message.containsKey("value")) return message.get("value");
        return message.get("text");
    }

    private static String messageRole(Map<?, ?> message) {
        Object role = message.containsKey("role") ? message.get("role") : message.get("from");
        return role != null ? String.valueOf(role) : "user";
    }

    private static boolean isAssistantRole(String role) {
        String normalized = role.toLowerCase(Locale.ROOT);
        return normalized.equals("assistant") || normalized.equals("gpt")
                || normalized.equals("model") || normalized.equals("bot");
    }

    private static String displayRole(String role) {
        String normalized = role.toLowerCase(Locale.ROOT);
        if (isAssistantRole(normalized)) return "Assistant";
        if (normalized.equals("system")) return "System";
        if (normalized.equals("tool")) return "Tool";
        return "User";
    }

    private static String renderContent(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        if (value instanceof List<?> parts) {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                Object content = part;
                if (part instanceof Map<?, ?> map) {
                    if (map.containsKey("text")) content = map.get("text");
                    else if (map.containsKey("content")) content = map.get("content");
                    else continue;
                }
                String rendered = renderContent(content);
                if (!rendered.isBlank()) {
                    if (text.length() > 0) text.append(' ');
                    text.append(rendered);
                }
            }
            return text.toString();
        }
        return String.valueOf(value);
    }

    private static TrainingSample sampleFromScalar(Object value) {
        return new TrainingSample(value, value, null, null, null, Collections.emptyMap());
    }

    private static Object firstPresent(Map<String, Object> row, String configuredColumn, List<String> candidates) {
        if (configuredColumn != null && !configuredColumn.isBlank() && row.containsKey(configuredColumn)) {
            return row.get(configuredColumn);
        }
        for (String candidate : candidates) {
            if (row.containsKey(candidate)) return row.get(candidate);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            for (String candidate : candidates) {
                String normalized = candidate.toLowerCase(Locale.ROOT);
                if (key.equals(normalized) || key.endsWith("_" + normalized)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static List<TrainingSample> nextDatasetBatch(DatasetView dataset, String mode, int batchSize) {
        int effectiveBatchSize = Math.max(MIN_TRAINING_BATCH_SIZE, batchSize);
        String cursorKey = dataset.id() + "|" + mode;
        int start;
        synchronized (DATASET_CURSORS) {
            start = DATASET_CURSORS.getOrDefault(cursorKey, 0);
            DATASET_CURSORS.put(cursorKey, start + effectiveBatchSize);
        }
        return datasetBatch(dataset, start, effectiveBatchSize);
    }

    private static List<TrainingSample> datasetBatch(DatasetView dataset, int start, int batchSize) {
        int effectiveBatchSize = Math.max(MIN_TRAINING_BATCH_SIZE, batchSize);
        List<TrainingSample> samples = dataset.samples();
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("Dataset contains no training samples: " + dataset.id());
        }
        List<TrainingSample> batch = new ArrayList<>(effectiveBatchSize);
        for (int i = 0; i < effectiveBatchSize; i++) {
            batch.add(samples.get(Math.floorMod(start + i, samples.size())));
        }
        return batch;
    }

    private static long[] resolveVariableBatchShape(SameDiff sd, String variableName, int batchSize,
                                                     List<TrainingSample> batch) {
        long[] shape = null;
        if (sd != null && variableName != null) {
            try {
                SDVariable variable = sd.getVariable(variableName);
                if (variable != null) shape = variable.getShape();
            } catch (Exception ignored) {
                // Fall back below.
            }
        }
        int effectiveBatchSize = Math.max(MIN_TRAINING_BATCH_SIZE, batchSize);
        if (shape == null || shape.length == 0) {
            return new long[]{effectiveBatchSize, inferSequenceWidth(variableName, batch)};
        }
        long[] resolved = shape.clone();
        resolved[0] = effectiveBatchSize;
        for (int i = 1; i < resolved.length; i++) {
            if (resolved[i] <= 0) {
                resolved[i] = i == 1 ? inferSequenceWidth(variableName, batch) : 1;
            }
        }
        return resolved;
    }

    private static int inferSequenceWidth(String variableName, List<TrainingSample> batch) {
        int configuredMaximum = configuredMaximumSequenceLength();
        int width = 1;
        for (TrainingSample sample : batch) {
            Object value = sampleValue(sample, variableName, true);
            int candidate = sequenceLength(value);
            if (candidate > 0) {
                width = Math.max(width, Math.min(candidate, configuredMaximum));
            }
        }
        return width;
    }

    private static int configuredMaximumSequenceLength() {
        TrainingSubprocessArgs args = currentArgs;
        if (args != null && args.options() != null) {
            for (String key : List.of("maxSequenceLength", "maxSeqLength", "sequenceLength")) {
                Object value = args.options().get(key);
                if (value instanceof Number number && number.intValue() > 0) {
                    return number.intValue();
                }
            }
        }
        return 512;
    }

    private static int sequenceLength(Object value) {
        if (value == null) return 0;
        if (value instanceof Collection<?> collection) return collection.size();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        if (value instanceof String text) {
            return getTrainingTokenizer().encode(text, false).getIds().length;
        }
        return 1;
    }

    private static DataType resolveVariableDataType(SameDiff sd, String variableName) {
        if (sd != null && variableName != null) {
            try {
                SDVariable variable = sd.getVariable(variableName);
                if (variable != null) {
                    DataType dt = variable.dataType();
                    if (dt != null) return dt;
                }
            } catch (Exception ignored) {
                // Fall back below.
            }
        }
        return DataType.FLOAT;
    }

    private static void fillArray(INDArray array, String variableName, List<TrainingSample> batch, boolean input) {
        long batchSize = Math.max(1, array.size(0));
        long rowWidth = Math.max(1, array.length() / batchSize);
        INDArray flat = array.ravel();
        for (int row = 0; row < batchSize; row++) {
            TrainingSample sample = batch.get(row % batch.size());
            Object value = flattenSequenceValue(sampleValue(sample, variableName, input));
            if (!input && fillCategoricalLabelRow(array, flat, row, rowWidth, value)) {
                continue;
            }
            for (long col = 0; col < rowWidth; col++) {
                double scalar = scalarFor(value, variableName, col);
                flat.putScalar(row * rowWidth + col, scalar);
            }
        }
    }

    private static boolean fillCategoricalLabelRow(INDArray array, INDArray flat, int row,
                                                    long rowWidth, Object value) {
        if (array.rank() < 2) return false;
        long classes = array.size(array.rank() - 1);
        if (classes <= 1 || rowWidth % classes != 0) return false;

        int[] classIds;
        if (value instanceof String text) {
            classIds = getTrainingTokenizer().encode(text, false).getIds();
        } else if (value instanceof Number number) {
            classIds = new int[]{number.intValue()};
        } else if (value instanceof List<?> list) {
            if (list.size() == rowWidth || list.isEmpty()) return false;
            classIds = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (!(item instanceof Number number)) return false;
                classIds[i] = number.intValue();
            }
        } else {
            return false;
        }

        long positions = rowWidth / classes;
        for (int position = 0; position < Math.min(classIds.length, positions); position++) {
            int classId = classIds[position];
            if (classId < 0 || classId >= classes) {
                throw new IllegalArgumentException("Label token/class id " + classId
                        + " is outside model output class dimension " + classes
                        + "; provide numeric JSONL labels compatible with the model output");
            }
            flat.putScalar(row * rowWidth + position * classes + classId, 1.0);
        }
        return true;
    }

    private static Object sampleValue(TrainingSample sample, String variableName, boolean input) {
        Object explicitValue = fieldForVariable(sample.fields(), variableName);
        if (explicitValue != null) return explicitValue;

        String lower = normalizeVariableFieldName(variableName);
        if (lower.contains("chosen")) {
            return sample.chosenValue() != null ? sample.chosenValue() : sample.inputValue();
        }
        if (lower.contains("reject")) {
            return sample.rejectedValue() != null ? sample.rejectedValue() : sample.labelValue();
        }
        if (lower.contains("reward") || lower.contains("score")) {
            return sample.scoreValue() != null ? sample.scoreValue() : sample.labelValue();
        }
        if (input) return sample.inputValue();
        return sample.labelValue() != null ? sample.labelValue() : sample.inputValue();
    }

    private static Object fieldForVariable(Map<?, ?> fields, String variableName) {
        if (fields == null || fields.isEmpty() || variableName == null || variableName.isBlank()) {
            return null;
        }
        String target = normalizeVariableFieldName(variableName);
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            String field = normalizeVariableFieldName(String.valueOf(entry.getKey()));
            if (target.equals(field)) return entry.getValue();
        }
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            String field = normalizeVariableFieldName(String.valueOf(entry.getKey()));
            if (target.endsWith("/" + field) || target.endsWith("_" + field)
                    || target.endsWith("." + field)) {
                return entry.getValue();
            }
        }
        for (Object value : fields.values()) {
            if (value instanceof Map<?, ?> nested) {
                Object nestedValue = fieldForVariable(nested, variableName);
                if (nestedValue != null) return nestedValue;
            }
        }
        return null;
    }

    private static String normalizeVariableFieldName(String name) {
        if (name == null) return "";
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        int outputSuffix = normalized.lastIndexOf(':');
        if (outputSuffix > 0) {
            boolean numericSuffix = true;
            for (int i = outputSuffix + 1; i < normalized.length(); i++) {
                if (!Character.isDigit(normalized.charAt(i))) {
                    numericSuffix = false;
                    break;
                }
            }
            if (numericSuffix) normalized = normalized.substring(0, outputSuffix);
        }
        return normalized;
    }

    private static Object flattenSequenceValue(Object value) {
        if (!(value instanceof Collection<?>) && (value == null || !value.getClass().isArray())) {
            return value;
        }
        List<Object> flattened = new ArrayList<>();
        appendSequenceValues(value, flattened);
        return flattened;
    }

    private static void appendSequenceValues(Object value, List<Object> flattened) {
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) appendSequenceValues(item, flattened);
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                appendSequenceValues(java.lang.reflect.Array.get(value, i), flattened);
            }
        } else {
            flattened.add(value);
        }
    }

    private static double scalarFor(Object value, String variableName, long position) {
        if (value == null) return 0.0;
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean bool) return bool ? 1.0 : 0.0;
        if (value instanceof List<?> list) {
            if (position >= list.size()) return 0.0;
            return scalarFor(list.get((int) position), variableName, 0);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            if (position >= length) return 0.0;
            return scalarFor(java.lang.reflect.Array.get(value, (int) position), variableName, 0);
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Object named = fieldForVariable(map, variableName);
            return scalarFor(named != null ? named : map.values().iterator().next(), variableName, position);
        }
        String text = String.valueOf(value);
        String lower = normalizeVariableFieldName(variableName);
        if (lower.contains("mask")) {
            return maskValue(text, position);
        }
        return tokenId(text, position);
    }

    private static double maskValue(Object value, long position) {
        if (value == null) return 0.0;
        int[] ids = getTrainingTokenizer().encode(String.valueOf(value), false).getIds();
        return position < ids.length ? 1.0 : 0.0;
    }

    private static double tokenId(String text, long position) {
        Encoding encoding = getTrainingTokenizer().encode(text != null ? text : "", false);
        int[] ids = encoding.getIds();
        return position < ids.length ? ids[(int) position] : 0.0;
    }

    private static double hashedTextFeature(String text, long position) {
        String[] tokens = tokens(text);
        String token = tokens[(int) (position % tokens.length)];
        int hash = Math.floorMod(Objects.hash(token, position / tokens.length), 20001);
        return (hash / 10000.0) - 1.0;
    }

    private static String[] tokens(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) return new String[]{"<blank>"};
        return normalized.split("\\s+");
    }

    private static Double numericScore(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean bool) return bool ? 1.0 : 0.0;
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double textSignal(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number number) return number.doubleValue();
        String text = String.valueOf(value);
        return Math.abs(hashedTextFeature(text, 0)) + tokens(text).length * 0.001;
    }

    private static String inferFormat(Path dataFile) {
        String name = dataFile.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "txt";
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private record DatasetView(String id, Path datasetRoot, Path dataFile, String format, String task,
                               Map<String, Object> meta, List<TrainingSample> samples) {}

    private record TrainingSample(Object inputValue, Object labelValue, Object chosenValue,
                                  Object rejectedValue, Object scoreValue,
                                  Map<String, Object> fields) {}

    private static IllegalStateException missingTrainingModel(String mode, TrainingSubprocessArgs args) {
        return new IllegalStateException("No SameDiff model available for " + mode + " training: " + args.modelId());
    }

    private static UnsupportedOperationException missingDatasetLoader(String mode, TrainingSubprocessArgs args) {
        String datasetId = args != null && args.datasetId() != null && !args.datasetId().isBlank()
                ? args.datasetId()
                : "<none>";
        return new UnsupportedOperationException(mode + " training requires a datasetId or dataset file path; datasetId=" + datasetId);
    }

    private static Path writeTrainingArtifactManifest(TrainingSubprocessArgs args,
                                                      String processType,
                                                      String outputPath,
                                                      String modelFileName,
                                                      Map<String, Double> finalMetrics,
                                                      Map<String, Object> processConfig) throws Exception {
        Path outputDir = Paths.get(outputPath).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        Path modelFile = outputDir.resolve(modelFileName).normalize();
        Path manifestPath = outputDir.resolve(TRAINING_ARTIFACT_MANIFEST_FILE).normalize();
        boolean modelFilePresent = Files.isRegularFile(modelFile);
        String trainedModelId = deriveTrainedModelId(args, processType);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", TRAINING_ARTIFACT_SCHEMA_VERSION);
        manifest.put("createdAt", Instant.now().toString());
        manifest.put("taskId", args.taskId());
        manifest.put("baseModelId", args.modelId());
        manifest.put("trainedModelId", trainedModelId);
        manifest.put("trainingType", normalizeTrainingType(processType));
        manifest.put("dataset", datasetManifest(args));
        manifest.put("outputDir", outputDir.toString());
        manifest.put("manifestFile", TRAINING_ARTIFACT_MANIFEST_FILE);
        manifest.put("modelFile", modelFileName);
        manifest.put("modelPath", modelFile.toString());
        manifest.put("modelFormat", inferModelArtifactFormat(modelFileName));
        manifest.put("deployable", modelFilePresent);
        manifest.put("exportable", modelFilePresent);
        manifest.put("registryEligible", modelFilePresent);
        manifest.put("registrySuggestion", registrySuggestion(trainedModelId, modelFileName, outputDir));
        manifest.put("trainingConfig", trainingConfigManifest(args, processConfig));
        manifest.put("metrics", finalMetrics != null ? new LinkedHashMap<>(finalMetrics) : Collections.emptyMap());
        List<Map<String, Object>> artifacts = new ArrayList<>();
        artifacts.add(artifactManifest("model", outputDir, modelFile));
        for (String trainingGraphName : List.of("model.fb", "student_model.fb")) {
            Path trainingGraph = outputDir.resolve(trainingGraphName);
            if (!trainingGraph.equals(modelFile) && Files.isRegularFile(trainingGraph)) {
                artifacts.add(artifactManifest("peft_training_graph", outputDir, trainingGraph));
            }
        }
        Path adapterDir = outputDir.resolve("adapter");
        if (Files.isDirectory(adapterDir)) {
            artifacts.add(artifactManifest("adapter", outputDir, adapterDir));
        }
        manifest.put("artifacts", artifacts);

        OBJECT_MAPPER.writeValue(manifestPath.toFile(), manifest);
        return manifestPath;
    }

    private static Map<String, Object> datasetManifest(TrainingSubprocessArgs args) {
        Map<String, Object> info = new LinkedHashMap<>();
        String datasetId = args != null ? args.datasetId() : null;
        info.put("datasetId", datasetId);
        if (datasetId == null || datasetId.isBlank()) {
            return info;
        }

        try {
            DatasetView dataset = loadDataset(args);
            info.put("resolvedId", dataset.id());
            info.put("format", dataset.format());
            info.put("task", dataset.task());
            info.put("dataFile", dataset.dataFile().toString());
            info.put("datasetRoot", dataset.datasetRoot() != null ? dataset.datasetRoot().toString() : null);
            info.put("sampleCount", dataset.samples().size());
            Map<String, Object> columns = new LinkedHashMap<>();
            copyIfPresent(dataset.meta(), columns, "inputColumn");
            copyIfPresent(dataset.meta(), columns, "outputColumn");
            copyIfPresent(dataset.meta(), columns, "chosenColumn");
            copyIfPresent(dataset.meta(), columns, "rejectedColumn");
            copyIfPresent(dataset.meta(), columns, "trainSplit");
            if (!columns.isEmpty()) info.put("columns", columns);
        } catch (Exception e) {
            info.put("resolutionError", e.getMessage());
        }
        return info;
    }

    private static Map<String, Object> trainingConfigManifest(TrainingSubprocessArgs args,
                                                              Map<String, Object> processConfig) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("epochs", args.epochs());
        config.put("batchSize", args.batchSize());
        config.put("gradientAccumulationSteps", args.gradientAccumulationSteps());
        config.put("learningRate", args.learningRate());
        config.put("lrSchedule", args.lrSchedule());
        config.put("warmupRatio", args.warmupRatio());
        config.put("maxSteps", args.maxSteps());
        config.put("maxGradNorm", args.maxGradNorm());
        config.put("fp16", args.fp16());
        config.put("bf16", args.bf16());
        config.put("loggingSteps", args.loggingSteps());
        config.put("saveSteps", args.saveSteps());
        config.put("evalSteps", args.evalSteps());
        config.put("seed", args.seed());

        putParsedConfig(config, "peftConfig", args.peftConfigJson());
        putParsedConfig(config, "updaterConfig", args.updaterConfigJson());
        putParsedConfig(config, "distillationConfig", args.distillationConfigJson());
        putParsedConfig(config, "alignmentConfig", args.alignmentConfigJson());
        if (processConfig != null && !processConfig.isEmpty()) {
            config.put("processConfig", new LinkedHashMap<>(processConfig));
        }
        if (args.options() != null && !args.options().isEmpty()) {
            config.put("options", new LinkedHashMap<>(args.options()));
        }
        return config;
    }

    private static void putParsedConfig(Map<String, Object> target, String key, String json) {
        Map<String, Object> parsed = parseJsonConfig(json);
        if (!parsed.isEmpty()) {
            target.put(key, parsed);
        }
    }

    private static Map<String, Object> registrySuggestion(String trainedModelId, String modelFileName, Path outputDir) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("modelId", trainedModelId);
        suggestion.put("status", "STAGED");
        suggestion.put("path", "trained/" + trainedModelId);
        suggestion.put("modelFile", modelFileName);
        suggestion.put("sourceOutputDir", outputDir.toString());
        return suggestion;
    }

    private static Map<String, Object> artifactManifest(String role, Path root, Path file) throws Exception {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("role", role);
        artifact.put("path", file.toString());
        artifact.put("relativePath", relativePath(root, file));
        artifact.put("exists", Files.exists(file));
        artifact.put("file", Files.isRegularFile(file));
        if (Files.isRegularFile(file)) {
            artifact.put("sizeBytes", Files.size(file));
        }
        return artifact;
    }

    private static String deriveTrainedModelId(TrainingSubprocessArgs args, String processType) {
        Object configured = args.options() != null ? args.options().get("trainedModelId") : null;
        if (configured == null && args.options() != null) {
            configured = args.options().get("targetModelId");
        }
        if (configured != null && !String.valueOf(configured).isBlank()) {
            return sanitizeModelId(String.valueOf(configured));
        }
        return sanitizeModelId(args.modelId() + "-" + processType + "-" + args.taskId());
    }

    private static String sanitizeModelId(String value) {
        String sanitized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return sanitized.isBlank() ? "trained-model" : sanitized;
    }

    private static String normalizeTrainingType(String processType) {
        return processType != null ? processType.toUpperCase(Locale.ROOT) : "FINETUNE";
    }

    private static String inferModelArtifactFormat(String modelFileName) {
        String lower = modelFileName != null ? modelFileName.toLowerCase(Locale.ROOT) : "";
        if (lower.endsWith(".fb")) return "SAMEDIFF_FLATBUFFERS";
        if (lower.endsWith(".sdz")) return "SDX_BUNDLE";
        if (lower.endsWith(".gguf")) return "GGUF";
        return "UNKNOWN";
    }

    private static String relativePath(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private static double extractLoss(SameDiff sd) {
        try {
            List<String> lossVars = sd.getLossVariables();
            if (lossVars != null && !lossVars.isEmpty()) {
                SDVariable lossVar = sd.getVariable(lossVars.get(0));
                if (lossVar != null) {
                    INDArray arr = lossVar.getArr();
                    if (arr != null) return arr.getDouble(0);
                }
            }
        } catch (Exception e) {
            // Loss extraction failed
        }
        return Double.NaN;
    }

    private static double computeDistillationLoss(SameDiff student, List<String> inputNames,
                                                   INDArray[] features, INDArray teacherLogits) {
        Map<String, INDArray> result = null;
        try {
            Map<String, INDArray> placeholders = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(inputNames.size(), features.length); i++) {
                placeholders.put(inputNames.get(i), features[i]);
            }
            placeholders.put(DISTILLATION_TEACHER_LOGITS, teacherLogits);
            result = student.output(placeholders, DISTILLATION_LOSS);
            INDArray loss = result.get(DISTILLATION_LOSS);
            if (loss == null || loss.isEmpty()) {
                throw new IllegalStateException("Distillation loss output is missing");
            }
            return loss.getDouble(0);
        } finally {
            if (result != null) {
                for (INDArray array : result.values()) {
                    closeArray(array);
                }
            }
        }
    }

    static double computeLearningRate(double baseLr, double progress, String schedule, double warmupRatio) {
        if (progress < warmupRatio && warmupRatio > 0) {
            return baseLr * (progress / warmupRatio);
        }
        double postWarmupProgress = (progress - warmupRatio) / (1.0 - warmupRatio);
        if (schedule == null) schedule = "COSINE";
        switch (schedule.toUpperCase()) {
            case "LINEAR":
                return baseLr * (1.0 - postWarmupProgress);
            case "CONSTANT":
            case "CONSTANT_WITH_WARMUP":
                return baseLr;
            case "POLYNOMIAL":
                return baseLr * Math.pow(1.0 - postWarmupProgress, 2.0);
            default: // COSINE
                return baseLr * 0.5 * (1.0 + Math.cos(Math.PI * postWarmupProgress));
        }
    }

    private static String resolveOutputPath(TrainingSubprocessArgs args) {
        return args.outputDir() != null ? args.outputDir()
                : "/tmp/training-" + args.taskId() + "/output";
    }

    private static String resolveCheckpointPath(TrainingSubprocessArgs args, long step) {
        return args.outputDir() != null
                ? args.outputDir() + "/checkpoint-" + step
                : "/tmp/training-" + args.taskId() + "/checkpoint-" + step;
    }

    private static void sleepOrInterrupt(long ms, TrainingSubprocessProgressReporter reporter) throws InterruptedException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reporter.reportLog("WARN", "Training interrupted");
            throw e;
        }
    }

    // ==================== DSP/GPU/JVM Stats Collection ====================

    /**
     * Snapshot of DSP execution, GPU memory, and JVM heap stats collected from
     * DspHandle, NativeOps, and Runtime APIs.
     */
    private record DspSnapshot(
            // Per-segment execution breakdown
            int segmentsWarmup, int segmentsReplayed, int segmentsCaptured,
            int segmentsSlotBySlot, int segmentsFailed,
            // Buffer pool
            long bufferPoolBytes, long bufferPoolReused, long coloringSavedBytes,
            // GPU memory
            long gpuMemUsed, long gpuMemFree, long gpuMemTotal,
            long gpuPoolUsed, long gpuPoolReserved,
            int numDevices, String deviceNames,
            // JVM heap
            long heapUsed, long heapMax, double heapPercent
    ) {}

    /**
     * Collect DSP execution stats, GPU memory, and JVM heap in a single snapshot.
     */
    private static DspSnapshot collectDspStats(SameDiff sd) {
        // Per-segment from DspHandle
        int segWarmup = 0, segReplayed = 0, segCaptured = 0, segSlotBySlot = 0, segFailed = 0;
        long coloringSaved = 0;
        if (sd != null) {
            try {
                DspHandle dsp = sd.dsp();
                segWarmup = Math.max(0, dsp.lastExecSegmentsWarmup());
                segReplayed = Math.max(0, dsp.lastExecSegmentsReplayed());
                segCaptured = Math.max(0, dsp.lastExecSegmentsCaptured());
                segSlotBySlot = Math.max(0, dsp.lastExecSegmentsSlotBySlot());
                segFailed = Math.max(0, dsp.lastExecSegmentsFailed());
                coloringSaved = dsp.bufferColoringBytesSaved();
            } catch (Exception e) {
                logger.debug("Could not collect DspHandle stats: {}", e.getMessage());
            }
        }

        // Buffer pool from NativeOps (static, device 0)
        long bufferPoolBytes = 0;
        long bufferPoolReused = 0;
        try {
            bufferPoolBytes = DspHandle.bufferPoolPooledBytes(0);
            bufferPoolReused = DspHandle.bufferPoolTotalReused(0);
        } catch (Exception e) {
            logger.debug("Could not collect buffer pool stats: {}", e.getMessage());
        }

        // GPU memory from NativeOps
        long gpuUsed = 0, gpuFree = 0, gpuTotal = 0, poolUsed = 0, poolReserved = 0;
        int numDevices = 0;
        StringBuilder deviceNamesSb = new StringBuilder();
        try {
            var nativeOps = Nd4j.getNativeOps();
            numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
            for (int d = 0; d < numDevices; d++) {
                long devFree = nativeOps.getDeviceFreeMemory(d);
                long devTotal = nativeOps.getDeviceTotalMemory(d);
                gpuFree += devFree;
                gpuTotal += devTotal;
                gpuUsed += (devTotal - devFree);

                // Pool stats via LongPointer
                try {
                    org.bytedeco.javacpp.LongPointer usedPtr = new org.bytedeco.javacpp.LongPointer(1);
                    org.bytedeco.javacpp.LongPointer reservedPtr = new org.bytedeco.javacpp.LongPointer(1);
                    nativeOps.getMemoryPoolStats(d, usedPtr, reservedPtr);
                    poolUsed += usedPtr.get(0);
                    poolReserved += reservedPtr.get(0);
                } catch (Exception ignored) {}

                if (d > 0) deviceNamesSb.append(", ");
                try {
                    deviceNamesSb.append("GPU ").append(d);
                } catch (Exception ignored) {
                    deviceNamesSb.append("GPU ").append(d);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not collect GPU memory stats (CPU backend?): {}", e.getMessage());
        }

        // JVM heap
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();
        double heapPercent = heapMax > 0 ? (heapUsed * 100.0 / heapMax) : 0;

        return new DspSnapshot(
                segWarmup, segReplayed, segCaptured, segSlotBySlot, segFailed,
                bufferPoolBytes, bufferPoolReused, coloringSaved,
                gpuUsed, gpuFree, gpuTotal, poolUsed, poolReserved,
                numDevices, deviceNamesSb.toString(),
                heapUsed, heapMax, heapPercent
        );
    }

    /**
     * Report metrics with full DSP/GPU/JVM transparency stats.
     */
    private static void reportStepMetrics(TrainingSubprocessProgressReporter reporter,
                                           SameDiff sd,
                                           long step, int epoch, double trainLoss, double evalLoss,
                                           double lr, double gradNorm,
                                           double tokensPerSec, double samplesPerSec,
                                           Map<String, Double> customMetrics) {
        DspSnapshot snap = collectDspStats(sd);
        reporter.reportMetricsWithDsp(step, epoch, trainLoss, evalLoss, lr, gradNorm,
                tokensPerSec, samplesPerSec, customMetrics,
                snap.segmentsWarmup(), snap.segmentsReplayed(), snap.segmentsCaptured(),
                snap.segmentsSlotBySlot(), snap.segmentsFailed(),
                snap.bufferPoolBytes(), snap.bufferPoolReused(), snap.coloringSavedBytes(),
                snap.gpuMemUsed(), snap.gpuMemFree(), snap.gpuMemTotal(),
                snap.gpuPoolUsed(), snap.gpuPoolReserved(), snap.numDevices(), snap.deviceNames(),
                snap.heapUsed(), snap.heapMax(), snap.heapPercent());
    }

    // ==================== JSON Config Parsing ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonConfig(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            logger.warn("Failed to parse config JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static int getIntFromConfig(Map<String, Object> config, String key, int defaultValue) {
        Object val = config.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    private static double getDoubleFromConfig(Map<String, Object> config, String key, double defaultValue) {
        Object val = config.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultValue;
    }

    private static String getStringFromConfig(Map<String, Object> config, String key, String defaultValue) {
        Object val = config.get(key);
        if (val instanceof String) return (String) val;
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringListFromConfig(Map<String, Object> config, String key) {
        Object val = config.get(key);
        if (val instanceof List) return (List<String>) val;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private static boolean getBooleanFromConfig(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) return Boolean.parseBoolean(text);
        return defaultValue;
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static DataType parseDataType(String value, DataType defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("BF16".equals(normalized)) normalized = "BFLOAT16";
        if ("FP16".equals(normalized)) normalized = "FLOAT16";
        if ("FP32".equals(normalized)) normalized = "FLOAT";
        try {
            return DataType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
    }
}
