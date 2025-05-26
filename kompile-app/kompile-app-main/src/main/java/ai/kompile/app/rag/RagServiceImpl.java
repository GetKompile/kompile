/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

package ai.kompile.app.rag;

import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.rag.RagQuery;
import ai.kompile.core.rag.RagResult;
import ai.kompile.core.rag.RagService;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;


@Service("ragServiceImpl")
@Primary
public class RagServiceImpl implements RagService {
    private static final Logger logger = LoggerFactory.getLogger(RagServiceImpl.class);

    private DocumentRetriever keywordRetriever;
    private final LanguageModel languageModel;
    private VectorStore vectorStore;

    // Using k from RagQuery for retriever limits if provided, otherwise defaults
    // These can be further refined or made configurable
    private static final int DEFAULT_TOTAL_RESULTS_TARGET = 4; // Example: target 2 from each by default
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.0;

    @Autowired
    public RagServiceImpl(
            List<DocumentRetriever> keywordRetrievers, // Renamed for clarity
            LanguageModel languageModel,
            List<VectorStore> vectorStores) { // Renamed for clarity

        if(vectorStores.size() > 1) {
            for(VectorStore vs : vectorStores) {
                if(!(vs instanceof NoOpVectorStoreImpl)) {
                    this.vectorStore = vs;
                    break; // Found a non-NoOp VectorStore
                }
            }
            if (this.vectorStore == null) { // If all were NoOp
                this.vectorStore = vectorStores.get(0);
            }
        } else if (!vectorStores.isEmpty()) {
            this.vectorStore = vectorStores.get(0);
        } else {
            logger.error("No VectorStore beans found. RAG semantic search will be disabled.");
            // Initialize with a NoOp or throw an exception based on desired behavior
            this.vectorStore = new NoOpVectorStoreImpl();
        }


        if(keywordRetrievers.size() > 1) {
            for(DocumentRetriever retriever : keywordRetrievers) {
                if(!(retriever instanceof NoOpDocumentRetrieverImpl)) {
                    this.keywordRetriever = retriever;
                    break; // Found a non-NoOp DocumentRetriever
                }
            }
            if (this.keywordRetriever == null) { // If all were NoOp
                this.keywordRetriever = keywordRetrievers.get(0);
            }
        } else if (!keywordRetrievers.isEmpty()) {
            this.keywordRetriever = keywordRetrievers.get(0);
        } else {
            logger.error("No DocumentRetriever beans found. RAG keyword search will be disabled.");
            // Initialize with a NoOp or throw an exception
            this.keywordRetriever = new NoOpDocumentRetrieverImpl();
        }

        this.languageModel = languageModel;
        logger.info("RagServiceImpl (Hybrid) initialized with KeywordRetriever: {}, VectorStore: {}, LanguageModel: {}",
                this.keywordRetriever != null ? this.keywordRetriever.getClass().getSimpleName() : "null",
                this.vectorStore != null ? this.vectorStore.getClass().getSimpleName() : "null",
                this.languageModel != null ? this.languageModel.getClass().getSimpleName() : "null");
    }

