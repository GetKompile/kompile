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
package ai.kompile.react.component.impl;

import ai.kompile.core.evaluation.*;
import ai.kompile.react.api.Reasoner;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.eval.EvalTracker;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalTestResult;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.TokenUsage;
import ai.kompile.react.model.ToolCall;
import ai.kompile.react.model.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * A Reasoner that uses evaluation feedback to guide and improve reasoning.
 * Integrates with the evaluation system and fact sheet test cases.
 *
 * <p>Key features:
 * <ul>
 *   <li>Uses past evaluation results to inform current reasoning</li>
 *   <li>Tracks similar queries and their outcomes</li>
 *   <li>Provides self-evaluation during reasoning steps</li>
 *   <li>Records results for future learning</li>
 * </ul>
 */
@Slf4j
public class EvalBasedReasoner implements Reasoner {

    private static final String EVAL_SYSTEM_PROMPT = """
            You are a helpful assistant that reasons carefully and self-evaluates your responses.

            You have access to evaluation criteria and past performance data.
            Use this information to:
            1. Ensure your reasoning is grounded in facts
            2. Avoid patterns that have led to failures
            3. Follow successful reasoning patterns from similar queries
            4. Self-check for potential issues before responding

            Think step by step and use available tools when needed.
            When you have enough information to answer, call the final_answer tool.
            """;

    private final String id;
    private final String name;
    private final ChatClient chatClient;
    private final EvaluationService evaluationService;
    private final EvalTracker evalTracker;
    private final String systemPrompt;
    private final ObjectMapper objectMapper;
    private final boolean selfEvaluate;
    private final double qualityThreshold;

    @Builder
    public EvalBasedReasoner(
            String id,
            String name,
            ChatClient chatClient,
            EvaluationService evaluationService,
            EvalTracker evalTracker,
            String systemPrompt,
            Boolean selfEvaluate,
            Double qualityThreshold
    ) {
        this.id = id != null ? id : "eval-based-reasoner";
        this.name = name != null ? name : "Evaluation-Based Reasoner";
        this.chatClient = chatClient;
        this.evaluationService = evaluationService;
        this.evalTracker = evalTracker;
        this.systemPrompt = systemPrompt != null ? systemPrompt : EVAL_SYSTEM_PROMPT;
        this.objectMapper = new ObjectMapper();
        this.selfEvaluate = selfEvaluate != null ? selfEvaluate : true;
        this.qualityThreshold = qualityThreshold != null ? qualityThreshold : 0.7;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CompletableFuture<ReActMessage> reason(AgentContext context) {
        return CompletableFuture.supplyAsync(() -> reasonSync(context));
    }

    @Override
    public ReActMessage reasonSync(AgentContext context) {
        log.debug("Eval-Based Reasoner processing step {}", context.getCurrentStep());

        try {
            String query = extractQuery(context);
            Long factSheetId = getFactSheetId(context);

            // Get evaluation context for similar queries
            String evalContext = buildEvalContext(query, factSheetId);

            // Build messages with evaluation context
            List<Message> messages = buildMessages(context, evalContext);

            // Create prompt and call LLM
            Prompt prompt = new Prompt(messages);
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

            ReActMessage result = parseResponse(response);

            // Self-evaluate if enabled and this is a potential final answer
            if (selfEvaluate && shouldSelfEvaluate(result, context)) {
                result = selfEvaluateAndRefine(result, query, context);
            }

            // Track for learning
            recordForLearning(query, result, factSheetId, context);

            return result;

        } catch (Exception e) {
            log.error("Eval-Based Reasoner failed: {}", e.getMessage(), e);
            throw new RuntimeException("Eval-based reasoning failed: " + e.getMessage(), e);
        }
    }

    private String extractQuery(AgentContext context) {
        List<ReActMessage> messages = context.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ReActMessage msg = messages.get(i);
            if (msg.getRole() == ReActMessage.Role.USER && msg.getContent() != null) {
                return msg.getContent();
            }
        }
        return "";
    }

