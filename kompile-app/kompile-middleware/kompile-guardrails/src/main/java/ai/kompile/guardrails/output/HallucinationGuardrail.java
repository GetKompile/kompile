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

import java.util.ArrayList;
import java.util.List;

/**
 * Detects hallucinations in LLM output by checking if claims are grounded in context.
 * <p>
 * This guardrail compares the LLM's response against the retrieved documents
 * to identify claims that are not supported by the provided context.
 */
@Slf4j
@RequiredArgsConstructor
public class HallucinationGuardrail implements OutputGuardrail {

    private static final String HALLUCINATION_CHECK_PROMPT = """
            You are a fact-checking assistant. Analyze the following response and determine if each claim
            is supported by the provided context documents.

            Context Documents:
            %s

            Response to check:
            %s

            For each major claim in the response, determine if it is:
            - SUPPORTED: The claim is directly supported by the context
            - UNSUPPORTED: The claim cannot be verified from the context
            - CONTRADICTED: The claim contradicts information in the context

            Respond with ONLY a JSON object:
            {
              "overallScore": 0.0-1.0,
              "hasHallucination": true/false,
              "claims": [
                {"claim": "claim text", "status": "SUPPORTED/UNSUPPORTED/CONTRADICTED", "evidence": "quote from context or null"}
              ]
            }
            """;

    private final ChatClient chatClient;
    private final GuardrailsProperties properties;

    @Override
    public GuardrailResult validate(String output, String originalQuery,
                                    List<String> retrievedContext, GuardrailContext context) {
        if (retrievedContext == null || retrievedContext.isEmpty()) {
            log.debug("No context provided, skipping hallucination check");
            return GuardrailResult.pass(getName());
        }

        try {
            String contextText = String.join("\n---\n", retrievedContext);
            String prompt = String.format(HALLUCINATION_CHECK_PROMPT, contextText, output);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response != null && response.contains("\"hasHallucination\": true")) {
                double score = extractScore(response);
                double threshold = properties.getOutput().getHallucination().getThreshold();

                if (score < threshold) {
                    log.warn("Hallucination detected in output (score: {})", score);

                    List<GuardrailResult.Violation> violations = extractViolations(response);

                    return GuardrailResult.builder()
                            .guardrailName(getName())
                            .passed(false)
                            .action(supportsRetry() ? GuardrailAction.RETRY : GuardrailAction.WARN)
                            .category(GuardrailCategory.HALLUCINATION)
                            .failureReason("Response contains claims not supported by context")
                            .violations(violations)
                            .confidence(1.0 - score)
                            .build();
                }
            }
        } catch (Exception e) {
            log.debug("Hallucination check failed: {}", e.getMessage());
        }

        return GuardrailResult.pass(getName());
    }

    private double extractScore(String response) {
        try {
            int start = response.indexOf("\"overallScore\":") + 15;
            int end = response.indexOf(",", start);
            if (end == -1) end = response.indexOf("}", start);
            return Double.parseDouble(response.substring(start, end).trim());
        } catch (Exception e) {
            return 0.5;
        }
    }

    private List<GuardrailResult.Violation> extractViolations(String response) {
        List<GuardrailResult.Violation> violations = new ArrayList<>();

        // Simple parsing - in production you'd use proper JSON parsing
        if (response.contains("UNSUPPORTED") || response.contains("CONTRADICTED")) {
            violations.add(GuardrailResult.Violation.builder()
                    .type("hallucination")
                    .description("Claim not supported by provided context")
                    .severity(GuardrailResult.ViolationSeverity.HIGH)
                    .build());
        }

        return violations;
    }

    @Override
    public String getName() {
        return "hallucination";
    }

    @Override
    public GuardrailCategory[] getCategories() {
        return new GuardrailCategory[]{GuardrailCategory.HALLUCINATION};
    }

    @Override
    public boolean supportsRetry() {
        return properties.getOutput().getHallucination().isSupportsRetry();
    }

    @Override
    public String generateRepromptInstruction(GuardrailResult result, int attemptNumber) {
        return """
                IMPORTANT: Your previous response contained claims not supported by the provided context.
                Please revise your response to ONLY include information that can be directly verified
                from the context documents. If you're uncertain about something, say so explicitly
                rather than making unsupported claims.
                """;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
