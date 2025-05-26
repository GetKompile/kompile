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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for LLMChat components.
 * This configuration class automatically sets up LLMChat beans when the required
 * dependencies are available, providing sensible defaults while allowing for customization.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(LLMChatConfiguration.LLMChatProperties.class)
@ConditionalOnProperty(prefix = "kompile.llm.chat", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LLMChatConfiguration {

    /**
     * Configuration properties for LLMChat.
     */
    @ConfigurationProperties(prefix = "kompile.llm.chat")
    public static class LLMChatProperties {
        
        /**
         * Whether to enable LLMChat auto-configuration.
         */
        private boolean enabled = true;
        
        /**
         * Default system message for all LLMChat instances.
         */
        private String defaultSystem;
        
        /**
         * Whether to automatically configure chat memory advisor when ChatMemory is available.
         */
        private boolean autoConfigureMemory = true;
        
        /**
         * Whether to automatically configure RAG advisor when VectorStore is available.
         */
        private boolean autoConfigureRag = true;
        
        /**
         * Maximum number of documents to retrieve for RAG operations.
         */
        private int ragMaxDocuments = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDefaultSystem() {
            return defaultSystem;
        }

        public void setDefaultSystem(String defaultSystem) {
            this.defaultSystem = defaultSystem;
        }

        public boolean isAutoConfigureMemory() {
            return autoConfigureMemory;
        }

        public void setAutoConfigureMemory(boolean autoConfigureMemory) {
            this.autoConfigureMemory = autoConfigureMemory;
        }

        public boolean isAutoConfigureRag() {
            return autoConfigureRag;
        }

        public void setAutoConfigureRag(boolean autoConfigureRag) {
            this.autoConfigureRag = autoConfigureRag;
        }

        public int getRagMaxDocuments() {
            return ragMaxDocuments;
        }

        public void setRagMaxDocuments(int ragMaxDocuments) {
            this.ragMaxDocuments = ragMaxDocuments;
        }
    }

    /**
     * Provides an LLMChat.Builder bean for autowiring.
     * This bean allows other components to inject and use LLMChat.Builder directly.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @return an LLMChat.Builder instance
     */
    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnMissingBean(LLMChat.Builder.class)
    public LLMChat.Builder llmChatBuilder(ChatClient.Builder chatClientBuilder) {
        return LLMChatFactory.builder(chatClientBuilder);
    }

    /**
     * Creates a basic LLMChat instance when no custom implementation is provided.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param properties the configuration properties
     * @return a configured LLMChat instance
     */
    @Bean
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnMissingBean
    public LLMChat llmChat(
            ChatClient.Builder chatClientBuilder,
            LLMChatProperties properties) {
        
        LLMChat.Builder builder = LLMChatFactory.builder(chatClientBuilder);
        
        if (properties.getDefaultSystem() != null) {
            builder = builder.defaultSystem(properties.getDefaultSystem());
        }
        
        return builder.build();
    }

    /**
     * Creates an LLMChat with chat memory when ChatMemory is available and auto-configuration is enabled.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param chatMemory the chat memory implementation
     * @param properties the configuration properties
     * @return a configured LLMChat instance with memory
     */
    @Bean("llmChatWithMemory")
    @ConditionalOnBean({ChatClient.Builder.class, ChatMemory.class})
    @ConditionalOnProperty(prefix = "kompile.llm.chat", name = "auto-configure-memory", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "llmChat")
    public LLMChat llmChatWithMemory(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            LLMChatProperties properties) {
        
        LLMChat.Builder builder = LLMChatFactory.builder(chatClientBuilder);
        
        if (properties.getDefaultSystem() != null) {
            builder = builder.defaultSystem(properties.getDefaultSystem());
        }
        
        // Add memory advisor
        builder = builder.defaultAdvisors(
                org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
                        .builder(chatMemory)
                        .build()
        );
        
        return builder.build();
    }

    /**
     * Creates an LLMChat with RAG when VectorStore is available and auto-configuration is enabled.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param vectorStore the vector store implementation
     * @param properties the configuration properties
     * @return a configured LLMChat instance with RAG
     */
    @Bean("llmChatWithRAG")
    @ConditionalOnBean({ChatClient.Builder.class, VectorStore.class})
    @ConditionalOnProperty(prefix = "kompile.llm.chat", name = "auto-configure-rag", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = {"llmChat", "llmChatWithMemory"})
    public LLMChat llmChatWithRAG(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            LLMChatProperties properties) {
        
        return LLMChatUtils.createDocumentAnalyst(
                chatClientBuilder,
                vectorStore,
                properties.getRagMaxDocuments()
        );
    }

    /**
     * Creates a comprehensive LLMChat with both memory and RAG when both are available.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param chatMemory the chat memory implementation
     * @param vectorStore the vector store implementation
     * @param properties the configuration properties
     * @return a configured LLMChat instance with memory and RAG
     */
    @Bean("llmChatComprehensive")
    @ConditionalOnBean({ChatClient.Builder.class, ChatMemory.class, VectorStore.class})
    @ConditionalOnProperty(prefix = "kompile.llm.chat", name = {"auto-configure-memory", "auto-configure-rag"}, havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = {"llmChat", "llmChatWithMemory", "llmChatWithRAG"})
    public LLMChat llmChatComprehensive(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            VectorStore vectorStore,
            LLMChatProperties properties) {
        
        String systemMessage = properties.getDefaultSystem();
        if (systemMessage == null) {
            systemMessage = "You are a helpful AI assistant with access to conversation history " +
                          "and document context. Use the provided information to give accurate, " +
                          "contextual responses.";
        }
        
        return LLMChatUtils.createComprehensive(
                chatClientBuilder,
                chatMemory,
                vectorStore,
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .topK(properties.getRagMaxDocuments())
                        .build()
        );
    }
}
