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
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * CLI command group for training operations against a running kompile-app.
 *
 * <p>Subcommands mirror the training REST API:</p>
 * <ul>
 *   <li>{@code start} — launch a training job with CLI flags</li>
 *   <li>{@code list} — list all training jobs</li>
 *   <li>{@code status} — get detailed status of a job</li>
 *   <li>{@code logs} — fetch or stream training logs</li>
 *   <li>{@code cancel} — cancel a running job</li>
 *   <li>{@code history} — query persisted job history</li>
 *   <li>{@code wizard} — interactive step-by-step configuration</li>
 * </ul>
 */
@Command(
        name = "train",
        description = "Manage model training jobs.%n%n" +
                "Start, monitor, and manage training runs against a kompile-app instance.%n%n" +
                "Commands:%n" +
                "  start     Launch a new training job%n" +
                "  list      List all training jobs%n" +
                "  status    Get job status and metrics%n" +
                "  logs      View or stream training logs%n" +
                "  cancel    Cancel a running job%n" +
                "  history   Query persisted job history%n" +
                "  wizard    Interactive training configuration wizard%n",
        subcommands = {
                TrainCommand.StartCmd.class,
                TrainCommand.ListCmd.class,
                TrainCommand.StatusCmd.class,
                TrainCommand.LogsCmd.class,
                TrainCommand.CancelCmd.class,
                TrainCommand.HistoryCmd.class,
                TrainWizardCmd.class
        },
        mixinStandardHelpOptions = true
)
public class TrainCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ==================== start ====================

    @Command(name = "start", description = "Launch a new training job.",
            mixinStandardHelpOptions = true)
    static class StartCmd implements Callable<Integer> {

        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--model", "-m"}, required = true,
                description = "Model ID to fine-tune")
        private String modelId;

        @CommandLine.Option(names = {"--dataset", "-d"}, required = true,
                description = "Dataset ID for training")
        private String datasetId;

        @CommandLine.Option(names = {"--type", "-t"}, defaultValue = "FINETUNE",
                description = "Training type: FINETUNE, LORA, DISTILLATION, ALIGNMENT (default: ${DEFAULT-VALUE})")
        private String trainingType;

        @CommandLine.Option(names = {"--epochs"}, defaultValue = "3",
                description = "Number of training epochs (default: ${DEFAULT-VALUE})")
        private int epochs;

        @CommandLine.Option(names = {"--batch-size"}, defaultValue = "8",
                description = "Training batch size (default: ${DEFAULT-VALUE})")
        private int batchSize;

        @CommandLine.Option(names = {"--lr", "--learning-rate"}, defaultValue = "1e-4",
                description = "Learning rate (default: ${DEFAULT-VALUE})")
        private double learningRate;

        @CommandLine.Option(names = {"--lr-schedule"}, defaultValue = "COSINE",
                description = "LR schedule: COSINE, LINEAR, CONSTANT, POLYNOMIAL (default: ${DEFAULT-VALUE})")
        private String lrSchedule;

        @CommandLine.Option(names = {"--warmup-ratio"}, defaultValue = "0.1",
                description = "Warmup ratio (default: ${DEFAULT-VALUE})")
        private double warmupRatio;

        @CommandLine.Option(names = {"--max-steps"}, defaultValue = "-1",
                description = "Max training steps; -1 for unlimited (default: ${DEFAULT-VALUE})")
        private int maxSteps;

        @CommandLine.Option(names = {"--max-grad-norm"}, defaultValue = "1.0",
                description = "Gradient clipping threshold (default: ${DEFAULT-VALUE})")
        private double maxGradNorm;

        @CommandLine.Option(names = {"--grad-accum-steps"}, defaultValue = "1",
                description = "Gradient accumulation steps (default: ${DEFAULT-VALUE})")
        private int gradientAccumulationSteps;

        @CommandLine.Option(names = {"--fp16"},
                description = "Enable FP16 mixed precision")
        private boolean fp16;

        @CommandLine.Option(names = {"--bf16"},
                description = "Enable BF16 mixed precision")
        private boolean bf16;

        @CommandLine.Option(names = {"--optimizer"}, defaultValue = "ADAMW",
                description = "Optimizer type: ADAM, ADAMW, SGD, ADAGRAD (default: ${DEFAULT-VALUE})")
        private String optimizer;

        @CommandLine.Option(names = {"--logging-steps"}, defaultValue = "10",
                description = "Log every N steps (default: ${DEFAULT-VALUE})")
        private int loggingSteps;

        @CommandLine.Option(names = {"--save-steps"}, defaultValue = "500",
                description = "Save checkpoint every N steps (default: ${DEFAULT-VALUE})")
        private int saveSteps;

        @CommandLine.Option(names = {"--eval-steps"}, defaultValue = "500",
                description = "Evaluate every N steps (default: ${DEFAULT-VALUE})")
        private int evalSteps;

        @CommandLine.Option(names = {"--seed"}, defaultValue = "42",
                description = "Random seed (default: ${DEFAULT-VALUE})")
        private int seed;

        @CommandLine.Option(names = {"--auto-register"}, defaultValue = "true", negatable = true,
                description = "Auto-register model after training (default: ${DEFAULT-VALUE})")
        private boolean autoRegister;

        @CommandLine.Option(names = {"--evaluate-after"}, defaultValue = "false",
                description = "Run evaluation benchmarks after training (default: ${DEFAULT-VALUE})")
        private boolean evaluateAfterTraining;

        @CommandLine.Option(names = {"--enable-monitoring"}, defaultValue = "true", negatable = true,
                description = "Enable DSP/throughput monitoring (default: ${DEFAULT-VALUE})")
        private boolean enableMonitoring;

        @CommandLine.Option(names = {"--output-dir"},
                description = "Output directory for trained model")
        private String outputDir;

        // LoRA options
        @CommandLine.Option(names = {"--lora-rank"}, defaultValue = "8",
                description = "LoRA rank (default: ${DEFAULT-VALUE})")
        private int loraRank;

        @CommandLine.Option(names = {"--lora-alpha"}, defaultValue = "16",
                description = "LoRA alpha (default: ${DEFAULT-VALUE})")
        private double loraAlpha;

        @CommandLine.Option(names = {"--lora-dropout"}, defaultValue = "0.05",
                description = "LoRA dropout (default: ${DEFAULT-VALUE})")
        private double loraDropout;

        @CommandLine.Option(names = {"--lora-targets"},
                description = "LoRA target modules (comma-separated, e.g. q_proj,v_proj)")
        private String loraTargets;

        @CommandLine.Option(names = {"--watch", "-w"},
                description = "Stream live training logs after launching")
        private boolean watch;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            try {
                Map<String, Object> request = buildRequest();
                String response = client.postString("/api/training/start", request);
                ObjectMapper mapper = client.getObjectMapper();
                JsonNode status = mapper.readTree(response);

                String jobId = status.path("jobId").asText(status.path("taskId").asText("unknown"));
                System.out.println();
                System.out.println("Training job launched successfully!");
                printJobStatus(status);

                if (watch) {
                    System.out.println();
                    return streamLogs(client, jobId);
                }

                System.out.println();
                System.out.printf("Tip: Run 'kompile app train logs --job %s --follow' to stream live logs.%n", jobId);
                return 0;
            } catch (Exception e) {
                System.err.println("Error launching training: " + e.getMessage());
                return 1;
            }
        }

        private Map<String, Object> buildRequest() {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("modelId", modelId);
            req.put("datasetId", datasetId);
            req.put("epochs", epochs);
            req.put("batchSize", batchSize);
            req.put("lrSchedule", lrSchedule);
            req.put("warmupRatio", warmupRatio);
            req.put("maxSteps", maxSteps);
            req.put("maxGradNorm", maxGradNorm);
            req.put("gradientAccumulationSteps", gradientAccumulationSteps);
            req.put("fp16", fp16);
            req.put("bf16", bf16);
            req.put("loggingSteps", loggingSteps);
            req.put("saveSteps", saveSteps);
            req.put("evalSteps", evalSteps);
            req.put("seed", seed);
            req.put("autoRegister", autoRegister);
            req.put("evaluateAfterTraining", evaluateAfterTraining);
            req.put("enableMonitoring", enableMonitoring);
            if (outputDir != null) req.put("outputDir", outputDir);

            Map<String, Object> updaterConfig = new LinkedHashMap<>();
            updaterConfig.put("type", optimizer);
            updaterConfig.put("learningRate", learningRate);
            req.put("updaterConfig", updaterConfig);

            String type = trainingType.toUpperCase();
            if ("LORA".equals(type)) {
                Map<String, Object> peftConfig = new LinkedHashMap<>();
                peftConfig.put("peftType", "LORA");
                peftConfig.put("rank", loraRank);
                peftConfig.put("alpha", loraAlpha);
                peftConfig.put("dropout", loraDropout);
                if (loraTargets != null && !loraTargets.isBlank()) {
                    peftConfig.put("targetModules", Arrays.asList(loraTargets.split(",")));
                }
                req.put("peftConfig", peftConfig);
            }

            return req;
        }
    }

    // ==================== list ====================

    @Command(name = "list", aliases = {"ls"}, description = "List all training jobs.",
            mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {

        @CommandLine.Mixin private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            try {
                String response = client.getString("/api/training/jobs");
                ObjectMapper mapper = client.getObjectMapper();
                JsonNode jobs = mapper.readTree(response);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                if (!jobs.isArray() || jobs.isEmpty()) {
                    System.out.println("No training jobs found.");
                    return 0;
                }

                System.out.println();
                System.out.printf("  %-20s %-12s %-20s %-10s %-8s %-12s%n",
                        "JOB ID", "STATUS", "MODEL", "EPOCH", "LOSS", "ELAPSED");
                System.out.printf("  %-20s %-12s %-20s %-10s %-8s %-12s%n",
                        "------", "------", "-----", "-----", "----", "-------");
                for (JsonNode j : jobs) {
                    String id = j.path("jobId").asText(j.path("taskId").asText("-"));
                    String st = j.path("status").asText("-");
                    String model = truncate(j.path("modelId").asText("-"), 20);
                    String epoch = j.path("currentEpoch").asInt(0) + "/" + j.path("totalEpochs").asInt(0);
                    String loss = j.path("loss").asDouble(0) > 0 ? String.format("%.4f", j.path("loss").asDouble()) : "-";
                    String elapsed = formatElapsed(j.path("elapsedMs").asLong(0));
                    System.out.printf("  %-20s %-12s %-20s %-10s %-8s %-12s%n",
                            truncate(id, 20), st, model, epoch, loss, elapsed);
                }
                System.out.println();
                return 0;
            } catch (Exception e) {
                System.err.println("Error listing jobs: " + e.getMessage());
                return 1;
            }
        }
    }

    // ==================== status ====================

    @Command(name = "status", description = "Get detailed status of a training job.",
            mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {

        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--job", "-j"}, required = true,
                description = "Job ID")
        private String jobId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            try {
                String response = client.getString("/api/training/jobs/" + jobId);
                ObjectMapper mapper = client.getObjectMapper();
                JsonNode status = mapper.readTree(response);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                printJobStatus(status);

                // Also fetch latest metrics
                try {
                    String metricsResp = client.getString("/api/training/jobs/" + jobId + "/metrics");
                    JsonNode metrics = mapper.readTree(metricsResp);
                    if (metrics.isArray() && !metrics.isEmpty()) {
                        JsonNode last = metrics.get(metrics.size() - 1);
                        System.out.println();
                        System.out.println("  Latest Metrics:");
                        OutputFormatter.printKv("Train Loss", formatDouble(last.path("trainLoss").asDouble()));
                        OutputFormatter.printKv("Eval Loss", formatDouble(last.path("evalLoss").asDouble()));
                        OutputFormatter.printKv("Learning Rate", String.format("%.2e", last.path("learningRate").asDouble()));
                        OutputFormatter.printKv("Tokens/sec", formatDouble(last.path("tokensPerSecond").asDouble()));
                        OutputFormatter.printKv("Samples/sec", formatDouble(last.path("samplesPerSecond").asDouble()));
                        if (last.has("dspPlanPhase") && !last.path("dspPlanPhase").asText("").isEmpty()) {
                            System.out.println();
                            System.out.println("  DSP Monitor:");
                            OutputFormatter.printKv("Plan Phase", last.path("dspPlanPhase").asText());
                            OutputFormatter.printKv("Cache Hits", last.path("dspReplayCacheHits").asLong());
                            OutputFormatter.printKv("Cache Misses", last.path("dspReplayCacheMisses").asLong());
                            OutputFormatter.printKv("Recompilations", last.path("dspRecompilationCount").asInt());
                            OutputFormatter.printKv("Frozen Executions", last.path("dspFrozenExecutionCount").asInt());
                            OutputFormatter.printKv("Plan Segments", last.path("dspNumSegments").asInt());
                        }
                    }
                } catch (Exception ignored) {}

                System.out.println();
                return 0;
            } catch (Exception e) {
                System.err.println("Error getting job status: " + e.getMessage());
                return 1;
            }
        }
    }

    // ==================== logs ====================

    @Command(name = "logs", description = "View or stream training logs.",
            mixinStandardHelpOptions = true)
    static class LogsCmd implements Callable<Integer> {

        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--job", "-j"}, required = true,
                description = "Job ID")
        private String jobId;

        @CommandLine.Option(names = {"--follow", "-f"},
                description = "Stream live logs (SSE)")
        private boolean follow;

        @CommandLine.Option(names = {"--tail", "-n"}, defaultValue = "50",
                description = "Number of recent log entries to show (default: ${DEFAULT-VALUE})")
        private int tail;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            try {
                if (follow) {
                    return streamLogs(client, jobId);
                }

                // Fetch historical logs
                String response = client.getString("/api/training/jobs/" + jobId + "/logs");
                ObjectMapper mapper = client.getObjectMapper();
                JsonNode logs = mapper.readTree(response);

                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                if (!logs.isArray() || logs.isEmpty()) {
                    System.out.println("No log entries for job " + jobId);
                    return 0;
                }

                System.out.println();
                int start = Math.max(0, logs.size() - tail);
                for (int i = start; i < logs.size(); i++) {
                    JsonNode entry = logs.get(i);
                    printLogEntry(entry);
                }
                System.out.printf("%n  (%d total entries, showing last %d)%n%n", logs.size(), logs.size() - start);
                return 0;
            } catch (Exception e) {
                System.err.println("Error getting logs: " + e.getMessage());
                return 1;
            }
        }
    }

    // ==================== cancel ====================

    @Command(name = "cancel", description = "Cancel a running training job.",
            mixinStandardHelpOptions = true)
    static class CancelCmd implements Callable<Integer> {

        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--job", "-j"}, required = true,
                description = "Job ID to cancel")
        private String jobId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            try {
                String response = client.postEmpty("/api/training/jobs/" + jobId + "/cancel");
                ObjectMapper mapper = client.getObjectMapper();
                JsonNode result = mapper.readTree(response);

                boolean cancelled = result.path("cancelled").asBoolean(false);
                if (cancelled) {
                    System.out.println("Training job " + jobId + " cancelled successfully.");
                } else {
                    System.out.println("Could not cancel job " + jobId + " (may already be completed or not found).");
                }
                return cancelled ? 0 : 1;
            } catch (Exception e) {
                System.err.println("Error cancelling job: " + e.getMessage());
                return 1;
            }
        }
    }

    // ==================== history ====================

    @Command(name = "history", description = "Query persisted training job history.",
            mixinStandardHelpOptions = true)
    static class HistoryCmd implements Callable<Integer> {

        @CommandLine.Mixin private AppClientMixin app;

        @CommandLine.Option(names = {"--status"},
                description = "Filter by status: RUNNING, COMPLETED, FAILED, CANCELLED")
        private String status;

        @CommandLine.Option(names = {"--type"},
                description = "Filter by training type: FINETUNE, LORA, DISTILLATION, ALIGNMENT")
        private String type;

        @CommandLine.Option(names = {"--model"},
                description = "Filter by model ID")
        private String model;

        @CommandLine.Option(names = {"--recent"}, defaultValue = "24",
                description = "Show jobs from last N hours (default: ${DEFAULT-VALUE})")
        private int recentHours;

        @CommandLine.Option(names = {"--stats"},
                description = "Show training statistics summary")
        private boolean stats;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            try {
                ObjectMapper mapper = client.getObjectMapper();

                if (stats) {
                    String response = client.getString("/api/training/history/statistics");
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        JsonNode s = mapper.readTree(response);
                        System.out.println();
                        System.out.println("  Training Statistics:");
                        for (Iterator<Map.Entry<String, JsonNode>> it = s.fields(); it.hasNext(); ) {
                            Map.Entry<String, JsonNode> f = it.next();
                            OutputFormatter.printKv(f.getKey(), f.getValue().asText());
                        }
                        System.out.println();
                    }
                    return 0;
                }

                String path;
                if (status != null) {
                    path = "/api/training/history/status/" + status.toUpperCase();
                } else if (type != null) {
                    path = "/api/training/history/type/" + type.toUpperCase();
                } else if (model != null) {
                    path = "/api/training/history/model/" + model;
                } else {
                    path = "/api/training/history/recent?hours=" + recentHours;
                }

                String response = client.getString(path);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode jobs = mapper.readTree(response);
                // Handle paginated responses
                if (jobs.has("content") && jobs.path("content").isArray()) {
                    jobs = jobs.path("content");
                }
                if (!jobs.isArray() || jobs.isEmpty()) {
                    System.out.println("No training history found.");
                    return 0;
                }

                System.out.println();
                System.out.printf("  %-20s %-12s %-10s %-20s %-10s %-12s%n",
                        "TASK ID", "STATUS", "TYPE", "MODEL", "LOSS", "STARTED");
                System.out.printf("  %-20s %-12s %-10s %-20s %-10s %-12s%n",
                        "-------", "------", "----", "-----", "----", "-------");
                for (JsonNode j : jobs) {
                    String id = truncate(j.path("taskId").asText("-"), 20);
                    String st = j.path("status").asText("-");
                    String tp = j.path("trainingType").asText("-");
                    String mdl = truncate(j.path("modelId").asText("-"), 20);
                    String loss = j.path("finalLoss").asDouble(0) > 0 ?
                            String.format("%.4f", j.path("finalLoss").asDouble()) : "-";
                    String started = j.path("startedAt").asText("-");
                    if (started.length() > 19) started = started.substring(0, 19);
                    System.out.printf("  %-20s %-12s %-10s %-20s %-10s %-12s%n",
                            id, st, tp, mdl, loss, started);
                }
                System.out.println();
                return 0;
            } catch (Exception e) {
                System.err.println("Error querying history: " + e.getMessage());
                return 1;
            }
        }
    }

    // ==================== Shared Helpers ====================

    static void printJobStatus(JsonNode status) {
        System.out.println();
        String id = status.path("jobId").asText(status.path("taskId").asText("-"));
        OutputFormatter.printKv("Job ID", id);
        OutputFormatter.printKv("Status", status.path("status").asText("-"));
        OutputFormatter.printKv("Model", status.path("modelId").asText("-"));
        OutputFormatter.printKv("Dataset", status.path("datasetId").asText("-"));
        OutputFormatter.printKv("Epoch", status.path("currentEpoch").asInt(0) + " / " +
                status.path("totalEpochs").asInt(0));
        OutputFormatter.printKv("Step", status.path("currentStep").asLong(0) + " / " +
                status.path("totalSteps").asLong(0));
        if (status.path("loss").asDouble(0) > 0) {
            OutputFormatter.printKv("Loss", formatDouble(status.path("loss").asDouble()));
        }
        if (status.path("learningRate").asDouble(0) > 0) {
            OutputFormatter.printKv("Learning Rate", String.format("%.2e", status.path("learningRate").asDouble()));
        }
        if (status.has("outputModelPath") && !status.path("outputModelPath").asText("").isEmpty()) {
            OutputFormatter.printKv("Output", status.path("outputModelPath").asText());
        }
        if (status.path("elapsedMs").asLong(0) > 0) {
            OutputFormatter.printKv("Elapsed", formatElapsed(status.path("elapsedMs").asLong()));
        }
        if (status.has("error") && !status.path("error").asText("").isEmpty()) {
            OutputFormatter.printKv("Error", status.path("error").asText());
        }
    }

    static int streamLogs(KompileHttpClient client, String jobId) {
        String url = client.getBaseUrl() + "/api/training/jobs/" + jobId + "/stream";
        System.out.printf("Streaming logs for job %s (Ctrl+C to stop)...%n%n", jobId);

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(0); // no read timeout for SSE

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                ObjectMapper mapper = JsonUtils.standardMapper();
                String line;
                String currentEvent = "";
                StringBuilder dataBuffer = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        currentEvent = line.substring(6).trim();
                        dataBuffer.setLength(0);
                    } else if (line.startsWith("data:")) {
                        dataBuffer.append(line.substring(5).trim());
                    } else if (line.isEmpty() && dataBuffer.length() > 0) {
                        // End of event
                        String data = dataBuffer.toString();
                        handleSseEvent(mapper, currentEvent, data);
                        dataBuffer.setLength(0);
                        currentEvent = "";
                    }
                }
            }
            System.out.println();
            System.out.println("Stream ended.");
            return 0;
        } catch (Exception e) {
            System.err.println("Error streaming: " + e.getMessage());
            return 1;
        }
    }

    private static void handleSseEvent(ObjectMapper mapper, String event, String data) {
        try {
            JsonNode node = mapper.readTree(data);
            switch (event) {
                case "log":
                    printLogEntry(node);
                    break;
                case "metrics":
                    printMetricsLine(node);
                    break;
                case "status":
                    String st = node.path("status").asText();
                    if ("COMPLETED".equals(st) || "FAILED".equals(st) || "CANCELLED".equals(st)) {
                        System.out.println();
                        System.out.printf("  >> Job %s: %s%n", node.path("jobId").asText(), st);
                        if (node.has("error") && !node.path("error").asText("").isEmpty()) {
                            System.out.printf("  >> Error: %s%n", node.path("error").asText());
                        }
                    }
                    break;
                case "ready":
                    System.out.println("  >> Metrics available — monitoring is live.");
                    break;
                case "dsp_alert":
                    System.out.println();
                    System.out.printf("  !! DSP ALERT: %s%n", node.path("message").asText());
                    System.out.println();
                    break;
                case "heartbeat":
                    // keepalive, no output
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {}
    }

    static void printLogEntry(JsonNode entry) {
        String time = entry.path("timestamp").asText("-");
        if (time.length() > 19) time = time.substring(11, 19);
        String level = entry.path("level").asText("INFO");
        String msg = entry.path("message").asText("");

        String prefix;
        switch (level.toUpperCase()) {
            case "ERROR":   prefix = "\033[31m[ERR]\033[0m";  break;
            case "WARN":
            case "WARNING": prefix = "\033[33m[WRN]\033[0m";  break;
            case "DEBUG":   prefix = "\033[90m[DBG]\033[0m";  break;
            default:        prefix = "\033[36m[INF]\033[0m";  break;
        }
        System.out.printf("  %s %s %s%n", time, prefix, msg);
    }

    private static void printMetricsLine(JsonNode m) {
        long step = m.path("step").asLong();
        int epoch = m.path("epoch").asInt();
        double loss = m.path("trainLoss").asDouble();
        double lr = m.path("learningRate").asDouble();
        double tokSec = m.path("tokensPerSecond").asDouble();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  \033[90m[METRIC]\033[0m step=%d epoch=%d loss=%.6f lr=%.2e",
                step, epoch, loss, lr));
        if (tokSec > 0) {
            sb.append(String.format(" tok/s=%.0f", tokSec));
        }

        // DSP info
        String dspPhase = m.path("dspPlanPhase").asText("");
        if (!dspPhase.isEmpty()) {
            sb.append(String.format(" dsp=%s", dspPhase));
            int recomp = m.path("dspRecompilationCount").asInt();
            if (recomp > 0) {
                sb.append(String.format(" \033[31mRECOMPILE=%d\033[0m", recomp));
            }
        }

        System.out.println(sb);
    }

    static String formatDouble(double v) {
        if (v == 0) return "-";
        return String.format("%.6f", v);
    }

    static String formatElapsed(long ms) {
        if (ms <= 0) return "-";
        long secs = ms / 1000;
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m " + (secs % 60) + "s";
        return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
    }

    static String truncate(String s, int max) {
        if (s == null) return "-";
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
