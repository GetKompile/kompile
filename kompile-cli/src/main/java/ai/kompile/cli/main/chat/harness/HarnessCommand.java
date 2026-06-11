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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Standalone CLI command for managing the Agent Performance Harness.
 * Works without starting a chat session — reads persisted data from
 * ~/.kompile/perf-data.json and ~/.kompile/harness-config.json.
 *
 * Usage: kompile perf [report|recommend|config|reset]
 */
@CommandLine.Command(
        name = "perf",
        description = "Agent Performance Harness — analyze and tune model performance across sessions.",
        subcommands = {
                HarnessCommand.ReportCommand.class,
                HarnessCommand.RecommendCommand.class,
                HarnessCommand.ConfigCommand.class,
                HarnessCommand.ResetCommand.class
        },
        mixinStandardHelpOptions = true
)
public class HarnessCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // Default: show report
        return new ReportCommand().call();
    }

    @CommandLine.Command(name = "report", description = "Show cross-session model performance report",
            mixinStandardHelpOptions = true)
    static class ReportCommand implements Callable<Integer> {

        @CommandLine.Option(names = "--json", description = "Output in JSON format")
        boolean json;

        @CommandLine.Option(names = "--task", description = "Filter by task type (code-review, planning, research, general)")
        String taskType;

        @CommandLine.Option(names = "--days", description = "Number of days to include (default: 30)", defaultValue = "30")
        int days;

        @Override
        public Integer call() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            HarnessConfig config = HarnessConfig.load(mapper);
            ModelPerformanceStore store = new ModelPerformanceStore(
                    config.getMaxRecordAge(), config.getMaxRecords());
            store.loadFromFile();

            if (store.size() == 0) {
                if (json) {
                    System.out.println("{\"records\": 0, \"message\": \"No performance data collected yet.\"}");
                } else {
                    System.out.println("No performance data collected yet.");
                    System.out.println("Use 'kompile chat' to build performance history.");
                }
                return 0;
            }

            if (json) {
                printJsonReport(mapper, store, taskType, days);
            } else {
                printTextReport(store, taskType, days);
            }

            return 0;
        }

        private void printJsonReport(ObjectMapper mapper, ModelPerformanceStore store,
                                      String taskFilter, int days) {
            ObjectNode root = mapper.createObjectNode();
            root.put("totalRecords", store.size());
            root.put("days", days);

            ObjectNode taskSummaries = mapper.createObjectNode();
            for (String type : store.getTaskTypes()) {
                if (taskFilter != null && !taskFilter.equals(type)) continue;
                List<ModelPerformanceStore.TaskModelSummary> leaderboard = store.getLeaderboard(type, days);
                ArrayNode entries = mapper.createArrayNode();
                for (ModelPerformanceStore.TaskModelSummary s : leaderboard) {
                    ObjectNode entry = mapper.createObjectNode();
                    entry.put("model", s.model());
                    entry.put("avgScore", Math.round(s.avgScore() * 10.0) / 10.0);
                    entry.put("avgLatencyMs", s.avgLatencyMs());
                    entry.put("callCount", s.callCount());
                    entry.put("escapeCount", s.escapeCount());
                    entry.put("escapeRate", Math.round(s.escapeRate() * 1000.0) / 1000.0);
                    entries.add(entry);
                }
                taskSummaries.set(type, entries);
            }
            root.set("taskSummaries", taskSummaries);

            Map<String, String> recs = store.getRecommendations();
            ObjectNode recommendations = mapper.createObjectNode();
            recs.forEach(recommendations::put);
            root.set("recommendations", recommendations);

            // Outcome stats per model
            ObjectNode outcomeStats = mapper.createObjectNode();
            for (String model : store.getModels()) {
                Map<String, Integer> stats = store.getOutcomeStats(model, days);
                if (!stats.isEmpty()) {
                    ObjectNode modelStats = mapper.createObjectNode();
                    stats.forEach(modelStats::put);
                    modelStats.put("successRate",
                            Math.round(store.getSuccessRate(model, days) * 1000.0) / 1000.0);
                    outcomeStats.set(model, modelStats);
                }
            }
            if (!outcomeStats.isEmpty()) {
                root.set("outcomeStats", outcomeStats);
            }

            try {
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            } catch (Exception e) {
                System.err.println("Error serializing report: " + e.getMessage());
            }
        }

        private void printTextReport(ModelPerformanceStore store, String taskFilter, int days) {
            TerminalRenderer term = new TerminalRenderer();
            AsciiRenderer ascii = new AsciiRenderer(term);

            // ── Header ──
            System.out.println();
            System.out.println(ascii.sectionHeader(
                    "Performance Report (" + store.size() + " records, last " + days + " days)"));
            System.out.println();

            // ── Leaderboard per task type with sparklines ──
            for (String type : store.getTaskTypes()) {
                if (taskFilter != null && !taskFilter.equals(type)) continue;

                List<ModelPerformanceStore.TaskModelSummary> leaderboard = store.getLeaderboard(type, days);
                if (leaderboard.isEmpty()) continue;

                StringBuilder body = new StringBuilder();
                int rank = 1;
                for (ModelPerformanceStore.TaskModelSummary s : leaderboard) {
                    String model = shortenModel(s.model());
                    double[] trend = store.getModelScoreTrend(s.model(), 12);

                    body.append(String.format(" %d. ", rank));
                    body.append(AsciiRenderer.padRight(model, 22));

                    // Score bar
                    int filled = (int) Math.round(s.avgScore() / 5.0 * 8);
                    filled = Math.max(0, Math.min(8, filled));
                    String bar = "█".repeat(filled) + "░".repeat(8 - filled);
                    if (s.avgScore() >= 4.0) body.append(term.green("[" + bar + "]"));
                    else if (s.avgScore() >= 3.0) body.append(term.yellow("[" + bar + "]"));
                    else body.append(term.red("[" + bar + "]"));
                    body.append(" ");

                    // Sparkline trend
                    if (trend.length > 1) {
                        body.append(ascii.coloredSparkline(trend));
                        body.append(" ");
                    }

                    // Score + stats
                    body.append(ascii.scoreTrend(null, s.avgScore()));
                    body.append(term.dim(String.format("  %dc %dms", s.callCount(), s.avgLatencyMs())));

                    // Escape rate
                    if (s.escapeCount() > 0) {
                        body.append(" ").append(term.red(
                                String.format("%desc/%.0f%%", s.escapeCount(), s.escapeRate() * 100)));
                    }

                    if (rank == 1) {
                        body.append(" ").append(term.green("*"));
                    }
                    body.append("\n");
                    rank++;
                }

                System.out.println(ascii.panel(type, body.toString().stripTrailing()));
                System.out.println();
            }

            // ── Judge dimensions heatmap ──
            Set<String> models = store.getModels();
            boolean hasJudgeData = false;
            for (String model : models) {
                float[] dims = store.getModelJudgeDimensions(model, days);
                if (dims[0] > 0 || dims[1] > 0) { hasJudgeData = true; break; }
            }
            if (hasJudgeData) {
                StringBuilder dimBody = new StringBuilder();
                dimBody.append(AsciiRenderer.padRight("", 24));
                dimBody.append(AsciiRenderer.padRight("correct", 10));
                dimBody.append(AsciiRenderer.padRight("complete", 10));
                dimBody.append(AsciiRenderer.padRight("design", 10));
                dimBody.append(AsciiRenderer.padRight("thinking", 10));
                dimBody.append("\n");

                for (String model : models) {
                    float[] dims = store.getModelJudgeDimensions(model, days);
                    if (dims[0] <= 0 && dims[1] <= 0) continue;

                    dimBody.append(AsciiRenderer.padRight(shortenModel(model), 24));
                    dimBody.append(dims[0] > 0 ? ascii.scoreHeatmap(dims[0]) + "   " : AsciiRenderer.padRight("-", 10));
                    dimBody.append(dims[1] > 0 ? ascii.scoreHeatmap(dims[1]) + "   " : AsciiRenderer.padRight("-", 10));
                    dimBody.append(dims[2] > 0 ? ascii.scoreHeatmap(dims[2]) + "   " : AsciiRenderer.padRight("-", 10));
                    dimBody.append(dims[3] > 0 ? ascii.scoreHeatmap(dims[3]) + "   " : AsciiRenderer.padRight("-", 10));
                    dimBody.append("\n");
                }
                System.out.println(ascii.panel("Judge Dimensions", dimBody.toString().stripTrailing()));
                System.out.println();
            }

            // ── Outcome stats ──
            Set<String> allModels = store.getModels();
            boolean hasOutcomeData = false;
            for (String model : allModels) {
                Map<String, Integer> stats = store.getOutcomeStats(model, days);
                if (!stats.isEmpty()) { hasOutcomeData = true; break; }
            }
            if (hasOutcomeData) {
                StringBuilder outcomeBody = new StringBuilder();
                outcomeBody.append(AsciiRenderer.padRight("", 24));
                outcomeBody.append(AsciiRenderer.padRight("success%", 10));
                outcomeBody.append(AsciiRenderer.padRight("pass", 8));
                outcomeBody.append(AsciiRenderer.padRight("fail", 8));
                outcomeBody.append(AsciiRenderer.padRight("escape", 8));
                outcomeBody.append(AsciiRenderer.padRight("timeout", 8));
                outcomeBody.append("\n");

                for (String model : allModels) {
                    Map<String, Integer> stats = store.getOutcomeStats(model, days);
                    if (stats.isEmpty()) continue;

                    float successRate = store.getSuccessRate(model, days);
                    int completed = stats.getOrDefault("COMPLETED", 0);
                    int failed = stats.getOrDefault("FAILED", 0);
                    int escaped = stats.getOrDefault("ESCAPED", 0);
                    int timedOut = stats.getOrDefault("TIMED_OUT", 0);

                    outcomeBody.append(AsciiRenderer.padRight(shortenModel(model), 24));
                    String rateStr = String.format("%.0f%%", successRate * 100);
                    outcomeBody.append(AsciiRenderer.padRight(
                            successRate >= 0.8 ? term.green(rateStr)
                                    : successRate >= 0.5 ? term.yellow(rateStr)
                                    : term.red(rateStr), 10));
                    outcomeBody.append(AsciiRenderer.padRight(String.valueOf(completed), 8));
                    outcomeBody.append(AsciiRenderer.padRight(failed > 0 ? term.red(String.valueOf(failed)) : "0", 8));
                    outcomeBody.append(AsciiRenderer.padRight(escaped > 0 ? term.red(String.valueOf(escaped)) : "0", 8));
                    outcomeBody.append(AsciiRenderer.padRight(timedOut > 0 ? term.yellow(String.valueOf(timedOut)) : "0", 8));
                    outcomeBody.append("\n");
                }
                System.out.println(ascii.panel("Outcome Tracking", outcomeBody.toString().stripTrailing()));
                System.out.println();
            }

            // ── Recommendations ──
            Map<String, String> recs = store.getRecommendations();
            if (!recs.isEmpty()) {
                StringBuilder recBody = new StringBuilder();
                recs.forEach((task, model) ->
                        recBody.append(AsciiRenderer.padRight(task, 20))
                                .append(term.green(model)).append("\n"));
                System.out.println(ascii.panel("Recommendations", recBody.toString().stripTrailing()));
            } else {
                System.out.println(term.dim("  Not enough data for recommendations."));
            }
            System.out.println();
        }

        private static String shortenModel(String model) {
            if (model == null) return "unknown";
            return model.replaceAll("-\\d{8}$", "");
        }
    }

    @CommandLine.Command(name = "recommend", description = "Show recommended model per task type",
            mixinStandardHelpOptions = true)
    static class RecommendCommand implements Callable<Integer> {

        @CommandLine.Option(names = "--task", description = "Specific task type")
        String taskType;

        @Override
        public Integer call() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            HarnessConfig config = HarnessConfig.load(mapper);
            ModelPerformanceStore store = new ModelPerformanceStore(
                    config.getMaxRecordAge(), config.getMaxRecords());
            store.loadFromFile();

            Map<String, String> recs = store.getRecommendations();

            if (taskType != null) {
                String best = recs.get(taskType);
                if (best != null) {
                    System.out.println(best);
                } else {
                    System.err.println("No data for task type: " + taskType);
                    return 1;
                }
            } else {
                if (recs.isEmpty()) {
                    System.out.println("No performance data collected yet.");
                    return 0;
                }
                recs.forEach((task, model) ->
                        System.out.printf("%-20s %s%n", task, model));
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "config", description = "View or modify harness configuration",
            mixinStandardHelpOptions = true)
    static class ConfigCommand implements Callable<Integer> {

        @CommandLine.Option(names = "--judge", description = "Enable/disable judge (on|off)")
        String judge;

        @CommandLine.Option(names = "--threshold", description = "Quality swap threshold (1.0-5.0)")
        Float threshold;

        @CommandLine.Option(names = "--auto-swap", description = "Enable/disable auto-swap (on|off)")
        String autoSwap;

        @CommandLine.Option(names = "--verbose", description = "Enable/disable verbose logging (on|off)")
        String verbose;

        @CommandLine.Option(names = "--judge-model", description = "Set judge model name")
        String judgeModel;

        @CommandLine.Option(names = "--escape", description = "Enable/disable escape detection (on|off)")
        String escape;

        @CommandLine.Option(names = "--thinking", description = "Enable/disable thinking analysis (on|off)")
        String thinking;

        @CommandLine.Option(names = "--escape-weight", description = "Escape signal weight (0.0-1.0)")
        Float escapeWeight;

        @CommandLine.Option(names = "--judge-weight", description = "Judge signal weight (0.0-1.0)")
        Float judgeWeight;

        @CommandLine.Option(names = "--efficiency-weight", description = "Efficiency signal weight (0.0-1.0)")
        Float efficiencyWeight;

        @CommandLine.Option(names = "--thinking-weight", description = "Thinking signal weight (0.0-1.0)")
        Float thinkingWeight;

        @CommandLine.Option(names = "--judge-provider", description = "Provider for judge LLM (anthropic, openai, gemini, etc.)")
        String judgeProvider;

        @CommandLine.Option(names = "--judge-api-key", description = "API key for judge provider (or use env var)")
        String judgeApiKey;

        @CommandLine.Option(names = "--judge-base-url", description = "Base URL for judge provider")
        String judgeBaseUrl;

        @CommandLine.Option(names = "--judge-mode", description = "Judge backend mode (auto, remote, local, auto-server)")
        String judgeMode;

        @CommandLine.Option(names = "--judge-local-model", description = "Local model ID for judge (e.g. qwen3.5-0.8b) or file path")
        String judgeLocalModel;

        @CommandLine.Option(names = "--judge-local-quant", description = "Quantization for local model (e.g. Q4_K_M)")
        String judgeLocalQuant;

        @CommandLine.Option(names = "--judge-server-type", description = "Server type for auto-server mode (ollama, kompile)")
        String judgeServerType;

        @CommandLine.Option(names = "--judge-server-port", description = "Port for auto-server mode (0 = default)")
        Integer judgeServerPort;

        @Override
        public Integer call() {
            ObjectMapper mapper = new ObjectMapper();
            HarnessConfig config = HarnessConfig.load(mapper);

            boolean modified = false;

            if (judge != null) {
                config.setJudgeEnabled("on".equalsIgnoreCase(judge));
                modified = true;
            }
            if (threshold != null) {
                config.setSwapThresholdScore(threshold);
                modified = true;
            }
            if (autoSwap != null) {
                config.setAutoSwapEnabled("on".equalsIgnoreCase(autoSwap));
                modified = true;
            }
            if (verbose != null) {
                config.setVerboseLogging("on".equalsIgnoreCase(verbose));
                modified = true;
            }
            if (judgeModel != null) {
                config.setJudgeModel(judgeModel.isEmpty() ? null : judgeModel);
                modified = true;
            }
            if (escape != null) {
                config.setEscapeDetectionEnabled("on".equalsIgnoreCase(escape));
                modified = true;
            }
            if (thinking != null) {
                config.setThinkingAnalysisEnabled("on".equalsIgnoreCase(thinking));
                modified = true;
            }
            if (escapeWeight != null) {
                config.setEscapeWeight(escapeWeight);
                modified = true;
            }
            if (judgeWeight != null) {
                config.setJudgeWeight(judgeWeight);
                modified = true;
            }
            if (efficiencyWeight != null) {
                config.setEfficiencyWeight(efficiencyWeight);
                modified = true;
            }
            if (thinkingWeight != null) {
                config.setThinkingWeight(thinkingWeight);
                modified = true;
            }
            if (judgeProvider != null) {
                config.setJudgeProvider(judgeProvider.isEmpty() ? null : judgeProvider);
                modified = true;
            }
            if (judgeApiKey != null) {
                config.setJudgeApiKey(judgeApiKey.isEmpty() ? null : judgeApiKey);
                modified = true;
            }
            if (judgeBaseUrl != null) {
                config.setJudgeBaseUrl(judgeBaseUrl.isEmpty() ? null : judgeBaseUrl);
                modified = true;
            }
            if (judgeMode != null) {
                config.setJudgeMode(judgeMode.isEmpty() ? null : judgeMode);
                modified = true;
            }
            if (judgeLocalModel != null) {
                config.setJudgeLocalModel(judgeLocalModel.isEmpty() ? null : judgeLocalModel);
                modified = true;
            }
            if (judgeLocalQuant != null) {
                config.setJudgeLocalQuant(judgeLocalQuant.isEmpty() ? null : judgeLocalQuant);
                modified = true;
            }
            if (judgeServerType != null) {
                config.setJudgeServerType(judgeServerType.isEmpty() ? null : judgeServerType);
                modified = true;
            }
            if (judgeServerPort != null) {
                config.setJudgeServerPort(judgeServerPort);
                modified = true;
            }

            if (modified) {
                config.save(mapper);
                System.out.println("Configuration updated.");
            }

            // Show current config
            System.out.println("Harness Configuration (" + HarnessConfig.getConfigFilePath() + ")");
            System.out.println("-".repeat(55));
            System.out.printf("  %-25s %s%n", "enabled:", config.isEnabled());
            System.out.printf("  %-25s %s%n", "judge-enabled:", config.isJudgeEnabled());
            System.out.printf("  %-25s %s%n", "judge-mode:", config.getJudgeMode() != null ? config.getJudgeMode() : "auto");
            System.out.printf("  %-25s %s%n", "judge-model:", config.getJudgeModel() != null ? config.getJudgeModel() : "(default)");
            System.out.printf("  %-25s %s%n", "judge-provider:", config.getJudgeProvider() != null ? config.getJudgeProvider() : "(same as chat)");
            System.out.printf("  %-25s %s%n", "judge-api-key:", config.getJudgeApiKey() != null ? "****" + config.getJudgeApiKey().substring(Math.max(0, config.getJudgeApiKey().length() - 4)) : "(same as chat or env)");
            if (config.getJudgeBaseUrl() != null) {
                System.out.printf("  %-25s %s%n", "judge-base-url:", config.getJudgeBaseUrl());
            }
            System.out.printf("  %-25s %.1f%n", "swap-threshold:", config.getSwapThresholdScore());
            System.out.printf("  %-25s %d%n", "rolling-window:", config.getRollingWindowSize());
            System.out.printf("  %-25s %s%n", "auto-swap:", config.isAutoSwapEnabled());
            System.out.printf("  %-25s %s%n", "rate-limit-fallback:", config.isRateLimitFallbackEnabled());
            System.out.printf("  %-25s %s%n", "verbose:", config.isVerboseLogging());
            System.out.printf("  %-25s %s%n", "persist-cross-session:", config.isPersistCrossSession());
            System.out.println();
            System.out.println("Judge Backend:");
            String modeDisplay = config.getJudgeMode() != null ? config.getJudgeMode() : "auto";
            System.out.printf("  %-25s %s%n", "mode:", modeDisplay);
            if ("local".equals(modeDisplay) || "auto".equals(modeDisplay)) {
                System.out.printf("  %-25s %s%n", "local-model:", config.getJudgeLocalModel() != null ? config.getJudgeLocalModel() : "qwen3.5-0.8b");
                System.out.printf("  %-25s %s%n", "local-quant:", config.getJudgeLocalQuant() != null ? config.getJudgeLocalQuant() : "Q4_K_M");
                System.out.printf("  %-25s %s%n", "samediff-llm available:", LocalJudgeBackend.checkClassesAvailable());
            }
            if ("auto-server".equals(modeDisplay) || "auto".equals(modeDisplay)) {
                System.out.printf("  %-25s %s%n", "server-type:", config.getJudgeServerType() != null ? config.getJudgeServerType() : "ollama");
                System.out.printf("  %-25s %s%n", "server-port:", config.getJudgeServerPort() > 0 ? config.getJudgeServerPort() : "(default)");
            }
            System.out.println();
            System.out.println("Signal Layers:");
            System.out.printf("  %-25s %s%n", "escape-detection:", config.isEscapeDetectionEnabled());
            System.out.printf("  %-25s %s%n", "thinking-analysis:", config.isThinkingAnalysisEnabled());
            System.out.println();
            System.out.println("Composite Weights:");
            System.out.printf("  %-25s %.0f%%%n", "escape:", config.getEscapeWeight() * 100);
            System.out.printf("  %-25s %.0f%%%n", "judge:", config.getJudgeWeight() * 100);
            System.out.printf("  %-25s %.0f%%%n", "efficiency:", config.getEfficiencyWeight() * 100);
            System.out.printf("  %-25s %.0f%%%n", "thinking:", config.getThinkingWeight() * 100);

            return 0;
        }
    }

    @CommandLine.Command(name = "reset", description = "Clear performance data",
            mixinStandardHelpOptions = true)
    static class ResetCommand implements Callable<Integer> {

        @CommandLine.Option(names = "--model", description = "Clear data for specific model only")
        String model;

        @CommandLine.Option(names = "--confirm", description = "Skip confirmation prompt")
        boolean confirm;

        @Override
        public Integer call() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            HarnessConfig config = HarnessConfig.load(mapper);
            ModelPerformanceStore store = new ModelPerformanceStore(
                    config.getMaxRecordAge(), config.getMaxRecords());
            store.loadFromFile();

            int before = store.size();
            if (before == 0) {
                System.out.println("No performance data to clear.");
                return 0;
            }

            if (!confirm) {
                String target = model != null ? "data for model '" + model + "'" : "all performance data";
                System.out.println("This will clear " + target + " (" + before + " records).");
                System.out.println("Use --confirm to proceed.");
                return 0;
            }

            store.clear(model);
            store.saveToFile();

            int after = store.size();
            System.out.println("Cleared " + (before - after) + " records. " + after + " remaining.");
            return 0;
        }
    }
}
