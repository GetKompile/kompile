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

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.harness.*;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool that exposes the Agent Performance Harness functionality over
 * the MCP stdio protocol. Reads from the persisted performance data store
 * at ~/.kompile/perf-data.json — no live harness instance needed.
 *
 * Actions:
 *   - report:      Cross-session model performance leaderboard
 *   - recommend:   Best model per task type
 *   - record:      Record a new performance observation (for external agents)
 *   - config:      View or modify harness configuration
 *   - reset:       Clear performance data
 */
public class StdioHarnessTool {

    private final ObjectMapper objectMapper;
    private final McpSessionTracker sessionTracker;

    public StdioHarnessTool(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public StdioHarnessTool(ObjectMapper objectMapper, McpSessionTracker sessionTracker) {
        this.objectMapper = objectMapper;
        this.sessionTracker = sessionTracker;
        if (!objectMapper.getRegisteredModuleIds().contains("jackson-datatype-jsr310")) {
            objectMapper.registerModule(new JavaTimeModule());
        }
    }

    public String id() { return "performance_harness"; }

    public String description() {
        return "Agent performance evaluation and model comparison. "
                + "Actions: report (leaderboard), recommend (best model for task), "
                + "record (log observation with escape detection), config (view/update settings), "
                + "stats (session metrics), reset (clear data).";
    }

    public JsonNode parameterSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        var action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "The action to perform");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("report");
        actionEnum.add("recommend");
        actionEnum.add("record");
        actionEnum.add("config");
        actionEnum.add("stats");
        actionEnum.add("reset");

        // Report/recommend params
        var taskType = props.putObject("task_type");
        taskType.put("type", "string");
        taskType.put("description", "Filter by task type (for report/recommend). Common types: code-review, planning, research, exploration, general");

        var days = props.putObject("days");
        days.put("type", "integer");
        days.put("description", "Number of days to include in report (default: 30)");

        var provider = props.putObject("provider");
        provider.put("type", "string");
        provider.put("description", "Filter by provider (for recommend). E.g.: anthropic, openai, gemini");

        // Record params
        var model = props.putObject("model");
        model.put("type", "string");
        model.put("description", "Model name (for record/reset)");

        var agentName = props.putObject("agent_name");
        agentName.put("type", "string");
        agentName.put("description", "Agent name that produced the output (for record)");

        var qualityScore = props.putObject("quality_score");
        qualityScore.put("type", "number");
        qualityScore.put("description", "Quality score 1.0-5.0 (for record, optional if agent_output provided)");

        var agentOutput = props.putObject("agent_output");
        agentOutput.put("type", "string");
        agentOutput.put("description", "Full agent output text (for record). Enables server-side escape detection and quality evaluation. Recommended over quality_score for automatic evaluation.");

        var reasoning = props.putObject("reasoning");
        reasoning.put("type", "string");
        reasoning.put("description", "Evaluation reasoning (for record)");

        var latencyMs = props.putObject("latency_ms");
        latencyMs.put("type", "integer");
        latencyMs.put("description", "Response latency in milliseconds (for record)");

        // Record: multi-signal fields
        var escapeType = props.putObject("escape_type");
        escapeType.put("type", "string");
        escapeType.put("description", "Escape type if detected (for record). E.g.: EXPLICIT_REFUSAL, EMPTY_OUTPUT, TOOL_LOOP");

        var correctness = props.putObject("correctness");
        correctness.put("type", "number");
        correctness.put("description", "Correctness score 1-5 (for record, optional)");

        var completeness = props.putObject("completeness");
        completeness.put("type", "number");
        completeness.put("description", "Completeness score 1-5 (for record, optional)");

        var designQuality = props.putObject("design_quality");
        designQuality.put("type", "number");
        designQuality.put("description", "Design quality score 1-5 or null (for record, optional)");

        var subagentsSpawned = props.putObject("subagents_spawned");
        subagentsSpawned.put("type", "integer");
        subagentsSpawned.put("description", "Number of subagents spawned (for record, optional)");

        var hitMaxSteps = props.putObject("hit_max_steps");
        hitMaxSteps.put("type", "boolean");
        hitMaxSteps.put("description", "Whether agent hit max step limit (for record, optional)");

        var toolCalls = props.putObject("tool_calls");
        toolCalls.put("type", "integer");
        toolCalls.put("description", "Total tool calls made during the turn (for record, optional)");

