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

package ai.kompile.evaluation;

import ai.kompile.core.evaluation.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

/**
 * Evaluates if the retrieved context documents are relevant to the query.
 * <p>
 * This helps identify retrieval quality issues before they affect response quality.
 */
@Slf4j
@RequiredArgsConstructor
public class ContextRelevancyEvaluator implements RagEvaluator {

    private static final String EVALUATION_PROMPT = """
            Evaluate if the retrieved context documents are relevant to answering the query.

            Query: "%s"

            Context Documents:
            %s

            For each document, determine if it contains information useful for answering the query.
            Calculate the relevancy score as: (relevant documents) / (total documents)

            Respond with ONLY a JSON object:
            {
              "score": 0.0-1.0,
              "totalDocuments": N,
              "relevantDocuments": N,
              "explanation": "brief explanation"
            }
            """;

    private final ChatClient chatClient;
    private final EvaluationProperties properties;

    @Override
    public EvaluationResult evaluate(String query, String response,
                                     List<String> retrievedDocuments, EvaluationContext context) {
        if (retrievedDocuments == null || retrievedDocuments.isEmpty()) {
            log.debug("No context provided, skipping context relevancy evaluation");
            return EvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(false)
                    .score(0.0)
                    .explanation("No context documents retrieved")
                    .build();
        }

        try {
            StringBuilder contextBuilder = new StringBuilder();
            for (int i = 0; i < retrievedDocuments.size(); i++) {
                contextBuilder.append(String.format("Document %d:\n%s\n---\n",
                        i + 1, retrievedDocuments.get(i)));
            }

            String prompt = String.format(EVALUATION_PROMPT, query, contextBuilder.toString());

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            JsonNode json = EvaluationResponseParser.parse(result);
            if (json == null) {
                log.warn("Could not parse context relevancy evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "Failed to parse LLM evaluation response");
            }

            double score = EvaluationResponseParser.getDouble(json, "score", -1.0);
            if (score < 0.0) {
                log.warn("No valid score in context relevancy evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "LLM response did not contain a valid score");
            }

            String explanation = EvaluationResponseParser.getString(json, "explanation", null);
            int total = EvaluationResponseParser.getInt(json, "totalDocuments", 0);
            int relevant = EvaluationResponseParser.getInt(json, "relevantDocuments", 0);

            double threshold = context.getThreshold() != null
                    ? context.getThreshold()
                    : properties.getContextRelevancy().getThreshold();

            return EvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(score >= threshold)
                    .score(score)
                    .threshold(threshold)
                    .explanation(explanation)
                    .metrics(Map.of(
                            "totalDocuments", (double) total,
                            "relevantDocuments", (double) relevant
                    ))
                    .build();

        } catch (Exception e) {
            log.error("Context relevancy evaluation failed: {}", e.getMessage());
            return EvaluationResult.fail(getName(), getType(), 0.0, e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "context-relevancy";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.CONTEXT_RELEVANCY;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
