/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.rag;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.rag.RagQuery;
import ai.kompile.core.rag.RagService;
import ai.kompile.core.retrievers.DocumentRetriever;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service("ragServiceImpl")
@Primary
public class RagServiceImpl implements RagService {
    private static final Logger logger = LoggerFactory.getLogger(RagServiceImpl.class);

    private final DocumentRetriever keywordRetriever;
    private final LanguageModel languageModel;
    // EmbeddingModel is not directly injected here if VectorStore's similaritySearch(String query,...)
    // internally uses an EmbeddingModel to embed the query.
    private final VectorStore vectorStore;

    private static final int DEFAULT_KEYWORD_RESULTS = 2;
    private static final int DEFAULT_SEMANTIC_RESULTS = 2;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.0; // 0.0 means effectively no threshold for some stores, or use a higher value like 0.7

    @Autowired
    public RagServiceImpl(
            DocumentRetriever keywordRetriever,
            LanguageModel languageModel,
            VectorStore vectorStore) {
        this.keywordRetriever = keywordRetriever;
        this.languageModel = languageModel;
        this.vectorStore = vectorStore;
        logger.info("RagServiceImpl (Hybrid) initialized with KeywordRetriever: {}, VectorStore: {}, LanguageModel: {}",
                keywordRetriever.getClass().getSimpleName(),
                vectorStore.getClass().getSimpleName(),
                languageModel.getClass().getSimpleName());
    }

