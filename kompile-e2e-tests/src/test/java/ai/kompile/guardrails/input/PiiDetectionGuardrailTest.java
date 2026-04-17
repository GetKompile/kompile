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

import ai.kompile.core.guardrails.GuardrailAction;
import ai.kompile.core.guardrails.GuardrailCategory;
import ai.kompile.core.guardrails.GuardrailContext;
import ai.kompile.core.guardrails.GuardrailResult;
import ai.kompile.guardrails.GuardrailsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PiiDetectionGuardrail.
 */
class PiiDetectionGuardrailTest {

    private GuardrailsProperties properties;
    private PiiDetectionGuardrail guardrail;

    @BeforeEach
    void setUp() {
        properties = new GuardrailsProperties();
        properties.getInput().getPii().setEnabled(true);
        properties.getInput().getPii().setDetectEmail(true);
        properties.getInput().getPii().setDetectPhone(true);
        properties.getInput().getPii().setDetectSsn(true);
        properties.getInput().getPii().setDetectCreditCard(true);
        properties.getInput().getPii().setBlockOnDetection(true);

        guardrail = new PiiDetectionGuardrail(properties);
    }

    // ==================== Email Detection Tests ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "Contact me at john@example.com",
            "Email: user.name@company.org",
            "Send to test123@subdomain.domain.co.uk"
    })
    @DisplayName("Should detect email addresses")
    void testDetectEmail(String input) {
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertFalse(result.isPassed());
        assertNotNull(result.getViolations());
        assertFalse(result.getViolations().isEmpty());
        assertEquals("email", result.getViolations().get(0).getType());
    }

    @Test
    @DisplayName("Should not flag text without email")
    void testNoFalsePositiveEmail() {
        String input = "This is a normal message without any personal information";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertTrue(result.isPassed());
    }

    // ==================== Phone Number Detection Tests ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "Call me at 555-123-4567",
            "My phone: (555)123-4567",
            "Reach me at +1-555-123-4567"
    })
    @DisplayName("Should detect phone numbers")
    void testDetectPhone(String input) {
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertFalse(result.isPassed());
        assertTrue(result.getViolations().stream()
                .anyMatch(v -> v.getType().equals("phone")));
    }

    // ==================== SSN Detection Tests ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "My SSN is 123-45-6789",
            "SSN: 123456789",
            "Social security number 123-45-6789"
    })
    @DisplayName("Should detect Social Security Numbers")
    void testDetectSsn(String input) {
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertFalse(result.isPassed());
        assertTrue(result.getViolations().stream()
                .anyMatch(v -> v.getType().equals("ssn")));
    }

    // ==================== Credit Card Detection Tests ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "My card is 4532015112830366",          // Visa
            "Card number: 5425233430109903",        // Mastercard
            "Pay with 378282246310005"              // Amex
    })
    @DisplayName("Should detect credit card numbers")
    void testDetectCreditCard(String input) {
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertFalse(result.isPassed());
        assertTrue(result.getViolations().stream()
                .anyMatch(v -> v.getType().equals("credit_card")));
    }

    // ==================== Multiple PII Types Tests ====================

    @Test
    @DisplayName("Should detect multiple PII types in one input")
    void testDetectMultiplePiiTypes() {
        String input = "Contact john@email.com or call 555-123-4567";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertFalse(result.isPassed());
        assertTrue(result.getViolations().size() >= 2);
    }

    // ==================== Configuration Tests ====================

    @Test
    @DisplayName("Should not detect disabled PII types")
    void testDisabledDetection() {
        properties.getInput().getPii().setDetectEmail(false);
        String input = "Contact me at john@example.com";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should return WARN action when not blocking")
    void testWarnAction() {
        properties.getInput().getPii().setBlockOnDetection(false);
        String input = "My email is test@test.com";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertFalse(result.isPassed());
        assertEquals(GuardrailAction.WARN, result.getAction());
    }

    @Test
    @DisplayName("Should return BLOCK action when blocking enabled")
    void testBlockAction() {
        properties.getInput().getPii().setBlockOnDetection(true);
        String input = "My email is test@test.com";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertFalse(result.isPassed());
        assertEquals(GuardrailAction.BLOCK, result.getAction());
    }

    // ==================== Metadata Tests ====================

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("pii-detection", guardrail.getName());
    }

    @Test
    @DisplayName("Should have correct categories")
    void testGetCategories() {
        GuardrailCategory[] categories = guardrail.getCategories();

        assertEquals(2, categories.length);
        assertTrue(java.util.Arrays.asList(categories).contains(GuardrailCategory.PII));
        assertTrue(java.util.Arrays.asList(categories).contains(GuardrailCategory.SENSITIVE_DATA));
    }

    @Test
    @DisplayName("Should not require LLM")
    void testRequiresLlm() {
        assertFalse(guardrail.requiresLlm());
    }

    @Test
    @DisplayName("Should have positive priority")
    void testPriority() {
        assertTrue(guardrail.getPriority() > 0);
    }

    // ==================== Masking Tests ====================

    @Test
    @DisplayName("Should mask detected PII in violations")
    void testMaskingPii() {
        String input = "My email is johndoe@example.com";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(input, context);

        assertFalse(result.isPassed());
        String maskedContent = result.getViolations().get(0).getContent();
        // Should be partially masked
        assertTrue(maskedContent.contains("***"));
        assertFalse(maskedContent.equals("johndoe@example.com"));
    }
}
