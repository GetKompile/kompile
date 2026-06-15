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
 * Evaluates the correctness of an answer against a ground truth reference.
 * <p>
 * Combines semantic similarity and factual accuracy to provide a comprehensive
 * correctness score. Requires a ground truth answer in the evaluation context.
 */
@Slf4j
@RequiredArgsConstructor
public class AnswerCorrectnessEvaluator implements RagEvaluator {

    private static final String EVALUATION_PROMPT = """
            Compare the generated answer with the ground truth answer.
            Evaluate both semantic similarity and factual accuracy.

            Question: "%s"

            Ground Truth Answer: "%s"

            Generated Answer: "%s"

            Evaluate:
            1. Semantic Similarity (0.0-1.0): How similar is the meaning?
            2. Factual Accuracy (0.0-1.0): Are the facts correct?
            3. Completeness (0.0-1.0): Does it cover all key points?

            Respond with ONLY a JSON object:
            {
              "semanticSimilarity": 0.0-1.0,
              "factualAccuracy": 0.0-1.0,
              "completeness": 0.0-1.0,
              "overallScore": 0.0-1.0,
              "explanation": "brief explanation"
            }
            """;

    private final ChatClient chatClient;
    private final EvaluationProperties properties;

    @Override
    public EvaluationResult evaluate(String query, String response,
                                     List<String> retrievedDocuments, EvaluationContext context) {
        String groundTruth = context.getGroundTruth();
        if (groundTruth == null || groundTruth.isBlank()) {
            log.debug("No ground truth provided, skipping answer correctness evaluation");
            return EvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(true)
                    .score(1.0)
                    .explanation("Skipped: No ground truth provided")
                    .build();
        }

        try {
            String prompt = String.format(EVALUATION_PROMPT, query, groundTruth, response);

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            JsonNode json = EvaluationResponseParser.parse(result);
            if (json == null) {
                log.warn("Could not parse answer correctness evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "Failed to parse LLM evaluation response");
            }

            double semanticScore = EvaluationResponseParser.getDouble(json, "semanticSimilarity", -1.0);
            double factualScore = EvaluationResponseParser.getDouble(json, "factualAccuracy", -1.0);
            double overallScore = EvaluationResponseParser.getDouble(json, "overallScore", -1.0);

            if (semanticScore < 0.0 || factualScore < 0.0) {
                log.warn("Missing required scores in answer correctness response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "LLM response did not contain valid semantic/factual scores");
            }

            String explanation = EvaluationResponseParser.getString(json, "explanation", null);

            EvaluationProperties.AnswerCorrectnessConfig config = properties.getAnswerCorrectness();
            double threshold = context.getThreshold() != null
                    ? context.getThreshold()
                    : config.getThreshold();

            // Calculate weighted score
            double weightedScore = (semanticScore * config.getSemanticWeight()) +
                    (factualScore * config.getFactualWeight());

            return EvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(weightedScore >= threshold)
                    .score(weightedScore)
                    .threshold(threshold)
                    .explanation(explanation)
                    .metrics(Map.of(
                            "semanticSimilarity", semanticScore,
                            "factualAccuracy", factualScore,
                            "llmOverallScore", overallScore < 0.0 ? weightedScore : overallScore
                    ))
                    .build();

        } catch (Exception e) {
            log.error("Answer correctness evaluation failed: {}", e.getMessage());
            return EvaluationResult.fail(getName(), getType(), 0.0, e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "answer-correctness";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.ANSWER_CORRECTNESS;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }

    @Override
    public boolean requiresGroundTruth() {
        return true;
    }
}
