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
import ai.kompile.core.filter.FilterAction;
import ai.kompile.core.filter.FilterContext;
import ai.kompile.core.filter.FilterPhase;
import ai.kompile.core.filter.FilterResult;
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
import ai.kompile.filterchain.service.FilterChainService;
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
import java.util.Map;

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
    private final FilterChainService filterChainService;

    @Autowired
    public KompileRagOrchestratorImpl(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @Autowired(required = false) @Qualifier("hybridOptimizedRetriever") OptimizedRetriever retriever,
            @Autowired(required = false) @Qualifier("defaultQueryProcessor") QueryProcessor queryProcessor,
            @Autowired(required = false) @Qualifier("defaultContextBuilder") ContextBuilder contextBuilder,
            @Autowired(required = false) KompileChatMemory chatMemory,
            @Autowired(required = false) LanguageModel languageModel,
            @Autowired(required = false) ConversationalLanguageModel conversationalLanguageModel,
            @Autowired(required = false) FilterChainService filterChainService) {

        this.embeddingModel = embeddingModel;
        this.retriever = retriever;
        this.queryProcessor = queryProcessor;
        this.contextBuilder = contextBuilder;
        this.chatMemory = chatMemory;
        this.languageModel = languageModel;
        this.conversationalLanguageModel = conversationalLanguageModel;
        this.filterChainService = filterChainService;

        log.info("KompileRagOrchestrator initialized with:");
        log.info("  - EmbeddingModel: {}", embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "null");
        log.info("  - OptimizedRetriever: {}", retriever != null ? retriever.getName() : "null");
        log.info("  - QueryProcessor: {}", queryProcessor != null ? queryProcessor.getName() : "null");
        log.info("  - ContextBuilder: {}", contextBuilder != null ? contextBuilder.getName() : "null");
        log.info("  - ChatMemory: {}", chatMemory != null ? chatMemory.getClass().getSimpleName() : "null");
        log.info("  - LanguageModel: {}", languageModel != null ? languageModel.getClass().getSimpleName() : "null");
        log.info("  - ConversationalLanguageModel: {}", conversationalLanguageModel != null ?
                conversationalLanguageModel.getClass().getSimpleName() : "null");
        log.info("  - FilterChainService: {}", filterChainService != null ?
                (filterChainService.isEnabled() ? "enabled" : "disabled") : "null");
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
            // FILTER CHAIN: PRE_RETRIEVAL PHASE
            // ══════════════════════════════════════════════════════════════════════
            FilterContext filterContext = FilterContext.forConversation(conversationId, userMessage, history);
            filterContext.setSystemPrompt(options.systemPrompt());
            filterContext.setMaxResults(options.totalK());

            if (isFilterChainEnabled()) {
                FilterResult preRetrievalResult = filterChainService.execute(filterContext, FilterPhase.PRE_RETRIEVAL);
                if (preRetrievalResult.isTerminating()) {
                    return handleFilterTermination(preRetrievalResult, filterContext, startTime);
                }
                filterContext = preRetrievalResult.getMutatedContext();
            }

            // ══════════════════════════════════════════════════════════════════════
            // STEP 2: Process Query
            // ══════════════════════════════════════════════════════════════════════
            // Use rewritten query from filters if available
            String queryToProcess = filterContext.getRewrittenQuery() != null ?
                    filterContext.getRewrittenQuery() : userMessage;
            ProcessedQuery processedQuery = processQuery(queryToProcess, history, options);
            String queryForRetrieval = processedQuery.rewrittenQuery();

            // Update filter context with processed query
            if (filterContext.getRewrittenQuery() == null) {
                filterContext.setRewrittenQuery(queryForRetrieval);
            }

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
            List<ScoredDocument> documents = new ArrayList<>(retrievalResult.documents());

            // Clean up embedding
            if (queryEmbedding != null && !queryEmbedding.wasClosed()) {
                try {
                    queryEmbedding.close();
                } catch (Exception e) {
                    log.trace("Error closing query embedding: {}", e.getMessage());
                }
            }

            // ══════════════════════════════════════════════════════════════════════
            // FILTER CHAIN: POST_RETRIEVAL PHASE
            // ══════════════════════════════════════════════════════════════════════
            filterContext.setRetrievedDocuments(documents);

            if (isFilterChainEnabled()) {
                FilterResult postRetrievalResult = filterChainService.execute(filterContext, FilterPhase.POST_RETRIEVAL);
                if (postRetrievalResult.isTerminating()) {
                    return handleFilterTermination(postRetrievalResult, filterContext, startTime);
                }
                filterContext = postRetrievalResult.getMutatedContext();
                // Filters may have modified the documents (reranked, filtered, enriched)
                documents = filterContext.getRetrievedDocuments();
            }

            // ══════════════════════════════════════════════════════════════════════
            // STEP 5: Build Context
            // ══════════════════════════════════════════════════════════════════════
            BuiltContext builtContext = buildContext(documents, history, userMessage, options);
            filterContext.setFormattedContext(builtContext.contextString());

            // ══════════════════════════════════════════════════════════════════════
            // FILTER CHAIN: PRE_LLM PHASE
            // ══════════════════════════════════════════════════════════════════════
            if (isFilterChainEnabled()) {
                FilterResult preLlmResult = filterChainService.execute(filterContext, FilterPhase.PRE_LLM);
                if (preLlmResult.isTerminating()) {
                    return handleFilterTermination(preLlmResult, filterContext, startTime);
                }
                filterContext = preLlmResult.getMutatedContext();
                // Update context if filter modified it
                if (filterContext.getFormattedContext() != null &&
                        !filterContext.getFormattedContext().equals(builtContext.contextString())) {
                    builtContext = BuiltContext.builder()
                            .contextString(filterContext.getFormattedContext())
                            .userPromptWithContext(filterContext.getFormattedContext() + "\n\n" + userMessage)
                            .includedDocCount(documents.size())
                            .build();
                }
            }

            // ══════════════════════════════════════════════════════════════════════
            // STEP 6: Generate Response
            // ══════════════════════════════════════════════════════════════════════
            long generationStart = System.currentTimeMillis();
            String answer = generateResponse(conversationId, builtContext, options);
            long generationTimeMs = System.currentTimeMillis() - generationStart;
            filterContext.setLlmResponse(answer);
            filterContext.setGenerationTimeMs(generationTimeMs);

            // ══════════════════════════════════════════════════════════════════════
            // FILTER CHAIN: POST_LLM PHASE
            // ══════════════════════════════════════════════════════════════════════
            if (isFilterChainEnabled()) {
                FilterResult postLlmResult = filterChainService.execute(filterContext, FilterPhase.POST_LLM);
                if (postLlmResult.isTerminating()) {
                    return handleFilterTermination(postLlmResult, filterContext, startTime);
                }
                filterContext = postLlmResult.getMutatedContext();
                // Use potentially modified response
                answer = filterContext.getLlmResponse();
            }

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
                    .filterTraces(filterContext.getTraces())
                    .filterMutations(filterContext.getMutations())
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

    // ═══════════════════════════════════════════════════════════════════════════════
    // FILTER CHAIN HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Check if the filter chain is enabled and available.
     */
    private boolean isFilterChainEnabled() {
        return filterChainService != null && filterChainService.isEnabled();
    }

    /**
     * Handle a terminating filter result by returning an appropriate ConversationalRagResult.
     *
     * @param result The terminating filter result
     * @param context The filter context at termination
     * @param startTime The start time of the chat request
     * @return A ConversationalRagResult reflecting the filter termination
     */
    private ConversationalRagResult handleFilterTermination(FilterResult result, FilterContext context, long startTime) {
        long totalTimeMs = System.currentTimeMillis() - startTime;

        FilterAction action = result.getAction();
        String message = result.getMessage();
        int statusCode = result.getHttpStatusCode();

        log.info("Filter chain terminated with action={}, status={}, message='{}' in {}ms",
                action, statusCode, message, totalTimeMs);

        if (action == FilterAction.TERMINATE_SUCCESS) {
            // Early successful response from filter
            return ConversationalRagResult.builder()
                    .answer(message != null ? message : "Request handled by filter")
                    .retrievedDocuments(context.getRetrievedDocuments() != null ?
                            context.getRetrievedDocuments() : List.of())
                    .formattedContext(context.getFormattedContext())
                    .conversationHistory(context.getConversationHistory())
                    .totalTimeMs(totalTimeMs)
                    .filterTraces(context.getTraces())
                    .filterMutations(context.getMutations())
                    .filterTerminated(true)
                    .filterTerminationStatus(statusCode)
                    .build();
        } else if (action == FilterAction.TERMINATE_USER_ERROR) {
            // User error (4xx) - policy violation, validation failure, etc.
            return ConversationalRagResult.builder()
                    .answer(message != null ? message : "Request blocked by filter")
                    .error(true)
                    .errorMessage(message)
                    .errorCode(statusCode)
                    .totalTimeMs(totalTimeMs)
                    .filterTraces(context.getTraces())
                    .filterMutations(context.getMutations())
                    .filterTerminated(true)
                    .filterTerminationStatus(statusCode)
                    .build();
        } else {
            // Fatal error (5xx) - filter failure
            return ConversationalRagResult.builder()
                    .answer("An error occurred processing your request")
                    .error(true)
                    .errorMessage(message != null ? message : "Filter chain error")
                    .errorCode(statusCode)
                    .totalTimeMs(totalTimeMs)
                    .filterTraces(context.getTraces())
                    .filterMutations(context.getMutations())
                    .filterTerminated(true)
                    .filterTerminationStatus(statusCode)
                    .build();
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
