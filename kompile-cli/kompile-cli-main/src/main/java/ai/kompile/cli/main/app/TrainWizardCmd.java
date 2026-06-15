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

package ai.kompile.cli.main.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Interactive wizard for configuring and launching a training job.
 * Dynamically discovers available models, datasets, optimizers,
 * LR schedules, and PEFT types from the running kompile-app instance.
 */
@Command(
        name = "wizard",
        description = "Interactive wizard to configure and launch a training job.%n%n" +
                "Walks through model selection, dataset selection, training type,%n" +
                "hyperparameters, PEFT/LoRA configuration, and monitoring options.%n%n" +
                "Dynamically discovers available models and datasets from%n" +
                "the running kompile-app instance.",
        mixinStandardHelpOptions = true
)
public class TrainWizardCmd implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = {"--watch", "-w"},
            description = "Stream live logs after launching")
    private boolean watch;

    private KompileHttpClient client;
    private ObjectMapper mapper;
    private Scanner scanner;

    // Discovered state
    private List<String> modelIds;
    private List<String> datasetIds;
    private List<String> optimizerTypes;
    private List<String> lrSchedules;
    private List<String> peftTypes;

    @Override
    public Integer call() {
        client = app.requireClient();
        if (client == null) return 1;
        mapper = client.getObjectMapper();
        scanner = new Scanner(System.in);

        try {
            printHeader();
            discoverCapabilities();

            // Step 1: Model
            String modelId = selectModel();
            if (modelId == null) return 1;

            // Step 2: Dataset
            String datasetId = selectDataset();
            if (datasetId == null) return 1;

            // Step 3: Training type
            String trainingType = selectTrainingType();

            // Step 4: Hyperparameters
            printSection("4", "Hyperparameters");
            int epochs = promptInt("Epochs", 3, 1, 1000);
            int batchSize = promptInt("Batch size", 8, 1, 512);
            double lr = promptDouble("Learning rate", 1e-4, 1e-8, 1.0);

            String optimizer = selectFromList("Optimizer", optimizerTypes, "ADAMW");
            String schedule = selectFromList("LR schedule", lrSchedules, "COSINE");
            double warmupRatio = promptDouble("Warmup ratio", 0.1, 0.0, 1.0);
            double maxGradNorm = promptDouble("Max gradient norm", 1.0, 0.0, 100.0);

            // Step 5: Precision & accumulation
            printSection("5", "Precision & Accumulation");
            boolean fp16 = promptYesNo("Enable FP16", false);
            boolean bf16 = !fp16 && promptYesNo("Enable BF16", false);
            int gradAccum = promptInt("Gradient accumulation steps", 1, 1, 256);

            // Step 6: LoRA config (if applicable)
            Map<String, Object> peftConfig = null;
            if ("LORA".equals(trainingType)) {
                printSection("6", "LoRA Configuration");
                String peftType = selectFromList("PEFT type", peftTypes, "LORA");
                int rank = promptInt("Rank (r)", 8, 1, 256);
                double alpha = promptDouble("Alpha", 16.0, 1.0, 512.0);
                double dropout = promptDouble("Dropout", 0.05, 0.0, 1.0);
                String targets = promptString("Target modules (comma-sep)", "q_proj,v_proj");

                peftConfig = new LinkedHashMap<>();
                peftConfig.put("peftType", peftType);
                peftConfig.put("rank", rank);
                peftConfig.put("alpha", alpha);
                peftConfig.put("dropout", dropout);
                if (targets != null && !targets.isBlank()) {
                    peftConfig.put("targetModules", Arrays.asList(targets.split(",")));
                }
            }

            // Step 7: Logging & checkpointing
            String stepLabel = "LORA".equals(trainingType) ? "7" : "6";
            printSection(stepLabel, "Logging & Checkpointing");
            int loggingSteps = promptInt("Logging steps", 10, 1, 10000);
            int saveSteps = promptInt("Save checkpoint steps", 500, 1, 100000);
            int evalSteps = promptInt("Eval steps", 500, 1, 100000);
            int maxSteps = promptInt("Max steps (-1=unlimited)", -1, -1, 10000000);
            int seed = promptInt("Random seed", 42, 0, Integer.MAX_VALUE);

            // Step 8: Options
            String optLabel = "LORA".equals(trainingType) ? "8" : "7";
            printSection(optLabel, "Options");
            boolean autoRegister = promptYesNo("Auto-register trained model", true);
            boolean evalAfter = promptYesNo("Run evaluation after training", false);
            boolean monitoring = promptYesNo("Enable monitoring (DSP, throughput)", true);

            // Build request
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("modelId", modelId);
            request.put("datasetId", datasetId);
            request.put("epochs", epochs);
            request.put("batchSize", batchSize);
            request.put("lrSchedule", schedule);
            request.put("warmupRatio", warmupRatio);
            request.put("maxSteps", maxSteps);
            request.put("maxGradNorm", maxGradNorm);
            request.put("gradientAccumulationSteps", gradAccum);
            request.put("fp16", fp16);
            request.put("bf16", bf16);
            request.put("loggingSteps", loggingSteps);
            request.put("saveSteps", saveSteps);
            request.put("evalSteps", evalSteps);
            request.put("seed", seed);
            request.put("autoRegister", autoRegister);
            request.put("evaluateAfterTraining", evalAfter);
            request.put("enableMonitoring", monitoring);

            Map<String, Object> updaterConfig = new LinkedHashMap<>();
            updaterConfig.put("type", optimizer);
            updaterConfig.put("learningRate", lr);
            request.put("updaterConfig", updaterConfig);

            if (peftConfig != null) {
                request.put("peftConfig", peftConfig);
            }

            // Review
            printReview(request, trainingType);
            if (!promptYesNo("Launch this training job?", true)) {
                System.out.println("Cancelled.");
                return 0;
            }

            // Submit
            System.out.println();
            System.out.println("Launching training...");
            String response = client.postString("/api/training/start", request);
            JsonNode status = mapper.readTree(response);

            String jobId = status.path("jobId").asText(status.path("taskId").asText("unknown"));
            System.out.println();
            System.out.println("  Training job launched successfully!");
            TrainCommand.printJobStatus(status);
            System.out.println();

            if (watch || promptYesNo("Stream live training logs?", true)) {
                return TrainCommand.streamLogs(client, jobId);
            }

            System.out.printf("Tip: Run 'kompile app train logs --job %s --follow' to stream live logs.%n", jobId);
            return 0;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // ==================== Discovery ====================

    private void discoverCapabilities() {
        System.out.println("Discovering server capabilities...");

        modelIds = discoverList("/api/staging/registry", "modelId", "id");
        datasetIds = discoverList("/api/datasets", "id", "datasetId", "name");
        optimizerTypes = discoverNameList("/api/training/updater-types");
        lrSchedules = discoverNameList("/api/training/lr-schedules");
        peftTypes = discoverNameList("/api/training/peft-types");

        // Fallbacks
        if (optimizerTypes.isEmpty()) {
            optimizerTypes = List.of("ADAM", "ADAMW", "SGD", "ADAGRAD");
        }
        if (lrSchedules.isEmpty()) {
            lrSchedules = List.of("COSINE", "LINEAR", "CONSTANT", "POLYNOMIAL");
        }
        if (peftTypes.isEmpty()) {
            peftTypes = List.of("LORA", "QLORA", "PREFIX_TUNING", "PROMPT_TUNING");
        }

        System.out.printf("  Found %d model(s), %d dataset(s), %d optimizer(s), %d schedule(s), %d PEFT type(s)%n%n",
                modelIds.size(), datasetIds.size(), optimizerTypes.size(),
                lrSchedules.size(), peftTypes.size());
    }

    private List<String> discoverList(String path, String... fieldNames) {
        List<String> result = new ArrayList<>();
        try {
            String response = client.getString(path);
            JsonNode root = mapper.readTree(response);

            // Handle object wrappers (e.g. { "models": [...] })
            JsonNode array = root;
            if (!root.isArray()) {
                for (String key : new String[]{"models", "entries", "content"}) {
                    if (root.has(key) && root.path(key).isArray()) {
                        array = root.path(key);
                        break;
                    }
                }
            }

            if (array.isArray()) {
                for (JsonNode item : array) {
                    for (String field : fieldNames) {
                        String val = item.path(field).asText("");
                        if (!val.isEmpty()) {
                            result.add(val);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Discovery failure is non-fatal — user can type values manually
        }
        return result;
    }

    private List<String> discoverNameList(String path) {
        List<String> result = new ArrayList<>();
        try {
            String response = client.getString(path);
            JsonNode root = mapper.readTree(response);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    if (item.isTextual()) {
                        result.add(item.asText());
                    } else if (item.has("name")) {
                        result.add(item.path("name").asText());
                    } else if (item.has("type")) {
                        result.add(item.path("type").asText());
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ==================== Step Implementations ====================

    private String selectModel() {
        printSection("1", "Model Selection");
        if (modelIds.isEmpty()) {
            System.out.println("  No models found in the registry.");
            System.out.println("  You can type a model ID manually, or use");
            System.out.println("  'kompile app train wizard' after staging a model.");
            System.out.println();
            String manual = promptString("Model ID", null);
            if (manual == null || manual.isBlank()) {
                System.out.println("Model ID is required. Aborting.");
                return null;
            }
            return manual;
        }

        return selectFromNumberedList("Select a model", modelIds);
    }

    private String selectDataset() {
        printSection("2", "Dataset Selection");
        if (datasetIds.isEmpty()) {
            System.out.println("  No datasets found.");
            System.out.println("  Upload a dataset first: kompile app train (coming soon)");
            System.out.println("  Or enter a dataset ID manually.");
            System.out.println();
            String manual = promptString("Dataset ID", null);
            if (manual == null || manual.isBlank()) {
                System.out.println("Dataset ID is required. Aborting.");
                return null;
            }
            return manual;
        }

        return selectFromNumberedList("Select a dataset", datasetIds);
    }

    private String selectTrainingType() {
        printSection("3", "Training Type");
        List<String> types = List.of("FINETUNE", "LORA", "DISTILLATION", "ALIGNMENT");
        System.out.println("  Training approaches:");
        System.out.println("    1) FINETUNE     - Full model fine-tuning");
        System.out.println("    2) LORA         - Parameter-efficient (LoRA adapters)");
        System.out.println("    3) DISTILLATION - Knowledge distillation from teacher");
        System.out.println("    4) ALIGNMENT    - RLHF/DPO/KTO alignment");
        System.out.println();

        int choice = promptInt("Select training type", 1, 1, 4);
        String selected = types.get(choice - 1);
        System.out.printf("  Selected: %s%n%n", selected);
        return selected;
    }

    // ==================== Review ====================

    private void printReview(Map<String, Object> request, String trainingType) {
        System.out.println();
        System.out.println("===========================================================");
        System.out.println("              TRAINING CONFIGURATION REVIEW");
        System.out.println("===========================================================");
        System.out.println();
        OutputFormatter.printKv("Model", request.get("modelId"));
        OutputFormatter.printKv("Dataset", request.get("datasetId"));
        OutputFormatter.printKv("Training Type", trainingType);
        OutputFormatter.printKv("Epochs", request.get("epochs"));
        OutputFormatter.printKv("Batch Size", request.get("batchSize"));

        @SuppressWarnings("unchecked")
        Map<String, Object> updater = (Map<String, Object>) request.get("updaterConfig");
        if (updater != null) {
            OutputFormatter.printKv("Optimizer", updater.get("type"));
            OutputFormatter.printKv("Learning Rate", String.format("%.2e", (double) updater.get("learningRate")));
        }

        OutputFormatter.printKv("LR Schedule", request.get("lrSchedule"));
        OutputFormatter.printKv("Warmup Ratio", request.get("warmupRatio"));
        OutputFormatter.printKv("Max Grad Norm", request.get("maxGradNorm"));
        OutputFormatter.printKv("FP16", request.get("fp16"));
        OutputFormatter.printKv("BF16", request.get("bf16"));
        OutputFormatter.printKv("Grad Accum Steps", request.get("gradientAccumulationSteps"));
        OutputFormatter.printKv("Logging Steps", request.get("loggingSteps"));
        OutputFormatter.printKv("Save Steps", request.get("saveSteps"));
        OutputFormatter.printKv("Max Steps", request.get("maxSteps"));
        OutputFormatter.printKv("Seed", request.get("seed"));
        OutputFormatter.printKv("Auto-Register", request.get("autoRegister"));
        OutputFormatter.printKv("Evaluate After", request.get("evaluateAfterTraining"));
        OutputFormatter.printKv("Monitoring", request.get("enableMonitoring"));

        if (request.containsKey("peftConfig")) {
            System.out.println();
            System.out.println("  LoRA Configuration:");
            @SuppressWarnings("unchecked")
            Map<String, Object> peft = (Map<String, Object>) request.get("peftConfig");
            OutputFormatter.printKv("  PEFT Type", peft.get("peftType"));
            OutputFormatter.printKv("  Rank", peft.get("rank"));
            OutputFormatter.printKv("  Alpha", peft.get("alpha"));
            OutputFormatter.printKv("  Dropout", peft.get("dropout"));
            OutputFormatter.printKv("  Targets", peft.get("targetModules"));
        }

        System.out.println();
        System.out.println("===========================================================");
        System.out.println();
    }

    // ==================== Prompt Helpers ====================

    private String selectFromNumberedList(String prompt, List<String> items) {
        for (int i = 0; i < items.size(); i++) {
            System.out.printf("    %d) %s%n", i + 1, items.get(i));
        }
        System.out.println();
        int idx = promptInt(prompt + " (number)", 1, 1, items.size());
        String selected = items.get(idx - 1);
        System.out.printf("  Selected: %s%n%n", selected);
        return selected;
    }

    private String selectFromList(String label, List<String> options, String defaultValue) {
        if (options.isEmpty()) return defaultValue;

        StringBuilder hint = new StringBuilder();
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) hint.append(", ");
            hint.append(options.get(i));
        }
        System.out.printf("  Available: %s%n", hint);

        String result = promptString(label, defaultValue);
        // Validate
        if (result != null && !options.contains(result.toUpperCase())) {
            String upper = result.toUpperCase();
            for (String opt : options) {
                if (opt.equalsIgnoreCase(result)) return opt;
            }
            // Accept user input even if not in list (server may support more)
        }
        return result != null ? result.toUpperCase() : defaultValue;
    }

    private String promptString(String label, String defaultValue) {
        if (defaultValue != null) {
            System.out.printf("  %s [%s]: ", label, defaultValue);
        } else {
            System.out.printf("  %s: ", label);
        }
        System.out.flush();
        String line = scanner.nextLine().trim();
        if (line.isEmpty()) return defaultValue;
        return line;
    }

    private int promptInt(String label, int defaultValue, int min, int max) {
        while (true) {
            System.out.printf("  %s [%d]: ", label, defaultValue);
            System.out.flush();
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) return defaultValue;
            try {
                int value = Integer.parseInt(line);
                if (value < min || value > max) {
                    System.out.printf("    Value must be between %d and %d.%n", min, max);
                    continue;
                }
                return value;
            } catch (NumberFormatException e) {
                System.out.println("    Please enter a valid integer.");
            }
        }
    }

    private double promptDouble(String label, double defaultValue, double min, double max) {
        while (true) {
            String defStr = defaultValue < 0.01 ? String.format("%.1e", defaultValue) :
                    String.format("%.4f", defaultValue);
            System.out.printf("  %s [%s]: ", label, defStr);
            System.out.flush();
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) return defaultValue;
            try {
                double value = Double.parseDouble(line);
                if (value < min || value > max) {
                    System.out.printf("    Value must be between %s and %s.%n",
                            String.format("%.4f", min), String.format("%.4f", max));
                    continue;
                }
                return value;
            } catch (NumberFormatException e) {
                System.out.println("    Please enter a valid number.");
            }
        }
    }

    private boolean promptYesNo(String label, boolean defaultValue) {
        String hint = defaultValue ? "Y/n" : "y/N";
        System.out.printf("  %s [%s]: ", label, hint);
        System.out.flush();
        String line = scanner.nextLine().trim().toLowerCase();
        if (line.isEmpty()) return defaultValue;
        return line.startsWith("y");
    }

    // ==================== Display ====================

    private void printHeader() {
        System.out.println();
        System.out.println("===========================================================");
        System.out.println("            KOMPILE TRAINING JOB WIZARD");
        System.out.println("===========================================================");
        System.out.println("  Configure and launch a training job step by step.");
        System.out.println("  Press Enter to accept [defaults] shown in brackets.");
        System.out.println();
    }

    private void printSection(String number, String title) {
        System.out.printf("%n--- Step %s: %s ---%n%n", number, title);
    }
}
