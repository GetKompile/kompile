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

package ai.kompile.core.llm.memory;

import ai.kompile.core.llm.chat.LLMChat;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.util.Assert;

import java.util.List;
import java.util.UUID;

/**
 * Utility class for working with Kompile chat memory and LLMChat integration.
 * Provides convenience methods for common chat memory operations and conversational flows.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public final class ChatMemoryUtils {

    private ChatMemoryUtils() {
        // Utility class
    }

    /**
     * Generates a unique conversation ID.
     * 
     * @return a unique conversation ID
     */
    public static String generateConversationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Generates a conversation ID based on a user identifier.
     * Useful for maintaining user-specific conversations.
     * 
     * @param userId the user identifier
     * @return a conversation ID for the user
     */
    public static String generateUserConversationId(String userId) {
        Assert.hasText(userId, "userId cannot be null or empty");
        return "user_" + userId + "_" + System.currentTimeMillis();
    }

    /**
     * Generates a session-based conversation ID.
     * Useful for web applications with session management.
     * 
     * @param sessionId the session identifier
     * @return a conversation ID for the session
     */
    public static String generateSessionConversationId(String sessionId) {
        Assert.hasText(sessionId, "sessionId cannot be null or empty");
        return "session_" + sessionId;
    }

    /**
     * Creates a user message from text content.
     * 
     * @param content the message content
     * @return the user message
     */
    public static Message createUserMessage(String content) {
        Assert.hasText(content, "content cannot be null or empty");
        return new UserMessage(content);
    }

    /**
     * Creates an assistant message from text content.
     * 
     * @param content the message content
     * @return the assistant message
     */
    public static Message createAssistantMessage(String content) {
        Assert.hasText(content, "content cannot be null or empty");
        return new AssistantMessage(content);
    }

    /**
     * Calculates the approximate token count for messages.
     * This is a rough estimation based on word count and average tokens per word.
     * 
     * @param messages the messages to count
     * @return the approximate token count
     */
    public static int estimateTokenCount(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        
        int totalChars = messages.stream()
                .mapToInt(message -> message.getText() != null ? message.getText().length() : 0)
                .sum();
        
        // Rough estimation: ~4 characters per token on average
        return Math.max(1, totalChars / 4);
    }

    /**
     * Checks if the estimated token count exceeds a limit.
     * Useful for implementing token-based memory management.
     * 
     * @param messages the messages to check
     * @param tokenLimit the token limit
     * @return true if the estimated count exceeds the limit
     */
    public static boolean exceedsTokenLimit(List<Message> messages, int tokenLimit) {
        Assert.isTrue(tokenLimit > 0, "tokenLimit must be positive");
        return estimateTokenCount(messages) > tokenLimit;
    }

    /**
     * Builds a conversation summary from messages.
     * This can be used for long-term memory or context compression.
     * 
     * @param messages the messages to summarize
     * @param maxSummaryLength the maximum length of the summary
     * @return a summary of the conversation
     */
    public static String buildConversationSummary(List<Message> messages, int maxSummaryLength) {
        Assert.isTrue(maxSummaryLength > 0, "maxSummaryLength must be positive");
        
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        
        StringBuilder summary = new StringBuilder();
        for (Message message : messages) {
            String role = message.getMessageType().name().toLowerCase();
            String content = message.getText();
            
            if (content != null && !content.trim().isEmpty()) {
                summary.append(role).append(": ")
                       .append(content.length() > 100 ? content.substring(0, 100) + "..." : content)
                       .append("\n");
                
                if (summary.length() > maxSummaryLength) {
                    break;
                }
            }
        }
        
        return summary.toString().trim();
    }

    /**
     * Builds a conversation summary using an LLMChat to generate intelligent summaries.
     * 
     * @param llmChat the LLMChat instance to use for summarization
     * @param messages the messages to summarize
     * @param maxSummaryLength the maximum length of the summary
     * @return an AI-generated summary of the conversation
     */
    public static String buildIntelligentSummary(LLMChat llmChat, List<Message> messages, int maxSummaryLength) {
        Assert.notNull(llmChat, "llmChat cannot be null");
        Assert.isTrue(maxSummaryLength > 0, "maxSummaryLength must be positive");
        
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        
        String conversationText = buildConversationSummary(messages, maxSummaryLength * 3);
        
        try {
            return llmChat.prompt()
                    .system("You are a conversation summarizer. Create a concise but informative summary " +
                           "of the conversation that captures the key topics, decisions, and context. " +
                           "Maximum length: " + maxSummaryLength + " characters.")
                    .user("Please summarize this conversation:\n\n" + conversationText)
                    .call()
                    .content();
        } catch (Exception e) {
            // Fallback to simple summary if AI summarization fails
            return buildConversationSummary(messages, maxSummaryLength);
        }
    }

    /**
     * Validates a conversation ID.
     * 
     * @param conversationId the conversation ID to validate
     * @return true if the conversation ID is valid
     */
    public static boolean isValidConversationId(String conversationId) {
        return conversationId != null && 
               !conversationId.trim().isEmpty() && 
               conversationId.length() <= 255; // Reasonable length limit
    }

    /**
     * Sanitizes a conversation ID by removing invalid characters.
     * 
     * @param conversationId the conversation ID to sanitize
     * @return the sanitized conversation ID
     */
    public static String sanitizeConversationId(String conversationId) {
        if (conversationId == null) {
            return generateConversationId();
        }
        
        // Remove problematic characters and limit length
        String sanitized = conversationId.replaceAll("[^a-zA-Z0-9_-]", "_");
        
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 255);
        }
        
        if (sanitized.trim().isEmpty()) {
            return generateConversationId();
        }
        
        return sanitized;
    }

    /**
     * Creates a memory-aware conversational flow using LLMChat.
     * This is a higher-level utility for implementing conversational applications.
     * 
     * @param llmChat the LLMChat instance with memory capabilities
     * @param conversationId the conversation ID
     * @param userMessage the user's message
     * @return the AI response
     */
    public static String conversationalFlow(
            LLMChat llmChat,
            String conversationId,
            String userMessage) {
        
        Assert.notNull(llmChat, "llmChat cannot be null");
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userMessage, "userMessage cannot be null or empty");
        
        return llmChat.prompt()
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * Creates a memory-aware conversational flow with additional context.
     * 
     * @param llmChat the LLMChat instance with memory capabilities
     * @param conversationId the conversation ID
     * @param userMessage the user's message
     * @param contextProvider optional function to provide additional context
     * @return the AI response
     */
    public static String conversationalFlowWithContext(
            LLMChat llmChat,
            String conversationId,
            String userMessage,
            java.util.function.Supplier<String> contextProvider) {
        
        Assert.notNull(llmChat, "llmChat cannot be null");
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userMessage, "userMessage cannot be null or empty");
        
        var promptSpec = llmChat.prompt()
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(userMessage);
        
        // Add context if provider is available
        if (contextProvider != null) {
            String context = contextProvider.get();
            if (context != null && !context.trim().isEmpty()) {
                promptSpec = promptSpec.system("Additional context: " + context);
            }
        }
        
        return promptSpec.call().content();
    }

    /**
     * Creates a reactive conversational flow using LLMChat streaming.
     * 
     * @param llmChat the LLMChat instance with memory capabilities
     * @param conversationId the conversation ID
     * @param userMessage the user's message
     * @return a flux of response chunks
     */
    public static reactor.core.publisher.Flux<String> streamingConversationalFlow(
            LLMChat llmChat,
            String conversationId,
            String userMessage) {
        
        Assert.notNull(llmChat, "llmChat cannot be null");
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userMessage, "userMessage cannot be null or empty");
        
        return llmChat.prompt()
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(userMessage)
                .stream()
                .content();
    }

    /**
     * Creates a reactive conversational flow with additional context.
     * 
     * @param llmChat the LLMChat instance with memory capabilities
     * @param conversationId the conversation ID
     * @param userMessage the user's message
     * @param contextProvider optional function to provide additional context
     * @return a flux of response chunks
     */
    public static reactor.core.publisher.Flux<String> streamingConversationalFlowWithContext(
            LLMChat llmChat,
            String conversationId,
            String userMessage,
            java.util.function.Supplier<String> contextProvider) {
        
        Assert.notNull(llmChat, "llmChat cannot be null");
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userMessage, "userMessage cannot be null or empty");
        
        var promptSpec = llmChat.prompt()
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(userMessage);
        
        // Add context if provider is available
        if (contextProvider != null) {
            String context = contextProvider.get();
            if (context != null && !context.trim().isEmpty()) {
                promptSpec = promptSpec.system("Additional context: " + context);
            }
        }
        
        return promptSpec.stream().content();
    }

    /**
     * Manages conversation memory by checking token limits and summarizing when necessary.
     * 
     * @param chatMemory the chat memory instance
     * @param llmChat the LLMChat instance for summarization
     * @param conversationId the conversation ID
     * @param tokenLimit the maximum token count before summarization
     * @return true if summarization was performed, false otherwise
     */
    public static boolean manageConversationMemory(
            KompileChatMemory chatMemory,
            LLMChat llmChat,
            String conversationId,
            int tokenLimit) {
        
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(llmChat, "llmChat cannot be null");
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.isTrue(tokenLimit > 0, "tokenLimit must be positive");
        
        List<Message> messages = chatMemory.get(conversationId);
        
        if (exceedsTokenLimit(messages, tokenLimit)) {
            // Summarize the conversation
            String summary = buildIntelligentSummary(llmChat, messages, tokenLimit / 4);
            
            // Clear and add summary
            chatMemory.clear(conversationId);
            if (!summary.isEmpty()) {
                chatMemory.add(conversationId, createAssistantMessage(
                        "Previous conversation summary: " + summary));
            }
            
            return true;
        }
        
        return false;
    }

    /**
     * Creates a conversational session with automatic memory management.
     * This utility encapsulates the common pattern of conversation with memory cleanup.
     * 
     * @param llmChat the LLMChat instance
     * @param chatMemory the chat memory instance  
     * @param conversationId the conversation ID
     * @param userMessage the user's message
     * @param maxTokens the maximum tokens before summarization
     * @return the AI response
     */
    public static String managedConversationalSession(
            LLMChat llmChat,
            KompileChatMemory chatMemory,
            String conversationId,
            String userMessage,
            int maxTokens) {
        
        Assert.notNull(llmChat, "llmChat cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.hasText(userMessage, "userMessage cannot be null or empty");
        Assert.isTrue(maxTokens > 0, "maxTokens must be positive");
        
        // Manage memory before processing the message
        manageConversationMemory(chatMemory, llmChat, conversationId, maxTokens);
        
        // Process the conversational flow
        return conversationalFlow(llmChat, conversationId, userMessage);
    }

    /**
     * Creates a batch conversational processor for handling multiple conversations efficiently.
     * 
     * @param llmChat the LLMChat instance
     * @param chatMemory the chat memory instance
     * @param conversations map of conversation ID to user message
     * @param maxTokensPerConversation maximum tokens per conversation
     * @return map of conversation ID to AI response
     */
    public static java.util.Map<String, String> batchConversationalProcess(
            LLMChat llmChat,
            KompileChatMemory chatMemory,
            java.util.Map<String, String> conversations,
            int maxTokensPerConversation) {
        
        Assert.notNull(llmChat, "llmChat cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(conversations, "conversations cannot be null");
        Assert.isTrue(maxTokensPerConversation > 0, "maxTokensPerConversation must be positive");
        
        java.util.Map<String, String> responses = new java.util.HashMap<>();
        
        for (java.util.Map.Entry<String, String> entry : conversations.entrySet()) {
            String conversationId = entry.getKey();
            String userMessage = entry.getValue();
            
            try {
                String response = managedConversationalSession(
                        llmChat, chatMemory, conversationId, userMessage, maxTokensPerConversation);
                responses.put(conversationId, response);
            } catch (Exception e) {
                responses.put(conversationId, "Error: " + e.getMessage());
            }
        }
        
        return responses;
    }
}
