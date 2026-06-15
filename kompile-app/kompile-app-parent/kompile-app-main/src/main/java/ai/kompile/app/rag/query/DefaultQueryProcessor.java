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

package ai.kompile.app.rag.query;

import ai.kompile.core.rag.query.ProcessedQuery;
import ai.kompile.core.rag.query.QueryIntent;
import ai.kompile.core.rag.query.QueryProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Default query processor that handles basic query transformations.
 * <p>
 * This implementation provides:
 * <ul>
 *   <li>Intent detection based on query patterns</li>
 *   <li>Basic reference resolution for follow-up queries</li>
 *   <li>Context compression by extracting key terms from history</li>
 * </ul>
 *
 * <p>For more sophisticated query rewriting (e.g., using an LLM to rewrite queries),
 * consider using {@code LlmQueryProcessor} or Spring AI's {@code CompressionQueryTransformer}.
 */
@Slf4j
@Service("defaultQueryProcessor")
public class DefaultQueryProcessor implements QueryProcessor {

    // Patterns for detecting follow-up queries
    private static final Pattern FOLLOW_UP_PATTERN = Pattern.compile(
        "(?i)(tell me more|what else|more about|explain|elaborate|continue|go on|" +
        "what about|how about|and what|also|additionally|furthermore)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PRONOUN_PATTERN = Pattern.compile(
        "(?i)\\b(it|this|that|these|those|they|them|their|its|he|she|him|her)\\b"
    );

    private static final Pattern CLARIFICATION_PATTERN = Pattern.compile(
        "(?i)(what do you mean|clarify|explain what|what is meant|can you explain)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
        "(?i)(compare|versus|vs\\.?|difference between|how does .* compare)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SUMMARIZATION_PATTERN = Pattern.compile(
        "(?i)(summarize|summary|recap|overview|in brief|briefly)",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public ProcessedQuery process(String rawQuery, List<Message> conversationHistory) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return ProcessedQuery.unchanged("");
        }

        // Detect intent
        QueryIntent intent = detectIntent(rawQuery);

        // If this is a follow-up or contains pronouns, try to resolve references
        boolean needsRewriting = intent == QueryIntent.FOLLOW_UP ||
                                  PRONOUN_PATTERN.matcher(rawQuery).find();

        String rewrittenQuery = rawQuery;
        boolean wasRewritten = false;

        if (needsRewriting && conversationHistory != null && !conversationHistory.isEmpty()) {
            String contextualQuery = resolveReferences(rawQuery, conversationHistory);
            if (!contextualQuery.equals(rawQuery)) {
                rewrittenQuery = contextualQuery;
                wasRewritten = true;
                log.debug("Query rewritten: '{}' -> '{}'", rawQuery, rewrittenQuery);
            }
        }

        return ProcessedQuery.builder(rawQuery)
            .rewrittenQuery(rewrittenQuery)
            .intent(intent)
            .build();
    }

    /**
     * Detects the intent of the query based on patterns.
     */
    private QueryIntent detectIntent(String query) {
        if (FOLLOW_UP_PATTERN.matcher(query).find()) {
            return QueryIntent.FOLLOW_UP;
        }
        if (CLARIFICATION_PATTERN.matcher(query).find()) {
            return QueryIntent.CLARIFICATION;
        }
        if (COMPARISON_PATTERN.matcher(query).find()) {
            return QueryIntent.COMPARISON;
        }
        if (SUMMARIZATION_PATTERN.matcher(query).find()) {
            return QueryIntent.SUMMARIZATION;
        }
        if (query.trim().endsWith("?")) {
            return QueryIntent.QUESTION;
        }
        return QueryIntent.QUESTION; // Default to question
    }

    /**
     * Attempts to resolve pronoun references using conversation history.
     * <p>
     * This is a simple heuristic-based approach. For production use,
     * consider using an LLM-based query rewriter.
     */
    private String resolveReferences(String query, List<Message> history) {
        if (history.isEmpty()) {
            return query;
        }

        // Extract key topics from recent conversation
        StringBuilder contextBuilder = new StringBuilder();
        int maxMessages = Math.min(4, history.size()); // Look at last 4 messages

        for (int i = history.size() - maxMessages; i < history.size(); i++) {
            Message msg = history.get(i);
            if (msg instanceof UserMessage) {
                String content = msg.getText();
                if (content != null && content.length() > 10) {
                    // Extract potential topic phrases (nouns, key terms)
                    extractKeyTerms(content, contextBuilder);
                }
            } else if (msg instanceof AssistantMessage) {
                String content = msg.getText();
                if (content != null && content.length() > 20) {
                    // Extract from first sentence of assistant response
                    int firstPeriod = content.indexOf('.');
                    if (firstPeriod > 0 && firstPeriod < 200) {
                        extractKeyTerms(content.substring(0, firstPeriod), contextBuilder);
                    }
                }
            }
        }

        String context = contextBuilder.toString().trim();
        if (context.isEmpty()) {
            return query;
        }

        // Simple replacement strategy: append context to queries with pronouns
        if (PRONOUN_PATTERN.matcher(query).find()) {
            // Check if query is very short (like "what about it?")
            if (query.split("\\s+").length <= 5) {
                return query + " " + context;
            }
        }

        // For follow-up queries, prepend context
        if (FOLLOW_UP_PATTERN.matcher(query).find()) {
            return context + " " + query;
        }

        return query;
    }

    /**
     * Extracts key terms from text and appends to the builder.
     */
    private void extractKeyTerms(String text, StringBuilder builder) {
        // Simple extraction: look for capitalized words and quoted phrases
        String[] words = text.split("\\s+");
        for (String word : words) {
            // Skip common words
            if (word.length() > 3 && Character.isUpperCase(word.charAt(0))) {
                String clean = word.replaceAll("[^a-zA-Z0-9]", "");
                if (clean.length() > 3 && !isCommonWord(clean)) {
                    if (builder.length() > 0) {
                        builder.append(" ");
                    }
                    builder.append(clean);
                }
            }
        }
    }

    /**
     * Checks if a word is a common word that shouldn't be used as context.
     */
    private boolean isCommonWord(String word) {
        String lower = word.toLowerCase();
        return switch (lower) {
            case "the", "this", "that", "these", "those", "there", "here",
                 "what", "when", "where", "which", "who", "whom", "whose",
                 "have", "has", "had", "been", "being", "will", "would",
                 "could", "should", "might", "must", "shall", "from", "with",
                 "about", "into", "through", "during", "before", "after" -> true;
            default -> false;
        };
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getName() {
        return "DefaultQueryProcessor";
    }
}