    @Override
    public String answerQuery(RagQuery ragQuery) {
        logger.info("RagServiceImpl processing RAG query: '{}', useToolCalling: {}", ragQuery.getQuery(), ragQuery.isUseToolCalling());

        if (ragQuery.getQuery() == null || ragQuery.getQuery().trim().isEmpty()) {
            logger.warn("Received an empty or null query.");
            return "Error: Query cannot be empty.";
        }

        Set<String> combinedContextSet = new LinkedHashSet<>();

        // 1. Keyword Search (Sparse Retrieval)
        try {
            logger.debug("Performing keyword retrieval for: {}", ragQuery.getQuery());
            List<String> keywordDocs = keywordRetriever.retrieve(ragQuery.getQuery(), DEFAULT_KEYWORD_RESULTS);
            if (keywordDocs != null && !keywordDocs.isEmpty()) {
                keywordDocs.stream()
                        .filter(doc -> doc != null && !doc.startsWith("Error:"))
                        .forEach(combinedContextSet::add);
                logger.info("Keyword search returned {} valid snippets.",
                        keywordDocs.stream().filter(doc -> doc != null && !doc.startsWith("Error:")).count());
            } else {
                logger.warn("Keyword search returned no results or an error for query: {}", ragQuery.getQuery());
            }
        } catch (Exception e) {
            logger.error("Error during keyword retrieval for query [{}]: {}", ragQuery.getQuery(), e.getMessage(), e);
        }

        // 2. Semantic Search (Dense Retrieval) using VectorStore
        try {
            logger.debug("Performing semantic vector search for: {}", ragQuery.getQuery());
            // Assumes VectorStore.similaritySearch(String query,...) handles query embedding.
            List<Document> semanticSpringAiDocs = vectorStore.similaritySearch(
                    ragQuery.getQuery(),
                    DEFAULT_SEMANTIC_RESULTS,
                    DEFAULT_SIMILARITY_THRESHOLD
            );
            if (semanticSpringAiDocs != null && !semanticSpringAiDocs.isEmpty()) {
                semanticSpringAiDocs.stream()
                        .map(Document::getText) // Document.getContent()
                        .filter(content -> content != null && !content.trim().isEmpty())
                        .forEach(combinedContextSet::add);
                logger.info("Semantic search returned {} valid snippets.", semanticSpringAiDocs.size());
            } else {
                logger.warn("Semantic search returned no results for query: {}", ragQuery.getQuery());
            }
        } catch (Exception e) {
            logger.error("Error during semantic vector search for query [{}]: {}", ragQuery.getQuery(), e.getMessage(), e);
        }

        List<String> finalContext = new ArrayList<>(combinedContextSet);
        if (finalContext.isEmpty()) {
            logger.warn("No context retrieved from any source for query: {}. LLM will answer without specific context.", ragQuery.getQuery());
        }

        logger.info("Total unique context snippets for LLM: {}. Preview: {}",
                finalContext.size(),
                finalContext.stream().map(s -> s.substring(0, Math.min(s.length(), 70)) + (s.length() > 70 ? "..." : "")).collect(Collectors.toList()));

        // 3. Call Language Model
        try {
            if (!ragQuery.isUseToolCalling()) {
                logger.debug("Generating simple response using LanguageModel for query: {}", ragQuery.getQuery());
                return languageModel.generateResponse(ragQuery.getQuery(), finalContext);
            } else {
                logger.debug("Generating response with potential tool calls using LanguageModel for query: {}", ragQuery.getQuery());
                ChatResponse chatResponse = languageModel.generateResponseWithPotentialToolCalls(ragQuery.getQuery(), finalContext);

                if (chatResponse == null) {
                    logger.error("Received null ChatResponse from language model for query: {}", ragQuery.getQuery());
                    return "Error: Language model returned a null response.";
                }

                Generation firstResult = chatResponse.getResult();
                if (firstResult != null) {
                    AssistantMessage assistantMessage = firstResult.getOutput();
                    if (assistantMessage != null) {
                        String content = assistantMessage.getText(); // Using getText() as per your last instruction
                        if (content != null && !content.trim().isEmpty()) {
                            logger.info("Final LLM response (after potential tool calls), length {}: {}", content.length(), content.substring(0, Math.min(content.length(),100)) + (content.length() > 100 ? "..." : ""));
                            return content;
                        } else {
                            logger.warn("LLM output content (from assistantMessage.getText()) is null or empty for query: {}", ragQuery.getQuery());
                        }
                    } else {
                        logger.warn("Generation output (AssistantMessage) is null for query: {}", ragQuery.getQuery());
                    }
                } else {
                    logger.warn("ChatResponse result (Generation) is null for query: {}", ragQuery.getQuery());
                }

                ChatResponseMetadata responseMetadata = chatResponse.getMetadata();
                ChatGenerationMetadata generationMetadata = firstResult != null ? firstResult.getMetadata() : null;

                String responseId = "N/A";
                String modelName = "N/A";
                String usageInfoStr = "N/A";
                String rateLimitInfoStr = "N/A";
                String finishReason = "N/A";

                if (responseMetadata != null) {
                    responseId = responseMetadata.getId();
                    modelName = responseMetadata.getModel();
                    Usage usage = responseMetadata.getUsage();
                    if (usage != null) usageInfoStr = usage.toString();
                    RateLimit rateLimit = responseMetadata.getRateLimit();
                    if (rateLimit != null) rateLimitInfoStr = rateLimit.toString();
                }

                if (generationMetadata != null) {
                    finishReason = generationMetadata.getFinishReason();
                    if (generationMetadata.containsKey("tool_error")) {
                        Object toolError = generationMetadata.get("tool_error");
                        return "Error: Tool execution reported an issue. Details: " + toolError;
                    }
                    if (generationMetadata.containsKey("error")) {
                        Object errorVal = generationMetadata.get("error");
                        return "Error: An issue occurred. Details: " + errorVal;
                    }
                }

                logger.warn("No primary output content from getText() for query: {}. Response ID: {}, Model: {}, Usage: {}, RateLimit: {}, FinishReason: {}",
                        ragQuery.getQuery(), responseId, modelName, usageInfoStr, rateLimitInfoStr, finishReason);

                return "Error: Could not get a final response content from the language model after considering tools. Please check logs for details.";
            }
        } catch (Exception e) {
            logger.error("Error interacting with Language Model for query [{}]: {}", ragQuery.getQuery(), e.getMessage(), e);
            return "Error: Failed to get an answer from the language model due to an unexpected internal error.";
        }
    }
}