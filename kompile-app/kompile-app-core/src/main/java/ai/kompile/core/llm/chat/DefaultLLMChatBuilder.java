/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.core.llm.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default implementation of the LLMChat Builder that creates LLMChat instances
 * with configured defaults and proper Spring AI ChatClient integration.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public class DefaultLLMChatBuilder implements LLMChat.Builder {

    private final ChatClient.Builder chatClientBuilder;

    /**
     * Creates a new builder with the specified ChatClient.Builder.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     */
    public DefaultLLMChatBuilder(ChatClient.Builder chatClientBuilder) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        this.chatClientBuilder = chatClientBuilder;
    }

    @Override
    public LLMChat.Builder defaultAdvisors(Advisor... advisors) {
        Assert.notNull(advisors, "advisors cannot be null");
        chatClientBuilder.defaultAdvisors(advisors);
        return this;
    }

    @Override
    public LLMChat.Builder defaultAdvisors(Consumer<LLMChat.AdvisorSpec> advisorSpecConsumer) {
        Assert.notNull(advisorSpecConsumer, "advisorSpecConsumer cannot be null");
        chatClientBuilder.defaultAdvisors(advisorSpec -> {
            DefaultAdvisorSpec defaultSpec = new DefaultAdvisorSpec(advisorSpec);
            advisorSpecConsumer.accept(defaultSpec);
        });
        return this;
    }

    @Override
    public LLMChat.Builder defaultAdvisors(List<Advisor> advisors) {
        Assert.notNull(advisors, "advisors cannot be null");
        chatClientBuilder.defaultAdvisors(advisors);
        return this;
    }

    @Override
    public LLMChat.Builder defaultOptions(ChatOptions chatOptions) {
        Assert.notNull(chatOptions, "chatOptions cannot be null");
        chatClientBuilder.defaultOptions(chatOptions);
        return this;
    }

    @Override
    public LLMChat.Builder defaultUser(String text) {
        Assert.hasText(text, "text cannot be null or empty");
        chatClientBuilder.defaultUser(text);
        return this;
    }

    @Override
    public LLMChat.Builder defaultUser(Resource text, Charset charset) {
        Assert.notNull(text, "text cannot be null");
        Assert.notNull(charset, "charset cannot be null");
        chatClientBuilder.defaultUser(text, charset);
        return this;
    }

    @Override
    public LLMChat.Builder defaultUser(Resource text) {
        Assert.notNull(text, "text cannot be null");
        chatClientBuilder.defaultUser(text);
        return this;
    }

    @Override
    public LLMChat.Builder defaultUser(Consumer<LLMChat.PromptUserSpec> userSpecConsumer) {
        Assert.notNull(userSpecConsumer, "userSpecConsumer cannot be null");
        chatClientBuilder.defaultUser(userSpec -> {
            DefaultPromptUserSpec defaultSpec = new DefaultPromptUserSpec(userSpec);
            userSpecConsumer.accept(defaultSpec);
        });
        return this;
    }

    @Override
    public LLMChat.Builder defaultSystem(String text) {
        Assert.hasText(text, "text cannot be null or empty");
        chatClientBuilder.defaultSystem(text);
        return this;
    }

    @Override
    public LLMChat.Builder defaultSystem(Resource text, Charset charset) {
        Assert.notNull(text, "text cannot be null");
        Assert.notNull(charset, "charset cannot be null");
        chatClientBuilder.defaultSystem(text, charset);
        return this;
    }

    @Override
    public LLMChat.Builder defaultSystem(Resource text) {
        Assert.notNull(text, "text cannot be null");
        chatClientBuilder.defaultSystem(text);
        return this;
    }

    @Override
    public LLMChat.Builder defaultSystem(Consumer<LLMChat.PromptSystemSpec> systemSpecConsumer) {
        Assert.notNull(systemSpecConsumer, "systemSpecConsumer cannot be null");
        chatClientBuilder.defaultSystem(systemSpec -> {
            DefaultPromptSystemSpec defaultSpec = new DefaultPromptSystemSpec(systemSpec);
            systemSpecConsumer.accept(defaultSpec);
        });
        return this;
    }

    @Override
    public LLMChat.Builder defaultTemplateRenderer(TemplateRenderer templateRenderer) {
        Assert.notNull(templateRenderer, "templateRenderer cannot be null");
        chatClientBuilder.defaultTemplateRenderer(templateRenderer);
        return this;
    }

    @Override
    public LLMChat.Builder defaultToolNames(String... toolNames) {
        Assert.notNull(toolNames, "toolNames cannot be null");
        chatClientBuilder.defaultToolNames(toolNames);
        return this;
    }

    @Override
    public LLMChat.Builder defaultTools(Object... toolObjects) {
        Assert.notNull(toolObjects, "toolObjects cannot be null");
        chatClientBuilder.defaultTools(toolObjects);
        return this;
    }

    @Override
    public LLMChat.Builder defaultToolCallbacks(ToolCallback... toolCallbacks) {
        Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
        chatClientBuilder.defaultToolCallbacks(toolCallbacks);
        return this;
    }

    @Override
    public LLMChat.Builder defaultToolCallbacks(List<ToolCallback> toolCallbacks) {
        Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
        chatClientBuilder.defaultToolCallbacks(toolCallbacks);
        return this;
    }

    @Override
    public LLMChat.Builder defaultToolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
        Assert.notNull(toolCallbackProviders, "toolCallbackProviders cannot be null");
        chatClientBuilder.defaultToolCallbacks(toolCallbackProviders);
        return this;
    }

    @Override
    public LLMChat.Builder defaultToolContext(Map<String, Object> toolContext) {
        Assert.notNull(toolContext, "toolContext cannot be null");
        chatClientBuilder.defaultToolContext(toolContext);
        return this;
    }

    @Override
    public LLMChat.Builder clone() {
        return new DefaultLLMChatBuilder(chatClientBuilder.clone());
    }

    @Override
    public LLMChat build() {
        ChatClient chatClient = chatClientBuilder.build();
        return new DefaultLLMChat(chatClient);
    }

    /**
     * Gets the underlying ChatClient.Builder.
     * 
     * @return the ChatClient.Builder
     */
    public ChatClient.Builder getChatClientBuilder() {
        return chatClientBuilder;
    }

    /**
     * Default implementation of AdvisorSpec.
     */
    private static class DefaultAdvisorSpec implements LLMChat.AdvisorSpec {
        private final ChatClient.AdvisorSpec spec;

        DefaultAdvisorSpec(ChatClient.AdvisorSpec spec) {
            this.spec = spec;
        }

        @Override
        public LLMChat.AdvisorSpec param(String k, Object v) {
            Assert.hasText(k, "k cannot be null or empty");
            spec.param(k, v);
            return this;
        }

        @Override
        public LLMChat.AdvisorSpec params(Map<String, Object> p) {
            Assert.notNull(p, "p cannot be null");
            spec.params(p);
            return this;
        }

        @Override
        public LLMChat.AdvisorSpec advisors(Advisor... advisors) {
            Assert.notNull(advisors, "advisors cannot be null");
            spec.advisors(advisors);
            return this;
        }

        @Override
        public LLMChat.AdvisorSpec advisors(List<Advisor> advisors) {
            Assert.notNull(advisors, "advisors cannot be null");
            spec.advisors(advisors);
            return this;
        }
    }

    /**
     * Default implementation of PromptSystemSpec.
     */
    private static class DefaultPromptSystemSpec implements LLMChat.PromptSystemSpec {
        private final ChatClient.PromptSystemSpec spec;

        DefaultPromptSystemSpec(ChatClient.PromptSystemSpec spec) {
            this.spec = spec;
        }

        @Override
        public LLMChat.PromptSystemSpec text(String text) {
            Assert.hasText(text, "text cannot be null or empty");
            spec.text(text);
            return this;
        }

        @Override
        public LLMChat.PromptSystemSpec text(Resource text, Charset charset) {
            Assert.notNull(text, "text cannot be null");
            Assert.notNull(charset, "charset cannot be null");
            spec.text(text, charset);
            return this;
        }

        @Override
        public LLMChat.PromptSystemSpec text(Resource text) {
            Assert.notNull(text, "text cannot be null");
            spec.text(text);
            return this;
        }

        @Override
        public LLMChat.PromptSystemSpec params(Map<String, Object> p) {
            Assert.notNull(p, "p cannot be null");
            spec.params(p);
            return this;
        }

        @Override
        public LLMChat.PromptSystemSpec param(String k, Object v) {
            Assert.hasText(k, "k cannot be null or empty");
            spec.param(k, v);
            return this;
        }
    }

    /**
     * Default implementation of PromptUserSpec.
     */
    private static class DefaultPromptUserSpec implements LLMChat.PromptUserSpec {
        private final ChatClient.PromptUserSpec spec;

        DefaultPromptUserSpec(ChatClient.PromptUserSpec spec) {
            this.spec = spec;
        }

        @Override
        public LLMChat.PromptUserSpec text(String text) {
            Assert.hasText(text, "text cannot be null or empty");
            spec.text(text);
            return this;
        }

        @Override
        public LLMChat.PromptUserSpec text(Resource text, Charset charset) {
            Assert.notNull(text, "text cannot be null");
            Assert.notNull(charset, "charset cannot be null");
            spec.text(text, charset);
            return this;
        }

        @Override
        public LLMChat.PromptUserSpec text(Resource text) {
            Assert.notNull(text, "text cannot be null");
            spec.text(text);
            return this;
        }

        @Override
        public LLMChat.PromptUserSpec params(Map<String, Object> p) {
            Assert.notNull(p, "p cannot be null");
            spec.params(p);
            return this;
        }

        @Override
        public LLMChat.PromptUserSpec param(String k, Object v) {
            Assert.hasText(k, "k cannot be null or empty");
            spec.param(k, v);
            return this;
        }

        @Override
        public LLMChat.PromptUserSpec media(Media... media) {
            Assert.notNull(media, "media cannot be null");
            spec.media(media);
            return this;
        }

        @Override
        public LLMChat.PromptUserSpec media(MimeType mimeType, URL url) {
            Assert.notNull(mimeType, "mimeType cannot be null");
            Assert.notNull(url, "url cannot be null");
            spec.media(mimeType, url);
            return this;
        }

        @Override
        public LLMChat.PromptUserSpec media(MimeType mimeType, Resource resource) {
            Assert.notNull(mimeType, "mimeType cannot be null");
            Assert.notNull(resource, "resource cannot be null");
            spec.media(mimeType, resource);
            return this;
        }
    }
}