        var toolErrors = props.putObject("tool_errors");
        toolErrors.put("type", "integer");
        toolErrors.put("description", "Number of tool call errors (for record, optional)");

        // Config params
        var judgeEnabled = props.putObject("judge_enabled");
        judgeEnabled.put("type", "boolean");
        judgeEnabled.put("description", "Enable/disable judge LLM (for config)");

        var threshold = props.putObject("threshold");
        threshold.put("type", "number");
        threshold.put("description", "Quality swap threshold 1.0-5.0 (for config)");

        var autoSwap = props.putObject("auto_swap");
        autoSwap.put("type", "boolean");
        autoSwap.put("description", "Enable/disable auto model swap (for config)");

        var judgeModel = props.putObject("judge_model");
        judgeModel.put("type", "string");
        judgeModel.put("description", "Model to use as judge (for config). Empty string to use default.");

        var judgeProvider = props.putObject("judge_provider");
        judgeProvider.put("type", "string");
        judgeProvider.put("description", "Provider for judge LLM (for config). E.g.: anthropic, openai, gemini. Empty = same as chat provider.");

        var judgeApiKey = props.putObject("judge_api_key");
        judgeApiKey.put("type", "string");
        judgeApiKey.put("description", "API key for judge provider (for config). Falls back to env var (ANTHROPIC_API_KEY, OPENAI_API_KEY, etc.).");

