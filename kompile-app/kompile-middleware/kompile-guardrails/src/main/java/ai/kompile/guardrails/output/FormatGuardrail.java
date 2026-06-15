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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates output format (JSON, length constraints, etc.).
 */
@Slf4j
@RequiredArgsConstructor
public class FormatGuardrail implements OutputGuardrail {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final GuardrailsProperties properties;

    @Override
    public GuardrailResult validate(String output, String originalQuery,
                                    List<String> retrievedContext, GuardrailContext context) {
        GuardrailsProperties.FormatConfig config = properties.getOutput().getFormat();
        List<GuardrailResult.Violation> violations = new ArrayList<>();

        // Check length constraints
        if (config.getMaxLength() > 0 && output.length() > config.getMaxLength()) {
            violations.add(GuardrailResult.Violation.builder()
                    .type("length_exceeded")
                    .description("Response exceeds maximum length of " + config.getMaxLength())
                    .content("Actual length: " + output.length())
                    .severity(GuardrailResult.ViolationSeverity.MEDIUM)
                    .build());
        }

        if (config.getMinLength() > 0 && output.length() < config.getMinLength()) {
            violations.add(GuardrailResult.Violation.builder()
                    .type("length_insufficient")
                    .description("Response is shorter than minimum length of " + config.getMinLength())
                    .content("Actual length: " + output.length())
                    .severity(GuardrailResult.ViolationSeverity.MEDIUM)
                    .build());
        }

        // Check expected format
        String expectedFormat = config.getExpectedFormat();
        if (expectedFormat != null && !expectedFormat.isBlank()) {
            if ("json".equalsIgnoreCase(expectedFormat)) {
                if (!isValidJson(output)) {
                    violations.add(GuardrailResult.Violation.builder()
                            .type("invalid_json")
                            .description("Response is not valid JSON")
                            .severity(GuardrailResult.ViolationSeverity.HIGH)
                            .build());
                }
            }
        }

        if (!violations.isEmpty()) {
            log.warn("Format validation failed: {} violations", violations.size());

            return GuardrailResult.builder()
                    .guardrailName(getName())
                    .passed(false)
                    .action(GuardrailAction.RETRY)
                    .category(GuardrailCategory.INVALID_FORMAT)
                    .failureReason("Output format validation failed")
                    .violations(violations)
                    .confidence(1.0)
                    .build();
        }

        return GuardrailResult.pass(getName());
    }

    private boolean isValidJson(String text) {
        try {
            objectMapper.readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "format";
    }

    @Override
    public GuardrailCategory[] getCategories() {
        return new GuardrailCategory[]{GuardrailCategory.INVALID_FORMAT};
    }

    @Override
    public boolean supportsRetry() {
        return true;
    }

    @Override
    public String generateRepromptInstruction(GuardrailResult result, int attemptNumber) {
        StringBuilder sb = new StringBuilder("Please revise your response:\n");

        for (GuardrailResult.Violation violation : result.getViolations()) {
            sb.append("- ").append(violation.getDescription()).append("\n");
        }

        return sb.toString();
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }
}
