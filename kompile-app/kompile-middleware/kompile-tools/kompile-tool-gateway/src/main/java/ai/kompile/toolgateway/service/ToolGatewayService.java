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

package ai.kompile.toolgateway.service;

import ai.kompile.core.toolgateway.GatewayEvaluationListener;
import ai.kompile.toolgateway.model.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Core service that evaluates tool calls against the configured rules using an LLM.
 * <p>
 * Uses {@link ToolGatewayConfigService} for runtime configuration (enabled state,
 * fail-open policy, dry-run mode) and the existing kompile serving infrastructure
 * for LLM evaluation.
 * </p>
 */
public class ToolGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayService.class);
    private static final int MAX_RECENT_SCORES = 100;

    private final ToolGatewayConfigService configService;
    private final ToolGatewayRulesProvider rulesProvider;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final List<GatewayEvaluationListener> listeners = new ArrayList<>();
    private final ConcurrentLinkedDeque<GatewayJudgeScore> recentScores = new ConcurrentLinkedDeque<>();

    public ToolGatewayService(
            ToolGatewayConfigService configService,
            ToolGatewayRulesProvider rulesProvider,
            ChatModel chatModel) {
        this.configService = configService;
        this.rulesProvider = rulesProvider;
        this.chatModel = chatModel;
    }

    /**
     * Register listeners to be notified after each gateway evaluation.
     */
    public void addListener(GatewayEvaluationListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Get recent judge quality scores (most recent first).
     */
    public List<GatewayJudgeScore> getRecentScores() {
        return List.copyOf(recentScores);
    }

    /**
     * Evaluate a tool call against the gateway rules.
     * <p>
     * Returns ALLOW immediately if the gateway is disabled or no ChatModel is available.
     * </p>
     */
    public GatewayDecision evaluate(String toolName, Map<String, Object> args) {
        // Runtime enabled check — reads feature-flags-config.json
        if (!configService.isEnabled()) {
            return GatewayDecision.allow();
        }

        if (chatModel == null) {
            log.warn("Tool gateway enabled but no ChatModel available — allowing all tool calls. "
                    + "Start kompile-model-staging or configure a global LLM provider.");
            return GatewayDecision.allow();
        }

        ToolGatewayConfig config = configService.getConfig();

        List<ToolGatewayRule> matchingRules = rulesProvider.getMatchingRules(toolName);

        if (matchingRules.isEmpty()) {
            GatewayDecision decision = GatewayDecision.allow();
            logDecision(toolName, decision, config);
            return decision;
        }

        long startTime = System.currentTimeMillis();
        try {
            GatewayDecision decision = evaluateWithLlm(toolName, args, matchingRules);
            long latencyMs = System.currentTimeMillis() - startTime;

            logDecision(toolName, decision, config);
            fireListeners(toolName, decision, latencyMs);

            if (config.isDryRun() && decision.action() != GatewayAction.ALLOW) {
                log.info("[DRY-RUN] Gateway would have {} tool '{}' (rule: {}, reason: {}), "
                                + "but dry-run mode is active — allowing",
                        decision.action(), toolName, decision.matchedRuleId(), decision.reason());
                return GatewayDecision.allow(
                        "Dry-run: original decision was " + decision.action(),
                        decision.matchedRuleId());
            }

            // Judge scoring (self-evaluation of the gateway's decision quality)
            if (config.isJudgeScoringEnabled()) {
                evaluateGatewayQuality(toolName, args, decision, matchingRules);
            }

            return decision;

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.error("Tool gateway LLM evaluation failed for tool '{}': {}", toolName, e.getMessage());

            if (config.isFailOpen()) {
                log.warn("Fail-open policy: allowing tool '{}' despite evaluation failure", toolName);
                GatewayDecision decision = GatewayDecision.allow();
                fireListeners(toolName, decision, latencyMs);
                return decision;
            } else {
                GatewayDecision decision = GatewayDecision.block(
                        "Gateway evaluation failed and fail-closed policy is active: " + e.getMessage(),
                        null);
                fireListeners(toolName, decision, latencyMs);
                return decision;
            }
        }
    }

    private GatewayDecision evaluateWithLlm(
            String toolName,
            Map<String, Object> args,
            List<ToolGatewayRule> matchingRules) {

        List<Message> messages = new ArrayList<>();

        ToolGatewayRulesConfig rulesConfig = rulesProvider.getConfig();
        String systemPromptText = buildSystemPrompt(rulesConfig);
        messages.add(new SystemMessage(systemPromptText));

        String userPromptText = buildUserPrompt(toolName, args, matchingRules);
        messages.add(new UserMessage(userPromptText));

        Prompt prompt = new Prompt(messages);
        ChatResponse response = chatModel.call(prompt);

        String llmResponse = response.getResult().getOutput().getText();
        return parseLlmResponse(llmResponse, matchingRules);
    }

    /**
     * Score the quality of the gateway's own evaluation using the judge prompt.
     */
    private void evaluateGatewayQuality(String toolName, Map<String, Object> args,
                                         GatewayDecision decision, List<ToolGatewayRule> matchingRules) {
        try {
            String judgePrompt = buildJudgePrompt(toolName, args, decision, matchingRules);

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(JUDGE_SYSTEM_PROMPT));
            messages.add(new UserMessage(judgePrompt));

            ChatResponse response = chatModel.call(new Prompt(messages));
            String judgeResponse = response.getResult().getOutput().getText();

            GatewayJudgeScore score = parseJudgeResponse(judgeResponse);
            recentScores.addFirst(score);
            while (recentScores.size() > MAX_RECENT_SCORES) {
                recentScores.removeLast();
            }

            log.debug("Gateway judge score for tool '{}': correctness={}, completeness={}, reasoning='{}'",
                    toolName, score.correctness(), score.completeness(), score.reasoning());

        } catch (Exception e) {
            log.debug("Gateway judge scoring failed for tool '{}': {}", toolName, e.getMessage());
            GatewayJudgeScore errorScore = new GatewayJudgeScore(-1, -1, null, true, e.getMessage());
            recentScores.addFirst(errorScore);
            while (recentScores.size() > MAX_RECENT_SCORES) {
                recentScores.removeLast();
            }
        }
    }

    private static final String JUDGE_SYSTEM_PROMPT = """
            You are an impartial evaluator for tool gateway decisions.
            Rate the gateway decision on each dimension (1-5 scale):

            correctness:
              5 = The decision accurately reflects the rules and tool call
              3 = Mostly correct but missed some nuance
              1 = Wrong decision given the rules

            completeness:
              5 = All applicable rules were considered, reasoning is thorough
              3 = Main rule considered but missed edge cases
              1 = Failed to consider relevant rules

            Respond ONLY with valid JSON:
            {"correctness": <1-5>, "completeness": <1-5>, "reasoning": "<one sentence>"}
            """;

    private String buildJudgePrompt(String toolName, Map<String, Object> args,
                                     GatewayDecision decision, List<ToolGatewayRule> matchingRules) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool: ").append(toolName).append("\n");
        try {
            sb.append("Args: ").append(objectMapper.writeValueAsString(args)).append("\n");
        } catch (JsonProcessingException e) {
            sb.append("Args: ").append(args).append("\n");
        }
        sb.append("Decision: ").append(decision.action()).append("\n");
        sb.append("Reason: ").append(decision.reason()).append("\n");
        sb.append("Matched rule: ").append(decision.matchedRuleId()).append("\n\n");
        sb.append("Rules that were evaluated:\n");
        for (ToolGatewayRule rule : matchingRules) {
            sb.append("- ").append(rule.getId()).append(": ").append(rule.getCondition())
                    .append(" → ").append(rule.getAction()).append("\n");
        }
        return sb.toString();
    }

    private GatewayJudgeScore parseJudgeResponse(String response) {
        try {
            String cleaned = response.strip();
            if (cleaned.startsWith("```")) {
                int firstNewline = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstNewline > 0 && lastFence > firstNewline) {
                    cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
                }
            }

            JsonNode root = objectMapper.readTree(cleaned);
            float correctness = root.path("correctness").floatValue();
            float completeness = root.path("completeness").floatValue();
            String reasoning = root.path("reasoning").asText("No reasoning");
            return new GatewayJudgeScore(correctness, completeness, reasoning, false, null);
        } catch (Exception e) {
            return new GatewayJudgeScore(-1, -1, null, true, "Parse error: " + e.getMessage());
        }
    }

    private void fireListeners(String toolName, GatewayDecision decision, long latencyMs) {
        for (GatewayEvaluationListener listener : listeners) {
            try {
                listener.onEvaluation(toolName, decision.action().name(),
                        decision.reason(), decision.matchedRuleId(), latencyMs);
            } catch (Exception e) {
                log.warn("Gateway evaluation listener failed: {}", e.getMessage());
            }
        }
    }

    private String buildSystemPrompt(ToolGatewayRulesConfig config) {
        StringBuilder sb = new StringBuilder();

        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isBlank()) {
            sb.append(config.getSystemPrompt()).append("\n\n");
        }

        sb.append("""
                You are a tool gateway evaluator. Your job is to examine an incoming tool call \
                and determine whether it should be ALLOWED, REWRITTEN, or BLOCKED based on the \
                provided rules.

                You will receive:
                1. A set of rules, each with a condition, action, and optional rewrite instructions
                2. The tool name and its arguments

                For each rule, evaluate whether the condition is met by the tool call arguments.
                If a rule's condition is met, apply its action.
                If multiple rules match, apply the highest-priority one.
                If no rules match, the default action is ALLOW.

                Respond with ONLY a JSON object in this exact format:
                {
                  "action": "ALLOW" | "REWRITE" | "BLOCK",
                  "matchedRuleId": "<id of the rule that triggered, or null>",
                  "reason": "<brief explanation>",
                  "rewrittenArgs": { ... } // ONLY if action is REWRITE; must be a complete replacement args map
                }

                Do not include any text outside the JSON object.""");

        return sb.toString();
    }

    private String buildUserPrompt(String toolName, Map<String, Object> args, List<ToolGatewayRule> rules) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Tool Call\n");
        sb.append("Tool name: ").append(toolName).append("\n");
        sb.append("Arguments:\n```json\n");
        try {
            sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(args));
        } catch (JsonProcessingException e) {
            sb.append(args.toString());
        }
        sb.append("\n```\n\n");

        sb.append("## Applicable Rules (ordered by priority, highest first)\n\n");
        for (int i = 0; i < rules.size(); i++) {
            ToolGatewayRule rule = rules.get(i);
            sb.append("### Rule ").append(i + 1).append(": ").append(rule.getId()).append("\n");
            sb.append("- **Description**: ").append(rule.getDescription()).append("\n");
            sb.append("- **Condition**: ").append(rule.getCondition()).append("\n");
            sb.append("- **Action**: ").append(rule.getAction()).append("\n");
            sb.append("- **Priority**: ").append(rule.getPriority()).append("\n");

            if (rule.getAction() == GatewayAction.BLOCK && rule.getBlockMessage() != null) {
                sb.append("- **Block message**: ").append(rule.getBlockMessage()).append("\n");
            }
            if (rule.getAction() == GatewayAction.REWRITE && rule.getRewriteInstructions() != null) {
                sb.append("- **Rewrite instructions**: ").append(rule.getRewriteInstructions()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Evaluate the tool call against these rules and respond with the JSON decision.");
        return sb.toString();
    }

    private GatewayDecision parseLlmResponse(String llmResponse, List<ToolGatewayRule> matchingRules) {
        try {
            String cleaned = llmResponse.strip();
            if (cleaned.startsWith("```")) {
                int firstNewline = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstNewline > 0 && lastFence > firstNewline) {
                    cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
                }
            }

            JsonNode root = objectMapper.readTree(cleaned);

            String actionStr = root.path("action").asText("ALLOW");
            GatewayAction action;
            try {
                action = GatewayAction.valueOf(actionStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("LLM returned unknown action '{}', defaulting to ALLOW", actionStr);
                action = GatewayAction.ALLOW;
            }

            String reason = root.path("reason").asText("No reason provided");
            String matchedRuleId = root.path("matchedRuleId").isNull()
                    ? null : root.path("matchedRuleId").asText(null);

            Map<String, Object> rewrittenArgs = null;
            if (action == GatewayAction.REWRITE && root.has("rewrittenArgs") && !root.path("rewrittenArgs").isNull()) {
                rewrittenArgs = objectMapper.convertValue(
                        root.get("rewrittenArgs"),
                        new TypeReference<Map<String, Object>>() {});
            }

            return new GatewayDecision(action, reason, rewrittenArgs, matchedRuleId);

        } catch (Exception e) {
            log.error("Failed to parse LLM gateway response: '{}' — error: {}", llmResponse, e.getMessage());
            throw new RuntimeException("Unparseable LLM gateway response", e);
        }
    }

    private void logDecision(String toolName, GatewayDecision decision, ToolGatewayConfig config) {
        if (decision.action() == GatewayAction.ALLOW) {
            if (config.isVerboseLogging()) {
                log.info("Tool gateway ALLOW: tool='{}', rule={}, reason='{}'",
                        toolName, decision.matchedRuleId(), decision.reason());
            } else {
                log.debug("Tool gateway ALLOW: tool='{}', rule={}", toolName, decision.matchedRuleId());
            }
        } else {
            log.info("Tool gateway {}: tool='{}', rule={}, reason='{}'",
                    decision.action(), toolName, decision.matchedRuleId(), decision.reason());
        }
    }
}