    private Long getFactSheetId(AgentContext context) {
        Object factSheetId = context.getMetadata("fact_sheet_id");
        if (factSheetId instanceof Long) {
            return (Long) factSheetId;
        } else if (factSheetId instanceof Number) {
            return ((Number) factSheetId).longValue();
        }
        return null;
    }

    private String buildEvalContext(String query, Long factSheetId) {
        StringBuilder context = new StringBuilder();

        if (evalTracker != null && factSheetId != null) {
            // Get related test cases for this fact sheet
            List<EvalCase> relatedCases = evalTracker.getTestCasesForFactSheet(factSheetId)
                    .stream()
                    .filter(tc -> isSimilarQuery(query, tc.getQuery()))
                    .limit(3)
                    .toList();

            if (!relatedCases.isEmpty()) {
                context.append("## Related Test Cases\n\n");
                for (EvalCase tc : relatedCases) {
                    context.append("**").append(tc.getName()).append("**\n");
                    context.append("Query: ").append(tc.getQuery()).append("\n");
                    if (tc.getExpectedAnswer() != null) {
                        context.append("Expected: ").append(tc.getExpectedAnswer()).append("\n");
                    }
                    if (!tc.getExpectedFacts().isEmpty()) {
                        context.append("Key Facts: ").append(String.join(", ", tc.getExpectedFacts())).append("\n");
                    }

                    // Include past performance
                    Optional<EvalTestResult> lastResult = evalTracker.getLatestTestResult(tc.getId());
                    if (lastResult.isPresent()) {
                        EvalTestResult result = lastResult.get();
                        context.append("Last Result: ").append(result.isPassed() ? "PASSED" : "FAILED");
                        context.append(" (score: ").append(String.format("%.2f", result.getScore())).append(")\n");
                    }
                    context.append("\n");
                }
            }

            // Get failing test cases as warnings
            List<EvalCase> failingCases = evalTracker.getFailingTestCases(factSheetId);
            if (!failingCases.isEmpty()) {
                context.append("## Known Issues\n\n");
                context.append("The following test cases have been failing recently:\n");
                for (EvalCase tc : failingCases.stream().limit(3).toList()) {
                    context.append("- ").append(tc.getName()).append(": ").append(tc.getQuery()).append("\n");
                }
                context.append("\nBe careful to avoid these failure patterns.\n\n");
            }

            // Get overall metrics
            Map<String, Double> metrics = evalTracker.getFactSheetMetrics(factSheetId);
            if (!metrics.isEmpty() && metrics.get("total_results") > 0) {
                context.append("## Performance Metrics\n\n");
                context.append("Pass Rate: ").append(String.format("%.1f%%", metrics.get("pass_rate") * 100)).append("\n");
                context.append("Average Score: ").append(String.format("%.2f", metrics.get("average_score"))).append("\n\n");
            }
        }

        return context.toString();
    }

    private boolean isSimilarQuery(String query1, String query2) {
        if (query1 == null || query2 == null) {
            return false;
        }
        // Simple similarity check based on word overlap
        Set<String> words1 = Arrays.stream(query1.toLowerCase().split("\\W+"))
                .filter(w -> w.length() > 3)
                .collect(Collectors.toSet());
        Set<String> words2 = Arrays.stream(query2.toLowerCase().split("\\W+"))
                .filter(w -> w.length() > 3)
                .collect(Collectors.toSet());

        if (words1.isEmpty() || words2.isEmpty()) {
            return false;
        }

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        double similarity = (double) intersection.size() / Math.min(words1.size(), words2.size());
        return similarity > 0.3;
    }

