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
import lombok.RequiredArgsConstructor;
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
import java.util.stream.Collectors;

/**
 * Neo4j-based GraphRAG service implementation.
 * Bean registration is handled by Neo4jGraphBeans.
 */
@RequiredArgsConstructor
@Slf4j
public class Neo4jGraphRagService implements GraphRagService {

    private final Driver neo4jDriver;
    private final EmbeddingModel embeddingModel;
    private final LLMChat llmChat;
    private final KompileChatMemory chatMemory; // <-- DEPENDENCY INJECTION

    private static final String VECTOR_SEARCH_QUERY = """
            CALL db.index.vector.queryNodes('entity-embeddings', $topK, $queryVector)
            YIELD node, score
            RETURN node.description AS context, score
            """;

    @Override
    public GraphRagResult answerQuery(GraphRagQuery query) {
        String conversationId = query.getConversationId();

        // 1. Refine the query with conversation history
        String refinedQuery = refineQueryWithHistory(conversationId, query.getQuery());
        log.info("Original Query: '{}', Refined Query: '{}'", query.getQuery(), refinedQuery);

        // 2. Embed the (potentially refined) query
        // Wrap in try-catch to handle native pointer errors from ND4J
        INDArray queryVector;
        try {
            queryVector = embeddingModel.embed(refinedQuery);
        } catch (NullPointerException e) {
            // This catches JavaCPP "Pointer address of argument X is NULL" errors
            log.warn("Native pointer error during query embedding generation: {}", e.getMessage());
            return new GraphRagResult("Error generating query embedding. Please try again.", "");
        } catch (RuntimeException e) {
            // Catch other runtime exceptions from native operations
            log.warn("Runtime error during query embedding generation: {}", e.getMessage());
            return new GraphRagResult("Error generating query embedding. Please try again.", "");
        }

        // Handle empty/null embeddings
        if (queryVector == null || queryVector.isEmpty() || queryVector.length() == 0) {
            log.warn("Empty query embedding generated for query: {}", refinedQuery);
            return new GraphRagResult("Error generating query embedding. Please try again.", "");
        }

        // 3. Retrieve context from the graph using vector search
        String context = retrieveContext(queryVector, query.getK());

        if (context.isEmpty()) {
            log.warn("No context found for query: {}", refinedQuery);
            return new GraphRagResult("Could not find any relevant information in the graph to answer the query.", "");
        }

        // 4. Generate a synthesized answer using the LLM
        String finalAnswer = synthesizeAnswer(refinedQuery, context);

        // 5. Update chat memory with the current exchange
        chatMemory.add(conversationId, List.of(new UserMessage(query.getQuery()), new AssistantMessage(finalAnswer)));

        return new GraphRagResult(finalAnswer, context);
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

    private String retrieveContext(INDArray queryVector, int topK) {
        try (Session session = neo4jDriver.session()) {
            List<Record> records = session.run(VECTOR_SEARCH_QUERY,
                    Values.parameters("topK", topK, "queryVector", queryVector)).list();

            return records.stream()
                    .map(record -> record.get("context").asString())
                    .collect(Collectors.joining("\n---\n"));
        }
    }

    private String synthesizeAnswer(String userQuery, String context) {
        String prompt = """
                You are a helpful assistant. Answer the user's question based on the provided context.
                Do not use any information outside of this context.

                Context:
                \"""
                %s
                \"""

                Question:
                %s
                """.formatted(context, userQuery);

        return llmChat.prompt().user(prompt).call().content();
    }
}