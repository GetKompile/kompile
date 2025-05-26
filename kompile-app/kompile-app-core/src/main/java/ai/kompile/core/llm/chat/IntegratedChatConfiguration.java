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

import ai.kompile.core.llm.memory.KompileChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sample configuration showing how to integrate LLMChat with Kompile's chat memory system.
 * This demonstrates best practices for creating fully-featured conversational AI applications.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
@Configuration
@ConditionalOnBean({ChatClient.Builder.class, KompileChatMemory.class})
public class IntegratedChatConfiguration {

    /**
     * Creates an LLMChat instance that uses Kompile's chat memory system.
     * This provides the best of both worlds: LLMChat's fluent API and 
     * Kompile's enhanced memory management.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param kompileChatMemory the Kompile chat memory implementation
     * @param springAiChatMemory the Spring AI chat memory adapter
     * @return a fully configured LLMChat with memory capabilities
     */
    @Bean("integratedLLMChat")
    @ConditionalOnMissingBean(name = "integratedLLMChat")
    public LLMChat integratedLLMChat(
            ChatClient.Builder chatClientBuilder,
            KompileChatMemory kompileChatMemory,
            ChatMemory springAiChatMemory) {
        
        return LLMChatFactory.builder(chatClientBuilder)
                .defaultSystem(buildSystemMessage())
                .defaultAdvisors(
                        // Use Spring AI's MessageChatMemoryAdvisor with our adapter
                        org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
                                .builder(springAiChatMemory)
                                .build()
                )
                .build();
    }

    /**
     * Creates a specialized LLMChat for document analysis scenarios.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param kompileChatMemory the Kompile chat memory implementation
     * @param springAiChatMemory the Spring AI chat memory adapter
     * @return an LLMChat configured for document analysis
     */
    @Bean("documentAnalysisLLMChat")
    @ConditionalOnMissingBean(name = "documentAnalysisLLMChat")
    public LLMChat documentAnalysisLLMChat(
            ChatClient.Builder chatClientBuilder,
            KompileChatMemory kompileChatMemory,
            ChatMemory springAiChatMemory) {
        
        return LLMChatFactory.builder(chatClientBuilder)
                .defaultSystem(buildDocumentAnalysisSystemMessage())
                .defaultAdvisors(
                        org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
                                .builder(springAiChatMemory)
                                .build()
                )
                .build();
    }

    /**
     * Creates a coding assistant LLMChat with memory.
     * 
     * @param chatClientBuilder the Spring AI ChatClient builder
     * @param kompileChatMemory the Kompile chat memory implementation
     * @param springAiChatMemory the Spring AI chat memory adapter
     * @return an LLMChat configured as a coding assistant
     */
    @Bean("codingAssistantLLMChat")
    @ConditionalOnMissingBean(name = "codingAssistantLLMChat")
    public LLMChat codingAssistantLLMChat(
            ChatClient.Builder chatClientBuilder,
            KompileChatMemory kompileChatMemory,
            ChatMemory springAiChatMemory) {
        
        return LLMChatFactory.builder(chatClientBuilder)
                .defaultSystem(buildCodingAssistantSystemMessage())
                .defaultAdvisors(
                        org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
                                .builder(springAiChatMemory)
                                .build()
                )
                .build();
    }

    /**
     * Builds a comprehensive system message for general-purpose conversations.
     */
    private String buildSystemMessage() {
        return """
            You are a helpful AI assistant with access to conversation history.
            
            Key behaviors:
            - Maintain context from previous messages in the conversation
            - Provide accurate, helpful, and detailed responses
            - Ask clarifying questions when needed
            - Be concise but thorough in your explanations
            - If you don't know something, admit it rather than guessing
            
            Remember that you have access to the conversation history, so you can
            reference previous topics and build upon earlier discussions.
            """;
    }

    /**
     * Builds a system message optimized for document analysis.
     */
    private String buildDocumentAnalysisSystemMessage() {
        return """
            You are a specialized document analysis assistant with access to conversation history.
            
            Your primary functions:
            - Analyze documents and extract key information
            - Answer questions based on document content
            - Summarize complex documents
            - Compare and contrast multiple documents
            - Identify patterns and insights from textual data
            
            Guidelines:
            - Always base your responses on the provided document content
            - If information is not in the documents, clearly state this
            - Reference specific sections or pages when possible
            - Maintain context from previous analyses in our conversation
            - Provide structured, well-organized responses
            """;
    }

    /**
     * Builds a system message optimized for coding assistance.
     */
    private String buildCodingAssistantSystemMessage() {
        return """
            You are an expert coding assistant specializing in Java, Spring Framework, and related technologies.
            You have access to our conversation history.
            
            Your expertise includes:
            - Java programming (versions 8-21+)
            - Spring Framework (Boot, MVC, Data, Security, etc.)
            - Build tools (Maven, Gradle)
            - Testing (JUnit, Mockito, TestContainers)
            - Database integration
            - REST APIs and microservices
            - Code review and best practices
            
            Guidelines:
            - Provide working, production-ready code examples
            - Explain the reasoning behind your suggestions
            - Follow Java and Spring best practices
            - Consider security, performance, and maintainability
            - Reference our previous discussion when relevant
            - Ask for clarification on requirements when needed
            """;
    }
}
