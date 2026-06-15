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

package ai.kompile.core.rag.query;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Transforms conversational queries into effective retrieval queries.
 * <p>
 * Query processing is critical for handling follow-up questions that
 * reference previous conversation context (e.g., "tell me more about that",
 * "what else did they say?").
 *
 * <h2>Processing Capabilities</h2>
 * <ul>
 *   <li><b>Reference Resolution:</b> Resolve pronouns and references using conversation history</li>
 *   <li><b>Query Compression:</b> Compress long conversations into standalone queries</li>
 *   <li><b>Intent Detection:</b> Classify query type (question, follow-up, clarification, etc.)</li>
 *   <li><b>Entity Extraction:</b> Identify named entities mentioned in the query</li>
 *   <li><b>Filter Extraction:</b> Parse metadata filters from natural language</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>
 * Conversation History:
 *   User: "What's in the quarterly report?"
 *   Bot: "The report discusses revenue growth and performance metrics..."
 *
 * Current Query: "What else did they say about performance?"
 *
 * Processed Query:
 *   rewrittenQuery: "quarterly report performance metrics analysis details"
 *   intent: FOLLOW_UP
 *   entities: ["quarterly report", "performance"]
 * </pre>
 *
 * @see ProcessedQuery
 * @see QueryIntent
 */
public interface QueryProcessor {

    /**
     * Processes a query using conversation history for context.
     *
     * @param rawQuery The user's raw query string
     * @param conversationHistory Previous messages for context (may be empty)
     * @return Processed query with rewriting, intent, and metadata
     */
    ProcessedQuery process(String rawQuery, List<Message> conversationHistory);

    /**
     * Processes a query without conversation context.
     *
     * @param rawQuery The user's raw query string
     * @return Processed query (typically unchanged for standalone queries)
     */
    default ProcessedQuery process(String rawQuery) {
        return process(rawQuery, List.of());
    }

    /**
     * Checks if this processor is available and ready.
     * <p>
     * May return false if required models (e.g., for LLM-based rewriting)
     * are not loaded.
     *
     * @return true if processor is operational
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns the name/identifier of this processor implementation.
     *
     * @return Processor name
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
