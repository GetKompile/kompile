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
 * Kompile's abstraction for LLM chat interactions that provides enhanced functionality
 * while maintaining full compatibility with Spring AI's ChatClient interface.
 * 
 * <p>This interface mirrors Spring AI's ChatClient API exactly while adding Kompile-specific
 * enhancements and utilities. It provides a fluent API for communicating with AI models,
 * supporting both synchronous and streaming programming models.</p>
 * 
 * <p>To create instances of LLMChat, use {@link LLMChatFactory} which provides various
 * factory methods for different configuration scenarios.</p>
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public interface LLMChat {

    /**
     * Starts building a prompt request using a fluent API.
     * This is the main entry point for creating chat interactions.
     * 
     * @return a request spec for building the request
     */
    ChatClientRequestSpec prompt();

    /**
     * Starts building a prompt request with simple text content.
     * This is a convenience method for simple interactions.
     * 
     * @param content the user message content
     * @return a request spec for configuring the call
     */
    ChatClientRequestSpec prompt(String content);

    /**
     * Starts building a prompt request with a pre-built Prompt object.
     * 
     * @param prompt the prompt to use
     * @return a request spec for configuring the call
     */
    ChatClientRequestSpec prompt(Prompt prompt);

    /**
     * Creates a new builder to mutate this LLMChat instance.
     * 
     * @return a builder for creating a mutated instance
     */
    Builder mutate();

    /**
     * Builder interface for creating LLMChat instances.
     */
    interface Builder {
        
        /**
         * Sets default advisors.
         * 
         * @param advisors the default advisors
         * @return this builder
         */
        Builder defaultAdvisors(Advisor... advisors);

        /**
         * Sets default advisors using a consumer.
         * 
         * @param advisorSpecConsumer consumer to configure advisors
         * @return this builder
         */
        Builder defaultAdvisors(Consumer<AdvisorSpec> advisorSpecConsumer);

        /**
         * Sets default advisors from a list.
         * 
         * @param advisors the default advisors
         * @return this builder
         */
        Builder defaultAdvisors(List<Advisor> advisors);

        /**
         * Sets default chat options.
         * 
         * @param chatOptions the default options
         * @return this builder
         */
        Builder defaultOptions(ChatOptions chatOptions);

        /**
         * Sets the default user message.
         * 
         * @param text the default user message
         * @return this builder
         */
        Builder defaultUser(String text);

        /**
         * Sets the default user message from a resource.
         * 
         * @param text the resource containing user message
         * @param charset the charset to use
         * @return this builder
         */
        Builder defaultUser(Resource text, Charset charset);

        /**
         * Sets the default user message from a resource.
         * 
         * @param text the resource containing user message
         * @return this builder
         */
        Builder defaultUser(Resource text);

        /**
         * Sets the default user message using a consumer.
         * 
         * @param userSpecConsumer consumer to configure user message
         * @return this builder
         */
        Builder defaultUser(Consumer<PromptUserSpec> userSpecConsumer);

        /**
         * Sets the default system message.
         * 
         * @param text the default system message
         * @return this builder
         */
        Builder defaultSystem(String text);

        /**
         * Sets the default system message from a resource.
         * 
         * @param text the resource containing system message
         * @param charset the charset to use
         * @return this builder
         */
        Builder defaultSystem(Resource text, Charset charset);

        /**
         * Sets the default system message from a resource.
         * 
         * @param text the resource containing system message
         * @return this builder
         */
        Builder defaultSystem(Resource text);

        /**
         * Sets the default system message using a consumer.
         * 
         * @param systemSpecConsumer consumer to configure system message
         * @return this builder
         */
        Builder defaultSystem(Consumer<PromptSystemSpec> systemSpecConsumer);

        /**
         * Sets the default template renderer.
         * 
         * @param templateRenderer the template renderer
         * @return this builder
         */
        Builder defaultTemplateRenderer(TemplateRenderer templateRenderer);

        /**
         * Sets default tool names.
         * 
         * @param toolNames the default tool names
         * @return this builder
         */
        Builder defaultToolNames(String... toolNames);

        /**
         * Sets default tools.
         * 
         * @param toolObjects the default tool objects
         * @return this builder
         */
        Builder defaultTools(Object... toolObjects);

        /**
         * Sets default tool callbacks.
         * 
         * @param toolCallbacks the default tool callbacks
         * @return this builder
         */
        Builder defaultToolCallbacks(ToolCallback... toolCallbacks);

        /**
         * Sets default tool callbacks from a list.
         * 
         * @param toolCallbacks the default tool callbacks
         * @return this builder
         */
        Builder defaultToolCallbacks(List<ToolCallback> toolCallbacks);

        /**
         * Sets default tool callback providers.
         * 
         * @param toolCallbackProviders the default tool callback providers
         * @return this builder
         */
        Builder defaultToolCallbacks(ToolCallbackProvider... toolCallbackProviders);

        /**
         * Sets default tool context.
         * 
         * @param toolContext the default tool context
         * @return this builder
         */
        Builder defaultToolContext(Map<String, Object> toolContext);

        /**
         * Clones this builder.
         * 
         * @return a cloned builder
         */
        Builder clone();

        /**
         * Builds the LLMChat instance.
         * 
         * @return the configured LLMChat
         */
        LLMChat build();
    }

    /**
     * Specification for configuring chat requests.
     */
    interface ChatClientRequestSpec {
        
        /**
         * Creates a builder to mutate this request.
         * 
         * @return a builder for mutation
         */
        Builder mutate();

        /**
         * Configures advisors for this request.
         * 
         * @param consumer consumer to configure advisors
         * @return this spec for chaining
         */
        ChatClientRequestSpec advisors(Consumer<AdvisorSpec> consumer);

        /**
         * Adds advisors directly.
         * 
         * @param advisors the advisors to add
         * @return this spec for chaining
         */
        ChatClientRequestSpec advisors(Advisor... advisors);

        /**
         * Adds advisors from a list.
         * 
         * @param advisors the advisors to add
         * @return this spec for chaining
         */
        ChatClientRequestSpec advisors(List<Advisor> advisors);

        /**
         * Adds messages to the request.
         * 
         * @param messages the messages to add
         * @return this spec for chaining
         */
        ChatClientRequestSpec messages(Message... messages);

        /**
         * Adds messages from a list.
         * 
         * @param messages the messages to add
         * @return this spec for chaining
         */
        ChatClientRequestSpec messages(List<Message> messages);

        /**
         * Sets options for this request.
         * 
         * @param options the chat options
         * @param <T> the options type
         * @return this spec for chaining
         */
        <T extends ChatOptions> ChatClientRequestSpec options(T options);

        /**
         * Sets tool names for this request.
         * 
         * @param toolNames the tool names
         * @return this spec for chaining
         */
        ChatClientRequestSpec toolNames(String... toolNames);

        /**
         * Sets tools for this request.
         * 
         * @param toolObjects the tool objects
         * @return this spec for chaining
         */
        ChatClientRequestSpec tools(Object... toolObjects);

        /**
         * Sets tool callbacks for this request.
         * 
         * @param toolCallbacks the tool callbacks
         * @return this spec for chaining
         */
        ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks);

        /**
         * Sets tool callbacks from a list.
         * 
         * @param toolCallbacks the tool callbacks
         * @return this spec for chaining
         */
        ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks);

        /**
         * Sets tool callback providers.
         * 
         * @param toolCallbackProviders the tool callback providers
         * @return this spec for chaining
         */
        ChatClientRequestSpec toolCallbacks(ToolCallbackProvider... toolCallbackProviders);

        /**
         * Sets tool context for this request.
         * 
         * @param toolContext the tool context
         * @return this spec for chaining
         */
        ChatClientRequestSpec toolContext(Map<String, Object> toolContext);

        /**
         * Sets the system message content.
         * 
         * @param text the system message
         * @return this spec for chaining
         */
        ChatClientRequestSpec system(String text);

        /**
         * Sets the system message from a resource.
         * 
         * @param textResource the resource containing system message
         * @param charset the charset to use
         * @return this spec for chaining
         */
        ChatClientRequestSpec system(Resource textResource, Charset charset);

        /**
         * Sets the system message from a resource.
         * 
         * @param text the resource containing system message
         * @return this spec for chaining
         */
        ChatClientRequestSpec system(Resource text);

        /**
         * Sets the system message using a consumer.
         * 
         * @param consumer consumer to configure system message
         * @return this spec for chaining
         */
        ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer);

        /**
         * Sets the user message content.
         * 
         * @param text the user message
         * @return this spec for chaining
         */
        ChatClientRequestSpec user(String text);

        /**
         * Sets the user message from a resource.
         * 
         * @param text the resource containing user message
         * @param charset the charset to use
         * @return this spec for chaining
         */
        ChatClientRequestSpec user(Resource text, Charset charset);

        /**
         * Sets the user message from a resource.
         * 
         * @param text the resource containing user message
         * @return this spec for chaining
         */
        ChatClientRequestSpec user(Resource text);

        /**
         * Sets the user message using a consumer.
         * 
         * @param consumer consumer to configure user message
         * @return this spec for chaining
         */
        ChatClientRequestSpec user(Consumer<PromptUserSpec> consumer);

        /**
         * Sets the template renderer for this request.
         * 
         * @param templateRenderer the template renderer
         * @return this spec for chaining
         */
        ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer);

        /**
         * Finalizes the request and returns a call specification.
         * 
         * @return a call spec for executing the request
         */
        CallResponseSpec call();

        /**
         * Finalizes the request and returns a stream specification.
         * 
         * @return a stream spec for executing streaming requests
         */
        StreamResponseSpec stream();
    }

    /**
     * Specification for making synchronous calls.
     */
    interface CallResponseSpec {
        
        /**
         * Executes the request and maps the response to an entity using type reference.
         * 
         * @param type the parameterized type reference
         * @param <T> the entity type
         * @return the mapped entity
         */
        @Nullable
        <T> T entity(ParameterizedTypeReference<T> type);

        /**
         * Executes the request and maps the response to an entity using a structured output converter.
         * 
         * @param structuredOutputConverter the output converter
         * @param <T> the entity type
         * @return the mapped entity
         */
        @Nullable
        <T> T entity(StructuredOutputConverter<T> structuredOutputConverter);

        /**
         * Executes the request and maps the response to an entity.
         * 
         * @param type the target entity class
         * @param <T> the entity type
         * @return the mapped entity
         */
        @Nullable
        <T> T entity(Class<T> type);

        /**
         * Executes the request and returns the ChatClientResponse.
         * 
         * @return the chat client response
         */
        ChatClientResponse chatClientResponse();

        /**
         * Executes the request and returns the ChatResponse.
         * 
         * @return the chat response
         */
        @Nullable
        ChatResponse chatResponse();

        /**
         * Executes the request and returns the response content as a string.
         * 
         * @return the response content
         */
        @Nullable
        String content();
    }

    /**
     * Specification for making streaming calls.
     */
    interface StreamResponseSpec {
        
        /**
         * Executes the request and returns streaming ChatClientResponse objects.
         * 
         * @return a flux of chat client responses
         */
        Flux<ChatClientResponse> chatClientResponse();

        /**
         * Executes the request and returns streaming ChatResponse objects.
         * 
         * @return a flux of chat responses
         */
        Flux<ChatResponse> chatResponse();

        /**
         * Executes the request and returns streaming content.
         * 
         * @return a flux of content strings
         */
        Flux<String> content();
    }

    /**
     * Specification for configuring advisors.
     */
    interface AdvisorSpec {
        
        /**
         * Sets a parameter for advisors.
         * 
         * @param k the parameter key
         * @param v the parameter value
         * @return this spec for chaining
         */
        AdvisorSpec param(String k, Object v);

        /**
         * Sets multiple parameters for advisors.
         * 
         * @param p the parameters map
         * @return this spec for chaining
         */
        AdvisorSpec params(Map<String, Object> p);

        /**
         * Adds advisors.
         * 
         * @param advisors the advisors to add
         * @return this spec for chaining
         */
        AdvisorSpec advisors(Advisor... advisors);

        /**
         * Adds advisors from a list.
         * 
         * @param advisors the advisors to add
         * @return this spec for chaining
         */
        AdvisorSpec advisors(List<Advisor> advisors);
    }

    /**
     * Specification for configuring system messages.
     */
    interface PromptSystemSpec {
        
        /**
         * Sets the text content of the system message.
         * 
         * @param text the message text
         * @return this spec for chaining
         */
        PromptSystemSpec text(String text);

        /**
         * Sets the text content from a resource.
         * 
         * @param text the resource containing text
         * @param charset the charset to use
         * @return this spec for chaining
         */
        PromptSystemSpec text(Resource text, Charset charset);

        /**
         * Sets the text content from a resource.
         * 
         * @param text the resource containing text
         * @return this spec for chaining
         */
        PromptSystemSpec text(Resource text);

        /**
         * Sets multiple parameters for template substitution.
         * 
         * @param p the parameters map
         * @return this spec for chaining
         */
        PromptSystemSpec params(Map<String, Object> p);

        /**
         * Sets a parameter for template substitution.
         * 
         * @param k the parameter key
         * @param v the parameter value
         * @return this spec for chaining
         */
        PromptSystemSpec param(String k, Object v);
    }

    /**
     * Specification for configuring user messages.
     */
    interface PromptUserSpec {
        
        /**
         * Sets the text content of the user message.
         * 
         * @param text the message text
         * @return this spec for chaining
         */
        PromptUserSpec text(String text);

        /**
         * Sets the text content from a resource.
         * 
         * @param text the resource containing text
         * @param charset the charset to use
         * @return this spec for chaining
         */
        PromptUserSpec text(Resource text, Charset charset);

        /**
         * Sets the text content from a resource.
         * 
         * @param text the resource containing text
         * @return this spec for chaining
         */
        PromptUserSpec text(Resource text);

        /**
         * Sets multiple parameters for template substitution.
         * 
         * @param p the parameters map
         * @return this spec for chaining
         */
        PromptUserSpec params(Map<String, Object> p);

        /**
         * Sets a parameter for template substitution.
         * 
         * @param k the parameter key
         * @param v the parameter value
         * @return this spec for chaining
         */
        PromptUserSpec param(String k, Object v);

        /**
         * Adds media to the user message.
         * 
         * @param media the media to add
         * @return this spec for chaining
         */
        PromptUserSpec media(Media... media);

        /**
         * Adds media from URL.
         * 
         * @param mimeType the media MIME type
         * @param url the media URL
         * @return this spec for chaining
         */
        PromptUserSpec media(MimeType mimeType, URL url);

        /**
         * Adds media from resource.
         * 
         * @param mimeType the media MIME type
         * @param resource the media resource
         * @return this spec for chaining
         */
        PromptUserSpec media(MimeType mimeType, Resource resource);
    }
}
