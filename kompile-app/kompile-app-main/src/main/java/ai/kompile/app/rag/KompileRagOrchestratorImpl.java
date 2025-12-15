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

package ai.kompile.app.rag;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.llm.ConversationalLanguageModel;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.llm.memory.KompileChatMemory;
import ai.kompile.core.rag.ConversationalRagOptions;
import ai.kompile.core.rag.ConversationalRagResult;
import ai.kompile.core.rag.ConversationalRagService;
import ai.kompile.core.rag.context.BuiltContext;
import ai.kompile.core.rag.context.ContextBuildRequest;
import ai.kompile.core.rag.context.ContextBuilder;
import ai.kompile.core.rag.query.ProcessedQuery;
import ai.kompile.core.rag.query.QueryProcessor;
import ai.kompile.core.rag.retrieval.OptimizedRetriever;
import ai.kompile.core.rag.retrieval.RetrievalMetrics;
import ai.kompile.core.rag.retrieval.RetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Primary implementation of {@link ConversationalRagService} that orchestrates the full RAG pipeline.
 * <p>
 * This implementation is optimized for INDArray operations to avoid float[] conversion overhead.
 * The architecture follows this flow:
 * <pre>
 * User Query
 *     │
 *     ├──────────────────────────────────────────────────────────────────────────┐
 *     │                                                                          │
 *     ▼                                                                          │
 * 1. Get Conversation History (KompileChatMemory)                                │
 *     │                                                                          │
 *     ▼                                                                          │
 * 2. Process Query (QueryProcessor)                                              │
 *     │ - Detect intent                                                          │
 *     │ - Resolve references                                                     │
 *     │ - Rewrite for retrieval                                                  │
 *     │                                                                          │
 *     ▼                                                                          │
 * 3. Generate Embedding (EmbeddingModel → INDArray)                              │
 *     │                                                                          │
 *     ▼                                                                          │
 * 4. Retrieve Documents (OptimizedRetriever)                                     │
 *     │ - Semantic search with INDArray                                          │
 *     │ - Keyword search (BM25)                                                  │
 *     │ - Merge and deduplicate                                                  │
 *     │                                                                          │
 *     ▼                                                                          │
 * 5. Build Context (ContextBuilder)                                              │
 *     │ - Format documents                                                       │
 *     │ - Include relevant history                                               │
 *     │ - Respect token budget                                                   │
 *     │                                                                          │
 *     ▼                                                                          │
 * 6. Generate Response (ConversationalLanguageModel)                             │
 *     │                                                                          │
 *     ▼                                                                          │
 * 7. Update Conversation Memory                                                  │
 *     │                                                                          │
 *     ▼                                                                          │
 * ConversationalRagResult ◄──────────────────────────────────────────────────────┘
 * </pre>
 *
 * @see ConversationalRagService
 * @see OptimizedRetriever
 * @see QueryProcessor
 * @see ContextBuilder
 */
@Slf4j
@Service("kompileRagOrchestrator")
public class KompileRagOrchestratorImpl implements ConversationalRagService {

    private final EmbeddingModel embeddingModel;
    private final OptimizedRetriever retriever;
    private final QueryProcessor queryProcessor;
    private final ContextBuilder contextBuilder;
    private final KompileChatMemory chatMemory;
    private final LanguageModel languageModel;
    private final ConversationalLanguageModel conversationalLanguageModel;

