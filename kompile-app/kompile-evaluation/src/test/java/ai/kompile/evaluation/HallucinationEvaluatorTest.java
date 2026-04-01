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

import ai.kompile.core.evaluation.EvaluationContext;
import ai.kompile.core.evaluation.EvaluationResult;
import ai.kompile.core.evaluation.EvaluationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HallucinationEvaluator.
 */
@ExtendWith(MockitoExtension.class)
class HallucinationEvaluatorTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private EvaluationProperties properties;
    private HallucinationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new EvaluationProperties();
        properties.getHallucination().setEnabled(true);
        properties.getHallucination().setThreshold(0.8);
        evaluator = new HallucinationEvaluator(chatClient, properties);
    }

    @Test
    @DisplayName("Should detect no hallucinations in grounded response")
    void testNoHallucinations() {
        String response = "According to the document, the project was completed in 2023.";
        List<String> context = List.of("The project was successfully completed in December 2023.");
        String llmResponse = """
                {"score": 1.0, "totalClaims": 1, "hallucinatedClaims": 0,
                "hallucinations": [], "explanation": "All claims verified from context"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, context, evalContext);

        assertTrue(result.isPassed());
        assertEquals(1.0, result.getScore(), 0.01);
        assertTrue(result.getFindings().isEmpty());
    }

    @Test
    @DisplayName("Should detect hallucinations in response")
    void testDetectsHallucinations() {
        String response = "The company was founded by Steve Jobs in Silicon Valley in 1990.";
        List<String> context = List.of("The company was founded in 2010 in Austin, Texas.");
        String llmResponse = """
                {"score": 0.33, "totalClaims": 3, "hallucinatedClaims": 2,
                "hallucinations": ["Founder Steve Jobs not mentioned", "Location Silicon Valley not in context", "Year 1990 contradicts 2010"],
                "explanation": "Multiple claims not supported by context"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, context, evalContext);

        assertFalse(result.isPassed());
        assertTrue(result.getScore() < 0.8);
        assertFalse(result.getFindings().isEmpty());
    }

    @Test
    @DisplayName("Should pass when no context to compare against")
    void testNoContextProvided() {
        String response = "Any response without context";

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, List.of(), evalContext);

        assertTrue(result.isPassed());
        assertEquals(1.0, result.getScore());
    }

    @Test
    @DisplayName("Should extract individual hallucinations as findings")
    void testExtractsHallucinationsAsFindings() {
        String response = "The CEO announced record profits of $1 billion.";
        List<String> context = List.of("The company reported moderate growth this quarter.");
        String llmResponse = """
                {"score": 0.5, "totalClaims": 2, "hallucinatedClaims": 1,
                "hallucinations": ["Specific profit amount not mentioned in context"],
                "explanation": "Some claims not verified"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, context, evalContext);

        assertNotNull(result.getFindings());
        assertFalse(result.getFindings().isEmpty());
        assertEquals(EvaluationResult.FindingType.HALLUCINATION, result.getFindings().get(0).getType());
    }

    @Test
    @DisplayName("Should handle LLM errors gracefully")
    void testHandlesLlmError() {
        String response = "Some response";
        List<String> context = List.of("Some context");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("API error"));

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, context, evalContext);

        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("hallucination", evaluator.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(EvaluationType.HALLUCINATION_DETECTION, evaluator.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(evaluator.requiresLlm());
    }
}
