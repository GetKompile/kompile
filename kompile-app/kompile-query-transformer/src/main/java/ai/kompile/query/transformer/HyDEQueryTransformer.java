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
import java.util.List;

/**
 * Hypothetical Document Embedding (HyDE) query transformer.
 * <p>
 * Instead of searching with the query directly, HyDE generates a hypothetical
 * answer document and uses that for retrieval. This can improve retrieval for
 * questions where the answer vocabulary differs from the question vocabulary.
 */
@Slf4j
@RequiredArgsConstructor
public class HyDEQueryTransformer implements QueryTransformer {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a helpful assistant. Please write a detailed passage that would answer
            the following question. The passage should be informative and contain relevant
            facts and details.

            Question: %s

            Write a passage that answers this question:""";

    private final ChatClient chatClient;
    private final QueryTransformerProperties properties;

    @Override
    public List<String> transform(String query, QueryTransformContext context) {
        try {
            String prompt = String.format(DEFAULT_SYSTEM_PROMPT, query);

            String hypotheticalDocument = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (hypotheticalDocument != null && !hypotheticalDocument.isBlank()) {
                log.debug("Generated hypothetical document for HyDE ({} chars)",
                        hypotheticalDocument.length());

                List<String> result = new ArrayList<>();
                if (context.isIncludeOriginal() || properties.isIncludeOriginal()) {
                    result.add(query);
                }
                result.add(hypotheticalDocument.trim());
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to generate hypothetical document, returning original: {}",
                    e.getMessage());
        }

        return List.of(query);
    }

    @Override
    public String getName() {
        return "hyde";
    }

    @Override
    public QueryTransformationType getType() {
        return QueryTransformationType.HYPOTHETICAL_DOCUMENT;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
