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

package ai.kompile.llm.openai; // Your package for this specific implementation

import ai.kompile.core.llm.LanguageModel; // From your core abstractions module
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage; // Correct UserMessage import
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service("openAiLanguageModel")
@ConditionalOnProperty(name = "spring.ai.openai.api-key")
public class OpenAiLanguageModelImpl implements LanguageModel {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiLanguageModelImpl.class);
    private final ChatClient chatClient;

    public OpenAiLanguageModelImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        logger.info("OpenAiLanguageModelImpl initialized with ChatClient: {}", this.chatClient.getClass().getName());
    }

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        logger.debug("OpenAI generating simple response for query: {}", userQuery);
        String systemMessageContent = """
                You are a helpful AI assistant. Answer the user's query based on the provided context.
                If the context does not contain the answer, say that you don't know.
                Context:
                {context}
                """;
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemMessageContent);
        String contextString = (context == null) ? "" : context.stream().collect(Collectors.joining("\n---\n"));

        // Using Spring AI's Prompt class
        Prompt prompt = new Prompt(List.of(
                systemPromptTemplate.createMessage(Map.of("context", contextString)),
                new UserMessage(userQuery) // Spring AI's UserMessage
        ));

        // The fluent API call
        ChatResponse response = chatClient.prompt(prompt)
                .call()
                .chatResponse();

        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            return response.getResult().getOutput().getText();
        }
        logger.warn("OpenAI could not get a valid response or output for query: {}", userQuery);
        return "Error: Could not get a response from OpenAI language model.";
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        logger.debug("OpenAI generating response with potential tool calls for query: {}", userQuery);
        String systemMessageContent = """
                You are a helpful AI assistant. You have access to tools.
                Answer the user's query based on the provided context or by using tools if necessary.
                If the context does not contain the answer, consider using a tool.
                Available tools are for RAG queries (rag_query), listing files (list_files), and reading files (read_file).
                Context:
                {context}
                """;
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemMessageContent);
        String contextString = (context == null) ? "" : context.stream().collect(Collectors.joining("\n---\n"));

        // These tool names must match the 'name' attribute of your @Tool annotated methods
        String[] toolNamesArray = {"rag_query", "list_files", "read_file"};
        logger.info("Advertising tools to OpenAI: {}", String.join(", ", toolNamesArray));

        // Correct fluent API chain for Spring AI 1.0.0-M8+
        ChatResponse response = chatClient.prompt()
                .messages(
                        systemPromptTemplate.createMessage(Map.of("context", contextString)),
                        new UserMessage(userQuery) // Spring AI's UserMessage
                )
                .toolNames(toolNamesArray) // Pass as varargs or an array
                .call()
                .chatResponse();

        logger.debug("OpenAI LLM response (may include tool call): {}", response);
        return response;
    }
}