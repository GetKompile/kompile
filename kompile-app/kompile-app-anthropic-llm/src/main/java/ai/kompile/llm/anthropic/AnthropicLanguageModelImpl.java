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

package ai.kompile.llm.anthropic;

import ai.kompile.core.llm.LanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Import metadata classes
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.RateLimit;


@Service("anthropicLanguageModel")
@ConditionalOnProperty(name = "spring.ai.anthropic.api-key")
public class AnthropicLanguageModelImpl implements LanguageModel {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicLanguageModelImpl.class);
    private final ChatClient chatClient;

    @Autowired
    public AnthropicLanguageModelImpl(ChatClient.Builder chatClientBuilder) {
        // Spring AI auto-configuration with spring-ai-starter-model-anthropic
        // will provide a builder that creates an AnthropicChatClient.
        this.chatClient = chatClientBuilder.build();
        logger.info("AnthropicLanguageModelImpl initialized with ChatClient: {}", this.chatClient.getClass().getName());
    }

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        logger.debug("Anthropic generating simple response for query: {}", userQuery);
        String systemMessageContent = """
                You are a helpful AI assistant. Answer the user's query based on the provided context.
                If the context does not contain the answer, say that you don't know.
                Context:
                {context}
                """;
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemMessageContent);
        String contextString = (context == null) ? "" : context.stream().collect(Collectors.joining("\n---\n"));

        Prompt prompt = new Prompt(List.of(
                systemPromptTemplate.createMessage(Map.of("context", contextString)),
                new UserMessage(userQuery)
        ));

        ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

        if (response != null && response.getResult() != null) {
            AssistantMessage output = response.getResult().getOutput();
            if (output != null && output.getText() != null && !output.getText().trim().isEmpty()) {
                return output.getText();
            }
        }
        logger.warn("Anthropic could not get a valid response or output for query: {}", userQuery);
        return "Error: Could not get a response from Anthropic language model.";
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        logger.debug("Anthropic generating response with potential tool calls for query: {}", userQuery);
        // Note: Anthropic's tool/function calling syntax might differ from OpenAI.
        // Spring AI aims to abstract this, but specific options or prompt formatting might be needed.
        // For M8, Anthropic tool calling support via Spring AI might use a specific beta flag or options.
        // Check spring.ai.anthropic.chat.options.toolNames or similar in application.properties for Anthropic.
        String systemMessageContent = """
                You are a helpful AI assistant. You have access to tools.
                Answer the user's query based on the provided context or by using tools if necessary.
                If the context does not contain the answer, consider using a tool.
                Available tools are for RAG queries (rag_query), listing files (list_files), and reading files (read_file).
                When responding, if you need to call a tool, use the exact tool names provided.
                Context:
                {context}
                """;
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemMessageContent);
        String contextString = (context == null) ? "" : context.stream().collect(Collectors.joining("\n---\n"));

        String[] toolNamesArray = {"rag_query", "list_files", "read_file"};
        logger.info("Advertising tools to Anthropic: {}", String.join(", ", toolNamesArray));

        ChatResponse response = chatClient.prompt()
                .messages(
                        systemPromptTemplate.createMessage(Map.of("context", contextString)),
                        new UserMessage(userQuery)
                )
                .toolNames(toolNamesArray) // Standard Spring AI method to declare tools
                .call()
                .chatResponse();

        logger.debug("Anthropic LLM response (may include tool call): {}", response);
        return response;
    }
}