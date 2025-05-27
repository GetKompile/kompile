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

import ai.kompile.core.embeddings.VectorStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for working with LLMChat and common Spring AI advisor configurations.
 * Provides convenience methods for creating LLMChat instances with pre-configured
 * advisors for common use cases like RAG, chat memory, and function calling.
 * This class has been updated to work with the Kompile VectorStore wrapper.
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
     * Creates an LLMChat with custom vector store-based chat memory adapter.
     * This creates a Spring AI compatible VectorStore from our Kompile VectorStore wrapper.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param vectorStore the Kompile vector store implementation
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithVectorMemory(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        
        // Create adapter from our VectorStore to Spring AI VectorStore
        org.springframework.ai.vectorstore.VectorStore springAiVectorStore = createSpringAiVectorStoreAdapter(vectorStore);
        
        return LLMChatFactory.createWithAdvisors(
                chatClientBuilder,
                VectorStoreChatMemoryAdvisor.builder(springAiVectorStore).build()
        );
    }

    /**
     * Creates an LLMChat with RAG (Retrieval-Augmented Generation) capabilities.
     * Uses a vector store to provide question-answering with context retrieval.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param vectorStore the Kompile vector store for context retrieval
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithRAG(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        
        // Create adapter from our VectorStore to Spring AI VectorStore
        org.springframework.ai.vectorstore.VectorStore springAiVectorStore = createSpringAiVectorStoreAdapter(vectorStore);
        
        return LLMChatFactory.createWithAdvisors(
                chatClientBuilder,
                QuestionAnswerAdvisor.builder(springAiVectorStore)
                        .searchRequest(org.springframework.ai.vectorstore.SearchRequest.builder().build())
                        .build()
        );
    }

    /**
     * Creates an LLMChat with custom RAG configuration.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param vectorStore the Kompile vector store for context retrieval
     * @param maxDocuments the maximum number of documents to retrieve
     * @param similarityThreshold the similarity threshold for document retrieval
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithRAG(
            ChatClient.Builder chatClientBuilder, 
            VectorStore vectorStore, 
            int maxDocuments,
            double similarityThreshold) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        Assert.isTrue(maxDocuments > 0, "maxDocuments must be positive");
        
        // Create adapter from our VectorStore to Spring AI VectorStore
        org.springframework.ai.vectorstore.VectorStore springAiVectorStore = createSpringAiVectorStoreAdapter(vectorStore, maxDocuments, similarityThreshold);
        
        return LLMChatFactory.createWithAdvisors(
                chatClientBuilder,
                QuestionAnswerAdvisor.builder(springAiVectorStore)
                        .searchRequest(org.springframework.ai.vectorstore.SearchRequest.builder()
                                .topK(maxDocuments)
                                .similarityThreshold(similarityThreshold)
                                .build())
                        .build()
        );
    }

    /**
     * Creates an LLMChat with both chat memory and RAG capabilities.
     * This combines conversational memory with context retrieval for comprehensive AI interactions.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param chatMemory the chat memory implementation
     * @param vectorStore the Kompile vector store for context retrieval
     * @return a configured LLMChat instance
     */
    public static LLMChat createWithMemoryAndRAG(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            VectorStore vectorStore) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        
        // Create adapter from our VectorStore to Spring AI VectorStore
        org.springframework.ai.vectorstore.VectorStore springAiVectorStore = createSpringAiVectorStoreAdapter(vectorStore);
        
        List<Advisor> advisors = Arrays.asList(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                QuestionAnswerAdvisor.builder(springAiVectorStore)
                        .searchRequest(org.springframework.ai.vectorstore.SearchRequest.builder().build())
                        .build()
        );
        
        return LLMChatFactory.createWithAdvisors(chatClientBuilder, advisors);
    }

    /**
     * Creates an LLMChat with comprehensive configuration including memory, RAG, and custom advisors.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param chatMemory the chat memory implementation
     * @param vectorStore the Kompile vector store for context retrieval
     * @param maxDocuments the maximum number of documents to retrieve
     * @param similarityThreshold the similarity threshold for document retrieval
     * @param additionalAdvisors additional custom advisors
     * @return a configured LLMChat instance
     */
    public static LLMChat createComprehensive(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            VectorStore vectorStore,
            int maxDocuments,
            double similarityThreshold,
            Advisor... additionalAdvisors) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(chatMemory, "chatMemory cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        Assert.isTrue(maxDocuments > 0, "maxDocuments must be positive");
        
        // Create adapter from our VectorStore to Spring AI VectorStore
        org.springframework.ai.vectorstore.VectorStore springAiVectorStore = createSpringAiVectorStoreAdapter(vectorStore, maxDocuments, similarityThreshold);
        
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        advisors.add(QuestionAnswerAdvisor.builder(springAiVectorStore)
                .searchRequest(org.springframework.ai.vectorstore.SearchRequest.builder()
                        .topK(maxDocuments)
                        .similarityThreshold(similarityThreshold)
                        .build())
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
     * @param vectorStore the Kompile vector store containing document embeddings
     * @param maxDocuments the maximum number of documents to retrieve
     * @return a configured LLMChat instance
     */
    public static LLMChat createDocumentAnalyst(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            int maxDocuments) {
        return createDocumentAnalyst(chatClientBuilder, vectorStore, maxDocuments, 0.0);
    }

    /**
     * Creates an LLMChat for document analysis with RAG capabilities.
     * Optimized for processing and answering questions about documents.
     * 
     * @param chatClientBuilder the ChatClient builder
     * @param vectorStore the Kompile vector store containing document embeddings
     * @param maxDocuments the maximum number of documents to retrieve
     * @param similarityThreshold the similarity threshold for document retrieval
     * @return a configured LLMChat instance
     */
    public static LLMChat createDocumentAnalyst(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            int maxDocuments,
            double similarityThreshold) {
        Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
        Assert.notNull(vectorStore, "vectorStore cannot be null");
        Assert.isTrue(maxDocuments > 0, "maxDocuments must be positive");
        
        // Create adapter from our VectorStore to Spring AI VectorStore
        org.springframework.ai.vectorstore.VectorStore springAiVectorStore = createSpringAiVectorStoreAdapter(vectorStore, maxDocuments, similarityThreshold);
        
        org.springframework.ai.vectorstore.SearchRequest searchRequest = org.springframework.ai.vectorstore.SearchRequest.builder()
                .topK(maxDocuments)
                .similarityThreshold(similarityThreshold)
                .build();
        
        return LLMChatFactory.builder(chatClientBuilder)
                .defaultSystem("You are a helpful document analyst. Analyze the provided context carefully " +
                              "and answer questions based on the information in the documents. If the answer " +
                              "is not in the provided context, clearly state that.")
                .defaultAdvisors(QuestionAnswerAdvisor.builder(springAiVectorStore)
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

    /**
     * Creates a Spring AI VectorStore adapter from our Kompile VectorStore.
     * This allows our VectorStore interface to work with Spring AI advisors.
     * 
     * @param kompileVectorStore the Kompile vector store to adapt
     * @return a Spring AI compatible VectorStore
     */
    private static org.springframework.ai.vectorstore.VectorStore createSpringAiVectorStoreAdapter(VectorStore kompileVectorStore) {
        return createSpringAiVectorStoreAdapter(kompileVectorStore, 5, 0.0);
    }

    /**
     * Creates a Spring AI VectorStore adapter from our Kompile VectorStore with custom parameters.
     * This allows our VectorStore interface to work with Spring AI advisors.
     * 
     * @param kompileVectorStore the Kompile vector store to adapt  
     * @param defaultMaxDocuments the default maximum number of documents to retrieve
     * @param defaultSimilarityThreshold the default similarity threshold
     * @return a Spring AI compatible VectorStore
     */
    private static org.springframework.ai.vectorstore.VectorStore createSpringAiVectorStoreAdapter(
            VectorStore kompileVectorStore, 
            int defaultMaxDocuments, 
            double defaultSimilarityThreshold) {
        
        return new org.springframework.ai.vectorstore.VectorStore() {
            @Override
            public void add(List<Document> documents) {
                kompileVectorStore.add(documents);
            }

            @Override
            public void delete(List<String> idList) {
                kompileVectorStore.delete(idList);
            }

            @Override
            public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
                // Note: Our Kompile VectorStore doesn't support filter expressions yet
                // This would need to be implemented based on your specific VectorStore implementation
                throw new UnsupportedOperationException("Filter-based deletion not yet supported in Kompile VectorStore adapter");
            }

            @Override
            public List<Document> similaritySearch(org.springframework.ai.vectorstore.SearchRequest request) {
                String query = request.getQuery();
                int topK = request.getTopK() > 0 ? request.getTopK() : defaultMaxDocuments;
                double threshold = request.getSimilarityThreshold() >= 0 ? request.getSimilarityThreshold() : defaultSimilarityThreshold;
                
                if (threshold > 0) {
                    return kompileVectorStore.similaritySearch(query, topK, threshold);
                } else {
                    return kompileVectorStore.similaritySearch(query, topK);
                }
            }

            @Override
            public String getName() {
                return "KompileVectorStoreAdapter";
            }
        };
    }
}