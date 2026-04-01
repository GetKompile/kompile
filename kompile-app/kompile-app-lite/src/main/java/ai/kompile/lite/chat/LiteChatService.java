/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.lite.chat;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.chat.history.service.ChatHistoryService;
import ai.kompile.chat.history.domain.ChatMessage.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Core chat orchestration service for Kompile Lite.
 * Composes vector search, graph RAG, LLM, and chat history.
 */
@Service
public class LiteChatService {

    private static final Logger logger = LoggerFactory.getLogger(LiteChatService.class);
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    @Autowired
    private VectorStore vectorStore;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private GraphRagService graphRagService;

    @Autowired(required = false)
    private LanguageModel languageModel;

    @Autowired(required = false)
    private ChatHistoryService chatHistoryService;

    /**
     * Synchronous chat — returns full response.
     */
    public ChatResponse chat(String message, String sessionId) {
        logger.info("Chat request: sessionId={}, messageLength={}", sessionId, message.length());

        // 1. Vector search for relevant context
        List<String> vectorContext = retrieveVectorContext(message, 5);

        // 2. Graph RAG context (if available)
        List<String> graphContext = retrieveGraphContext(message, 5);

        // 3. Merge contexts
        List<String> allContext = new ArrayList<>();
        allContext.addAll(vectorContext);
        allContext.addAll(graphContext);

        // 4. Build conversation history
        List<ChatMessage> history = getConversationHistory(sessionId, 20);

        // 5. Generate LLM response
        String response;
        if (languageModel != null) {
            String prompt = buildPrompt(message, allContext, history);
            response = languageModel.generateResponse(prompt, allContext);
        } else {
            // No LLM — return context directly
            if (allContext.isEmpty()) {
                response = "No LLM configured and no relevant documents found. " +
                        "Set an API key for OpenAI, Anthropic, or Gemini in application.properties.";
            } else {
                response = "No LLM configured. Here are the relevant documents found:\n\n" +
                        allContext.stream()
                                .map(ctx -> "- " + ctx.substring(0, Math.min(ctx.length(), 500)))
                                .collect(Collectors.joining("\n\n"));
            }
        }

        // 6. Store in history
        storeInHistory(sessionId, message, response);

        return new ChatResponse(response, vectorContext.size(), graphContext.size(), sessionId);
    }

    /**
     * Streaming chat via SSE.
     */
    public SseEmitter chatStream(String message, String sessionId) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 minute timeout

        streamExecutor.submit(() -> {
            try {
                ChatResponse response = chat(message, sessionId);

                // Send the response in chunks for streaming effect
                String text = response.response();
                int chunkSize = 20;
                for (int i = 0; i < text.length(); i += chunkSize) {
                    String chunk = text.substring(i, Math.min(i + chunkSize, text.length()));
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(Map.of("content", chunk, "done", false)));
                    Thread.sleep(10); // Small delay for streaming effect
                }

                // Send completion event
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(Map.of(
                                "content", "",
                                "done", true,
                                "vectorResults", response.vectorResultCount(),
                                "graphResults", response.graphResultCount()
                        )));
                emitter.complete();
            } catch (IOException | InterruptedException e) {
                logger.warn("Stream error: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Get conversation history for a session.
     */
    public List<ChatMessage> getHistory(String sessionId) {
        return getConversationHistory(sessionId, 100);
    }

    /**
     * Delete conversation history for a session.
     */
    public void deleteHistory(String sessionId) {
        if (chatHistoryService != null) {
            chatHistoryService.deleteSession(sessionId);
        }
    }

    private List<String> retrieveVectorContext(String query, int k) {
        try {
            if (vectorStore != null && vectorStore.isVectorStoreAvailable()) {
                List<Document> docs = vectorStore.similaritySearch(query, k);
                return docs.stream()
                        .map(Document::getText)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            logger.warn("Vector search failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<String> retrieveGraphContext(String query, int k) {
        try {
            if (graphRagService != null) {
                var graphQuery = GraphRagQuery.builder()
                        .query(query)
                        .k(k)
                        .build();
                var result = graphRagService.answerQuery(graphQuery);
                if (result != null && result.getAnswer() != null) {
                    return List.of(result.getAnswer());
                }
            }
        } catch (Exception e) {
            logger.warn("Graph RAG query failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private String buildPrompt(String userMessage, List<String> context, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();

        if (!context.isEmpty()) {
            sb.append("Use the following context to answer the question. If the context doesn't contain ");
            sb.append("relevant information, say so and answer based on your general knowledge.\n\n");
            sb.append("Context:\n");
            for (int i = 0; i < context.size(); i++) {
                sb.append("[").append(i + 1).append("] ").append(context.get(i)).append("\n\n");
            }
        }

        if (!history.isEmpty()) {
            sb.append("Conversation history:\n");
            for (ChatMessage msg : history) {
                sb.append(msg.role()).append(": ").append(msg.content()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("User: ").append(userMessage);
        return sb.toString();
    }

    private List<ChatMessage> getConversationHistory(String sessionId, int maxMessages) {
        if (chatHistoryService != null) {
            try {
                var messages = chatHistoryService.getSessionMessages(sessionId);
                if (messages != null) {
                    return messages.stream()
                            .map(m -> new ChatMessage(m.getRole().name().toLowerCase(), m.getContent()))
                            .collect(Collectors.toList());
                }
            } catch (Exception e) {
                logger.debug("Could not retrieve history for session {}: {}", sessionId, e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private void storeInHistory(String sessionId, String userMessage, String assistantResponse) {
        if (chatHistoryService != null) {
            try {
                chatHistoryService.addMessage(sessionId, MessageRole.USER, userMessage, null);
                chatHistoryService.addMessage(sessionId, MessageRole.ASSISTANT, assistantResponse, null);
            } catch (Exception e) {
                logger.debug("Could not store history: {}", e.getMessage());
            }
        }
    }

    public record ChatResponse(String response, int vectorResultCount, int graphResultCount, String sessionId) {}
    public record ChatMessage(String role, String content) {}
}