    @Autowired
    public KompileRagOrchestratorImpl(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @Autowired(required = false) @Qualifier("hybridOptimizedRetriever") OptimizedRetriever retriever,
            @Autowired(required = false) @Qualifier("defaultQueryProcessor") QueryProcessor queryProcessor,
            @Autowired(required = false) @Qualifier("defaultContextBuilder") ContextBuilder contextBuilder,
            @Autowired(required = false) KompileChatMemory chatMemory,
            @Autowired(required = false) LanguageModel languageModel,
            @Autowired(required = false) ConversationalLanguageModel conversationalLanguageModel) {

        this.embeddingModel = embeddingModel;
        this.retriever = retriever;
        this.queryProcessor = queryProcessor;
        this.contextBuilder = contextBuilder;
        this.chatMemory = chatMemory;
        this.languageModel = languageModel;
        this.conversationalLanguageModel = conversationalLanguageModel;

        log.info("KompileRagOrchestrator initialized with:");
        log.info("  - EmbeddingModel: {}", embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "null");
        log.info("  - OptimizedRetriever: {}", retriever != null ? retriever.getName() : "null");
        log.info("  - QueryProcessor: {}", queryProcessor != null ? queryProcessor.getName() : "null");
        log.info("  - ContextBuilder: {}", contextBuilder != null ? contextBuilder.getName() : "null");
        log.info("  - ChatMemory: {}", chatMemory != null ? chatMemory.getClass().getSimpleName() : "null");
        log.info("  - LanguageModel: {}", languageModel != null ? languageModel.getClass().getSimpleName() : "null");
        log.info("  - ConversationalLanguageModel: {}", conversationalLanguageModel != null ?
                conversationalLanguageModel.getClass().getSimpleName() : "null");
    }

    @Override
    public ConversationalRagResult chat(String conversationId, String userMessage) {
        return chat(conversationId, userMessage, ConversationalRagOptions.defaults());
    }

