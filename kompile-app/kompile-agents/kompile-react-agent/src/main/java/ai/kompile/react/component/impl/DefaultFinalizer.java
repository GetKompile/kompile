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

import ai.kompile.react.api.Finalizer;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ToolCall;
import ai.kompile.react.model.ToolDefinition;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default implementation of the Finalizer interface.
 * Determines task completion and generates final answers or summaries.
 */
@Slf4j
public class DefaultFinalizer implements Finalizer {

    private static final String FINAL_ANSWER_TOOL = "final_answer";

    private static final String DEFAULT_SUMMARIZE_PROMPT = """
            You are a helpful assistant. The previous task reached its step limit without completing.
            Please summarize what was accomplished and what remains to be done based on the conversation history.

            Provide a clear, concise summary of:
            1. What the user was trying to accomplish
            2. What actions were taken
            3. What was achieved
            4. What could not be completed (if anything)
            5. Any recommendations for next steps
            """;

    private final String id;
    private final String name;
    private final ChatClient chatClient;
    private final String summarizePrompt;
    private final ToolDefinition answerSchema;

    @Builder
    public DefaultFinalizer(
            String id,
            String name,
            ChatClient chatClient,
            String summarizePrompt,
            ToolDefinition answerSchema
    ) {
        this.id = id != null ? id : "default-finalizer";
        this.name = name != null ? name : "Default Finalizer";
        this.chatClient = chatClient;
        this.summarizePrompt = summarizePrompt != null ? summarizePrompt : DEFAULT_SUMMARIZE_PROMPT;
        this.answerSchema = answerSchema != null ? answerSchema : createDefaultAnswerSchema();
    }

    private static ToolDefinition createDefaultAnswerSchema() {
        return ToolDefinition.builder()
                .name(FINAL_ANSWER_TOOL)
                .description("Call this to provide the final answer to the user's question. " +
                        "This is mandatory for task completion.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "answer", Map.of(
                                        "type", "string",
                                        "description", "The final answer to provide to the user"
                                )
                        ),
                        "required", List.of("answer")
                ))
                .build();
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
    public ToolDefinition getAnswerSchema() {
        return answerSchema;
    }

    @Override
    public boolean isComplete(ReActMessage message) {
        if (message == null || !message.hasToolCalls()) {
            return false;
        }

        // Check if any tool call is the final_answer tool
        return message.getToolCalls().stream()
                .anyMatch(tc -> FINAL_ANSWER_TOOL.equals(tc.getName()));
    }

    @Override
    public CompletableFuture<ReActMessage> resolveAnswer(AgentContext context, ReActMessage message) {
        return CompletableFuture.supplyAsync(() -> resolveAnswerSync(context, message));
    }

    @Override
    public ReActMessage resolveAnswerSync(AgentContext context, ReActMessage message) {
        log.debug("Resolving final answer from message");

        if (message == null || !message.hasToolCalls()) {
            return ReActMessage.assistant("Unable to resolve answer: no tool calls found");
        }

        // Find the final_answer tool call
        ToolCall finalAnswerCall = message.getToolCalls().stream()
                .filter(tc -> FINAL_ANSWER_TOOL.equals(tc.getName()))
                .findFirst()
                .orElse(null);

        if (finalAnswerCall == null) {
            return ReActMessage.assistant("Unable to resolve answer: final_answer tool not found");
        }

        // Extract the answer from arguments
        Map<String, Object> args = finalAnswerCall.getArguments();
        if (args == null || !args.containsKey("answer")) {
            // Try to use the thought as the answer
            if (message.getThought() != null) {
                return ReActMessage.assistant(message.getThought());
            }
            return ReActMessage.assistant("Unable to resolve answer: no answer provided");
        }

        String answer = String.valueOf(args.get("answer"));
        return ReActMessage.builder()
                .role(ReActMessage.Role.ASSISTANT)
                .content(answer)
                .metadata(Map.of("final_answer", true))
                .build();
    }

    @Override
    public CompletableFuture<ReActMessage> summarizeOnExceed(AgentContext context) {
        return CompletableFuture.supplyAsync(() -> summarizeOnExceedSync(context));
    }

    @Override
    public ReActMessage summarizeOnExceedSync(AgentContext context) {
        log.info("Generating summary for exceeded step limit");

        if (chatClient == null) {
            // No LLM available, create a simple summary
            return createSimpleSummary(context);
        }

        try {
            // Build messages for the summarization
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(summarizePrompt));

            // Add conversation history
            for (ReActMessage msg : context.getMessages()) {
                messages.add(toSpringAiMessage(msg));
            }

            // Add the request for summary
            messages.add(new UserMessage(
                    "Please summarize the conversation above. " +
                    "The maximum step limit (" + context.getMaxSteps() + ") was reached."
            ));

            // Call the LLM
            Prompt prompt = new Prompt(messages);
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

            if (response != null && !response.getResults().isEmpty()) {
                String summary = response.getResult().getOutput().getText();
                return ReActMessage.builder()
                        .role(ReActMessage.Role.ASSISTANT)
                        .content(summary)
                        .metadata(Map.of("summary", true, "max_steps_exceeded", true))
                        .build();
            }

            return createSimpleSummary(context);

        } catch (Exception e) {
            log.error("Failed to generate summary: {}", e.getMessage(), e);
            return createSimpleSummary(context);
        }
    }

    private ReActMessage createSimpleSummary(AgentContext context) {
        StringBuilder summary = new StringBuilder();
        summary.append("Task reached maximum step limit (")
                .append(context.getMaxSteps())
                .append(" steps).\n\n");

        summary.append("Steps completed:\n");

        int stepNum = 0;
        for (ReActMessage msg : context.getMessages()) {
            if (msg.getRole() == ReActMessage.Role.ASSISTANT && msg.getThought() != null) {
                stepNum++;
                summary.append(stepNum).append(". ").append(msg.getThought(), 0,
                        Math.min(100, msg.getThought().length()));
                if (msg.getThought().length() > 100) {
                    summary.append("...");
                }
                summary.append("\n");
            }
        }

        summary.append("\nPlease continue the conversation to complete the remaining tasks.");

        return ReActMessage.builder()
                .role(ReActMessage.Role.ASSISTANT)
                .content(summary.toString())
                .metadata(Map.of("summary", true, "max_steps_exceeded", true))
                .build();
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
}