    @Override
    public RagResult answerQuery(RagQuery ragQuery) {
        logger.info("RagServiceImpl processing RAG query: '{}', useToolCalling: {}, k: {}, searchType: {}",
                ragQuery.getQuery(), ragQuery.isUseToolCalling(), ragQuery.getK(), ragQuery.getSearchType());

        if (ragQuery.getQuery() == null || ragQuery.getQuery().trim().isEmpty()) {
            logger.warn("Received an empty or null query.");
            return new RagResult("Error: Query cannot be empty.", "", Collections.emptyList());
        }

        Set<RetrievedDoc> combinedDocsSet = new LinkedHashSet<>();
        List<String> finalContextForLLM = new ArrayList<>();

        // Determine how many results to fetch from each retriever
        // This is a simple strategy, can be made more sophisticated
        int kPerRetriever = Math.max(1, ragQuery.getK() / 2); // Ensure at least 1 if k is small

        // 1. Keyword Search (Sparse Retrieval)
        if (this.keywordRetriever != null && !(this.keywordRetriever instanceof NoOpDocumentRetrieverImpl)) {
            try {
                logger.debug("Performing keyword retrieval for: {} (k={})", ragQuery.getQuery(), kPerRetriever);
                List<RetrievedDoc> keywordRetrievedDocs = this.keywordRetriever.retrieveWithDetails(ragQuery.getQuery(), kPerRetriever);

                if (keywordRetrievedDocs != null && !keywordRetrievedDocs.isEmpty()) {
                    keywordRetrievedDocs.stream()
                            .filter(doc -> doc != null && doc.getContent() != null && !doc.getContent().startsWith("Error:"))
                            .forEach(doc -> {
                                combinedDocsSet.add(doc);
                                finalContextForLLM.add(doc.getContent());
                            });
                    logger.info("Keyword search returned {} valid documents.",
                            keywordRetrievedDocs.stream().filter(doc -> doc != null && doc.getContent() != null && !doc.getContent().startsWith("Error:")).count());
                } else {
                    logger.warn("Keyword search returned no results or an error for query: {}", ragQuery.getQuery());
                }
            } catch (Exception e) {
                logger.error("Error during keyword retrieval for query [{}]: {}", ragQuery.getQuery(), e.getMessage(), e);
            }
        } else {
            logger.warn("KeywordRetriever is not available or is a NoOp implementation. Skipping keyword search.");
        }


        // 2. Semantic Search (Dense Retrieval) using VectorStore
        if (this.vectorStore != null && !(this.vectorStore instanceof NoOpVectorStoreImpl)) {
            try {
                logger.debug("Performing semantic vector search for: {} (k={})", ragQuery.getQuery(), kPerRetriever);
                List<Document> semanticSpringAiDocs = vectorStore.similaritySearch(
                        ragQuery.getQuery(),
                        kPerRetriever,
                        DEFAULT_SIMILARITY_THRESHOLD
                );
                if (semanticSpringAiDocs != null && !semanticSpringAiDocs.isEmpty()) {
                    List<RetrievedDoc> semanticRetrievedDocs = semanticSpringAiDocs.stream()
                            .filter(doc -> doc.getText() != null && !doc.getText().trim().isEmpty()) // getText() instead of getContent()
                            .map(springDoc -> new RetrievedDoc(
                                    springDoc.getId() != null ? springDoc.getId() : UUID.randomUUID().toString(),
                                    springDoc.getText(), // getText()
                                    springDoc.getMetadata().containsKey("score") ? ((Number) springDoc.getMetadata().get("score")).floatValue() : 0.0f, // Example score extraction
                                    springDoc.getMetadata()))
                            .collect(Collectors.toList());

                    semanticRetrievedDocs.forEach(doc -> {
                        if (combinedDocsSet.add(doc)) { // Add to set to ensure uniqueness based on RetrievedDoc's equals/hashCode
                            finalContextForLLM.add(doc.getContent()); // Add content to LLM context
                        }
                    });
                    logger.info("Semantic search returned {} valid documents.", semanticRetrievedDocs.size());
                } else {
                    logger.warn("Semantic search returned no results for query: {}", ragQuery.getQuery());
                }
            } catch (Exception e) {
                logger.error("Error during semantic vector search for query [{}]: {}", ragQuery.getQuery(), e.getMessage(), e);
            }
        } else {
            logger.warn("VectorStore is not available or is a NoOp implementation. Skipping semantic search.");
        }


        List<RetrievedDoc> allRetrievedDocs = new ArrayList<>(combinedDocsSet);
        // Use LinkedHashSet to preserve order while removing duplicates for LLM context
        List<String> uniqueFinalContextForLLM = new ArrayList<>(new LinkedHashSet<>(finalContextForLLM));


        if (uniqueFinalContextForLLM.isEmpty()) {
            logger.warn("No context retrieved from any source for query: {}. LLM will answer without specific context.", ragQuery.getQuery());
        }

        String contextString = String.join("\n\n---\n\n", uniqueFinalContextForLLM);
        logger.info("Total unique context snippets for LLM: {}. Preview: {}",
                uniqueFinalContextForLLM.size(),
                uniqueFinalContextForLLM.stream().map(s -> s.substring(0, Math.min(s.length(), 70)) + (s.length() > 70 ? "..." : "")).collect(Collectors.toList()));

        // 3. Call Language Model
        try {
            String llmAnswer;
            if (!ragQuery.isUseToolCalling()) {
                logger.debug("Generating simple response using LanguageModel for query: {}", ragQuery.getQuery());
                llmAnswer = languageModel.generateResponse(ragQuery.getQuery(), uniqueFinalContextForLLM);
            } else {
                logger.debug("Generating response with potential tool calls using LanguageModel for query: {}", ragQuery.getQuery());
                ChatResponse chatResponse = languageModel.generateResponseWithPotentialToolCalls(ragQuery.getQuery(), uniqueFinalContextForLLM);

                if (chatResponse == null) {
                    logger.error("Received null ChatResponse from language model for query: {}", ragQuery.getQuery());
                    return new RagResult("Error: Language model returned a null response.", contextString, allRetrievedDocs);
                }

                Generation firstResult = chatResponse.getResult();
                if (firstResult != null && firstResult.getOutput() != null) {
                    llmAnswer = firstResult.getOutput().getText(); // Using getText()
                    if (llmAnswer == null || llmAnswer.trim().isEmpty()) {
                        logger.warn("LLM output content (from assistantMessage.getText()) is null or empty for query: {}", ragQuery.getQuery());
                        // Check metadata for tool errors if content is empty
                        ChatGenerationMetadata generationMetadata = firstResult.getMetadata();
                        if (generationMetadata != null) {
                            if (generationMetadata.containsKey("tool_error")) {
                                Object toolError = generationMetadata.get("tool_error");
                                llmAnswer = "Error: Tool execution reported an issue. Details: " + toolError;
                            } else if (generationMetadata.containsKey("error")) {
                                Object errorVal = generationMetadata.get("error");
                                llmAnswer = "Error: An issue occurred. Details: " + errorVal;
                            } else {
                                llmAnswer = "Error: LLM output was empty. Finish Reason: " + generationMetadata.getFinishReason();
                            }
                        } else {
                            llmAnswer = "Error: LLM output was empty and no metadata available.";
                        }
                    }
                } else {
                    logger.warn("ChatResponse result (Generation) or its output is null for query: {}", ragQuery.getQuery());
                    ChatResponseMetadata responseMetadata = chatResponse.getMetadata();
                    String responseId = responseMetadata != null ? responseMetadata.getId() : "N/A";
                    llmAnswer = "Error: Could not get a final response from the language model. Response ID: " + responseId;
                }
            }
            logger.info("Final LLM response, length {}: {}", llmAnswer.length(), llmAnswer.substring(0, Math.min(llmAnswer.length(),100)) + (llmAnswer.length() > 100 ? "..." : ""));
            return new RagResult(llmAnswer, contextString, allRetrievedDocs);

        } catch (Exception e) {
            logger.error("Error interacting with Language Model for query [{}]: {}", ragQuery.getQuery(), e.getMessage(), e);
            return new RagResult("Error: Failed to get an answer from the language model due to an unexpected internal error.", contextString, allRetrievedDocs);
        }
    }
}