    private List<Message> buildMessages(AgentContext context, String evalContext) {
        List<Message> messages = new ArrayList<>();

        // Build system prompt with eval context
        String effectiveSystemPrompt = context.getSystemPrompt() != null
                ? context.getSystemPrompt()
                : systemPrompt;

        if (!evalContext.isEmpty()) {
            effectiveSystemPrompt = effectiveSystemPrompt + "\n\n" + evalContext;
        }

        // Add tool descriptions
        String toolDescriptions = buildToolDescriptions(context.getToolkit().getTools());
        if (!toolDescriptions.isEmpty()) {
            effectiveSystemPrompt = effectiveSystemPrompt + "\n\n" + toolDescriptions;
        }

        // Add quality guidelines
        effectiveSystemPrompt = effectiveSystemPrompt + "\n\n" + buildQualityGuidelines();

        messages.add(new SystemMessage(effectiveSystemPrompt));

        // Add conversation messages
        for (ReActMessage msg : context.getMessages()) {
            messages.add(toSpringAiMessage(msg));
        }

        return messages;
    }

    private String buildQualityGuidelines() {
        return """
                ## Quality Guidelines

                Before providing your final answer, ensure:
                1. **Relevancy**: Your response directly addresses the user's question
                2. **Faithfulness**: All claims are grounded in the provided context
                3. **Completeness**: You've covered all aspects of the question
                4. **Correctness**: The information is accurate and up-to-date

                If you're unsure about any claim, indicate your uncertainty.
                """;
    }

    private String buildToolDescriptions(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## Available Tools\n\n");
        for (ToolDefinition tool : tools) {
            sb.append("### ").append(tool.getName()).append("\n");
            sb.append(tool.getDescription()).append("\n\n");
        }

        sb.append("\nTo use a tool, respond with JSON:\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"thought\": \"Your reasoning\",\n");
        sb.append("  \"self_check\": \"Brief self-evaluation of this step\",\n");
        sb.append("  \"tool_calls\": [{\"name\": \"tool_name\", \"arguments\": {...}}]\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    private Message toSpringAiMessage(ReActMessage msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> new SystemMessage(msg.getContent());
            case USER -> new UserMessage(msg.getContent());
            case ASSISTANT -> new AssistantMessage(
                    msg.getContent() != null ? msg.getContent() :
                            (msg.getThought() != null ? msg.getThought() : "")
            );
            case TOOL -> new UserMessage(
                    "Tool " + msg.getToolName() + " result: " + msg.getContent()
            );
        };
    }

    private ReActMessage parseResponse(ChatResponse response) {
        if (response == null || response.getResults().isEmpty()) {
            return ReActMessage.assistant("No response from LLM");
        }

        Generation generation = response.getResult();
        AssistantMessage assistantMessage = generation.getOutput();
        String content = assistantMessage.getText();

        TokenUsage usage = TokenUsage.empty();
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var aiUsage = response.getMetadata().getUsage();
            usage = TokenUsage.of(aiUsage.getPromptTokens(), aiUsage.getCompletionTokens());
        }

        try {
            JsonNode json = objectMapper.readTree(content);

            String thought = json.has("thought") ? json.get("thought").asText() : null;
            String selfCheck = json.has("self_check") ? json.get("self_check").asText() : null;
            List<ToolCall> toolCalls = new ArrayList<>();

            if (json.has("tool_calls") && json.get("tool_calls").isArray()) {
                for (JsonNode toolCallNode : json.get("tool_calls")) {
                    String toolName = toolCallNode.get("name").asText();
                    Map<String, Object> args = new HashMap<>();

                    if (toolCallNode.has("arguments")) {
                        args = objectMapper.convertValue(
                                toolCallNode.get("arguments"),
                                new TypeReference<Map<String, Object>>() {}
                        );
                    }

                    toolCalls.add(ToolCall.of(toolName, args));
                }
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("eval_enhanced", true);
            if (selfCheck != null) {
                metadata.put("self_check", selfCheck);
            }

            return ReActMessage.builder()
                    .role(ReActMessage.Role.ASSISTANT)
                    .thought(thought)
                    .content(content)
                    .toolCalls(toolCalls)
                    .usage(usage)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            log.debug("Response is not JSON: {}", e.getMessage());

            return ReActMessage.builder()
                    .role(ReActMessage.Role.ASSISTANT)
                    .content(content)
                    .usage(usage)
                    .metadata(Map.of("eval_enhanced", true))
                    .build();
        }
    }

