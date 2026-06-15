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

package ai.kompile.guardrails.input;

import ai.kompile.core.guardrails.*;
import ai.kompile.guardrails.GuardrailsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Detects toxic, hateful, or harmful content in user input.
 */
@Slf4j
@RequiredArgsConstructor
public class ToxicityGuardrail implements InputGuardrail {

    private static final String DETECTION_PROMPT = """
            Analyze the following text for toxic, harmful, or inappropriate content.
            Check for:
            1. Hate speech or discrimination
            2. Threats or violence
            3. Harassment or bullying
            4. Explicit or offensive language
            5. Self-harm content

            Text: "%s"

            Respond with ONLY a JSON object:
            {"isToxic": true/false, "category": "category_name or null", "confidence": 0.0-1.0, "reason": "explanation"}
            """;

    private final ChatClient chatClient;
    private final GuardrailsProperties properties;

    @Override
    public GuardrailResult validate(String input, GuardrailContext context) {
        try {
            String prompt = String.format(DETECTION_PROMPT, input);
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response != null && response.contains("\"isToxic\": true")) {
                GuardrailCategory category = extractCategory(response);
                log.warn("Toxic content detected: category={}", category);

                return GuardrailResult.builder()
                        .guardrailName(getName())
                        .passed(false)
                        .action(GuardrailAction.BLOCK)
                        .category(category)
                        .failureReason(extractReason(response))
                        .confidence(extractConfidence(response))
                        .build();
            }
        } catch (Exception e) {
            log.debug("Toxicity detection failed: {}", e.getMessage());
        }

        return GuardrailResult.pass(getName());
    }

    private GuardrailCategory extractCategory(String response) {
        if (response.contains("\"category\": \"violence\"")) return GuardrailCategory.VIOLENCE;
        if (response.contains("\"category\": \"hate\"")) return GuardrailCategory.TOXICITY;
        if (response.contains("\"category\": \"self-harm\"")) return GuardrailCategory.SELF_HARM;
        if (response.contains("\"category\": \"sexual\"")) return GuardrailCategory.SEXUAL;
        return GuardrailCategory.TOXICITY;
    }

    private String extractReason(String response) {
        try {
            int start = response.indexOf("\"reason\":") + 10;
            int end = response.indexOf("\"", start + 1);
            if (end > start) {
                return response.substring(start + 1, end);
            }
        } catch (Exception e) {
            log.debug("Failed to extract reason from toxicity response: {}", e.getMessage());
        }
        return "Toxic content detected";
    }

    private double extractConfidence(String response) {
        try {
            int start = response.indexOf("\"confidence\":") + 13;
            int end = response.indexOf(",", start);
            if (end == -1) end = response.indexOf("}", start);
            return Double.parseDouble(response.substring(start, end).trim());
        } catch (Exception e) {
            return 0.7;
        }
    }

    @Override
    public String getName() {
        return "toxicity";
    }

    @Override
    public GuardrailCategory[] getCategories() {
        return new GuardrailCategory[]{
                GuardrailCategory.TOXICITY,
                GuardrailCategory.VIOLENCE,
                GuardrailCategory.SELF_HARM,
                GuardrailCategory.SEXUAL
        };
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
