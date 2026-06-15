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

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates if the response is faithful to (grounded in) the provided context.
 * <p>
 * Measures how well the response sticks to information in the retrieved documents
 * without adding unsupported claims.
 */
@Slf4j
@RequiredArgsConstructor
public class FaithfulnessEvaluator implements RagEvaluator {

    private static final String EVALUATION_PROMPT = """
            Evaluate if the response is faithful to the provided context documents.
            Check if all claims in the response can be verified from the context.

            Context Documents:
            %s

            Response to evaluate:
            "%s"

            For each major claim:
            - SUPPORTED: Can be verified from the context
            - UNSUPPORTED: Cannot be verified (potential hallucination)
            - CONTRADICTED: Contradicts the context

            Calculate faithfulness score as: (supported claims) / (total claims)

            Respond with ONLY a JSON object:
            {
              "score": 0.0-1.0,
              "totalClaims": N,
              "supportedClaims": N,
              "unsupportedClaims": N,
              "contradictedClaims": N,
              "explanation": "brief explanation"
            }
            """;

    private final ChatClient chatClient;
    private final EvaluationProperties properties;

    @Override
    public EvaluationResult evaluate(String query, String response,
                                     List<String> retrievedDocuments, EvaluationContext context) {
        if (retrievedDocuments == null || retrievedDocuments.isEmpty()) {
            log.debug("No context provided, skipping faithfulness evaluation");
            return EvaluationResult.pass(getName(), getType(), 1.0);
        }

        try {
            String contextText = String.join("\n---\n", retrievedDocuments);
            String prompt = String.format(EVALUATION_PROMPT, contextText, response);

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            JsonNode json = EvaluationResponseParser.parse(result);
            if (json == null) {
                log.warn("Could not parse faithfulness evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "Failed to parse LLM evaluation response");
            }

            double score = EvaluationResponseParser.getDouble(json, "score", -1.0);
            if (score < 0.0) {
                log.warn("No valid score in faithfulness evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "LLM response did not contain a valid score");
            }

            String explanation = EvaluationResponseParser.getString(json, "explanation", null);
            double threshold = context.getThreshold() != null
                    ? context.getThreshold()
                    : properties.getFaithfulness().getThreshold();

            List<EvaluationResult.Finding> findings = extractFindings(json);

            return EvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(score >= threshold)
                    .score(score)
                    .threshold(threshold)
                    .explanation(explanation)
                    .findings(findings)
                    .build();

        } catch (Exception e) {
            log.error("Faithfulness evaluation failed: {}", e.getMessage());
            return EvaluationResult.fail(getName(), getType(), 0.0, e.getMessage());
        }
    }

    private List<EvaluationResult.Finding> extractFindings(JsonNode json) {
        List<EvaluationResult.Finding> findings = new ArrayList<>();

        int unsupported = EvaluationResponseParser.getInt(json, "unsupportedClaims", 0);
        int contradicted = EvaluationResponseParser.getInt(json, "contradictedClaims", 0);

        if (unsupported > 0) {
            findings.add(EvaluationResult.Finding.builder()
                    .type(EvaluationResult.FindingType.UNSUPPORTED_CLAIM)
                    .description(unsupported + " claims not supported by context")
                    .severity(EvaluationResult.FindingSeverity.WARNING)
                    .build());
        }

        if (contradicted > 0) {
            findings.add(EvaluationResult.Finding.builder()
                    .type(EvaluationResult.FindingType.CONTRADICTED_CLAIM)
                    .description(contradicted + " claims contradict the context")
                    .severity(EvaluationResult.FindingSeverity.ERROR)
                    .build());
        }

        return findings;
    }

    @Override
    public String getName() {
        return "faithfulness";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.FAITHFULNESS;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
