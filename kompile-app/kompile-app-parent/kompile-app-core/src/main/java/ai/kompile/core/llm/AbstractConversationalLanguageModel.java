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

package ai.kompile.core.llm;

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.llm.chat.LLMChatFactory;
import ai.kompile.core.llm.memory.KompileChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Base implementation of ConversationalLanguageModel that provides memory-aware conversation capabilities
 * using LLMChat for enhanced functionality and better integration with Kompile's abstractions.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public abstract class AbstractConversationalLanguageModel implements ConversationalLanguageModel {
    
    private static final Logger logger = LoggerFactory.getLogger(AbstractConversationalLanguageModel.class);
    
    protected final KompileChatMemory chatMemory;
    protected final LLMChat llmChat;

    /**
     * Creates a new conversational language model using LLMChat.
     * 
     * @param chatMemory the chat memory for storing conversation history
     * @param springAiChatMemory the Spring AI chat memory adapter
     * @param chatClientBuilder the chat client builder
     */
    protected AbstractConversationalLanguageModel(
            KompileChatMemory chatMemory,
            ChatMemory springAiChatMemory,
            ChatClient.Builder chatClientBuilder) {
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(springAiChatMemory, "springAiChatMemory cannot be null");
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        
        this.chatMemory = chatMemory;
        this.llmChat = LLMChatFactory.builder(chatClientBuilder)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(springAiChatMemory).build())
                .build();
    }

    /**
     * Creates a new conversational language model directly from an LLMChat instance.
     * This constructor is useful when you already have a configured LLMChat.
     * 
     * @param chatMemory the chat memory for storing conversation history
     * @param llmChat the configured LLMChat instance
     */
    protected AbstractConversationalLanguageModel(
            KompileChatMemory chatMemory,
            LLMChat llmChat) {
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(llmChat, "llmChat cannot be null");
        
        this.chatMemory = chatMemory;
        this.llmChat = llmChat;
    }

    @Override
    public String generateConversationalResponse(String conversationId, String userQuery, List<String> context) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userQuery, "userQuery cannot be null or empty");
        
        try {
            String contextStr = context != null && !context.isEmpty() ? 
                String.join("\n", context) : "";
            
            var promptSpec = llmChat.prompt()
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .user(buildPrompt(userQuery, contextStr));
            
            String response = promptSpec.call().content();
            
            logger.debug("Generated conversational response for conversation {}: {} characters", 
                    conversationId, response != null ? response.length() : 0);
            
            return response;
            
        } catch (Exception e) {
            logger.error("Error generating conversational response for conversation {}: {}", 
                    conversationId, e.getMessage(), e);
            return "Error: Unable to generate response. " + e.getMessage();
        }
    }

    @Override
    public ChatResponse generateConversationalResponseWithToolCalls(String conversationId, String userQuery, List<String> context) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userQuery, "userQuery cannot be null or empty");
        
        try {
            String contextStr = context != null && !context.isEmpty() ? 
                String.join("\n", context) : "";
            
            var promptSpec = llmChat.prompt()
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .user(buildPrompt(userQuery, contextStr));
            
            ChatResponse response = promptSpec.call().chatResponse();
            
            logger.debug("Generated conversational response with tool calls for conversation {}", conversationId);
            
            return response;
            
        } catch (Exception e) {
            logger.error("Error generating conversational response with tool calls for conversation {}: {}", 
                    conversationId, e.getMessage(), e);
            // Return error response
            return createErrorResponse("Error: Unable to generate response. " + e.getMessage());
        }
    }

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        Assert.hasText(userQuery, "userQuery cannot be null or empty");
        
        // For non-conversational calls, generate a temporary conversation ID
        String tempConversationId = "temp_" + System.currentTimeMillis();
        String response = generateConversationalResponse(tempConversationId, userQuery, context);
        
        // Clean up the temporary conversation
        clearConversation(tempConversationId);
        
        return response;
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        Assert.hasText(userQuery, "userQuery cannot be null or empty");
        
        // For non-conversational calls, generate a temporary conversation ID
        String tempConversationId = "temp_" + System.currentTimeMillis();
        ChatResponse response = generateConversationalResponseWithToolCalls(tempConversationId, userQuery, context);
        
        // Clean up the temporary conversation
        clearConversation(tempConversationId);
        
        return response;
    }

    @Override
    public void clearConversation(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        
        try {
            chatMemory.clear(conversationId);
            logger.debug("Cleared conversation {}", conversationId);
        } catch (Exception e) {
            logger.error("Error clearing conversation {}: {}", conversationId, e.getMessage(), e);
        }
    }

    @Override
    public KompileChatMemory getChatMemory() {
        return chatMemory;
    }

    /**
     * Generates a streaming conversational response.
     * This method provides reactive streaming capabilities for conversational interactions.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param userQuery the user's input
     * @param context additional context to include in the prompt
     * @return a flux of response chunks
     */
    public reactor.core.publisher.Flux<String> generateStreamingConversationalResponse(
            String conversationId, 
            String userQuery, 
            List<String> context) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userQuery, "userQuery cannot be null or empty");
        
        try {
            String contextStr = context != null && !context.isEmpty() ? 
                String.join("\n", context) : "";
            
            var promptSpec = llmChat.prompt()
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .user(buildPrompt(userQuery, contextStr));
            
            return promptSpec.stream().content()
                    .doOnNext(chunk -> logger.trace("Streaming chunk for conversation {}: {}", conversationId, chunk))
                    .doOnComplete(() -> logger.debug("Completed streaming response for conversation {}", conversationId))
                    .doOnError(error -> logger.error("Error in streaming response for conversation {}: {}", 
                            conversationId, error.getMessage(), error));
            
        } catch (Exception e) {
            logger.error("Error starting streaming conversational response for conversation {}: {}", 
                    conversationId, e.getMessage(), e);
            return reactor.core.publisher.Flux.just("Error: Unable to generate streaming response. " + e.getMessage());
        }
    }

    /**
     * Generates a conversational response with enhanced context management.
     * This method provides additional features like automatic context trimming and intelligent summarization.
     * 
     * @param conversationId the unique identifier for the conversation
     * @param userQuery the user's input
     * @param context additional context to include in the prompt
     * @param maxTokens the maximum number of tokens to use
     * @return the generated response
     */
    public String generateEnhancedConversationalResponse(
            String conversationId, 
            String userQuery, 
            List<String> context,
            int maxTokens) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userQuery, "userQuery cannot be null or empty");
        Assert.isTrue(maxTokens > 0, "maxTokens must be positive");
        
        try {
            // Check if we need to manage context due to token limits
            List<org.springframework.ai.chat.messages.Message> conversationHistory = chatMemory.get(conversationId);
            
            if (ai.kompile.core.llm.memory.ChatMemoryUtils.exceedsTokenLimit(conversationHistory, maxTokens)) {
                // Summarize older messages to stay within token limit
                String summary = ai.kompile.core.llm.memory.ChatMemoryUtils.buildIntelligentSummary(
                        llmChat, conversationHistory, maxTokens / 4);
                
                // Clear the conversation and add the summary
                chatMemory.clear(conversationId);
                if (!summary.isEmpty()) {
                    chatMemory.add(conversationId, 
                            ai.kompile.core.llm.memory.ChatMemoryUtils.createAssistantMessage(
                                    "Previous conversation summary: " + summary));
                }
            }
            
            return generateConversationalResponse(conversationId, userQuery, context);
            
        } catch (Exception e) {
            logger.error("Error generating enhanced conversational response for conversation {}: {}", 
                    conversationId, e.getMessage(), e);
            return "Error: Unable to generate enhanced response. " + e.getMessage();
        }
    }

    /**
     * Builds the full prompt including user query and context.
     * Subclasses can override this to customize prompt construction.
     * 
     * @param userQuery the user's input
     * @param context the additional context
     * @return the full prompt
     */
    protected String buildPrompt(String userQuery, String context) {
        if (context == null || context.trim().isEmpty()) {
            return userQuery;
        }
        
        return String.format("Context:\n%s\n\nUser Query: %s", context.trim(), userQuery);
    }

    /**
     * Creates an error response for exceptional cases.
     * Subclasses can override this to customize error handling.
     * 
     * @param errorMessage the error message
     * @return the error chat response
     */
    protected abstract ChatResponse createErrorResponse(String errorMessage);

    /**
     * Gets the underlying LLMChat for advanced operations.
     * 
     * @return the LLMChat instance
     */
    protected LLMChat getLLMChat() {
        return llmChat;
    }

    /**
     * Gets the underlying Spring AI ChatClient if available.
     * This method provides access to the wrapped ChatClient for advanced scenarios.
     * 
     * @return the ChatClient instance if available
     * @throws UnsupportedOperationException if the underlying implementation doesn't support ChatClient access
     */
    protected ChatClient getChatClient() {
        try {
            return ai.kompile.core.llm.chat.LLMChatUtils.extractChatClient(llmChat);
        } catch (Exception e) {
            throw new UnsupportedOperationException("Cannot extract ChatClient from LLMChat implementation", e);
        }
    }
}
