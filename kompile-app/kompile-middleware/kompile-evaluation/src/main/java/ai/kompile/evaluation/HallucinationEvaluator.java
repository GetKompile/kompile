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
 * Detects hallucinations in LLM responses.
 * <p>
 * Identifies claims in the response that are not supported by the provided context.
 * Returns a score where higher = fewer hallucinations (inverse of hallucination rate).
 */
@Slf4j
@RequiredArgsConstructor
public class HallucinationEvaluator implements RagEvaluator {

    private static final String EVALUATION_PROMPT = """
            Analyze the response for hallucinations - claims not supported by the context.

            Context Documents:
            %s

            Response to analyze:
            "%s"

            For each factual claim in the response, check if it can be verified from the context.
            A hallucination is a claim that:
            1. Cannot be verified from the context documents
            2. Contradicts information in the context
            3. Adds specific details not present in the context

            Calculate: hallucination_score = 1.0 - (hallucinated_claims / total_claims)

            Respond with ONLY a JSON object:
            {
              "score": 0.0-1.0,
              "totalClaims": N,
              "hallucinatedClaims": N,
              "hallucinations": ["brief description of each hallucination"],
              "explanation": "overall assessment"
            }
            """;

    private final ChatClient chatClient;
    private final EvaluationProperties properties;

    @Override
    public EvaluationResult evaluate(String query, String response,
                                     List<String> retrievedDocuments, EvaluationContext context) {
        if (retrievedDocuments == null || retrievedDocuments.isEmpty()) {
            log.debug("No context provided, cannot evaluate hallucinations");
            return EvaluationResult.builder()
                    .evaluatorName(getName())
                    .evaluationType(getType())
                    .passed(true)
                    .score(1.0)
                    .explanation("No context to compare against")
                    .build();
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
                log.warn("Could not parse hallucination evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "Failed to parse LLM evaluation response");
            }

            double score = EvaluationResponseParser.getDouble(json, "score", -1.0);
            if (score < 0.0) {
                log.warn("No valid score in hallucination evaluation response");
                return EvaluationResult.fail(getName(), getType(), 0.0,
                        "LLM response did not contain a valid score");
            }

            String explanation = EvaluationResponseParser.getString(json, "explanation", null);
            List<EvaluationResult.Finding> findings = extractHallucinations(json);

            double threshold = context.getThreshold() != null
                    ? context.getThreshold()
                    : properties.getHallucination().getThreshold();

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
            log.error("Hallucination evaluation failed: {}", e.getMessage());
            return EvaluationResult.fail(getName(), getType(), 0.0, e.getMessage());
        }
    }

    private List<EvaluationResult.Finding> extractHallucinations(JsonNode json) {
        List<EvaluationResult.Finding> findings = new ArrayList<>();

        List<String> hallucinations = EvaluationResponseParser.getStringArray(json, "hallucinations");
        for (String hallucination : hallucinations) {
            findings.add(EvaluationResult.Finding.builder()
                    .type(EvaluationResult.FindingType.HALLUCINATION)
                    .description(hallucination)
                    .severity(EvaluationResult.FindingSeverity.ERROR)
                    .build());
        }

        return findings;
    }

    @Override
    public String getName() {
        return "hallucination";
    }

    @Override
    public EvaluationType getType() {
        return EvaluationType.HALLUCINATION_DETECTION;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
