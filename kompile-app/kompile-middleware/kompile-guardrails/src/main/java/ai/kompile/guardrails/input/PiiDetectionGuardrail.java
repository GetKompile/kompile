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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects personally identifiable information (PII) in user input.
 * <p>
 * Uses pattern matching to detect common PII types without requiring an LLM.
 */
@Slf4j
@RequiredArgsConstructor
public class PiiDetectionGuardrail implements InputGuardrail {

    // PII patterns
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?1[-.]?)?\\(?[0-9]{3}\\)?[-.]?[0-9]{3}[-.]?[0-9]{4}");

    private static final Pattern SSN_PATTERN = Pattern.compile(
            "\\b\\d{3}[-]?\\d{2}[-]?\\d{4}\\b");

    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b");

    private final GuardrailsProperties properties;

    @Override
    public GuardrailResult validate(String input, GuardrailContext context) {
        GuardrailsProperties.PiiConfig config = properties.getInput().getPii();
        List<GuardrailResult.Violation> violations = new ArrayList<>();

        if (config.isDetectEmail()) {
            detectPii(input, EMAIL_PATTERN, "email", violations);
        }

        if (config.isDetectPhone()) {
            detectPii(input, PHONE_PATTERN, "phone", violations);
        }

        if (config.isDetectSsn()) {
            detectPii(input, SSN_PATTERN, "ssn", violations);
        }

        if (config.isDetectCreditCard()) {
            detectPii(input, CREDIT_CARD_PATTERN, "credit_card", violations);
        }

        if (!violations.isEmpty()) {
            log.warn("PII detected in input: {} items", violations.size());

            GuardrailAction action = config.isBlockOnDetection()
                    ? GuardrailAction.BLOCK
                    : GuardrailAction.WARN;

            return GuardrailResult.builder()
                    .guardrailName(getName())
                    .passed(false)
                    .action(action)
                    .category(GuardrailCategory.PII)
                    .failureReason("Personally identifiable information detected")
                    .violations(violations)
                    .confidence(1.0)
                    .build();
        }

        return GuardrailResult.pass(getName());
    }

    private void detectPii(String input, Pattern pattern, String type,
                           List<GuardrailResult.Violation> violations) {
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            String matched = matcher.group();
            // Mask the PII for logging
            String masked = maskPii(matched, type);

            violations.add(GuardrailResult.Violation.builder()
                    .type(type)
                    .description("Detected " + type.replace("_", " "))
                    .content(masked)
                    .position(matcher.start())
                    .severity(GuardrailResult.ViolationSeverity.HIGH)
                    .build());
        }
    }

    private String maskPii(String value, String type) {
        if (value.length() <= 4) {
            return "****";
        }
        return switch (type) {
            case "email" -> {
                int atIndex = value.indexOf('@');
                if (atIndex > 2) {
                    yield value.substring(0, 2) + "***" + value.substring(atIndex);
                }
                yield "***@***";
            }
            case "phone" -> "***-***-" + value.substring(value.length() - 4);
            case "ssn" -> "***-**-" + value.substring(value.length() - 4);
            case "credit_card" -> "****-****-****-" + value.substring(value.length() - 4);
            default -> value.substring(0, 2) + "****" + value.substring(value.length() - 2);
        };
    }

    @Override
    public String getName() {
        return "pii-detection";
    }

    @Override
    public GuardrailCategory[] getCategories() {
        return new GuardrailCategory[]{GuardrailCategory.PII, GuardrailCategory.SENSITIVE_DATA};
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public boolean requiresLlm() {
        return false; // Pattern-based only
    }
}
