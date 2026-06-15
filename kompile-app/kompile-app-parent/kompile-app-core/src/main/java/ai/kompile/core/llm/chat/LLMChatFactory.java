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
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Factory class for creating LLMChat instances with various configuration options.
 * This factory simplifies the creation of LLMChat instances and provides convenient
 * methods for common use cases.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public final class LLMChatFactory {

    private LLMChatFactory() {
        // Utility class
    }

    /**
     * Creates an LLMChat builder with the specified ChatClient.Builder.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @return a new LLMChat builder
     */
    public static LLMChat.Builder builder(ChatClient.Builder chatClientBuilder) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        return new DefaultLLMChatBuilder(chatClientBuilder);
    }

    /**
     * Creates an LLMChat instance directly from a ChatClient.Builder.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @return a new LLMChat instance
     */
    public static LLMChat create(ChatClient.Builder chatClientBuilder) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        return new DefaultLLMChat(chatClientBuilder.build());
    }

    /**
     * Creates an LLMChat instance directly from a ChatClient.
     * 
     * @param chatClient the Spring AI ChatClient
     * @return a new LLMChat instance
     */
    public static LLMChat create(ChatClient chatClient) {
        Assert.notNull(chatClient, "chatClient cannot be null");
        return new DefaultLLMChat(chatClient);
    }

    /**
     * Creates an LLMChat with a default system message.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param systemMessage the default system message
     * @return a new LLMChat instance
     */
    public static LLMChat createWithSystem(ChatClient.Builder chatClientBuilder, String systemMessage) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.hasText(systemMessage, "systemMessage cannot be null or empty");
        
        return builder(chatClientBuilder)
                .defaultSystem(systemMessage)
                .build();
    }

    /**
     * Creates an LLMChat with default advisors.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param advisors the default advisors
     * @return a new LLMChat instance
     */
    public static LLMChat createWithAdvisors(ChatClient.Builder chatClientBuilder, Advisor... advisors) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(advisors, "advisors cannot be null");
        
        return builder(chatClientBuilder)
                .defaultAdvisors(advisors)
                .build();
    }

    /**
     * Creates an LLMChat with default advisors from a list.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param advisors the default advisors
     * @return a new LLMChat instance
     */
    public static LLMChat createWithAdvisors(ChatClient.Builder chatClientBuilder, List<Advisor> advisors) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(advisors, "advisors cannot be null");
        
        return builder(chatClientBuilder)
                .defaultAdvisors(advisors)
                .build();
    }

    /**
     * Creates an LLMChat with default function names.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param functionNames the default function names
     * @return a new LLMChat instance
     */
    public static LLMChat createWithFunctions(ChatClient.Builder chatClientBuilder, String... functionNames) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(functionNames, "functionNames cannot be null");
        
        return builder(chatClientBuilder)
                .defaultToolNames(functionNames)
                .build();
    }

    /**
     * Creates an LLMChat with default tools.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param toolObjects the default tool objects
     * @return a new LLMChat instance
     */
    public static LLMChat createWithTools(ChatClient.Builder chatClientBuilder, Object... toolObjects) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(toolObjects, "toolObjects cannot be null");
        
        return builder(chatClientBuilder)
                .defaultTools(toolObjects)
                .build();
    }

    /**
     * Creates an LLMChat with default tool callbacks.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param toolCallbacks the default tool callbacks
     * @return a new LLMChat instance
     */
    public static LLMChat createWithToolCallbacks(ChatClient.Builder chatClientBuilder, ToolCallback... toolCallbacks) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
        
        return builder(chatClientBuilder)
                .defaultToolCallbacks(toolCallbacks)
                .build();
    }

    /**
     * Creates an LLMChat with default tool callbacks from a list.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param toolCallbacks the default tool callbacks
     * @return a new LLMChat instance
     */
    public static LLMChat createWithToolCallbacks(ChatClient.Builder chatClientBuilder, List<ToolCallback> toolCallbacks) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
        
        return builder(chatClientBuilder)
                .defaultToolCallbacks(toolCallbacks)
                .build();
    }

    /**
     * Creates a fully configured LLMChat with system message, advisors, and function names.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param systemMessage the default system message
     * @param advisors the default advisors
     * @param functionNames the default function names
     * @return a new LLMChat instance
     */
    public static LLMChat createConfigured(
            ChatClient.Builder chatClientBuilder,
            String systemMessage,
            List<Advisor> advisors,
            String... functionNames) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        
        LLMChat.Builder builder = builder(chatClientBuilder);
        
        if (systemMessage != null && !systemMessage.trim().isEmpty()) {
            builder = builder.defaultSystem(systemMessage);
        }
        
        if (advisors != null && !advisors.isEmpty()) {
            builder = builder.defaultAdvisors(advisors);
        }
        
        if (functionNames != null && functionNames.length > 0) {
            builder = builder.defaultToolNames(functionNames);
        }
        
        return builder.build();
    }

    /**
     * Creates a fully configured LLMChat with options.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param systemMessage the default system message
     * @param advisors the default advisors
     * @param chatOptions the default chat options
     * @param functionNames the default function names
     * @return a new LLMChat instance
     */
    public static LLMChat createConfigured(
            ChatClient.Builder chatClientBuilder,
            String systemMessage,
            List<Advisor> advisors,
            ChatOptions chatOptions,
            String... functionNames) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        
        LLMChat.Builder builder = builder(chatClientBuilder);
        
        if (systemMessage != null && !systemMessage.trim().isEmpty()) {
            builder = builder.defaultSystem(systemMessage);
        }
        
        if (advisors != null && !advisors.isEmpty()) {
            builder = builder.defaultAdvisors(advisors);
        }
        
        if (chatOptions != null) {
            builder = builder.defaultOptions(chatOptions);
        }
        
        if (functionNames != null && functionNames.length > 0) {
            builder = builder.defaultToolNames(functionNames);
        }
        
        return builder.build();
    }

    /**
     * Wraps an existing ChatClient in an LLMChat interface.
     * 
     * @param chatClient the existing ChatClient
     * @return a new LLMChat instance wrapping the ChatClient
     */
    public static LLMChat wrap(ChatClient chatClient) {
        return create(chatClient);
    }
}
