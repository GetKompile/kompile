/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.llm.chat.LLMChat;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Stub implementation of LLMChat for testing.
 */
public class StubLLMChat implements LLMChat {

    private String fixedResponse = "Default stub response";
    private boolean shouldThrowException = false;

    public void setFixedResponse(String response) {
        this.fixedResponse = response;
    }

    public void setShouldThrowException(boolean shouldThrow) {
        this.shouldThrowException = shouldThrow;
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return new StubRequestSpec();
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        return new StubRequestSpec();
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        return new StubRequestSpec();
    }

    @Override
    public Builder mutate() {
        return null;
    }

    private class StubRequestSpec implements ChatClientRequestSpec {
        @Override
        public Builder mutate() { return null; }

        @Override
        public ChatClientRequestSpec advisors(Consumer<AdvisorSpec> consumer) { return this; }

        @Override
        public ChatClientRequestSpec advisors(Advisor... advisors) { return this; }

        @Override
        public ChatClientRequestSpec advisors(List<Advisor> advisors) { return this; }

        @Override
        public ChatClientRequestSpec messages(Message... messages) { return this; }

        @Override
        public ChatClientRequestSpec messages(List<Message> messages) { return this; }

        @Override
        public <T extends ChatOptions> ChatClientRequestSpec options(T options) { return this; }

        @Override
        public ChatClientRequestSpec toolNames(String... toolNames) { return this; }

        @Override
        public ChatClientRequestSpec tools(Object... toolObjects) { return this; }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks) { return this; }

        @Override
        public ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks) { return this; }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallbackProvider... toolCallbackProviders) { return this; }

        @Override
        public ChatClientRequestSpec toolContext(Map<String, Object> toolContext) { return this; }

        @Override
        public ChatClientRequestSpec system(String text) { return this; }

        @Override
        public ChatClientRequestSpec system(Resource textResource, Charset charset) { return this; }

        @Override
        public ChatClientRequestSpec system(Resource text) { return this; }

        @Override
        public ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer) { return this; }

        @Override
        public ChatClientRequestSpec user(String text) { return this; }

        @Override
        public ChatClientRequestSpec user(Resource text, Charset charset) { return this; }

        @Override
        public ChatClientRequestSpec user(Resource text) { return this; }

        @Override
        public ChatClientRequestSpec user(Consumer<PromptUserSpec> consumer) { return this; }

        @Override
        public ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer) { return this; }

        @Override
        public CallResponseSpec call() {
            return new StubCallResponseSpec();
        }

        @Override
        public StreamResponseSpec stream() {
            return null;
        }
    }

    private class StubCallResponseSpec implements CallResponseSpec {
        @Nullable
        @Override
        public <T> T entity(ParameterizedTypeReference<T> type) { return null; }

        @Nullable
        @Override
        public <T> T entity(StructuredOutputConverter<T> structuredOutputConverter) { return null; }

        @Nullable
        @Override
        public <T> T entity(Class<T> type) { return null; }

        @Override
        public ChatClientResponse chatClientResponse() { return null; }

        @Nullable
        @Override
        public ChatResponse chatResponse() { return null; }

        @Nullable
        @Override
        public String content() {
            if (shouldThrowException) {
                throw new RuntimeException("Simulated LLM error");
            }
            return fixedResponse;
        }
    }
}
