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

package ai.kompile.graph.neo4j;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.llm.memory.KompileChatMemory;
import ai.kompile.knowledgegraph.resolution.SessionEntityState;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Neo4j-based GraphRAG service implementation.
 * Bean registration is handled by Neo4jGraphBeans.
 */
@Slf4j
public class Neo4jGraphRagService implements GraphRagService {

    private final Driver neo4jDriver;
    private final EmbeddingModel embeddingModel;
    private final LLMChat llmChat;
    private final KompileChatMemory chatMemory;

    // Per-conversation entity tracking
    private final Map<String, SessionEntityState> sessionEntities = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger turnCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    private static final Pattern ENTITY_MENTION_PATTERN = Pattern.compile(
            "\\b(that|the|this|those)\\s+(company|person|organization|place|product|event|ceo|founder|manager)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final String VECTOR_SEARCH_QUERY = """
            CALL db.index.vector.queryNodes('entity-embeddings', $topK, $queryVector)
            YIELD node, score
            RETURN node.description AS context, node.title AS title, node.id AS entityId, labels(node) AS labels, score
            """;

    public Neo4jGraphRagService(Driver neo4jDriver, EmbeddingModel embeddingModel,
                                 LLMChat llmChat, KompileChatMemory chatMemory) {
        this.neo4jDriver = neo4jDriver;
        this.embeddingModel = embeddingModel;
        this.llmChat = llmChat;
        this.chatMemory = chatMemory;
    }

    @Override
    public GraphRagResult answerQuery(GraphRagQuery query) {
        String conversationId = query.getConversationId();
        int currentTurn = turnCounter.incrementAndGet();

        // 1. Get or create session entity state
        SessionEntityState entityState = sessionEntities.computeIfAbsent(
                conversationId, k -> new SessionEntityState());

        // 2. Resolve ambiguous entity references in the query
        String resolvedQuery = resolveEntityReferences(query.getQuery(), entityState);

        // 3. Refine the query with conversation history
        String refinedQuery = refineQueryWithHistory(conversationId, resolvedQuery);
        String safeQuery = query.getQuery() != null ? query.getQuery().replaceAll("[\\r\\n]", " ") : null;
        log.info("Original: '{}', Resolved: '{}', Refined: '{}'",
                safeQuery, resolvedQuery, refinedQuery);

        // 4. Embed the (potentially refined) query
        INDArray queryVector;
        try {
            queryVector = embeddingModel.embed(refinedQuery);
        } catch (NullPointerException e) {
            log.warn("Native pointer error during query embedding generation: {}", e.getMessage());
            return GraphRagResult.builder().answer("Error generating query embedding. Please try again.").formattedContext("").build();
        } catch (RuntimeException e) {
            log.warn("Runtime error during query embedding generation: {}", e.getMessage());
            return GraphRagResult.builder().answer("Error generating query embedding. Please try again.").formattedContext("").build();
        }

        if (queryVector == null || queryVector.isEmpty() || queryVector.length() == 0) {
            log.warn("Empty query embedding generated for query: {}", refinedQuery);
            return GraphRagResult.builder().answer("Error generating query embedding. Please try again.").formattedContext("").build();
        }

        // 5. Retrieve context from the graph using vector search
        String context = retrieveContextWithEntityTracking(queryVector, query.getK(), entityState);

        if (context.isEmpty()) {
            log.warn("No context found for query: {}", refinedQuery);
            return GraphRagResult.builder().answer("Could not find any relevant information in the graph to answer the query.").formattedContext("").build();
        }

        // 6. Generate a synthesized answer using the LLM
        String entityContext = entityState.buildEntityContext(5);
        String finalAnswer = synthesizeAnswer(refinedQuery, context, entityContext);

        // 7. Update chat memory with the current exchange
        chatMemory.add(conversationId, List.of(new UserMessage(query.getQuery()), new AssistantMessage(finalAnswer)));

        return GraphRagResult.builder().answer(finalAnswer).formattedContext(context).build();
    }

    /**
     * Resolve ambiguous references like "that company" or "the CEO" using session entity state.
     */
    private String resolveEntityReferences(String query, SessionEntityState entityState) {
        if (entityState.size() == 0) return query;

        Matcher matcher = ENTITY_MENTION_PATTERN.matcher(query);
        StringBuffer sb = new StringBuffer();
        boolean modified = false;

        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            SessionEntityState.TrackedEntity resolved = entityState.resolveReference(fullMatch);
            if (resolved != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved.name()));
                modified = true;
                log.debug("Resolved '{}' -> '{}'", fullMatch, resolved.name());
            }
        }
        matcher.appendTail(sb);

        return modified ? sb.toString() : query;
    }

    /**
     * Get session entity state for a conversation (used by AgentChatService).
     */
    public SessionEntityState getSessionEntityState(String conversationId) {
        return sessionEntities.computeIfAbsent(conversationId, k -> new SessionEntityState());
    }

    /**
     * Refines the user query by incorporating conversation history, similar to the logic
     * in the Python implementation's `_build_query` method.
     */
    private String refineQueryWithHistory(String conversationId, String currentQuery) {
        if (!StringUtils.hasText(conversationId) || !chatMemory.exists(conversationId)) {
            return currentQuery; // No history, use the query as-is
        }

        List<Message> history = chatMemory.get(conversationId, 10); // Get last 10 messages
        String historyStr = history.stream()
                .map(msg -> msg.getMessageType().getValue() + ": " + msg.getText())
                .collect(Collectors.joining("\n"));

        String prompt = """
                Based on the following conversation history and the new user query,
                generate a single, standalone query that contains all the necessary context
                to be understood without the history.

                Conversation History:
                ---
                %s
                ---
                New User Query: %s
                """.formatted(historyStr, currentQuery);

        return llmChat.prompt().user(prompt).call().content();
    }

    private String retrieveContextWithEntityTracking(INDArray queryVector, int topK, SessionEntityState entityState) {
        try (Session session = neo4jDriver.session()) {
            List<Record> records = session.run(VECTOR_SEARCH_QUERY,
                    Values.parameters("topK", topK, "queryVector", queryVector.toFloatVector())).list();

            StringBuilder contextBuilder = new StringBuilder();
            for (Record record : records) {
                String context = record.get("context").asString();
                String title = record.containsKey("title") ? record.get("title").asString(null) : null;
                String entityId = record.containsKey("entityId") ? record.get("entityId").asString(null) : null;

                // Track entities found in search results
                if (title != null && entityId != null) {
                    List<Object> labelObjs = record.containsKey("labels") ? record.get("labels").asList() : List.of();
                    String type = labelObjs.stream()
                            .map(Object::toString)
                            .filter(l -> !"__Entity__".equals(l))
                            .findFirst()
                            .orElse("CONCEPT");

                    entityState.trackEntity(entityId, title, type, List.of(), turnCounter.get(), entityId);
                }

                if (contextBuilder.length() > 0) contextBuilder.append("\n---\n");
                contextBuilder.append(context);
            }

            return contextBuilder.toString();
        }
    }

    private String synthesizeAnswer(String userQuery, String context, String entityContext) {
        String entityInfo = entityContext != null && !entityContext.isEmpty()
                ? "\n\n" + entityContext + "\n"
                : "";

        String prompt = """
                You are a helpful assistant. Answer the user's question based on the provided context.
                Do not use any information outside of this context.
                %s
                Context:
                \"""
                %s
                \"""

                Question:
                %s
                """.formatted(entityInfo, context, userQuery);

        return llmChat.prompt().user(prompt).call().content();
    }
}