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

import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;

import java.util.function.IntSupplier;

import java.util.*;

/**
 * TUI rendering for the performance harness dashboard, inline swap
 * notifications, and /stats integration.
 * <p>
 * Uses {@link AsciiRenderer} panels, sparklines, heatmap cells, and
 * horizontal bar charts for rich terminal graph output.
 */
public class HarnessRenderer {

    private final TerminalRenderer renderer;
    private final AsciiRenderer ascii;

    public HarnessRenderer(TerminalRenderer renderer) {
        this.renderer = renderer;
        this.ascii = new AsciiRenderer(renderer);
    }

    public HarnessRenderer(TerminalRenderer renderer, int terminalWidth) {
        this.renderer = renderer;
        this.ascii = new AsciiRenderer(renderer, terminalWidth);
    }

    public HarnessRenderer(TerminalRenderer renderer, IntSupplier widthSupplier) {
        this.renderer = renderer;
        this.ascii = new AsciiRenderer(renderer, widthSupplier);
    }

    /**
     * Render the full harness dashboard for /harness command.
     */
    public String renderDashboard(PerformanceHarness harness, ChatSessionMetrics metrics) {
        HarnessConfig config = harness.getConfig();
        ModelPerformanceStore store = harness.getStore();
        ModelRouter router = harness.getRouter();

        StringBuilder sb = new StringBuilder();
        sb.append("\n");

        // ── Status panel ──────────────────────────────────────────
        sb.append(renderStatusPanel(config));

        // ── Session scores with sparklines ────────────────────────
        if (metrics != null) {
            String sessionPanel = renderSessionPanel(metrics, store);
            if (sessionPanel != null) {
                sb.append(sessionPanel);
            }
        }

        // ── Cross-session leaderboard with trends ─────────────────
        String leaderboardPanel = renderLeaderboardPanel(store);
        if (leaderboardPanel != null) {
            sb.append(leaderboardPanel);
        }

        // ── Judge dimensions breakdown ────────────────────────────
        String judgePanel = renderJudgeDimensionsPanel(store);
        if (judgePanel != null) {
            sb.append(judgePanel);
        }

        // ── Swap history ──────────────────────────────────────────
        List<ModelRouter.SwapEvent> swaps = router.getSwapHistory();
        if (!swaps.isEmpty()) {
            sb.append(renderSwapHistory(swaps));
        }

        // ── Commands help ─────────────────────────────────────────
        sb.append(renderer.dim("  Commands:")).append("\n");
        sb.append(renderer.dim("    /harness on|off         Enable/disable harness")).append("\n");
        sb.append(renderer.dim("    /harness judge on|off   Enable/disable judge LLM")).append("\n");
        sb.append(renderer.dim("    /harness threshold <N>  Set quality swap threshold (1-5)")).append("\n");
        sb.append(renderer.dim("    /harness model <name>   Set judge model")).append("\n");
        sb.append(renderer.dim("    /harness verbose        Toggle verbose judge output")).append("\n");
        sb.append(renderer.dim("    /harness report         Full cross-session report")).append("\n");
        sb.append(renderer.dim("    /harness recommend      Best model per task type")).append("\n");
        sb.append(renderer.dim("    /harness reset          Clear session scores")).append("\n");

        return sb.toString();
    }

    // ── Dashboard sub-panels ──────────────────────────────────────

    private String renderStatusPanel(HarnessConfig config) {
        StringBuilder body = new StringBuilder();

        // Row 1: Status, judge, auto-swap
        body.append("Status: ").append(config.isEnabled() ? renderer.green("enabled") : renderer.red("disabled"));
        body.append("  Judge: ").append(config.isJudgeEnabled() ? renderer.green("on") : renderer.dim("off"));
        if (config.isJudgeEnabled() && config.getJudgeModel() != null) {
            body.append(" (").append(renderer.cyan(config.getJudgeModel())).append(")");
        }
        body.append("  Auto-swap: ").append(config.isAutoSwapEnabled() ? renderer.green("ON") : renderer.dim("OFF"));
        body.append("\n");

        // Row 2: Thresholds and layers
        body.append("Threshold: ").append(renderer.cyan(String.format("%.1f/5", config.getSwapThresholdScore())));
        body.append("  Window: ").append(renderer.cyan(String.valueOf(config.getRollingWindowSize())));
        body.append("  Escape: ").append(config.isEscapeDetectionEnabled() ? renderer.green("on") : renderer.dim("off"));
        body.append("  Thinking: ").append(config.isThinkingAnalysisEnabled() ? renderer.green("on") : renderer.dim("off"));
        body.append("\n");

        // Row 3: Composite weights as a multi-segment bar
        Map<String, Integer> weightSegments = new LinkedHashMap<>();
        weightSegments.put("escape " + (int) (config.getEscapeWeight() * 100) + "%",
                (int) (config.getEscapeWeight() * 100));
        weightSegments.put("judge " + (int) (config.getJudgeWeight() * 100) + "%",
                (int) (config.getJudgeWeight() * 100));
        weightSegments.put("efficiency " + (int) (config.getEfficiencyWeight() * 100) + "%",
                (int) (config.getEfficiencyWeight() * 100));
        weightSegments.put("thinking " + (int) (config.getThinkingWeight() * 100) + "%",
                (int) (config.getThinkingWeight() * 100));
        body.append(ascii.multiProgressBar(weightSegments, 40));

        // Row 4: Judge backend mode
        String judgeMode = config.getJudgeMode() != null ? config.getJudgeMode() : "auto";
        body.append("\n");
        body.append("Judge backend: ").append(renderer.cyan(judgeMode));
        if (config.getJudgeProvider() != null) {
            body.append("  provider: ").append(renderer.cyan(config.getJudgeProvider()));
        }

        return ascii.panel("Performance Harness", body.toString()) + "\n";
    }