    @Override
    public ConversationalRagResult chat(String conversationId, String userMessage, ConversationalRagOptions options) {
        if (userMessage == null || userMessage.isBlank()) {
            log.warn("Empty message received for conversation: {}", conversationId);
            return ConversationalRagResult.error("Message cannot be empty");
        }

        long startTime = System.currentTimeMillis();
        log.debug("Processing chat for conversation '{}': '{}'", conversationId, userMessage);

        try {
            // ══════════════════════════════════════════════════════════════════════
            // STEP 1: Get Conversation History
            // ══════════════════════════════════════════════════════════════════════
            List<Message> history = getConversationHistoryInternal(conversationId, options.maxHistoryMessages());

            // ══════════════════════════════════════════════════════════════════════
            // STEP 2: Process Query
            // ══════════════════════════════════════════════════════════════════════
            ProcessedQuery processedQuery = processQuery(userMessage, history, options);
            String queryForRetrieval = processedQuery.rewrittenQuery();

            // ══════════════════════════════════════════════════════════════════════
            // STEP 3: Generate Embedding (INDArray-native)
            // ══════════════════════════════════════════════════════════════════════
            INDArray queryEmbedding = null;
            if (options.enableSemanticSearch() && embeddingModel != null) {
                try {
                    queryEmbedding = embeddingModel.embed(queryForRetrieval);
                } catch (Exception e) {
                    log.warn("Error generating query embedding: {}", e.getMessage());
                }
            }

            // ══════════════════════════════════════════════════════════════════════
            // STEP 4: Retrieve Documents
            // ══════════════════════════════════════════════════════════════════════
            RetrievalResult retrievalResult = retrieveDocuments(queryEmbedding, queryForRetrieval, options);
            RetrievalMetrics retrievalMetrics = retrievalResult.metrics();
            List<ScoredDocument> documents = retrievalResult.documents();

            // Clean up embedding
            if (queryEmbedding != null && !queryEmbedding.wasClosed()) {
                try {
                    queryEmbedding.close();
                } catch (Exception e) {
                    log.trace("Error closing query embedding: {}", e.getMessage());
                }
            }

            // ══════════════════════════════════════════════════════════════════════
            // STEP 5: Build Context
            // ══════════════════════════════════════════════════════════════════════
            BuiltContext builtContext = buildContext(documents, history, userMessage, options);

            // ══════════════════════════════════════════════════════════════════════
            // STEP 6: Generate Response
            // ══════════════════════════════════════════════════════════════════════
            long generationStart = System.currentTimeMillis();
            String answer = generateResponse(conversationId, builtContext, options);
            long generationTimeMs = System.currentTimeMillis() - generationStart;

            // ══════════════════════════════════════════════════════════════════════
            // STEP 7: Update Conversation Memory
            // ══════════════════════════════════════════════════════════════════════
            updateConversationMemory(conversationId, userMessage, answer);
            List<Message> updatedHistory = getConversationHistoryInternal(conversationId, options.maxHistoryMessages());

            long totalTimeMs = System.currentTimeMillis() - startTime;
            log.info("RAG chat completed in {}ms (retrieval: {}ms, generation: {}ms) - {} docs retrieved",
                    totalTimeMs,
                    retrievalMetrics.totalTimeNanos() / 1_000_000,
                    generationTimeMs,
                    documents.size());

            return ConversationalRagResult.builder()
                    .answer(answer)
                    .retrievedDocuments(documents)
                    .formattedContext(builtContext.contextString())
                    .conversationHistory(updatedHistory)
                    .processedQuery(processedQuery)
                    .retrievalMetrics(retrievalMetrics)
                    .generationTimeMs(generationTimeMs)
                    .totalTimeMs(totalTimeMs)
                    .build();

        } catch (Exception e) {
            log.error("Error processing chat for conversation '{}': {}", conversationId, e.getMessage(), e);
            return ConversationalRagResult.error("Failed to process query: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> chatStream(String conversationId, String userMessage) {
        return chatStream(conversationId, userMessage, ConversationalRagOptions.defaults());
    }

    @Override
    public Flux<String> chatStream(String conversationId, String userMessage, ConversationalRagOptions options) {
        if (userMessage == null || userMessage.isBlank()) {
            return Flux.just("Error: Message cannot be empty");
        }

        return Flux.create(sink -> {
            try {
                // Get history
                List<Message> history = getConversationHistoryInternal(conversationId, options.maxHistoryMessages());

                // Process query
                ProcessedQuery processedQuery = processQuery(userMessage, history, options);
                String queryForRetrieval = processedQuery.rewrittenQuery();

                // Generate embedding
                INDArray queryEmbedding = null;
                if (options.enableSemanticSearch() && embeddingModel != null) {
                    try {
                        queryEmbedding = embeddingModel.embed(queryForRetrieval);
                    } catch (Exception e) {
                        log.warn("Error generating query embedding for streaming: {}", e.getMessage());
                    }
                }

                // Retrieve documents
                RetrievalResult retrievalResult = retrieveDocuments(queryEmbedding, queryForRetrieval, options);
                List<ScoredDocument> documents = retrievalResult.documents();

                // Clean up embedding
                if (queryEmbedding != null && !queryEmbedding.wasClosed()) {
                    try {
                        queryEmbedding.close();
                    } catch (Exception ignored) {}
                }

                // Build context
                BuiltContext builtContext = buildContext(documents, history, userMessage, options);

                // Stream response using ConversationalLanguageModel if available
                if (conversationalLanguageModel instanceof ai.kompile.core.llm.AbstractConversationalLanguageModel acm) {
                    List<String> contextList = List.of(builtContext.userPromptWithContext());
                    StringBuilder fullResponse = new StringBuilder();

                    acm.generateStreamingConversationalResponse(conversationId, userMessage, contextList)
                            .doOnNext(chunk -> {
                                fullResponse.append(chunk);
                                sink.next(chunk);
                            })
                            .doOnComplete(() -> {
                                // Update memory with full response
                                updateConversationMemory(conversationId, userMessage, fullResponse.toString());
                                sink.complete();
                            })
                            .doOnError(sink::error)
                            .subscribe();
                } else {
                    // Fall back to non-streaming with chunked output
                    String answer = generateResponse(conversationId, builtContext, options);
                    updateConversationMemory(conversationId, userMessage, answer);

                    // Emit in chunks for streaming effect
                    int chunkSize = 50;
                    for (int i = 0; i < answer.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, answer.length());
                        sink.next(answer.substring(i, end));
                    }
                    sink.complete();
                }

            } catch (Exception e) {
                log.error("Error in streaming chat: {}", e.getMessage(), e);
                sink.error(e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONVERSATION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════════

    @Override
    public void clearConversation(String conversationId) {
        if (chatMemory != null) {
            chatMemory.clear(conversationId);
            log.debug("Cleared conversation: {}", conversationId);
        }
    }

    @Override
    public List<Message> getConversationHistory(String conversationId) {
        if (chatMemory != null) {
            return chatMemory.get(conversationId);
        }
        return List.of();
    }

    @Override
    public boolean hasConversation(String conversationId) {
        if (chatMemory != null) {
            return chatMemory.exists(conversationId);
        }
        return false;
    }

    @Override
    public int getConversationSize(String conversationId) {
        if (chatMemory != null) {
            return chatMemory.size(conversationId);
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private List<Message> getConversationHistoryInternal(String conversationId, int maxMessages) {
        if (chatMemory != null && maxMessages > 0) {
            return chatMemory.get(conversationId, maxMessages);
        }
        return List.of();
    }

    private ProcessedQuery processQuery(String query, List<Message> history, ConversationalRagOptions options) {
        if (options.enableQueryProcessing() && queryProcessor != null && queryProcessor.isAvailable()) {
            return queryProcessor.process(query, history);
        }
        return ProcessedQuery.unchanged(query);
    }

    private RetrievalResult retrieveDocuments(INDArray queryEmbedding, String query, ConversationalRagOptions options) {
        if (retriever != null && retriever.isAvailable()) {
            if (queryEmbedding != null && !queryEmbedding.isEmpty() && !queryEmbedding.wasClosed()) {
                return retriever.retrieve(queryEmbedding, query, options.toRetrievalOptions());
            } else {
                return retriever.retrieve(query, options.toRetrievalOptions());
            }
        }
        log.warn("No retriever available, returning empty result");
        return RetrievalResult.empty();
    }

    private BuiltContext buildContext(List<ScoredDocument> documents, List<Message> history,
                                       String query, ConversationalRagOptions options) {
        if (contextBuilder != null) {
            ContextBuildRequest request = ContextBuildRequest.of(
                    documents,
                    history,
                    query,
                    options.maxContextTokens(),
                    options.systemPrompt()
            );
            return contextBuilder.build(request);
        }

        // Fallback: simple context building
        StringBuilder contextString = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            ScoredDocument doc = documents.get(i);
            contextString.append(String.format("Document %d: %s%n", i + 1, doc.getText()));
        }
        return BuiltContext.builder()
                .contextString(contextString.toString())
                .userPromptWithContext(contextString + "\n\n" + query)
                .includedDocCount(documents.size())
                .build();
    }

    private String generateResponse(String conversationId, BuiltContext builtContext,
                                     ConversationalRagOptions options) {
        List<String> context = builtContext.contextString().isBlank()
                ? List.of()
                : List.of(builtContext.userPromptWithContext());

        // Prefer ConversationalLanguageModel for memory-integrated responses
        if (conversationalLanguageModel != null) {
            if (options.useToolCalling()) {
                var response = conversationalLanguageModel.generateConversationalResponseWithToolCalls(
                        conversationId,
                        builtContext.userPromptWithContext(),
                        context
                );
                if (response != null && response.getResult() != null &&
                        response.getResult().getOutput() != null) {
                    return response.getResult().getOutput().getText();
                }
            }
            return conversationalLanguageModel.generateConversationalResponse(
                    conversationId,
                    builtContext.userPromptWithContext(),
                    context
            );
        }

        // Fall back to stateless LanguageModel
        if (languageModel != null) {
            if (options.useToolCalling()) {
                var response = languageModel.generateResponseWithPotentialToolCalls(
                        builtContext.userPromptWithContext(),
                        context
                );
                if (response != null && response.getResult() != null &&
                        response.getResult().getOutput() != null) {
                    return response.getResult().getOutput().getText();
                }
            }
            return languageModel.generateResponse(builtContext.userPromptWithContext(), context);
        }

        log.warn("No language model available, returning context only");
        return "No language model configured. Context retrieved: " + builtContext.includedDocCount() + " documents.";
    }

    private void updateConversationMemory(String conversationId, String userMessage, String assistantResponse) {
        if (chatMemory != null) {
            List<Message> messages = new ArrayList<>();
            messages.add(new UserMessage(userMessage));
            messages.add(new AssistantMessage(assistantResponse));
            chatMemory.add(conversationId, messages);
            log.trace("Updated conversation memory for: {}", conversationId);
        }
    }

    @Override
    public boolean isAvailable() {
        // At minimum we need a retriever or language model
        boolean hasRetriever = retriever != null && retriever.isAvailable();
        boolean hasLLM = languageModel != null || conversationalLanguageModel != null;
        return hasRetriever || hasLLM;
    }
}
