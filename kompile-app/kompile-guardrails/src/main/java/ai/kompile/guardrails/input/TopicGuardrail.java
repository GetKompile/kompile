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

import java.util.Set;

/**
 * Ensures user queries are on-topic based on configured allowed/blocked topics.
 */
@Slf4j
@RequiredArgsConstructor
public class TopicGuardrail implements InputGuardrail {

    private static final String TOPIC_CHECK_PROMPT = """
            Analyze the following user query and determine if it relates to the allowed topics.

            Allowed topics: %s
            Blocked topics: %s

            User query: "%s"

            Respond with ONLY a JSON object:
            {"isOnTopic": true/false, "detectedTopic": "topic_name", "confidence": 0.0-1.0, "reason": "explanation"}
            """;

    private final ChatClient chatClient;
    private final GuardrailsProperties properties;

    @Override
    public GuardrailResult validate(String input, GuardrailContext context) {
        GuardrailsProperties.TopicConfig config = properties.getInput().getTopic();
        Set<String> allowedTopics = config.getAllowedTopics();
        Set<String> blockedTopics = config.getBlockedTopics();

        // If no topics configured, allow everything
        if (allowedTopics.isEmpty() && blockedTopics.isEmpty()) {
            return GuardrailResult.pass(getName());
        }

        try {
            String prompt = String.format(TOPIC_CHECK_PROMPT,
                    allowedTopics.isEmpty() ? "any" : String.join(", ", allowedTopics),
                    blockedTopics.isEmpty() ? "none" : String.join(", ", blockedTopics),
                    input);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response != null && response.contains("\"isOnTopic\": false")) {
                log.warn("Off-topic query detected");

                return GuardrailResult.builder()
                        .guardrailName(getName())
                        .passed(false)
                        .action(GuardrailAction.BLOCK)
                        .category(GuardrailCategory.OFF_TOPIC)
                        .failureReason(extractReason(response))
                        .confidence(extractConfidence(response))
                        .build();
            }
        } catch (Exception e) {
            log.debug("Topic check failed: {}", e.getMessage());
        }

        return GuardrailResult.pass(getName());
    }

    private String extractReason(String response) {
        try {
            int start = response.indexOf("\"reason\":") + 10;
            int end = response.indexOf("\"", start + 1);
            if (end > start) {
                return response.substring(start + 1, end);
            }
        } catch (Exception ignored) {}
        return "Query is off-topic";
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
        return "topic";
    }

    @Override
    public GuardrailCategory[] getCategories() {
        return new GuardrailCategory[]{GuardrailCategory.OFF_TOPIC};
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }
}
