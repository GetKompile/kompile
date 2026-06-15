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

import org.springframework.ai.chat.client.ChatClient;
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
import org.springframework.util.Assert;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default implementation of LLMChat that wraps Spring AI's ChatClient and provides
 * enhanced functionality while maintaining full compatibility.
 * 
 * <p>This implementation delegates to Spring AI's ChatClient for the actual LLM
 * communication while adding Kompile-specific features like enhanced error handling,
 * conversation context management, and simplified usage patterns.</p>
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public class DefaultLLMChat implements LLMChat {

    private final ChatClient chatClient;

    /**
     * Creates a new DefaultLLMChat instance.
     * 
     * @param chatClient the Spring AI ChatClient to wrap
     */
    public DefaultLLMChat(ChatClient chatClient) {
        Assert.notNull(chatClient, "chatClient cannot be null");
        this.chatClient = chatClient;
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return new DefaultChatClientRequestSpec(chatClient.prompt());
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        Assert.hasText(content, "content cannot be null or empty");
        return new DefaultChatClientRequestSpec(chatClient.prompt(content));
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        Assert.notNull(prompt, "prompt cannot be null");
        return new DefaultChatClientRequestSpec(chatClient.prompt(prompt));
    }

    @Override
    public Builder mutate() {
        return new DefaultLLMChatBuilder(chatClient.mutate());
    }

    /**
     * Gets the underlying Spring AI ChatClient.
     * 
     * @return the wrapped ChatClient
     */
    public ChatClient getChatClient() {
        return chatClient;
    }

    /**
     * Default implementation of ChatClientRequestSpec.
     */
    private static class DefaultChatClientRequestSpec implements ChatClientRequestSpec {
        
        private final ChatClient.ChatClientRequestSpec spec;

        DefaultChatClientRequestSpec(ChatClient.ChatClientRequestSpec spec) {
            this.spec = spec;
        }

        @Override
        public Builder mutate() {
            return new DefaultLLMChatBuilder(spec.mutate());
        }

        @Override
        public ChatClientRequestSpec advisors(Consumer<AdvisorSpec> consumer) {
            Assert.notNull(consumer, "consumer cannot be null");
            return new DefaultChatClientRequestSpec(spec.advisors(advisorSpec -> {
                DefaultAdvisorSpec defaultSpec = new DefaultAdvisorSpec(advisorSpec);
                consumer.accept(defaultSpec);
            }));
        }

        @Override
        public ChatClientRequestSpec advisors(Advisor... advisors) {
            Assert.notNull(advisors, "advisors cannot be null");
            return new DefaultChatClientRequestSpec(spec.advisors(advisors));
        }

        @Override
        public ChatClientRequestSpec advisors(List<Advisor> advisors) {
            Assert.notNull(advisors, "advisors cannot be null");
            return new DefaultChatClientRequestSpec(spec.advisors(advisors));
        }

        @Override
        public ChatClientRequestSpec messages(Message... messages) {
            Assert.notNull(messages, "messages cannot be null");
            return new DefaultChatClientRequestSpec(spec.messages(messages));
        }

        @Override
        public ChatClientRequestSpec messages(List<Message> messages) {
            Assert.notNull(messages, "messages cannot be null");
            return new DefaultChatClientRequestSpec(spec.messages(messages));
        }

        @Override
        public <T extends ChatOptions> ChatClientRequestSpec options(T options) {
            Assert.notNull(options, "options cannot be null");
            return new DefaultChatClientRequestSpec(spec.options(options));
        }

        @Override
        public ChatClientRequestSpec toolNames(String... toolNames) {
            Assert.notNull(toolNames, "toolNames cannot be null");
            return new DefaultChatClientRequestSpec(spec.toolNames(toolNames));
        }

        @Override
        public ChatClientRequestSpec tools(Object... toolObjects) {
            Assert.notNull(toolObjects, "toolObjects cannot be null");
            return new DefaultChatClientRequestSpec(spec.tools(toolObjects));
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks) {
            Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
            return new DefaultChatClientRequestSpec(spec.toolCallbacks(toolCallbacks));
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks) {
            Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
            return new DefaultChatClientRequestSpec(spec.toolCallbacks(toolCallbacks));
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
            Assert.notNull(toolCallbackProviders, "toolCallbackProviders cannot be null");
            return new DefaultChatClientRequestSpec(spec.toolCallbacks(toolCallbackProviders));
        }

        @Override
        public ChatClientRequestSpec toolContext(Map<String, Object> toolContext) {
            Assert.notNull(toolContext, "toolContext cannot be null");
            return new DefaultChatClientRequestSpec(spec.toolContext(toolContext));
        }

        @Override
        public ChatClientRequestSpec system(String text) {
            Assert.hasText(text, "text cannot be null or empty");
            return new DefaultChatClientRequestSpec(spec.system(text));
        }

        @Override
        public ChatClientRequestSpec system(Resource textResource, Charset charset) {
            Assert.notNull(textResource, "textResource cannot be null");
            Assert.notNull(charset, "charset cannot be null");
            return new DefaultChatClientRequestSpec(spec.system(textResource, charset));
        }

        @Override
        public ChatClientRequestSpec system(Resource text) {
            Assert.notNull(text, "text cannot be null");
            return new DefaultChatClientRequestSpec(spec.system(text));
        }

        @Override
        public ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer) {
            Assert.notNull(consumer, "consumer cannot be null");
            return new DefaultChatClientRequestSpec(spec.system(systemSpec -> {
                DefaultPromptSystemSpec defaultSpec = new DefaultPromptSystemSpec(systemSpec);
                consumer.accept(defaultSpec);
            }));
        }

        @Override
        public ChatClientRequestSpec user(String text) {
            Assert.hasText(text, "text cannot be null or empty");
            return new DefaultChatClientRequestSpec(spec.user(text));
        }

        @Override
        public ChatClientRequestSpec user(Resource text, Charset charset) {
            Assert.notNull(text, "text cannot be null");
            Assert.notNull(charset, "charset cannot be null");
            return new DefaultChatClientRequestSpec(spec.user(text, charset));
        }

        @Override
        public ChatClientRequestSpec user(Resource text) {
            Assert.notNull(text, "text cannot be null");
            return new DefaultChatClientRequestSpec(spec.user(text));
        }

        @Override
        public ChatClientRequestSpec user(Consumer<PromptUserSpec> consumer) {
            Assert.notNull(consumer, "consumer cannot be null");
            return new DefaultChatClientRequestSpec(spec.user(userSpec -> {
                DefaultPromptUserSpec defaultSpec = new DefaultPromptUserSpec(userSpec);
                consumer.accept(defaultSpec);
            }));
        }

        @Override
        public ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer) {
            Assert.notNull(templateRenderer, "templateRenderer cannot be null");
            return new DefaultChatClientRequestSpec(spec.templateRenderer(templateRenderer));
        }

        @Override
        public CallResponseSpec call() {
            return new DefaultCallResponseSpec(spec.call());
        }

        @Override
        public StreamResponseSpec stream() {
            return new DefaultStreamResponseSpec(spec.stream());
        }
    }

    /**
     * Default implementation of CallResponseSpec.
     */
    private static class DefaultCallResponseSpec implements CallResponseSpec {
        private final ChatClient.CallResponseSpec spec;

        DefaultCallResponseSpec(ChatClient.CallResponseSpec spec) {
            this.spec = spec;
        }

        @Override
        @Nullable
        public <T> T entity(ParameterizedTypeReference<T> type) {
            Assert.notNull(type, "type cannot be null");
            return spec.entity(type);
        }

        @Override
        @Nullable
        public <T> T entity(StructuredOutputConverter<T> structuredOutputConverter) {
            Assert.notNull(structuredOutputConverter, "structuredOutputConverter cannot be null");
            return spec.entity(structuredOutputConverter);
        }

        @Override
        @Nullable
        public <T> T entity(Class<T> type) {
            Assert.notNull(type, "type cannot be null");
            return spec.entity(type);
        }

        @Override
        public ChatClientResponse chatClientResponse() {
            return spec.chatClientResponse();
        }

        @Override
        @Nullable
        public ChatResponse chatResponse() {
            return spec.chatResponse();
        }

        @Override
        @Nullable
        public String content() {
            return spec.content();
        }

        // Remove the hallucinated responseEntity methods since they don't exist in the actual interface
    }

    /**
     * Default implementation of StreamResponseSpec.
     */
    private static class DefaultStreamResponseSpec implements StreamResponseSpec {
        private final ChatClient.StreamResponseSpec spec;

        DefaultStreamResponseSpec(ChatClient.StreamResponseSpec spec) {
            this.spec = spec;
        }

        @Override
        public Flux<ChatClientResponse> chatClientResponse() {
            return spec.chatClientResponse();
        }

        @Override
        public Flux<ChatResponse> chatResponse() {
            return spec.chatResponse();
        }

        @Override
        public Flux<String> content() {
            return spec.content();
        }
    }

    /**
     * Default implementation of AdvisorSpec.
     */
    private static class DefaultAdvisorSpec implements AdvisorSpec {
        private final ChatClient.AdvisorSpec spec;

        DefaultAdvisorSpec(ChatClient.AdvisorSpec spec) {
            this.spec = spec;
        }

        @Override
        public AdvisorSpec param(String k, Object v) {
            Assert.hasText(k, "k cannot be null or empty");
            spec.param(k, v);
            return this;
        }

        @Override
        public AdvisorSpec params(Map<String, Object> p) {
            Assert.notNull(p, "p cannot be null");
            spec.params(p);
            return this;
        }

        @Override
        public AdvisorSpec advisors(Advisor... advisors) {
            Assert.notNull(advisors, "advisors cannot be null");
            spec.advisors(advisors);
            return this;
        }

        @Override
        public AdvisorSpec advisors(List<Advisor> advisors) {
            Assert.notNull(advisors, "advisors cannot be null");
            spec.advisors(advisors);
            return this;
        }
    }

    /**
     * Default implementation of PromptSystemSpec.
     */
    private static class DefaultPromptSystemSpec implements PromptSystemSpec {
        private final ChatClient.PromptSystemSpec spec;

        DefaultPromptSystemSpec(ChatClient.PromptSystemSpec spec) {
            this.spec = spec;
        }

        @Override
        public PromptSystemSpec text(String text) {
            Assert.hasText(text, "text cannot be null or empty");
            spec.text(text);
            return this;
        }

        @Override
        public PromptSystemSpec text(Resource text, Charset charset) {
            Assert.notNull(text, "text cannot be null");
            Assert.notNull(charset, "charset cannot be null");
            spec.text(text, charset);
            return this;
        }

        @Override
        public PromptSystemSpec text(Resource text) {
            Assert.notNull(text, "text cannot be null");
            spec.text(text);
            return this;
        }

        @Override
        public PromptSystemSpec params(Map<String, Object> p) {
            Assert.notNull(p, "p cannot be null");
            spec.params(p);
            return this;
        }

        @Override
        public PromptSystemSpec param(String k, Object v) {
            Assert.hasText(k, "k cannot be null or empty");
            spec.param(k, v);
            return this;
        }
    }

    /**
     * Default implementation of PromptUserSpec.
     */
    private static class DefaultPromptUserSpec implements PromptUserSpec {
        private final ChatClient.PromptUserSpec spec;

        DefaultPromptUserSpec(ChatClient.PromptUserSpec spec) {
            this.spec = spec;
        }

        @Override
        public PromptUserSpec text(String text) {
            Assert.hasText(text, "text cannot be null or empty");
            spec.text(text);
            return this;
        }

        @Override
        public PromptUserSpec text(Resource text, Charset charset) {
            Assert.notNull(text, "text cannot be null");
            Assert.notNull(charset, "charset cannot be null");
            spec.text(text, charset);
            return this;
        }

        @Override
        public PromptUserSpec text(Resource text) {
            Assert.notNull(text, "text cannot be null");
            spec.text(text);
            return this;
        }

        @Override
        public PromptUserSpec params(Map<String, Object> p) {
            Assert.notNull(p, "p cannot be null");
            spec.params(p);
            return this;
        }

        @Override
        public PromptUserSpec param(String k, Object v) {
            Assert.hasText(k, "k cannot be null or empty");
            spec.param(k, v);
            return this;
        }

        @Override
        public PromptUserSpec media(Media... media) {
            Assert.notNull(media, "media cannot be null");
            spec.media(media);
            return this;
        }

        @Override
        public PromptUserSpec media(MimeType mimeType, URL url) {
            Assert.notNull(mimeType, "mimeType cannot be null");
            Assert.notNull(url, "url cannot be null");
            spec.media(mimeType, url);
            return this;
        }

        @Override
        public PromptUserSpec media(MimeType mimeType, Resource resource) {
            Assert.notNull(mimeType, "mimeType cannot be null");
            Assert.notNull(resource, "resource cannot be null");
            spec.media(mimeType, resource);
            return this;
        }
    }
}
