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
 * Unit tests for FaithfulnessEvaluator.
 */
@ExtendWith(MockitoExtension.class)
class FaithfulnessEvaluatorTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private EvaluationProperties properties;
    private FaithfulnessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new EvaluationProperties();
        properties.getFaithfulness().setEnabled(true);
        properties.getFaithfulness().setThreshold(0.8);
        evaluator = new FaithfulnessEvaluator(chatClient, properties);
    }

    @Test
    @DisplayName("Should evaluate faithful response as passed")
    void testFaithfulResponsePasses() {
        String response = "The document states that Python was created in 1991.";
        List<String> context = List.of("Python is a programming language created by Guido van Rossum in 1991.");
        String llmResponse = """
                {"score": 0.95, "totalClaims": 1, "supportedClaims": 1,
                "unsupportedClaims": 0, "contradictedClaims": 0, "explanation": "All claims verified"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, context, evalContext);

        assertTrue(result.isPassed());
        assertTrue(result.getScore() >= 0.8);
    }

    @Test
    @DisplayName("Should detect unfaithful claims")
    void testUnfaithfulResponseFails() {
        String response = "Python was created in 2005 by Microsoft.";
        List<String> context = List.of("Python was created by Guido van Rossum in 1991.");
        String llmResponse = """
                {"score": 0.0, "totalClaims": 2, "supportedClaims": 0,
                "unsupportedClaims": 1, "contradictedClaims": 1, "explanation": "Claims contradict context"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, context, evalContext);

        assertFalse(result.isPassed());
        assertNotNull(result.getFindings());
    }

    @Test
    @DisplayName("Should pass when no context provided")
    void testNoContextProvided() {
        String response = "Any response without context to check";

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, List.of(), evalContext);

        assertTrue(result.isPassed());
        assertEquals(1.0, result.getScore());
    }

    @Test
    @DisplayName("Should pass when context is null")
    void testNullContextProvided() {
        String response = "Any response";

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, null, evalContext);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should handle LLM errors gracefully")
    void testHandlesLlmError() {
        String response = "Some response";
        List<String> context = List.of("Some context");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("Service unavailable"));

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate("query", response, context, evalContext);

        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("faithfulness", evaluator.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(EvaluationType.FAITHFULNESS, evaluator.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(evaluator.requiresLlm());
    }
}