    private boolean shouldSelfEvaluate(ReActMessage result, AgentContext context) {
        // Self-evaluate if this looks like a final answer
        if (result.getToolCalls() != null) {
            return result.getToolCalls().stream()
                    .anyMatch(tc -> "final_answer".equals(tc.getName()));
        }
        return false;
    }

    private ReActMessage selfEvaluateAndRefine(ReActMessage result, String query, AgentContext context) {
        if (evaluationService == null || !evaluationService.isEnabled()) {
            return result;
        }

        try {
            // Extract the answer content
            String answer = extractAnswerFromToolCall(result);
            if (answer == null || answer.isEmpty()) {
                return result;
            }

            // Get retrieved documents from context
            List<String> retrievedDocs = extractRetrievedDocuments(context);

            // Run quick evaluation
            EvaluationContext evalContext = EvaluationContext.builder()
                    .build();

            EvaluationReport report = evaluationService.evaluate(
                    query, answer, retrievedDocs,
                    List.of(EvaluationType.RELEVANCY, EvaluationType.FAITHFULNESS),
                    evalContext
            );

            // Check if quality meets threshold
            if (report.getOverallScore() < qualityThreshold) {
                log.debug("Self-evaluation score {} below threshold {}, may need refinement",
                        report.getOverallScore(), qualityThreshold);

                // Add evaluation feedback to metadata
                Map<String, Object> metadata = new HashMap<>(result.getMetadata());
                metadata.put("self_eval_score", report.getOverallScore());
                metadata.put("self_eval_passed", report.isOverallPassed());
                metadata.put("self_eval_issues", report.getSummary());

                return ReActMessage.builder()
                        .role(result.getRole())
                        .thought(result.getThought())
                        .content(result.getContent())
                        .toolCalls(result.getToolCalls())
                        .usage(result.getUsage())
                        .metadata(metadata)
                        .build();
            }

        } catch (Exception e) {
            log.warn("Self-evaluation failed: {}", e.getMessage());
        }

        return result;
    }

    private String extractAnswerFromToolCall(ReActMessage result) {
        if (result.getToolCalls() == null) {
            return null;
        }

        for (ToolCall tc : result.getToolCalls()) {
            if ("final_answer".equals(tc.getName())) {
                Object answer = tc.getArguments().get("answer");
                if (answer != null) {
                    return answer.toString();
                }
            }
        }
        return null;
    }

    private List<String> extractRetrievedDocuments(AgentContext context) {
        List<String> docs = new ArrayList<>();

        for (ReActMessage msg : context.getMessages()) {
            if (msg.getRole() == ReActMessage.Role.TOOL) {
                if ("search_knowledge_graph".equals(msg.getToolName()) ||
                        "rag_query".equals(msg.getToolName())) {
                    docs.add(msg.getContent());
                }
            }
        }

        return docs;
    }

    private void recordForLearning(String query, ReActMessage result, Long factSheetId, AgentContext context) {
        if (evalTracker == null) {
            return;
        }

        try {
            // Only record final answers
            String answer = extractAnswerFromToolCall(result);
            if (answer == null) {
                return;
            }

            EvalTestResult testResult = EvalTestResult.builder()
                    .id(UUID.randomUUID().toString())
                    .executionId(context.getExecutionId())
                    .factSheetId(factSheetId)
                    .query(query)
                    .actualAnswer(answer)
                    .stepsExecuted(context.getCurrentStep())
                    .startedAt(Instant.now().minusMillis(100)) // Approximate
                    .completedAt(Instant.now())
                    .totalTokens(context.getTotalUsage() != null ? context.getTotalUsage().getTotalTokens() : 0)
                    .build();

            // Add self-eval score if available
            if (result.getMetadata().containsKey("self_eval_score")) {
                testResult.setScore((Double) result.getMetadata().get("self_eval_score"));
                testResult.setPassed((Boolean) result.getMetadata().getOrDefault("self_eval_passed", false));
            }

            evalTracker.recordTestResult(testResult);

        } catch (Exception e) {
            log.warn("Failed to record for learning: {}", e.getMessage());
        }
    }
}
