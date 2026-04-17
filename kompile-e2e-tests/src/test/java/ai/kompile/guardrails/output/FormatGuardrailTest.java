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

import ai.kompile.core.guardrails.GuardrailAction;
import ai.kompile.core.guardrails.GuardrailCategory;
import ai.kompile.core.guardrails.GuardrailContext;
import ai.kompile.core.guardrails.GuardrailResult;
import ai.kompile.guardrails.GuardrailsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FormatGuardrail.
 */
class FormatGuardrailTest {

    private GuardrailsProperties properties;
    private FormatGuardrail guardrail;

    @BeforeEach
    void setUp() {
        properties = new GuardrailsProperties();
        properties.getOutput().getFormat().setEnabled(true);
        guardrail = new FormatGuardrail(properties);
    }

    // ==================== JSON Format Tests ====================

    @Test
    @DisplayName("Should validate valid JSON format")
    void testValidJsonFormat() {
        properties.getOutput().getFormat().setExpectedFormat("JSON");
        String output = "{\"name\": \"test\", \"value\": 123}";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should reject invalid JSON format")
    void testInvalidJsonFormat() {
        properties.getOutput().getFormat().setExpectedFormat("JSON");
        String output = "This is not JSON {broken";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertFalse(result.isPassed());
        assertEquals(GuardrailAction.RETRY, result.getAction());
    }

    @Test
    @DisplayName("Should validate JSON array format")
    void testValidJsonArrayFormat() {
        properties.getOutput().getFormat().setExpectedFormat("JSON");
        String output = "[{\"id\": 1}, {\"id\": 2}]";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertTrue(result.isPassed());
    }

    // ==================== Length Constraint Tests ====================

    @Test
    @DisplayName("Should pass output within max length")
    void testWithinMaxLength() {
        properties.getOutput().getFormat().setMaxLength(100);
        String output = "Short response";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should fail output exceeding max length")
    void testExceedsMaxLength() {
        properties.getOutput().getFormat().setMaxLength(10);
        String output = "This response is way too long for the limit";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("Should fail output below min length")
    void testBelowMinLength() {
        properties.getOutput().getFormat().setMinLength(50);
        String output = "Too short";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("Should pass output above min length")
    void testAboveMinLength() {
        properties.getOutput().getFormat().setMinLength(5);
        String output = "This is long enough";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should check both min and max length")
    void testBothLengthConstraints() {
        properties.getOutput().getFormat().setMinLength(10);
        properties.getOutput().getFormat().setMaxLength(50);
        String output = "This is within the range";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertTrue(result.isPassed());
    }

    // ==================== No Format Check Tests ====================

    @Test
    @DisplayName("Should pass any output when no format specified")
    void testNoFormatSpecified() {
        properties.getOutput().getFormat().setExpectedFormat(null);
        properties.getOutput().getFormat().setMaxLength(0);
        properties.getOutput().getFormat().setMinLength(0);
        String output = "Any random content";
        GuardrailContext context = GuardrailContext.builder().build();

        GuardrailResult result = guardrail.validate(output, "query", List.of(), context);

        assertTrue(result.isPassed());
    }

    // ==================== Metadata Tests ====================

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("format", guardrail.getName());
    }

    @Test
    @DisplayName("Should have correct category")
    void testGetCategories() {
        GuardrailCategory[] categories = guardrail.getCategories();

        assertEquals(1, categories.length);
        assertEquals(GuardrailCategory.INVALID_FORMAT, categories[0]);
    }

    @Test
    @DisplayName("Should not require LLM")
    void testRequiresLlm() {
        assertFalse(guardrail.requiresLlm());
    }

    @Test
    @DisplayName("Should support retry")
    void testSupportsRetry() {
        assertTrue(guardrail.supportsRetry());
    }
}
