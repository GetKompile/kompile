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
package ai.kompile.graph.neo4j.integration;

import ai.kompile.core.llm.chat.LLMChat;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Test implementation of LLMChat for integration tests.
 * Provides configurable responses and tracks calls for verification.
 */
public class TestLLMChat implements LLMChat {

    private Function<String, String> responseGenerator;
    private final List<String> receivedPrompts = new ArrayList<>();

    public TestLLMChat() {
        // Default: echo back the prompt with a prefix
        this.responseGenerator = prompt -> "Answer based on context: " + extractContextSummary(prompt);
    }

    /**
     * Set a custom response generator function.
     */
    public void setResponseGenerator(Function<String, String> generator) {
        this.responseGenerator = generator;
    }

    /**
     * Set a fixed response for all prompts.
     */
    public void setFixedResponse(String response) {
        this.responseGenerator = prompt -> response;
    }

    /**
     * Get all prompts that were sent to this LLM.
     */
    public List<String> getReceivedPrompts() {
        return new ArrayList<>(receivedPrompts);
    }

    /**
     * Clear the recorded prompts.
     */
    public void clearReceivedPrompts() {
        receivedPrompts.clear();
    }

    private String extractContextSummary(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return "No context provided.";
        }
        // Return a summary of the prompt
        if (prompt.length() > 100) {
            return prompt.substring(0, 100) + "...";
        }
        return prompt;
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return new TestRequestSpec(this);
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        TestRequestSpec spec = new TestRequestSpec(this);
        spec.setUserContent(content);
        return spec;
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        TestRequestSpec spec = new TestRequestSpec(this);
        if (prompt != null && !prompt.getInstructions().isEmpty()) {
            spec.setUserContent(prompt.getInstructions().get(0).getText());
        }
        return spec;
    }

    @Override
    public Builder mutate() {
        return null;
    }

    private class TestRequestSpec implements ChatClientRequestSpec {
        private final TestLLMChat parent;
        private String userContent = "";
        private String systemContent = "";

        TestRequestSpec(TestLLMChat parent) {
            this.parent = parent;
        }

        void setUserContent(String content) {
            this.userContent = content;
        }

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
        public ChatClientRequestSpec system(String text) {
            this.systemContent = text;
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Resource textResource, Charset charset) { return this; }

        @Override
        public ChatClientRequestSpec system(Resource text) { return this; }

        @Override
        public ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer) { return this; }

        @Override
        public ChatClientRequestSpec user(String text) {
            this.userContent = text;
            return this;
        }

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
            String fullPrompt = systemContent + "\n" + userContent;
            parent.receivedPrompts.add(fullPrompt);
            String response = parent.responseGenerator.apply(fullPrompt);
            return new TestCallResponseSpec(response);
        }

        @Override
        public StreamResponseSpec stream() {
            return null;
        }
    }

    private static class TestCallResponseSpec implements CallResponseSpec {
        private final String response;

        TestCallResponseSpec(String response) {
            this.response = response;
        }

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
            return response;
        }
    }
}
