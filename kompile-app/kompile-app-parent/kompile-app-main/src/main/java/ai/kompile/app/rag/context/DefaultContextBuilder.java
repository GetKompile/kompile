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

package ai.kompile.app.rag.context;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.rag.context.BuiltContext;
import ai.kompile.core.rag.context.ContextBuildRequest;
import ai.kompile.core.rag.context.ContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Default context builder that formats retrieved documents and conversation
 * history for LLM consumption.
 * <p>
 * Features:
 * <ul>
 *   <li>Token-aware truncation to stay within budget</li>
 *   <li>Document formatting with metadata</li>
 *   <li>Conversation history windowing</li>
 *   <li>Customizable system prompts</li>
 * </ul>
 */
@Slf4j
@Service("defaultContextBuilder")
public class DefaultContextBuilder implements ContextBuilder {

    private static final String DEFAULT_SYSTEM_PROMPT = """
        You are a helpful AI assistant. Use the provided context to answer questions accurately.
        If the context doesn't contain relevant information to answer the question, say so clearly.
        Be concise but thorough in your responses.
        """;

    private static final String RAG_CONTEXT_TEMPLATE = """

        ## Retrieved Context

        The following documents were retrieved to help answer your question:

        %s

        ---

        Based on the above context, please answer the following question:
        """;

    private static final String DOCUMENT_TEMPLATE = """
        ### Document %d (Score: %.3f)
        %s
        """;

    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    @Override
    public BuiltContext build(ContextBuildRequest request) {
        if (request == null) {
            return BuiltContext.empty();
        }

        BuiltContext.Builder builder = BuiltContext.builder();

        // Determine system prompt
        String systemPrompt = request.hasCustomSystemPrompt()
            ? request.systemPromptTemplate()
            : DEFAULT_SYSTEM_PROMPT;
        builder.systemPrompt(systemPrompt);

        int tokenBudget = request.maxTokens() > 0 ? request.maxTokens() : Integer.MAX_VALUE;
        int usedTokens = estimateTokens(systemPrompt);

        // Build context string from documents
        String contextString = "";
        int includedDocs = 0;
        boolean truncated = false;

        if (request.hasDocuments()) {
            StringBuilder contextBuilder = new StringBuilder();
            List<ScoredDocument> documents = request.documents();

            for (int i = 0; i < documents.size(); i++) {
                ScoredDocument doc = documents.get(i);
                String docText = formatDocument(doc, i + 1);
                int docTokens = estimateTokens(docText);

                // Check if we have room for this document
                if (usedTokens + docTokens > tokenBudget) {
                    // Try to include a truncated version
                    int remainingTokens = tokenBudget - usedTokens - 50; // Leave some buffer
                    if (remainingTokens > 100) {
                        String truncatedDoc = truncateToTokens(docText, remainingTokens);
                        contextBuilder.append(truncatedDoc).append("\n[... truncated]\n\n");
                        includedDocs++;
                        truncated = true;
                    }
                    break;
                }

                contextBuilder.append(docText).append("\n");
                usedTokens += docTokens;
                includedDocs++;
            }

            contextString = contextBuilder.toString().trim();
        }

        builder.contextString(contextString);
        builder.includedDocCount(includedDocs);
        builder.truncated(truncated);

        // Build user prompt with context
        String userPromptWithContext = request.currentQuery();
        if (!contextString.isEmpty()) {
            userPromptWithContext = String.format(RAG_CONTEXT_TEMPLATE, contextString) + request.currentQuery();
        }
        builder.userPromptWithContext(userPromptWithContext);
        usedTokens += estimateTokens(userPromptWithContext);

        // Format conversation history (if room in budget)
        List<Message> formattedHistory = new ArrayList<>();
        if (request.hasHistory()) {
            int historyBudget = tokenBudget - usedTokens - 100; // Leave buffer for response
            formattedHistory = formatHistory(request.conversationHistory(), historyBudget);
            for (Message msg : formattedHistory) {
                usedTokens += estimateTokens(msg.getText());
            }
        }
        builder.formattedHistory(formattedHistory);
        builder.estimatedTokens(usedTokens);

        return builder.build();
    }

    /**
     * Formats a single document for inclusion in the context.
     */
    private String formatDocument(ScoredDocument doc, int index) {
        String content = doc.getText();
        if (content == null || content.isBlank()) {
            content = "[Empty document]";
        }

        // Clean up content
        content = content.trim();

        return String.format(DOCUMENT_TEMPLATE, index, doc.score(), content);
    }

    /**
     * Formats conversation history, respecting token budget.
     */
    private List<Message> formatHistory(List<Message> history, int tokenBudget) {
        if (history == null || history.isEmpty() || tokenBudget <= 0) {
            return List.of();
        }

        List<Message> result = new ArrayList<>();
        int usedTokens = 0;

        // Process from most recent to oldest
        for (int i = history.size() - 1; i >= 0 && usedTokens < tokenBudget; i--) {
            Message msg = history.get(i);
            int msgTokens = estimateTokens(msg.getText());

            if (usedTokens + msgTokens <= tokenBudget) {
                result.add(0, msg); // Add at beginning to maintain order
                usedTokens += msgTokens;
            } else {
                // Can't fit more messages
                break;
            }
        }

        return result;
    }

    /**
     * Truncates text to approximately fit within token budget.
     */
    private String truncateToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        int maxChars = maxTokens * CHARS_PER_TOKEN_ESTIMATE;
        if (text.length() <= maxChars) {
            return text;
        }

        // Try to truncate at a sentence boundary
        String truncated = text.substring(0, maxChars);
        int lastPeriod = truncated.lastIndexOf('.');
        int lastNewline = truncated.lastIndexOf('\n');
        int breakPoint = Math.max(lastPeriod, lastNewline);

        if (breakPoint > maxChars / 2) {
            return truncated.substring(0, breakPoint + 1);
        }

        return truncated;
    }

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Simple heuristic: ~4 characters per token for English text
        return (int) Math.ceil((double) text.length() / CHARS_PER_TOKEN_ESTIMATE);
    }

    @Override
    public String getName() {
        return "DefaultContextBuilder";
    }
}
