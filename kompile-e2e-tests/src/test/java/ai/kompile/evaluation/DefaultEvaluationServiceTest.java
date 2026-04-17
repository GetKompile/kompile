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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DefaultEvaluationService.
 */
class DefaultEvaluationServiceTest {

    private EvaluationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new EvaluationProperties();
        properties.setEnabled(true);
    }

    @Test
    @DisplayName("Should skip evaluation when disabled")
    void testSkipsWhenDisabled() {
        properties.setEnabled(false);
        DefaultEvaluationService service = new DefaultEvaluationService(List.of(), properties);

        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationReport report = service.evaluate("query", "response", List.of("context"), context);

        assertTrue(report.getResults().isEmpty());
        assertTrue(report.isOverallPassed());
    }

    @Test
    @DisplayName("Should handle empty evaluator list")
    void testEmptyEvaluatorList() {
        DefaultEvaluationService service = new DefaultEvaluationService(List.of(), properties);

        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationReport report = service.evaluate("query", "response", List.of("context"), context);

        assertNotNull(report);
        assertTrue(report.getResults().isEmpty());
        assertTrue(report.isOverallPassed());
    }

    @Test
    @DisplayName("Should evaluate with passing evaluator")
    void testEvaluateWithPassingEvaluator() {
        RagEvaluator passingEvaluator = new RagEvaluator() {
            @Override
            public EvaluationResult evaluate(String query, String response, List<String> retrievedDocs, EvaluationContext context) {
                return EvaluationResult.pass("test-evaluator", EvaluationType.RELEVANCY, 0.9);
            }

            @Override
            public String getName() {
                return "test-evaluator";
            }

            @Override
            public EvaluationType getType() {
                return EvaluationType.RELEVANCY;
            }
        };

        DefaultEvaluationService service = new DefaultEvaluationService(List.of(passingEvaluator), properties);
        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationReport report = service.evaluate("query", "response", List.of("context"), context);

        assertNotNull(report);
        assertEquals(1, report.getResults().size());
        assertTrue(report.isOverallPassed());
    }

    @Test
    @DisplayName("Should evaluate with failing evaluator")
    void testEvaluateWithFailingEvaluator() {
        RagEvaluator failingEvaluator = new RagEvaluator() {
            @Override
            public EvaluationResult evaluate(String query, String response, List<String> retrievedDocs, EvaluationContext context) {
                return EvaluationResult.fail("failing-evaluator", EvaluationType.FAITHFULNESS, 0.3, "Low score");
            }

            @Override
            public String getName() {
                return "failing-evaluator";
            }

            @Override
            public EvaluationType getType() {
                return EvaluationType.FAITHFULNESS;
            }
        };

        DefaultEvaluationService service = new DefaultEvaluationService(List.of(failingEvaluator), properties);
        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationReport report = service.evaluate("query", "response", List.of("context"), context);

        assertNotNull(report);
        assertEquals(1, report.getResults().size());
        assertFalse(report.isOverallPassed());
    }

    @Test
    @DisplayName("Should get evaluators")
    void testGetEvaluators() {
        RagEvaluator evaluator = new RagEvaluator() {
            @Override
            public EvaluationResult evaluate(String query, String response, List<String> retrievedDocs, EvaluationContext context) {
                return EvaluationResult.pass("test", EvaluationType.RELEVANCY, 0.9);
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public EvaluationType getType() {
                return EvaluationType.RELEVANCY;
            }
        };

        DefaultEvaluationService service = new DefaultEvaluationService(List.of(evaluator), properties);
        assertEquals(1, service.getEvaluators().size());
    }

    @Test
    @DisplayName("Should check if service is enabled")
    void testIsEnabled() {
        DefaultEvaluationService service = new DefaultEvaluationService(List.of(), properties);
        assertTrue(service.isEnabled());

        properties.setEnabled(false);
        assertFalse(service.isEnabled());
    }

    @Test
    @DisplayName("Should include report metadata")
    void testReportMetadata() {
        RagEvaluator evaluator = new RagEvaluator() {
            @Override
            public EvaluationResult evaluate(String query, String response, List<String> retrievedDocs, EvaluationContext context) {
                return EvaluationResult.pass("test", EvaluationType.RELEVANCY, 0.9);
            }

            @Override
            public String getName() {
                return "test";
            }

            @Override
            public EvaluationType getType() {
                return EvaluationType.RELEVANCY;
            }
        };

        DefaultEvaluationService service = new DefaultEvaluationService(List.of(evaluator), properties);
        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationReport report = service.evaluate("test-query", "test-response", List.of("context"), context);

        assertEquals("test-query", report.getQuery());
        assertNotNull(report.getTimestamp());
    }
}
