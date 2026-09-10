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
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

            Judge the response against the supplied USER REQUEST, not against injected memory
            or generic assumptions about what the user might want.

            Respond ONLY with valid JSON on a single line:
            {"correctness": <1-5>, "completeness": <1-5>, "design_quality": <1-5 or null>, "thinking_coherence": <1-5 or null>, "reasoning": "<specific corrective feedback when any score is below 3; otherwise one-sentence rationale>"}

            Do not add any text before or after the JSON.
            """;

    static final String TOOL_CALL_JUDGE_SYSTEM_PROMPT = """
            You are the in-process quality judge for Kompile's main chat REPL.
            Review a proposed MCP tool call before it executes. Judge only whether the call
            is relevant to the user's request, consistent with the assistant's stated path,
            and structurally reasonable. Permission and enforcer policy are separate gates.

            Prefer ALLOW unless there is a clear problem. Use BLOCK for an irrelevant,
            contradictory, malformed, or unjustified call. Use REWRITE only when corrected
            JSON arguments are unambiguous and preserve the intended operation.

            Respond with exactly one JSON object and no surrounding prose:
            {"action":"ALLOW|BLOCK|REWRITE","reason":"brief reason","violations":[],"correction_prompt":"actionable feedback for the main chat","rewrittenArgs":null}
            For REWRITE, rewrittenArgs must be a JSON object. For ALLOW or BLOCK it must be null.
            """;

    private static final int MAX_OUTPUT_FOR_JUDGE = 6_000;
    private static final int MAX_TASK_PROMPT_FOR_JUDGE = 4_000;
    private static final int MAX_THINKING_FOR_JUDGE = 2_000;
    private static final int MAX_TOOL_CONTEXT_FOR_JUDGE = 4_000;
    private static final String FORMAT_REPAIR_INSTRUCTION = """

            [FORMAT REPAIR]
            Your previous response was not a parseable JSON object. Evaluate the same input again
            and return exactly one JSON object using the system prompt's schema. Do not add prose,
            markdown, code fences, comments, or placeholders.
            [END FORMAT REPAIR]
            """;
    private static final Set<String> DESIGN_TASK_TYPES = Set.of("code-review", "planning", "incident-response");

    private final JudgeBackend backend;
    private final ObjectMapper objectMapper;
    private final BackgroundProcessManager processManager;
    private final String processId;
    /** Supplies the user's durable judge guidance, or the empty string when absent. */
    private volatile java.util.function.Supplier<String> guidanceSupplier = () -> "";

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
        this(backend, objectMapper, null);
    }

    /**
     * Constructor with an explicit backend and watcher manager.
     * Useful for custom harness wiring that wants the same judge lifecycle tracking
     * as the config-driven constructors.
     */
    public JudgeLlmEvaluator(JudgeBackend backend, ObjectMapper objectMapper,
                             BackgroundProcessManager processManager) {
        this.objectMapper = objectMapper;
        this.backend = backend;
        this.processManager = processManager;
        this.processId = registerJudgeProcess(null);
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

    private void markJudgeProcessFailed() {
        if (processManager != null && processId != null) {
            processManager.fail(processId);
        }
    }

    /** Bind a live supplier of the user's durable judge guidance. */
    public void setGuidanceSupplier(java.util.function.Supplier<String> supplier) {
        this.guidanceSupplier = supplier == null ? () -> "" : supplier;
    }

    /** The current user guidance text (for display by /judge status). */
    public String currentGuidance() {
        String guidance = guidanceSupplier.get();
        return guidance == null ? "" : guidance;
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
            markJudgeProcessFailed();
            return JudgeDimensions.error("No judge backend available. "
                    + "Configure judge_mode, judge_provider, or judge_local_model in harness config.");
        }

        String userPrompt = buildUserPrompt(metrics, taskType, agentName);

        try {
            String responseText = generateJsonVerdict(
                    userPrompt, JUDGE_SYSTEM_PROMPT, qualityVerdictSchema());

            if (ResilientJudgeBackend.isErrorResponse(responseText)) {
                markJudgeProcessFailed();
                return JudgeDimensions.error("Judge backend failed: "
                        + bounded(responseText, 240));
            }

            JudgeDimensions dimensions = parseJudgeResponse(responseText, taskType, metrics.hasThinking());
            if (dimensions.isError()) {
                // Providers sometimes return quota/limit failures as ordinary text instead
                // of a non-zero process exit. That is still a dead judge watcher.
                markJudgeProcessFailed();
            }
            return dimensions;

        } catch (Exception e) {
            markJudgeProcessFailed();
            return JudgeDimensions.error("Judge call failed: " + e.getMessage());
        }
    }

    /** Review a proposed MCP tool call before the main chat executes it. */
    public EnforcerToolCallDecision evaluateToolCall(
            String userPrompt, String assistantContext, String toolName, String toolInput)
            throws Exception {
        if (backend == null || !backend.isAvailable()) {
            return EnforcerToolCallDecision.allow("No quality judge backend is available");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("[USER REQUEST]\n")
                .append(bounded(userPrompt, MAX_TOOL_CONTEXT_FOR_JUDGE))
                .append("\n[END USER REQUEST]\n\n");
        if (assistantContext != null && !assistantContext.isBlank()) {
            prompt.append("[ASSISTANT CONTEXT]\n")
                    .append(bounded(assistantContext, MAX_TOOL_CONTEXT_FOR_JUDGE))
                    .append("\n[END ASSISTANT CONTEXT]\n\n");
        }
        prompt.append("[PROPOSED MCP TOOL CALL]\nname: ")
                .append(toolName == null ? "" : toolName)
                .append("\narguments: ")
                .append(bounded(toolInput, MAX_TOOL_CONTEXT_FOR_JUDGE))
                .append("\n[END PROPOSED MCP TOOL CALL]\n\n");

        String guidance = guidanceSupplier.get();
        if (guidance != null && !guidance.isBlank()) {
            prompt.append("[USER GUIDANCE TO THE JUDGE]\n")
                    .append("The user has given you the following feedback and instructions. ")
                    .append("Treat it as high-priority context when reviewing this call:\n")
                    .append(bounded(guidance, MAX_TOOL_CONTEXT_FOR_JUDGE))
                    .append("\n[END USER GUIDANCE TO THE JUDGE]");
        }

        String response = generateJsonVerdict(
                prompt.toString(), TOOL_CALL_JUDGE_SYSTEM_PROMPT, toolVerdictSchema());
        if (ResilientJudgeBackend.isErrorResponse(response)) {
            throw new IllegalStateException("Quality judge returned an error response: "
                    + bounded(response, 240));
        }
        String json = extractJson(response);
        if (json == null) {
            throw new IllegalStateException("Quality judge did not return JSON");
        }
        objectMapper.readTree(json); // malformed JSON is an evaluator failure, not a policy block
        return EnforcerToolCallDecision.parse(objectMapper, response);
    }

    private String generateJsonVerdict(
            String prompt, String systemPrompt, JudgeBackend.JsonSchema outputSchema)
            throws Exception {
        String initial = backend.generateJson(prompt, systemPrompt, outputSchema);
        if (ResilientJudgeBackend.isErrorResponse(initial) || hasParseableJsonObject(initial)) {
            return initial;
        }
        return backend.generateJson(
                prompt + FORMAT_REPAIR_INSTRUCTION, systemPrompt, outputSchema);
    }

    private boolean hasParseableJsonObject(String response) {
        String json = extractJson(response);
        if (json == null) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            return root != null && root.isObject();
        } catch (Exception ignored) {
            return false;
        }
    }

    private JudgeBackend.JsonSchema qualityVerdictSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        integerScore(properties.putObject("correctness"));
        integerScore(properties.putObject("completeness"));
        nullableIntegerScore(properties.putObject("design_quality"));
        nullableIntegerScore(properties.putObject("thinking_coherence"));
        properties.putObject("reasoning").put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("correctness").add("completeness").add("design_quality")
                .add("thinking_coherence").add("reasoning");
        return new JudgeBackend.JsonSchema("kompile_quality_judge", schema, true);
    }

    private JudgeBackend.JsonSchema toolVerdictSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("action").put("type", "string")
                .putArray("enum").add("ALLOW").add("BLOCK").add("REWRITE");
        properties.putObject("reason").put("type", "string");
        properties.putObject("violations").put("type", "array")
                .putObject("items").put("type", "string");
        properties.putObject("correction_prompt").put("type", "string");
        properties.putObject("rewrittenArgs").putArray("type").add("object").add("null");
        ArrayNode required = schema.putArray("required");
        required.add("action").add("reason").add("violations")
                .add("correction_prompt").add("rewrittenArgs");
        // rewrittenArgs intentionally accepts arbitrary tool-specific properties.
        return new JudgeBackend.JsonSchema("kompile_tool_judge", schema, false);
    }

    private static void integerScore(ObjectNode node) {
        node.put("type", "integer").put("minimum", 1).put("maximum", 5);
    }

    private static void nullableIntegerScore(ObjectNode node) {
        node.putArray("type").add("integer").add("null");
        node.put("minimum", 1).put("maximum", 5);
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

        String taskPrompt = metrics.getTaskPrompt();
        if (taskPrompt != null && !taskPrompt.isBlank()) {
            prompt.append("[USER REQUEST]\n")
                    .append(bounded(taskPrompt, MAX_TASK_PROMPT_FOR_JUDGE))
                    .append("\n[END USER REQUEST]\n\n");
        }

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

    private static String bounded(String value, int maxChars) {
        if (value == null || value.isBlank()) return "(none)";
        String text = value.strip();
        return text.length() <= maxChars
                ? text : text.substring(0, maxChars) + "\n… [truncated]";
    }
}
