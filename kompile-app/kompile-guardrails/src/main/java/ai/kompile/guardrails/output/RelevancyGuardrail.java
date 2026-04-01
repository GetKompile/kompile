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

package ai.kompile.guardrails.output;

import ai.kompile.core.guardrails.*;
import ai.kompile.guardrails.GuardrailsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * Checks if the LLM response is relevant to the original query.
 */
@Slf4j
@RequiredArgsConstructor
public class RelevancyGuardrail implements OutputGuardrail {

    private static final String RELEVANCY_CHECK_PROMPT = """
            Evaluate if the following response adequately addresses the user's question.

            User Question: "%s"

            Response: "%s"

            Consider:
            1. Does the response directly address the question?
            2. Is the response on-topic?
            3. Does it provide useful information related to the query?

            Respond with ONLY a JSON object:
            {"isRelevant": true/false, "relevancyScore": 0.0-1.0, "reason": "explanation"}
            """;

    private final ChatClient chatClient;
    private final GuardrailsProperties properties;

    @Override
    public GuardrailResult validate(String output, String originalQuery,
                                    List<String> retrievedContext, GuardrailContext context) {
        try {
            String prompt = String.format(RELEVANCY_CHECK_PROMPT, originalQuery, output);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response != null) {
                double score = extractScore(response);
                double threshold = properties.getOutput().getRelevancy().getThreshold();

                if (score < threshold) {
                    log.warn("Response deemed irrelevant (score: {})", score);

                    return GuardrailResult.builder()
                            .guardrailName(getName())
                            .passed(false)
                            .action(supportsRetry() ? GuardrailAction.RETRY : GuardrailAction.WARN)
                            .category(GuardrailCategory.OFF_TOPIC)
                            .failureReason(extractReason(response))
                            .confidence(1.0 - score)
                            .build();
                }
            }
        } catch (Exception e) {
            log.debug("Relevancy check failed: {}", e.getMessage());
        }

        return GuardrailResult.pass(getName());
    }

    private double extractScore(String response) {
        try {
            int start = response.indexOf("\"relevancyScore\":") + 17;
            int end = response.indexOf(",", start);
            if (end == -1) end = response.indexOf("}", start);
            return Double.parseDouble(response.substring(start, end).trim());
        } catch (Exception e) {
            return 0.7;
        }
    }

    private String extractReason(String response) {
        try {
            int start = response.indexOf("\"reason\":") + 10;
            int end = response.indexOf("\"", start + 1);
            if (end > start) {
                return response.substring(start + 1, end);
            }
        } catch (Exception ignored) {}
        return "Response does not adequately address the query";
    }

    @Override
    public String getName() {
        return "relevancy";
    }

    @Override
    public GuardrailCategory[] getCategories() {
        return new GuardrailCategory[]{GuardrailCategory.OFF_TOPIC};
    }

    @Override
    public boolean supportsRetry() {
        return properties.getOutput().getRelevancy().isSupportsRetry();
    }

    @Override
    public String generateRepromptInstruction(GuardrailResult result, int attemptNumber) {
        return """
                Your previous response did not adequately address the user's question.
                Please provide a response that directly answers what was asked.
                Focus specifically on the information requested in the original query.
                """;
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
