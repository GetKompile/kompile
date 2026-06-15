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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Multi-query transformer that decomposes complex questions into sub-questions.
 * <p>
 * This is useful for complex questions that require gathering information from
 * multiple sources or perspectives. Each sub-question can retrieve different
 * relevant documents.
 */
@Slf4j
@RequiredArgsConstructor
public class MultiQueryTransformer implements QueryTransformer {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant that breaks down complex questions into simpler sub-questions.
            Given a question, identify if it can be decomposed into multiple simpler questions that,
            when answered together, would provide a complete answer to the original question.

            Rules:
            1. Only decompose if the question is genuinely complex or multi-part
            2. Each sub-question should be self-contained and answerable independently
            3. Generate between 2-4 sub-questions maximum
            4. If the question is already simple, return just the original question
            5. Return ONLY the questions, one per line, numbered 1., 2., 3., etc.

            Original question: %s

            Sub-questions:""";

    private final ChatClient chatClient;
    private final QueryTransformerProperties properties;

    @Override
    public List<String> transform(String query, QueryTransformContext context) {
        int maxQueries = context.getMaxQueries() > 0 ? context.getMaxQueries() : properties.getMaxQueries();

        try {
            String prompt = String.format(DEFAULT_SYSTEM_PROMPT, query);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            List<String> subQueries = parseSubQueries(response);

            if (subQueries.isEmpty() || subQueries.size() == 1) {
                log.debug("Question not decomposed, returning original");
                return List.of(query);
            }

            List<String> result = new ArrayList<>();
            if (context.isIncludeOriginal() || properties.isIncludeOriginal()) {
                result.add(query);
            }
            result.addAll(subQueries.stream()
                    .limit(maxQueries)
                    .collect(Collectors.toList()));

            log.debug("Decomposed query into {} sub-questions", result.size());
            return result;

        } catch (Exception e) {
            log.warn("Failed to decompose query, returning original: {}", e.getMessage());
            return List.of(query);
        }
    }

    private List<String> parseSubQueries(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        return Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> line.replaceFirst("^\\d+\\.\\s*", "")) // Remove numbering
                .filter(line -> !line.isBlank() && line.contains("?")) // Must be a question
                .collect(Collectors.toList());
    }

    @Override
    public String getName() {
        return "multi-query";
    }

    @Override
    public QueryTransformationType getType() {
        return QueryTransformationType.DECOMPOSITION;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
