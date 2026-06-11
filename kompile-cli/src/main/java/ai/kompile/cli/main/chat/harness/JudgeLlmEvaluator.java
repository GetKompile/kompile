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

import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Layer 3: Multi-dimensional judge LLM evaluation.
 * <p>
 * Delegates text generation to a {@link JudgeBackend}, which can be:
 * <ul>
 *   <li><b>remote</b> — HTTP call to Anthropic, OpenAI, ollama, etc.</li>
 *   <li><b>local</b> — in-process SameDiff model (GGUF via TextGenerator)</li>
 *   <li><b>auto-server</b> — dynamically start ollama/kompile-app, then HTTP</li>
 * </ul>
 * <p>
 * This class owns the judge prompt construction and response parsing;
 * the backend only handles raw text generation.
 */
public class JudgeLlmEvaluator {

    static final String JUDGE_SYSTEM_PROMPT = """
            You are an impartial quality evaluator for LLM agent responses.
            Rate the response on each applicable dimension (1-5 scale):

            correctness (always required):
              5 = Fully accurate, no factual/technical errors
              3 = Mostly correct, some inaccuracies
              1 = Fundamentally wrong or hallucinated

            completeness (always required):
              5 = Addressed every aspect of the task
              3 = Covered main points, missed details
              1 = Barely started or completely off-topic

            design_quality (only for code-review or planning tasks, null otherwise):
              5 = Excellent architecture, clean separation, idiomatic
              3 = Workable but has structural issues
              1 = Poor design, anti-patterns, will cause problems

            thinking_coherence (only when thinking text is provided, null otherwise):
              5 = Clear, logical, no contradictions
              3 = Some confusion but arrives at right answer
              1 = Contradictory, circular, or incoherent reasoning

            Respond ONLY with valid JSON on a single line:
            {"correctness": <1-5>, "completeness": <1-5>, "design_quality": <1-5 or null>, "thinking_coherence": <1-5 or null>, "reasoning": "<one sentence>"}

            Do not add any text before or after the JSON.
            """;

    private static final int MAX_OUTPUT_FOR_JUDGE = 6_000;
    private static final int MAX_THINKING_FOR_JUDGE = 2_000;
    private static final Set<String> DESIGN_TASK_TYPES = Set.of("code-review", "planning", "incident-response");

    private final JudgeBackend backend;
    private final ObjectMapper objectMapper;
    private final BackgroundProcessManager processManager;
    private final String processId;

    /**
     * Primary constructor: uses the main chat's LLM client as fallback.
     * The {@link JudgeBackendFactory} selects the right backend based on config.
     */
    public JudgeLlmEvaluator(DirectLlmClient llmClient, ObjectMapper objectMapper,
                              HarnessConfig config) {
        this(llmClient, objectMapper, config, null);
    }

    public JudgeLlmEvaluator(DirectLlmClient llmClient, ObjectMapper objectMapper,
                              HarnessConfig config,
                              BackgroundProcessManager processManager) {
        this.objectMapper = objectMapper;
        this.backend = JudgeBackendFactory.create(llmClient, config, objectMapper);
        this.processManager = processManager;
        this.processId = registerJudgeProcess(config);
    }

    /**
     * Standalone constructor for MCP/headless mode: no fallback client.
     */
    public JudgeLlmEvaluator(ObjectMapper objectMapper, HarnessConfig config) {
        this(objectMapper, config, null);
    }

    public JudgeLlmEvaluator(ObjectMapper objectMapper, HarnessConfig config,
                              BackgroundProcessManager processManager) {
        this.objectMapper = objectMapper;
        this.backend = JudgeBackendFactory.create(config, objectMapper);
        this.processManager = processManager;
        this.processId = registerJudgeProcess(config);
    }

    /**
     * Constructor with an explicit backend (for testing or custom wiring).
     */
    public JudgeLlmEvaluator(JudgeBackend backend, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.backend = backend;
        this.processManager = null;
        this.processId = null;
    }

    /**
     * Whether this evaluator can actually make judge calls.
     */
    public boolean isAvailable() {
        return backend != null && backend.isAvailable();
    }

    /**
     * Human-readable description of the active backend.
     */
    public String describeBackend() {
        return backend != null ? backend.describe() : "none";
    }

    /**
     * Clean up backend resources.
     */
    public void close() {
        if (backend != null) backend.close();
        if (processManager != null && processId != null) {
            processManager.complete(processId);
        }
    }

    private String registerJudgeProcess(HarnessConfig config) {
        if (processManager == null) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("backend", describeBackend());
        metadata.put("mode", safe(config != null ? config.getJudgeMode() : null, "auto"));
        metadata.put("provider", safe(config != null ? config.getJudgeProvider() : null, "auto"));
        metadata.put("model", safe(config != null ? config.getJudgeModel() : null, "auto"));
        BackgroundProcessManager.ProcessEntry entry = processManager.registerVirtual(
                BackgroundProcessManager.ProcessKind.JUDGE,
                "judge " + describeBackend(),
                "Quality judge watcher",
                metadata);
        System.out.println("[judge] watcher started: " + entry.getId()
                + " backend=" + describeBackend());
        return entry.getId();
    }

