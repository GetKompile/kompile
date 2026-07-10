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

        for (VectorStore vs : vectorStores) {
            if (!(vs instanceof NoOpVectorStoreImpl)) {
                this.vectorStore = vs;
                break;
            }
        }
        if (this.vectorStore == null) {
            logger.warn("No real VectorStore bean found. RAG semantic search will be unavailable.");
        }

        for (DocumentRetriever retriever : keywordRetrievers) {
            if (!(retriever instanceof NoOpDocumentRetrieverImpl)) {
                this.keywordRetriever = retriever;
                break;
            }
        }
        if (this.keywordRetriever == null) {
            logger.warn("No real DocumentRetriever bean found. RAG keyword search will be unavailable.");
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
            throw new IllegalArgumentException("RAG query cannot be empty");
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
                            .filter(doc -> doc != null && doc.getText() != null && !doc.getText().startsWith("Error:"))
                            .forEach(doc -> {
                                combinedDocsSet.add(doc);
                                finalContextForLLM.add(doc.getText());
                            });
                    logger.info("Keyword search returned {} valid documents.",
                            keywordRetrievedDocs.stream().filter(doc -> doc != null && doc.getText() != null && !doc.getText().startsWith("Error:")).count());
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
                                    springDoc.getMetadata(), // Example score extraction
                                    springDoc.getMetadata().containsKey("score") ? ((Number) springDoc.getMetadata().get("score")).floatValue() : 0.0f))
                            .collect(Collectors.toList());

                    semanticRetrievedDocs.forEach(doc -> {
                        if (combinedDocsSet.add(doc)) { // Add to set to ensure uniqueness based on RetrievedDoc's equals/hashCode
                            finalContextForLLM.add(doc.getText()); // Add content to LLM context
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
                    throw new IllegalStateException("Language model returned a null ChatResponse");
                }

                Generation firstResult = chatResponse.getResult();
                if (firstResult != null && firstResult.getOutput() != null) {
                    llmAnswer = firstResult.getOutput().getText(); // Using getText()
                    if (llmAnswer == null || llmAnswer.trim().isEmpty()) {
                        ChatGenerationMetadata generationMetadata = firstResult.getMetadata();
                        String finishReason = generationMetadata != null ? String.valueOf(generationMetadata.getFinishReason()) : "unknown";
                        throw new IllegalStateException("Language model returned empty output; finishReason=" + finishReason);
                    }
                } else {
                    ChatResponseMetadata responseMetadata = chatResponse.getMetadata();
                    String responseId = responseMetadata != null ? responseMetadata.getId() : "N/A";
                    throw new IllegalStateException("Language model ChatResponse had no generation output; responseId=" + responseId);
                }
            }
            logger.info("Final LLM response, length {}: {}", llmAnswer.length(), llmAnswer.substring(0, Math.min(llmAnswer.length(),100)) + (llmAnswer.length() > 100 ? "..." : ""));
            return new RagResult(llmAnswer, contextString, allRetrievedDocs);

        } catch (RuntimeException e) {
            logger.error("Error interacting with Language Model for query [{}]: {}", ragQuery.getQuery(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            logger.error("Error interacting with Language Model for query [{}]: {}", ragQuery.getQuery(), e.getMessage(), e);
            throw new IllegalStateException("Failed to get an answer from the language model", e);
        }
    }
}