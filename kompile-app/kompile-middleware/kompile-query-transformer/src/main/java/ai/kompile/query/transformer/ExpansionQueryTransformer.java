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
 * Expands a query into multiple semantic variants.
 * <p>
 * This improves recall by searching for multiple phrasings of the same question.
 * The expanded queries capture different ways to ask the same thing.
 */
@Slf4j
@RequiredArgsConstructor
public class ExpansionQueryTransformer implements QueryTransformer {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a query expansion assistant. Generate %d different versions of the given question
            that capture the same intent but use different words, phrases, or perspectives.

            Rules:
            1. Each version should be semantically similar but use different vocabulary
            2. Include variations like synonyms, rephrasing, and different question styles
            3. Keep each version concise and focused on the original intent
            4. Return ONLY the expanded queries, one per line, numbered 1., 2., 3., etc.

            Original question: %s

            Expanded versions:""";

    private final ChatClient chatClient;
    private final QueryTransformerProperties properties;

    @Override
    public List<String> transform(String query, QueryTransformContext context) {
        int maxQueries = context.getMaxQueries() > 0 ? context.getMaxQueries() : properties.getMaxQueries();
        boolean includeOriginal = context.isIncludeOriginal() || properties.isIncludeOriginal();

        try {
            String prompt = String.format(DEFAULT_SYSTEM_PROMPT, maxQueries, query);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            List<String> expanded = parseExpandedQueries(response);

            if (expanded.isEmpty()) {
                log.debug("No expansion generated, returning original query");
                return List.of(query);
            }

            List<String> result = new ArrayList<>();
            if (includeOriginal) {
                result.add(query);
            }
            result.addAll(expanded.stream()
                    .limit(maxQueries)
                    .collect(Collectors.toList()));

            log.debug("Expanded query into {} variants", result.size());
            return result;

        } catch (Exception e) {
            log.warn("Failed to expand query, returning original: {}", e.getMessage());
            return List.of(query);
        }
    }

    private List<String> parseExpandedQueries(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        return Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> line.replaceFirst("^\\d+\\.\\s*", "")) // Remove numbering
                .filter(line -> !line.isBlank())
                .collect(Collectors.toList());
    }

    @Override
    public String getName() {
        return "expansion";
    }

    @Override
    public QueryTransformationType getType() {
        return QueryTransformationType.EXPANSION;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
