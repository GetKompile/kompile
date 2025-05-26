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

package ai.kompile.core.llm.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * No-operation implementation of LLMChat that provides placeholder functionality
 * when no actual chat client is configured. This implementation logs warnings and
 * returns error messages for all operations.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
@Service
@ConditionalOnMissingBean(LLMChat.class)
public class NoOpLLMChat implements LLMChat {
    
    private static final Logger logger = LoggerFactory.getLogger(NoOpLLMChat.class);
    private static final String ERROR_MESSAGE = "LLMChat is not configured. Cannot perform chat operations.";

    public NoOpLLMChat() {
        logger.warn("No specific LLMChat implementation found. " +
                   "Initializing NoOpLLMChat. Chat functionality will be disabled.");
    }

    @Override
    public ChatClientRequestSpec prompt() {
        logger.warn(ERROR_MESSAGE);
        return new NoOpChatClientRequestSpec();
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        logger.warn(ERROR_MESSAGE + " Content: {}", content);
        return new NoOpChatClientRequestSpec();
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        logger.warn(ERROR_MESSAGE + " Prompt: {}", prompt);
        return new NoOpChatClientRequestSpec();
    }

    @Override
    public Builder mutate() {
        logger.warn(ERROR_MESSAGE);
        return new NoOpBuilder();
    }

    /**
     * No-op implementation of ChatClientRequestSpec.
     */
    private static class NoOpChatClientRequestSpec implements ChatClientRequestSpec {
        
        @Override
        public Builder mutate() {
            return new NoOpBuilder();
        }

        @Override
        public ChatClientRequestSpec advisors(Consumer<AdvisorSpec> consumer) {
            return this;
        }

        @Override
        public ChatClientRequestSpec advisors(Advisor... advisors) {
            return this;
        }

        @Override
        public ChatClientRequestSpec advisors(List<Advisor> advisors) {
            return this;
        }

        @Override
        public ChatClientRequestSpec messages(Message... messages) {
            return this;
        }

        @Override
        public ChatClientRequestSpec messages(List<Message> messages) {
            return this;
        }

        @Override
        public <T extends ChatOptions> ChatClientRequestSpec options(T options) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolNames(String... toolNames) {
            return this;
        }

        @Override
        public ChatClientRequestSpec tools(Object... toolObjects) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolContext(Map<String, Object> toolContext) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(String text) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Resource textResource, Charset charset) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Resource text) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(String text) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Resource text, Charset charset) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Resource text) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Consumer<PromptUserSpec> consumer) {
            return this;
        }

        @Override
        public ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer) {
            return this;
        }

        @Override
        public CallResponseSpec call() {
            return new NoOpCallResponseSpec();
        }

        @Override
        public StreamResponseSpec stream() {
            return new NoOpStreamResponseSpec();
        }
    }

    /**
     * No-op implementation of CallResponseSpec.
     */
    private static class NoOpCallResponseSpec implements CallResponseSpec {
        
        @Override
        public <T> T entity(ParameterizedTypeReference<T> type) {
            logger.warn("Cannot convert to entity type: {}", type.getType());
            return null;
        }

        @Override
        public <T> T entity(StructuredOutputConverter<T> structuredOutputConverter) {
            logger.warn("Cannot convert using structured output converter");
            return null;
        }

        @Override
        public <T> T entity(Class<T> type) {
            logger.warn("Cannot convert to entity class: {}", type.getName());
            return null;
        }

        @Override
        public ChatClientResponse chatClientResponse() {
            // Return a basic ChatClientResponse with error message
            ChatResponse chatResponse = createErrorChatResponse();
            return ChatClientResponse.builder()
                    .chatResponse(chatResponse).build();
        }

        @Override
        public ChatResponse chatResponse() {
            return createErrorChatResponse();
        }

        @Override
        public String content() {
            return "Error: " + ERROR_MESSAGE;
        }
    }

    /**
     * No-op implementation of StreamResponseSpec.
     */
    private static class NoOpStreamResponseSpec implements StreamResponseSpec {
        
        @Override
        public Flux<ChatClientResponse> chatClientResponse() {
            ChatResponse chatResponse = createErrorChatResponse();
            ChatClientResponse clientResponse = ChatClientResponse.builder()
                    .chatResponse(chatResponse).build();
            return Flux.just(clientResponse);
        }

        @Override
        public Flux<ChatResponse> chatResponse() {
            return Flux.just(createErrorChatResponse());
        }

        @Override
        public Flux<String> content() {
            return Flux.just("Error: " + ERROR_MESSAGE);
        }
    }

    /**
     * No-op implementation of Builder.
     */
    private static class NoOpBuilder implements Builder {
        
        @Override
        public Builder defaultAdvisors(Advisor... advisors) {
            return this;
        }

        @Override
        public Builder defaultAdvisors(Consumer<AdvisorSpec> advisorSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultAdvisors(List<Advisor> advisors) {
            return this;
        }

        @Override
        public Builder defaultOptions(ChatOptions chatOptions) {
            return this;
        }

        @Override
        public Builder defaultUser(String text) {
            return this;
        }

        @Override
        public Builder defaultUser(Resource text, Charset charset) {
            return this;
        }

        @Override
        public Builder defaultUser(Resource text) {
            return this;
        }

        @Override
        public Builder defaultUser(Consumer<PromptUserSpec> userSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultSystem(String text) {
            return this;
        }

        @Override
        public Builder defaultSystem(Resource text, Charset charset) {
            return this;
        }

        @Override
        public Builder defaultSystem(Resource text) {
            return this;
        }

        @Override
        public Builder defaultSystem(Consumer<PromptSystemSpec> systemSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultTemplateRenderer(TemplateRenderer templateRenderer) {
            return this;
        }

        @Override
        public Builder defaultToolNames(String... toolNames) {
            return this;
        }

        @Override
        public Builder defaultTools(Object... toolObjects) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(ToolCallback... toolCallbacks) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(List<ToolCallback> toolCallbacks) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
            return this;
        }

        @Override
        public Builder defaultToolContext(Map<String, Object> toolContext) {
            return this;
        }

        @Override
        public Builder clone() {
            return new NoOpBuilder();
        }

        @Override
        public LLMChat build() {
            logger.warn("Building NoOpLLMChat - functionality will be limited");
            return new NoOpLLMChat();
        }
    }

    /**
     * Creates an error ChatResponse for use in no-op implementations.
     */
    private static ChatResponse createErrorChatResponse() {
        Generation generation = new Generation(
                new AssistantMessage("Error: " + ERROR_MESSAGE), 
                null
        );
        return new ChatResponse(Collections.singletonList(generation));
    }
}