    private String renderSessionPanel(ChatSessionMetrics metrics, ModelPerformanceStore store) {
        Map<String, Double> avgScores = metrics.getAvgScoreByModel();
        if (avgScores.isEmpty()) return null;

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, Double> entry : avgScores.entrySet()) {
            String model = entry.getKey();
            double avg = entry.getValue();
            Map<String, List<Double>> rawScores = metrics.getQualityScoresByModel();
            int count = rawScores.containsKey(model) ? rawScores.get(model).size() : 0;

            // Score trend sparkline from cross-session store
            double[] trend = store.getModelScoreTrend(model, 15);

            body.append(padRight(shortenModel(model), 24));
            body.append(renderScoreBar(avg, 10));
            body.append(" ").append(ascii.scoreTrend(trend, avg));
            body.append("  ").append(renderer.dim("(" + count + " evals)"));
            body.append("\n");
        }

        return ascii.panel("Session Scores", body.toString().stripTrailing()) + "\n";
    }

    private String renderLeaderboardPanel(ModelPerformanceStore store) {
        Set<String> taskTypes = store.getTaskTypes();
        if (taskTypes.isEmpty()) return null;

        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (String taskType : taskTypes) {
            List<ModelPerformanceStore.TaskModelSummary> leaderboard = store.getLeaderboard(taskType, 30);
            if (leaderboard.isEmpty()) continue;

            if (!first) body.append("\n");
            first = false;

            body.append(renderer.bold(taskType)).append(" ").append(renderer.dim("(30d)")).append("\n");

            int rank = 1;
            for (ModelPerformanceStore.TaskModelSummary summary : leaderboard) {
                String model = shortenModel(summary.model());

                // Sparkline from recent scores
                double[] trend = store.getModelScoreTrend(summary.model(), 12);

                body.append("  ").append(rank).append(". ");
                body.append(padRight(model, 22));
                body.append(renderScoreBar(summary.avgScore(), 8));
                body.append(" ");

                // Sparkline trend
                if (trend.length > 1) {
                    body.append(ascii.coloredSparkline(trend));
                    body.append(" ");
                }

                body.append(renderer.cyan(String.format("%.1f", summary.avgScore())));
                body.append(renderer.dim("/" + summary.callCount()));

                // Escape rate indicator
                if (summary.escapeCount() > 0) {
                    body.append(" ").append(renderer.red(
                            String.format("%.0f%%esc", summary.escapeRate() * 100)));
                }

                if (rank == 1) {
                    body.append(" ").append(renderer.green("*"));
                }
                body.append("\n");

                rank++;
                if (rank > 5) break;
            }
        }

        if (body.isEmpty()) return null;
        return ascii.panel("Leaderboard", body.toString().stripTrailing()) + "\n";
    }

    private String renderJudgeDimensionsPanel(ModelPerformanceStore store) {
        Set<String> models = store.getModels();
        if (models.isEmpty()) return null;

        // Check if any model has judge dimension data
        boolean hasJudgeData = false;
        for (String model : models) {
            float[] dims = store.getModelJudgeDimensions(model, 30);
            if (dims[0] > 0 || dims[1] > 0) {
                hasJudgeData = true;
                break;
            }
        }
        if (!hasJudgeData) return null;

        StringBuilder body = new StringBuilder();
        body.append(padRight("", 24));
        body.append(padRight("correct", 10));
        body.append(padRight("complete", 10));
        body.append(padRight("design", 10));
        body.append(padRight("thinking", 10));
        body.append("\n");

        for (String model : models) {
            float[] dims = store.getModelJudgeDimensions(model, 30);
            if (dims[0] <= 0 && dims[1] <= 0) continue;

            body.append(padRight(shortenModel(model), 24));
            body.append(dims[0] > 0 ? ascii.scoreHeatmap(dims[0]) + "   " : padRight("-", 10));
            body.append(dims[1] > 0 ? ascii.scoreHeatmap(dims[1]) + "   " : padRight("-", 10));
            body.append(dims[2] > 0 ? ascii.scoreHeatmap(dims[2]) + "   " : padRight("-", 10));
            body.append(dims[3] > 0 ? ascii.scoreHeatmap(dims[3]) + "   " : padRight("-", 10));
            body.append("\n");
        }

        return ascii.panel("Judge Dimensions (30d)", body.toString().stripTrailing()) + "\n";
    }

    private String renderSwapHistory(List<ModelRouter.SwapEvent> swaps) {
        StringBuilder body = new StringBuilder();
        body.append("Total: ").append(swaps.size()).append("\n");
        ModelRouter.SwapEvent last = swaps.get(swaps.size() - 1);
        body.append("Last: ").append(last.agentName() != null ? last.agentName() : "primary");
        body.append(" -> ").append(renderer.cyan(shortenModel(last.toModel())));
        body.append(" (").append(last.reason()).append(")");
        return ascii.panel("Swaps", body.toString()) + "\n";
    }

    /**
     * Render inline swap notification during chat.
     */
    public String renderSwapNotification(String agentName, String fromModel, String toModel, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(renderer.yellow("  ! Model auto-swapped: "));
        if (agentName != null) {
            sb.append(agentName).append(" -> ");
        }
        sb.append(renderer.cyan(shortenModel(toModel))).append("\n");
        sb.append(renderer.dim("    Reason: " + reason)).append("\n");
        sb.append(renderer.dim("    Previous: " + shortenModel(fromModel)
                + "  New: " + shortenModel(toModel))).append("\n");
        return sb.toString();
    }

    /**
     * Render inline escape alert during chat.
     */
    public String renderEscapeAlert(String agentName, String escapeType, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(renderer.red("  ! Escape detected: "));
        if (agentName != null) {
            sb.append(agentName).append(" -> ");
        }
        sb.append(renderer.yellow(escapeType)).append("\n");
        if (detail != null && !detail.isEmpty()) {
            sb.append(renderer.dim("    " + detail)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Render performance section for /stats output.
     */
    public String renderStatsSection(ChatSessionMetrics metrics) {
        if (metrics.getJudgeCallCount() == 0 && metrics.getModelSwapCount() == 0
                && metrics.getEscapeCount() == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\nPerformance Harness\n");
        sb.append("  Judge calls: ").append(metrics.getJudgeCallCount());
        sb.append("  |  Swaps: ").append(metrics.getModelSwapCount());
        sb.append("  |  Escapes: ").append(metrics.getEscapeCount());
        sb.append("  |  Subagents: ").append(metrics.getSubagentsSpawned());
        sb.append("\n");
        if (metrics.getThinkingTokens() > 0) {
            sb.append("  Thinking tokens: ").append(metrics.getThinkingTokens()).append("\n");
        }

        // Escape breakdown
        Map<String, java.util.concurrent.atomic.AtomicInteger> escapesByType = metrics.getEscapesByType();
        if (!escapesByType.isEmpty()) {
            sb.append("  Escapes by type: ");
            boolean first = true;
            for (Map.Entry<String, java.util.concurrent.atomic.AtomicInteger> e : escapesByType.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append("=").append(e.getValue().get());
                first = false;
            }
            sb.append("\n");
        }

        Map<String, Double> avgScores = metrics.getAvgScoreByModel();
        if (!avgScores.isEmpty()) {
            for (Map.Entry<String, Double> entry : avgScores.entrySet()) {
                sb.append("  ").append(shortenModel(entry.getKey()));
                sb.append(": ").append(String.format("%.1f/5", entry.getValue()));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Render recommendations.
     */
    public String renderRecommendations(ModelPerformanceStore store) {
        Map<String, String> recs = store.getRecommendations();
        if (recs.isEmpty()) {
            return renderer.dim("  No performance data collected yet. Use the chat to build history.\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(renderer.bold("  Recommended Models")).append("\n");
        sb.append(renderer.dim("  " + "─".repeat(40))).append("\n");
        for (Map.Entry<String, String> entry : recs.entrySet()) {
            sb.append("  ").append(padRight(entry.getKey(), 20));
            sb.append(renderer.green(entry.getValue())).append("\n");
        }
        return sb.toString();
    }

    private String renderScoreBar(double score, int width) {
        int filled = (int) Math.round(score / 5.0 * width);
        filled = Math.max(0, Math.min(width, filled));
        String bar = "█".repeat(filled) + "░".repeat(width - filled);
        if (score >= 4.0) return renderer.green("[" + bar + "]");
        if (score >= 3.0) return renderer.yellow("[" + bar + "]");
        return renderer.red("[" + bar + "]");
    }

    private static String shortenModel(String model) {
        if (model == null) return "unknown";
        // Strip common date suffixes like -20250514
        return model.replaceAll("-\\d{8}$", "");
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}
