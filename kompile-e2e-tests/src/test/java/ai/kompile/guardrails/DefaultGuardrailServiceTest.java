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

package ai.kompile.guardrails;

import ai.kompile.core.guardrails.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DefaultGuardrailService.
 */
class DefaultGuardrailServiceTest {

    private GuardrailsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GuardrailsProperties();
        properties.setEnabled(true);
    }

    // ==================== Disabled Service Tests ====================

    @Test
    @DisplayName("Should pass all when guardrails disabled")
    void testDisabledGuardrails() {
        properties.setEnabled(false);
        DefaultGuardrailService service = new DefaultGuardrailService(
                List.of(),
                List.of(),
                properties
        );

        GuardrailContext context = GuardrailContext.builder().build();
        GuardrailResult result = service.validateInput("any input", context);

        assertTrue(result.isPassed());
    }

    // ==================== Empty Guardrails Tests ====================

    @Test
    @DisplayName("Should handle no input guardrails")
    void testNoInputGuardrails() {
        DefaultGuardrailService service = new DefaultGuardrailService(
                List.of(),
                List.of(),
                properties
        );

        GuardrailContext context = GuardrailContext.builder().build();
        GuardrailResult result = service.validateInput("test", context);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should handle no output guardrails")
    void testNoOutputGuardrails() {
        DefaultGuardrailService service = new DefaultGuardrailService(
                List.of(),
                List.of(),
                properties
        );

        GuardrailContext context = GuardrailContext.builder().build();
        GuardrailResult result = service.validateOutput("test", "query", List.of(), context);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should return available input guardrails")
    void testGetInputGuardrails() {
        // Use a simple implementation
        InputGuardrail testGuardrail = new InputGuardrail() {
            @Override
            public GuardrailResult validate(String input, GuardrailContext context) {
                return GuardrailResult.pass("test");
            }

            @Override
            public String getName() {
                return "test-input-guardrail";
            }

            @Override
            public GuardrailCategory[] getCategories() {
                return new GuardrailCategory[]{GuardrailCategory.CUSTOM};
            }
        };

        DefaultGuardrailService service = new DefaultGuardrailService(
                List.of(testGuardrail),
                List.of(),
                properties
        );

        List<InputGuardrail> guardrails = service.getInputGuardrails();
        assertEquals(1, guardrails.size());
        assertEquals("test-input-guardrail", guardrails.get(0).getName());
    }

    @Test
    @DisplayName("Should return available output guardrails")
    void testGetOutputGuardrails() {
        // Use a simple implementation
        OutputGuardrail testGuardrail = new OutputGuardrail() {
            @Override
            public GuardrailResult validate(String output, String originalQuery,
                                            List<String> retrievedContext, GuardrailContext context) {
                return GuardrailResult.pass("test");
            }

            @Override
            public String getName() {
                return "test-output-guardrail";
            }

            @Override
            public GuardrailCategory[] getCategories() {
                return new GuardrailCategory[]{GuardrailCategory.CUSTOM};
            }
        };

        DefaultGuardrailService service = new DefaultGuardrailService(
                List.of(),
                List.of(testGuardrail),
                properties
        );

        List<OutputGuardrail> guardrails = service.getOutputGuardrails();
        assertEquals(1, guardrails.size());
        assertEquals("test-output-guardrail", guardrails.get(0).getName());
    }

    @Test
    @DisplayName("Should validate input through passing guardrail")
    void testValidateInputPassing() {
        InputGuardrail passingGuardrail = new InputGuardrail() {
            @Override
            public GuardrailResult validate(String input, GuardrailContext context) {
                return GuardrailResult.pass("passing-guardrail");
            }

            @Override
            public String getName() {
                return "passing-guardrail";
            }

            @Override
            public GuardrailCategory[] getCategories() {
                return new GuardrailCategory[]{GuardrailCategory.CUSTOM};
            }
        };

        DefaultGuardrailService service = new DefaultGuardrailService(
                List.of(passingGuardrail),
                List.of(),
                properties
        );

        GuardrailContext context = GuardrailContext.builder().build();
        GuardrailResult result = service.validateInput("safe input", context);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should validate output through passing guardrail")
    void testValidateOutputPassing() {
        OutputGuardrail passingGuardrail = new OutputGuardrail() {
            @Override
            public GuardrailResult validate(String output, String originalQuery,
                                            List<String> retrievedContext, GuardrailContext context) {
                return GuardrailResult.pass("passing-output-guardrail");
            }

            @Override
            public String getName() {
                return "passing-output-guardrail";
            }

            @Override
            public GuardrailCategory[] getCategories() {
                return new GuardrailCategory[]{GuardrailCategory.CUSTOM};
            }
        };

        DefaultGuardrailService service = new DefaultGuardrailService(
                List.of(),
                List.of(passingGuardrail),
                properties
        );

        GuardrailContext context = GuardrailContext.builder().build();
        GuardrailResult result = service.validateOutput("safe output", "query", List.of(), context);

        assertTrue(result.isPassed());
    }
}
