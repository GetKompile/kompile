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
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for working with LLMChat and common Spring AI advisor configurations.
 * Provides convenience methods for creating LLMChat instances with pre-configured
 * advisors for common use cases like RAG, chat memory, and function calling.
 * 
 * @author Kompile Inc.
 * @since 1.0.0
 */
public final class LLMChatUtils {

    private LLMChatUtils() {
        // Utility class
    }

    /**
     * Creates an LLMChat with message-based chat memory.
     * The conversation history is included as a collection of messages.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param chatMemory the chat memory implementation
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithMessageMemory(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        
        return LLMChatFactory.createWithAdvisors(
                chatClientBuilder,
                MessageChatMemoryAdvisor.builder(chatMemory).build()
        );
    }

    /**
     * Creates an LLMChat with prompt-based chat memory.
     * The conversation history is appended to the system prompt as plain text.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param chatMemory the chat memory implementation
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithPromptMemory(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        
        return LLMChatFactory.createWithAdvisors(
                chatClientBuilder,
                PromptChatMemoryAdvisor.builder(chatMemory).build()
        );
    }

    /**
     * Creates an LLMChat with vector store-based chat memory.
     * The conversation history is stored and retrieved from a vector store.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param vectorStore the vector store implementation
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithVectorMemory(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        
        return LLMChatFactory.createWithAdvisors(
                chatClientBuilder,
                VectorStoreChatMemoryAdvisor.builder(vectorStore).build()
        );
    }

    /**
     * Creates an LLMChat with RAG (Retrieval-Augmented Generation) capabilities.
     * Uses a vector store to provide question-answering with context retrieval.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param vectorStore the vector store for context retrieval
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithRAG(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        
        return LLMChatFactory.createWithAdvisors(
                chatClientBuilder,
                QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.defaults())
                        .build()
        );
    }

    /**
     * Creates an LLMChat with custom RAG configuration.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param vectorStore the vector store for context retrieval
     * @param searchRequest the search request configuration
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithRAG(
            ChatClient.Builder chatClientBuilder, 
            VectorStore vectorStore, 
            SearchRequest searchRequest) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        Assert.notNull(searchRequest, "searchRequest cannot be null");
        
        return LLMChatFactory.createWithAdvisors(
                chatClientBuilder,
                QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(searchRequest)
                        .build()
        );
    }

    /**
     * Creates an LLMChat with both chat memory and RAG capabilities.
     * This combines conversational memory with context retrieval for comprehensive AI interactions.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param chatMemory the chat memory implementation
     * @param vectorStore the vector store for context retrieval
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithMemoryAndRAG(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            VectorStore vectorStore) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        
        List<Advisor> advisors = Arrays.asList(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.defaults())
                        .build()
        );
        
        return LLMChatFactory.createWithAdvisors(chatClientBuilder, advisors);
    }

    /**
     * Creates an LLMChat with comprehensive configuration including memory, RAG, and custom advisors.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param chatMemory the chat memory implementation
     * @param vectorStore the vector store for context retrieval
     * @param searchRequest the search request configuration
     * @param additionalAdvisors additional custom advisors
     * @return a configured LLMChat instance
     */
    public static LLMChat createComprehensive(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            VectorStore vectorStore,
            SearchRequest searchRequest,
            Advisor... additionalAdvisors) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        Assert.notNull(searchRequest, "searchRequest cannot be null");
        
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        advisors.add(QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .build());
        
        if (additionalAdvisors != null) {
            advisors.addAll(Arrays.asList(additionalAdvisors));
        }
        
        return LLMChatFactory.createWithAdvisors(chatClientBuilder, advisors);
    }

    /**
     * Creates an LLMChat configured for conversational AI with a specific persona.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param chatMemory the chat memory implementation
     * @param systemPersona the system message defining the AI's persona
     * @return a configured LLMChat instance
     */
    public static LLMChat createConversationalBot(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            String systemPersona) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.hasText(systemPersona, "systemPersona cannot be null or empty");
        
        return LLMChatFactory.builder(chatClientBuilder)
                .defaultSystem(systemPersona)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * Creates an LLMChat for function calling scenarios.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param functionNames the names of functions to make available
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithFunctions(ChatClient.Builder chatClientBuilder, String... functionNames) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(functionNames, "functionNames cannot be null");
        
        return LLMChatFactory.createWithFunctions(chatClientBuilder, functionNames);
    }

    /**
     * Creates an LLMChat for document analysis with RAG capabilities.
     * Optimized for processing and answering questions about documents.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param vectorStore the vector store containing document embeddings
     * @param maxDocuments the maximum number of documents to retrieve
     * @return a configured LLMChat instance
     */
    public static LLMChat createDocumentAnalyst(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            int maxDocuments) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        Assert.isTrue(maxDocuments > 0, "maxDocuments must be positive");
        
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(maxDocuments)
                .build();
        
        return LLMChatFactory.builder(chatClientBuilder)
                .defaultSystem("You are a helpful document analyst. Analyze the provided context carefully " +
                              "and answer questions based on the information in the documents. If the answer " +
                              "is not in the provided context, clearly state that.")
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(searchRequest)
                        .build())
                .build();
    }

    /**
     * Extracts the underlying Spring AI ChatClient from an LLMChat instance.
     * 
     * @param llmChat the LLMChat instance
     * @return the underlying ChatClient
     */
    public static ChatClient extractChatClient(LLMChat llmChat) {
        Assert.notNull(llmChat, "llmChat cannot be null");
        
        if (llmChat instanceof DefaultLLMChat) {
            return ((DefaultLLMChat) llmChat).getChatClient();
        }
        
        throw new IllegalArgumentException("Cannot extract ChatClient from unknown LLMChat implementation: " 
                + llmChat.getClass().getName());
    }

    /**
     * Checks if an LLMChat instance is backed by a Spring AI ChatClient.
     * 
     * @param llmChat the LLMChat instance to check
     * @return true if backed by ChatClient, false otherwise
     */
    public static boolean isSpringAIBacked(LLMChat llmChat) {
        return llmChat instanceof DefaultLLMChat;
    }
}