    private static String safe(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * Evaluate agent output quality across multiple dimensions.
     */
    public JudgeDimensions evaluate(TurnMetrics metrics, String taskType, String agentName) {
        String agentOutput = metrics.getAgentOutput();
        if (agentOutput == null || agentOutput.isBlank()) {
            return JudgeDimensions.error("Agent returned empty output");
        }

        if (backend == null || !backend.isAvailable()) {
            return JudgeDimensions.error("No judge backend available. "
                    + "Configure judge_mode, judge_provider, or judge_local_model in harness config.");
        }

        String userPrompt = buildUserPrompt(metrics, taskType, agentName);

        try {
            String responseText = backend.generate(userPrompt, JUDGE_SYSTEM_PROMPT);

            if (responseText == null || responseText.isBlank()) {
                return JudgeDimensions.error("Judge returned empty response");
            }

            return parseJudgeResponse(responseText, taskType, metrics.hasThinking());

        } catch (Exception e) {
            if (processManager != null && processId != null) {
                processManager.fail(processId);
            }
            return JudgeDimensions.error("Judge call failed: " + e.getMessage());
        }
    }

    /**
     * Detect the task type from agent name and output content.
     */
    public static String detectTaskType(String agentName, String agentOutput) {
        if (agentName != null) {
            switch (agentName) {
                case "code-reviewer": return "code-review";
                case "architect": return "planning";
                case "planner": return "planning";
                case "researcher": return "research";
                case "explore-quick":
                case "explore-deep":
                case "explorer": return "exploration";
                case "code-indexer": return "indexing";
                case "incident-responder":
                case "incident-response":
                case "iras": return "incident-response";
            }
        }

        if (agentOutput != null) {
            String lower = agentOutput.toLowerCase();
            if (lower.contains("git diff") || lower.contains("pull request")
                    || lower.contains("code review") || lower.contains("**critical**")) {
                return "code-review";
            }
            if (lower.contains("implementation plan") || lower.contains("architecture")) {
                return "planning";
            }
            if (lower.contains("remediation plan") || lower.contains("incident response")
                    || lower.contains("root cause analysis") || lower.contains("interrupt(")
                    || lower.contains("checkpoint") && lower.contains("approval")) {
                return "incident-response";
            }
        }

        return "general";
    }

    private String buildUserPrompt(TurnMetrics metrics, String taskType, String agentName) {
        StringBuilder prompt = new StringBuilder();

        // Agent output (main content to evaluate)
        String output = metrics.getAgentOutput();
        if (output.length() > MAX_OUTPUT_FOR_JUDGE) {
            output = output.substring(0, MAX_OUTPUT_FOR_JUDGE)
                    + "\n... (truncated, " + metrics.getAgentOutput().length() + " chars total)";
        }
        prompt.append("[AGENT OUTPUT TO EVALUATE]\n").append(output).append("\n[END OF OUTPUT]\n\n");

        // Thinking text (if available)
        if (metrics.hasThinking()) {
            String thinking = metrics.getThinkingText();
            if (thinking.length() > MAX_THINKING_FOR_JUDGE) {
                thinking = thinking.substring(0, MAX_THINKING_FOR_JUDGE)
                        + "\n... (truncated, " + metrics.getThinkingText().length() + " chars total)";
            }
            prompt.append("[AGENT THINKING/REASONING]\n").append(thinking).append("\n[END OF THINKING]\n\n");
        }

        // Context
        prompt.append("Task context: ").append(taskType).append("\n");
        prompt.append("Agent: ").append(agentName).append("\n");
        prompt.append("Steps taken: ").append(metrics.getAgenticSteps()).append("\n");
        prompt.append("Tool calls: ").append(metrics.getToolCallsTotal())
                .append(" (").append(metrics.getToolCallErrors()).append(" errors)\n\n");

        // Instructions based on what dimensions apply
        prompt.append("Evaluate the above. ");
        if (!DESIGN_TASK_TYPES.contains(taskType)) {
            prompt.append("Set design_quality to null (not a design task). ");
        }
        if (!metrics.hasThinking()) {
            prompt.append("Set thinking_coherence to null (no thinking provided). ");
        }

        return prompt.toString();
    }

    private JudgeDimensions parseJudgeResponse(String responseText, String taskType,
                                                boolean hasThinking) {
        String jsonStr = extractJson(responseText);
        if (jsonStr == null) {
            return JudgeDimensions.error("Could not parse judge JSON from: "
                    + responseText.substring(0, Math.min(200, responseText.length())));
        }

        try {
            JsonNode json = objectMapper.readTree(jsonStr);

            float correctness = json.path("correctness").floatValue();
            float completeness = json.path("completeness").floatValue();

            float designQuality = -1;
            if (DESIGN_TASK_TYPES.contains(taskType) && json.has("design_quality")
                    && !json.get("design_quality").isNull()) {
                designQuality = json.path("design_quality").floatValue();
            }

            float thinkingCoherence = -1;
            if (hasThinking && json.has("thinking_coherence")
                    && !json.get("thinking_coherence").isNull()) {
                thinkingCoherence = json.path("thinking_coherence").floatValue();
            }

            String reasoning = json.path("reasoning").asText("No reasoning provided");

            if (correctness < 1 || correctness > 5 || completeness < 1 || completeness > 5) {
                return JudgeDimensions.error("Judge scores out of range: c="
                        + correctness + " comp=" + completeness);
            }

            return JudgeDimensions.of(correctness, completeness, designQuality,
                    thinkingCoherence, reasoning);

        } catch (Exception e) {
            return JudgeDimensions.error("JSON parse error: " + e.getMessage());
        }
    }

    private static String extractJson(String text) {
        if (text == null) return null;
        text = text.trim();

        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        return null;
    }
}
