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

/**
 * Evaluates if the response is relevant to the user's query.
 * <p>
 * Measures how well the response addresses the question asked.
 */
@Slf4j
@RequiredArgsConstructor
public class RelevancyEvaluator implements RagEvaluator {

    private static final String EVALUATION_PROMPT = """
            Evaluate if the following response is relevant to the user's question.
            Consider:
            1. Does the response directly address the question?
            2. Is the information provided useful for answering the question?
            3. Is the response on-topic?

            Question: "%s"

            Response: "%s"

            Rate the relevancy on a scale of 0.0 to 1.0, where:
            - 1.0 = Perfectly relevant, directly answers the question
            - 0.7-0.9 = Mostly relevant with minor tangents
            - 0.4-0.6 = Partially relevant, some useful information
            - 0.1-0.3 = Mostly irrelevant
            - 0.0 = Completely irrelevant

            Respond with ONLY a JSON object:
            {"score": 0.0-1.0, "explanation": "brief explanation"}
            """;

    private final ChatClient chatClient;
    private final EvaluationProperties properties;

    @Override
    public EvaluationResult evaluate(String query, String response,
                                     List<String> retrievedDocuments, EvaluationContext context) {
        try {
            String prompt = String.format(EVALUATION_PROMPT, query, response);

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            JsonNode json = EvaluationResponseParser.parse(result);
            if (json == null) {
                log.warn("Could not parse relevancy evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "Failed to parse LLM evaluation response");
            }

            double score = EvaluationResponseParser.getDouble(json, "score", -1.0);
            if (score < 0.0) {
                log.warn("No valid score in relevancy evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "LLM response did not contain a valid score");
            }

            String explanation = EvaluationResponseParser.getString(json, "explanation", null);
            double threshold = context.getThreshold() != null
                    ? context.getThreshold()
                    : properties.getRelevancy().getThreshold();

            return EvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(score >= threshold)
                    .score(score)
                    .threshold(threshold)
                    .explanation(explanation)
                    .build();

        } catch (Exception e) {
            log.error("Relevancy evaluation failed: {}", e.getMessage());
            return EvaluationResult.fail(getName(), getType(), 0.0, e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "relevancy";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.RELEVANCY;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