        schema.putArray("required").add("action");
        return schema;
    }

    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments) {
        String action = (String) arguments.getOrDefault("action", "report");

        try {
            return switch (action) {
                case "report" -> handleReport(arguments);
                case "recommend" -> handleRecommend(arguments);
                case "record" -> handleRecord(arguments);
                case "config" -> handleConfig(arguments);
                case "stats" -> handleStats();
                case "reset" -> handleReset(arguments);
                default -> ToolResult.error("Unknown action: " + action
                        + ". Valid actions: report, recommend, record, config, stats, reset");
            };
        } catch (Exception e) {
            return ToolResult.error("Harness error: " + e.getMessage());
        }
    }

    private ToolResult handleReport(Map<String, Object> arguments) {
        String taskType = (String) arguments.get("task_type");
        int days = arguments.containsKey("days")
                ? ((Number) arguments.get("days")).intValue() : 30;

        ModelPerformanceStore store = getActiveStore();

        if (store.size() == 0) {
            return ToolResult.success("performance_harness",
                    "No performance data collected yet. Use the chat to build history, "
                            + "or use action='record' to add observations.");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("totalRecords", store.size());
        root.put("days", days);

        ObjectNode taskSummaries = objectMapper.createObjectNode();
        Set<String> types = store.getTaskTypes();
        for (String type : types) {
            if (taskType != null && !taskType.equals(type)) continue;
            List<ModelPerformanceStore.TaskModelSummary> leaderboard = store.getLeaderboard(type, days);
            ArrayNode entries = objectMapper.createArrayNode();
            for (ModelPerformanceStore.TaskModelSummary s : leaderboard) {
                ObjectNode entry = objectMapper.createObjectNode();
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
        ObjectNode recommendations = objectMapper.createObjectNode();
        recs.forEach(recommendations::put);
        root.set("recommendations", recommendations);

        try {
            return ToolResult.success("performance_harness",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            return ToolResult.error("Serialization error: " + e.getMessage());
        }
    }

    private ToolResult handleRecommend(Map<String, Object> arguments) {
        String taskType = (String) arguments.get("task_type");
        String provider = (String) arguments.get("provider");

        ModelPerformanceStore store = getActiveStore();

        if (taskType != null) {
            String best = store.getBestModelForTask(taskType, provider);
            if (best != null) {
                ObjectNode result = objectMapper.createObjectNode();
                result.put("taskType", taskType);
                result.put("recommendedModel", best);
                if (provider != null) result.put("provider", provider);
                return ToolResult.success("performance_harness", result.toString());
            }
            return ToolResult.success("performance_harness",
                    "No performance data for task type '" + taskType + "'"
                            + (provider != null ? " with provider '" + provider + "'" : "")
                            + ". Record some observations first.");
        }

        Map<String, String> recs = store.getRecommendations();
        if (recs.isEmpty()) {
            return ToolResult.success("performance_harness",
                    "No performance data collected yet.");
        }

        ObjectNode result = objectMapper.createObjectNode();
        recs.forEach(result::put);
        return ToolResult.success("performance_harness", result.toString());
    }

    private ToolResult handleRecord(Map<String, Object> arguments) {
        String model = (String) arguments.get("model");
        String agentName = (String) arguments.getOrDefault("agent_name", "unknown");
        String taskType = (String) arguments.getOrDefault("task_type", "general");
        String provider = (String) arguments.get("provider");
        String reasoning = (String) arguments.getOrDefault("reasoning", "");
        String agentOutput = (String) arguments.get("agent_output");

        if (model == null || model.isBlank()) {
            return ToolResult.error("'model' is required for action 'record'");
        }

        // quality_score is now optional — server-side evaluation can compute it
        Number scoreNum = (Number) arguments.get("quality_score");
        float callerScore = scoreNum != null ? scoreNum.floatValue() : 0f;
        if (callerScore != 0 && (callerScore < 1 || callerScore > 5)) {
            return ToolResult.error("quality_score must be between 1.0 and 5.0");
        }

        long latency = 0;
        if (arguments.containsKey("latency_ms")) {
            latency = ((Number) arguments.get("latency_ms")).longValue();
        }

        // Multi-signal optional fields from caller
        String callerEscapeType = (String) arguments.get("escape_type");
        float corr = arguments.containsKey("correctness")
                ? ((Number) arguments.get("correctness")).floatValue() : -1;
        float comp = arguments.containsKey("completeness")
                ? ((Number) arguments.get("completeness")).floatValue() : -1;
        float dq = arguments.containsKey("design_quality")
                ? ((Number) arguments.get("design_quality")).floatValue() : -1;
        int subagents = arguments.containsKey("subagents_spawned")
                ? ((Number) arguments.get("subagents_spawned")).intValue() : 0;
        boolean hitMax = arguments.containsKey("hit_max_steps")
                && Boolean.TRUE.equals(arguments.get("hit_max_steps"));
        int toolCalls = arguments.containsKey("tool_calls")
                ? ((Number) arguments.get("tool_calls")).intValue() : 0;
        int toolErrors = arguments.containsKey("tool_errors")
                ? ((Number) arguments.get("tool_errors")).intValue() : 0;

        // Build TurnMetrics for server-side evaluation
        String sessionId = sessionTracker != null
                ? sessionTracker.getMetrics().getSessionId()
                : "mcp-" + System.currentTimeMillis();

        TurnMetrics turnMetrics = TurnMetrics.builder()
                .sessionId(sessionId)
                .agentName(agentName)
                .model(model)
                .provider(provider)
                .latencyMs(latency)
                .subagentsSpawned(subagents)
                .hitMaxSteps(hitMax)
                .toolCallsTotal(toolCalls)
                .toolCallErrors(toolErrors)
                .agentOutput(agentOutput != null ? agentOutput : "")
                .build();

        // Server-side evaluation: escape detection + composite scoring
        float compositeScore = callerScore;
        EscapeDetector.EscapeResult serverEscape = null;
        String detectedTaskType = taskType;

        if (sessionTracker != null && agentOutput != null && !agentOutput.isBlank()) {
            detectedTaskType = JudgeLlmEvaluator.detectTaskType(agentName, agentOutput);
            if ("general".equals(taskType)) taskType = detectedTaskType;

            McpSessionTracker.EvaluationResult eval =
                    sessionTracker.evaluate(turnMetrics, taskType, callerScore);
            compositeScore = eval.compositeScore();
            serverEscape = eval.escapeResult();
        } else if (callerScore <= 0) {
            // No output and no caller score — can't evaluate
            return ToolResult.error("Either 'quality_score' or 'agent_output' is required. "
                    + "Provide agent_output for server-side evaluation, or quality_score for direct recording.");
        }

        // Determine escape state: prefer server-side detection, fall back to caller-reported
        boolean hadEscape = (serverEscape != null && serverEscape.hasEscape())
                || (callerEscapeType != null && !callerEscapeType.isBlank());
        String finalEscapeType = serverEscape != null && serverEscape.hasEscape()
                ? serverEscape.type().name()
                : callerEscapeType;
        String escapeDetail = serverEscape != null && serverEscape.hasEscape()
                ? serverEscape.detail() : null;

        // Build the persisted record
        ModelPerformanceRecord.Builder recBuilder = ModelPerformanceRecord.builder()
                .sessionId(sessionId)
                .agentName(agentName)
                .taskType(taskType)
                .provider(provider)
                .model(model)
                .latencyMs(latency)
                .qualityScore(compositeScore)
                .judgeReasoning(reasoning)
                .judgeWasCalled(callerScore > 0)
                .subagentsSpawned(subagents)
                .hitMaxSteps(hitMax)
                .toolCallErrors(toolErrors)
                .hadEscape(hadEscape)
                .timestamp(Instant.now());

        if (hadEscape && finalEscapeType != null) {
            recBuilder.escapeType(finalEscapeType);
        }
        if (escapeDetail != null) {
            recBuilder.escapeDetail(escapeDetail);
        }
        if (corr > 0) recBuilder.judgeCorrectness(corr);
        if (comp > 0) recBuilder.judgeCompleteness(comp);
        if (dq > 0) recBuilder.judgeDesignQuality(dq);

        ModelPerformanceRecord rec = recBuilder.build();

        // Persist — store auto-flushes periodically, and flushes
        // immediately here for the standalone (no-tracker) path.
        if (sessionTracker != null) {
            sessionTracker.recordToStore(rec);
        } else {
            HarnessConfig config = HarnessConfig.load(objectMapper);
            ModelPerformanceStore store = loadStore(config);
            store.record(rec);
            store.flush();
        }

        // Build response with evaluation details
        StringBuilder response = new StringBuilder();
        response.append("Recorded: ").append(model)
                .append(" scored ").append(String.format("%.1f", compositeScore)).append("/5")
                .append(" for ").append(taskType)
                .append(" (").append(agentName).append(").");
        if (hadEscape) {
            response.append(" Escape detected: ").append(finalEscapeType).append(".");
        }
        if (callerScore > 0 && Math.abs(compositeScore - callerScore) > 0.5f) {
            response.append(" Server-adjusted from caller score ")
                    .append(String.format("%.1f", callerScore)).append(".");
        }

        return ToolResult.success("performance_harness", response.toString());
    }

    private ToolResult handleConfig(Map<String, Object> arguments) {
        HarnessConfig config = HarnessConfig.load(objectMapper);
        boolean modified = false;

        if (arguments.containsKey("judge_enabled")) {
            config.setJudgeEnabled((Boolean) arguments.get("judge_enabled"));
            modified = true;
        }
        if (arguments.containsKey("threshold")) {
            config.setSwapThresholdScore(((Number) arguments.get("threshold")).floatValue());
            modified = true;
        }
        if (arguments.containsKey("auto_swap")) {
            config.setAutoSwapEnabled((Boolean) arguments.get("auto_swap"));
            modified = true;
        }
        if (arguments.containsKey("judge_model")) {
            String jm = (String) arguments.get("judge_model");
            config.setJudgeModel(jm.isEmpty() ? null : jm);
            modified = true;
        }
        if (arguments.containsKey("judge_provider")) {
            String jp = (String) arguments.get("judge_provider");
            config.setJudgeProvider(jp.isEmpty() ? null : jp);
            modified = true;
        }
        if (arguments.containsKey("judge_api_key")) {
            String jk = (String) arguments.get("judge_api_key");
            config.setJudgeApiKey(jk.isEmpty() ? null : jk);
            modified = true;
        }
        if (arguments.containsKey("judge_mode")) {
            String jm = (String) arguments.get("judge_mode");
            config.setJudgeMode(jm.isEmpty() ? null : jm);
            modified = true;
        }
        if (arguments.containsKey("judge_local_model")) {
            String jlm = (String) arguments.get("judge_local_model");
            config.setJudgeLocalModel(jlm.isEmpty() ? null : jlm);
            modified = true;
        }
        if (arguments.containsKey("judge_server_type")) {
            String jst = (String) arguments.get("judge_server_type");
            config.setJudgeServerType(jst.isEmpty() ? null : jst);
            modified = true;
        }

        if (modified) {
            config.save(objectMapper);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("enabled", config.isEnabled());
        result.put("judgeEnabled", config.isJudgeEnabled());
        result.put("judgeMode", config.getJudgeMode() != null ? config.getJudgeMode() : "auto");
        result.put("judgeModel", config.getJudgeModel() != null ? config.getJudgeModel() : "(default)");
        result.put("judgeProvider", config.getJudgeProvider() != null ? config.getJudgeProvider() : "(same as chat)");
        result.put("judgeApiKeySet", config.getJudgeApiKey() != null && !config.getJudgeApiKey().isBlank());
        if (config.getJudgeBaseUrl() != null) {
            result.put("judgeBaseUrl", config.getJudgeBaseUrl());
        }
        if (config.getJudgeLocalModel() != null) {
            result.put("judgeLocalModel", config.getJudgeLocalModel());
        }
        if (config.getJudgeServerType() != null) {
            result.put("judgeServerType", config.getJudgeServerType());
        }
        result.put("swapThreshold", config.getSwapThresholdScore());
        result.put("rollingWindowSize", config.getRollingWindowSize());
        result.put("autoSwapEnabled", config.isAutoSwapEnabled());
        result.put("rateLimitFallbackEnabled", config.isRateLimitFallbackEnabled());
        result.put("verboseLogging", config.isVerboseLogging());
        result.put("escapeDetectionEnabled", config.isEscapeDetectionEnabled());
        result.put("thinkingAnalysisEnabled", config.isThinkingAnalysisEnabled());

        ObjectNode weights = objectMapper.createObjectNode();
        weights.put("escape", config.getEscapeWeight());
        weights.put("judge", config.getJudgeWeight());
        weights.put("efficiency", config.getEfficiencyWeight());
        weights.put("thinking", config.getThinkingWeight());
        result.set("compositeWeights", weights);

        if (modified) {
            result.put("status", "updated");
        }

        try {
            return ToolResult.success("performance_harness",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            return ToolResult.error("Serialization error: " + e.getMessage());
        }
    }

    private ToolResult handleStats() {
        if (sessionTracker == null) {
            return ToolResult.success("performance_harness",
                    "No active MCP session tracker. Stats are only available during an MCP stdio session.");
        }

        var metrics = sessionTracker.getMetrics();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("sessionId", metrics.getSessionId());
        result.put("totalToolCalls", metrics.getTotalToolCalls());
        result.put("totalToolErrors", metrics.getTotalToolErrors());
        result.put("escapes", metrics.getEscapeCount());
        result.put("subagentsSpawned", metrics.getSubagentsSpawned());
        result.put("judgeCalls", metrics.getJudgeCallCount());
        result.put("modelSwaps", metrics.getModelSwapCount());
        if (metrics.getThinkingTokens() > 0) {
            result.put("thinkingTokens", metrics.getThinkingTokens());
        }

        // Tool breakdown
        ObjectNode toolBreakdown = objectMapper.createObjectNode();
        metrics.getToolCallCounts().forEach((k, v) -> toolBreakdown.put(k, v.get()));
        result.set("toolBreakdown", toolBreakdown);

        // Escape breakdown
        var escapesByType = metrics.getEscapesByType();
        if (!escapesByType.isEmpty()) {
            ObjectNode escapes = objectMapper.createObjectNode();
            escapesByType.forEach((type, count) -> escapes.put(type, count.get()));
            result.set("escapesByType", escapes);
        }

        // Scores
        Map<String, Double> avgScores = metrics.getAvgScoreByModel();
        if (!avgScores.isEmpty()) {
            ObjectNode scores = objectMapper.createObjectNode();
            avgScores.forEach((m, s) -> scores.put(m, Math.round(s * 10.0) / 10.0));
            result.set("avgCompositeScoreByModel", scores);
        }

        try {
            return ToolResult.success("performance_harness",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            return ToolResult.error("Serialization error: " + e.getMessage());
        }
    }

    private ToolResult handleReset(Map<String, Object> arguments) {
        String model = (String) arguments.get("model");

        HarnessConfig config = HarnessConfig.load(objectMapper);
        ModelPerformanceStore store = loadStore(config);

        int before = store.size();
        if (before == 0) {
            return ToolResult.success("performance_harness", "No performance data to clear.");
        }

        store.clear(model);
        store.saveToFile();

        int after = store.size();
        String target = model != null ? "model '" + model + "'" : "all models";
        return ToolResult.success("performance_harness",
                "Cleared " + (before - after) + " records for " + target
                        + ". " + after + " records remaining.");
    }

    /**
     * Get the active store: prefer the session tracker's in-memory store (includes
     * current session records) over loading from disk.
     */
    private ModelPerformanceStore getActiveStore() {
        if (sessionTracker != null) {
            return sessionTracker.getStore();
        }
        HarnessConfig config = HarnessConfig.load(objectMapper);
        return loadStore(config);
    }

    private ModelPerformanceStore loadStore(HarnessConfig config) {
        ModelPerformanceStore store = new ModelPerformanceStore(
                config.getMaxRecordAge(), config.getMaxRecords());
        store.loadFromFile();
        return store;
    }
}
