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

package ai.kompile.query.transformer;

import ai.kompile.core.query.QueryTransformContext;
import ai.kompile.core.query.QueryTransformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Compresses follow-up questions with conversation context into standalone queries.
 * <p>
 * This is useful when users ask follow-up questions that reference previous
 * messages (e.g., "What about the second one?" or "Can you explain more?").
 * The transformer uses an LLM to reformulate the query as a standalone question.
 */
@Slf4j
@RequiredArgsConstructor
public class CompressingQueryTransformer implements QueryTransformer {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a query reformulation assistant. Your task is to take a follow-up question
            and conversation history, then reformulate it as a standalone question that captures
            all necessary context.

            Rules:
            1. The reformulated question should be self-contained and understandable without the conversation history
            2. Preserve the original intent and any specific details mentioned
            3. If the question is already standalone, return it as-is
            4. Return ONLY the reformulated question, nothing else

            Conversation history:
            %s

            Current question: %s

            Reformulated standalone question:""";

    private final ChatClient chatClient;
    private final QueryTransformerProperties properties;

    @Override
    public List<String> transform(String query, QueryTransformContext context) {
        if (context.getConversationHistory() == null || context.getConversationHistory().isEmpty()) {
            log.debug("No conversation history, returning original query");
            return List.of(query);
        }

        try {
            String historyText = formatConversationHistory(context.getConversationHistory());
            String systemPrompt = properties.getSystemPrompt() != null
                    ? properties.getSystemPrompt()
                    : String.format(DEFAULT_SYSTEM_PROMPT, historyText, query);

            String reformulated = chatClient.prompt()
                    .user(systemPrompt)
                    .call()
                    .content();

            if (reformulated != null && !reformulated.isBlank()) {
                log.debug("Compressed query: '{}' -> '{}'", query, reformulated.trim());
                return List.of(reformulated.trim());
            }
        } catch (Exception e) {
            log.warn("Failed to compress query, returning original: {}", e.getMessage());
        }

        return List.of(query);
    }

    private String formatConversationHistory(List<Message> history) {
        return history.stream()
                .map(m -> m.getMessageType().name() + ": " + m.getText())
                .collect(Collectors.joining("\n"));
    }

    @Override
    public String getName() {
        return "compression";
    }

    @Override
    public QueryTransformationType getType() {
        return QueryTransformationType.COMPRESSION;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
