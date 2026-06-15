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
 * Step-back prompting query transformer.
 * <p>
 * Generates a more abstract, higher-level question that can help retrieve
 * broader context before answering the specific question. This is useful
 * for questions that require understanding foundational concepts.
 */
@Slf4j
@RequiredArgsConstructor
public class StepBackQueryTransformer implements QueryTransformer {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are an expert at generating abstract questions. Given a specific question,
            generate a more general "step-back" question that would help gather broader
            background knowledge needed to answer the original question.

            Examples:
            - Original: "What is the population of Paris in 2023?"
              Step-back: "What are the demographics and population trends of major European cities?"

            - Original: "How do I fix a null pointer exception in Java?"
              Step-back: "What are common causes of runtime exceptions in Java and how to debug them?"

            Original question: %s

            Generate a step-back question (return ONLY the question):""";

    private final ChatClient chatClient;
    private final QueryTransformerProperties properties;

    @Override
    public List<String> transform(String query, QueryTransformContext context) {
        try {
            String prompt = String.format(DEFAULT_SYSTEM_PROMPT, query);

            String stepBackQuery = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (stepBackQuery != null && !stepBackQuery.isBlank()) {
                log.debug("Generated step-back query: '{}'", stepBackQuery.trim());

                List<String> result = new ArrayList<>();
                // Step-back query first for broader context
                result.add(stepBackQuery.trim());
                // Then original query for specific answer
                if (context.isIncludeOriginal() || properties.isIncludeOriginal()) {
                    result.add(query);
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to generate step-back query, returning original: {}",
                    e.getMessage());
        }

        return List.of(query);
    }

    @Override
    public String getName() {
        return "step-back";
    }

    @Override
    public QueryTransformationType getType() {
        return QueryTransformationType.STEP_BACK;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
