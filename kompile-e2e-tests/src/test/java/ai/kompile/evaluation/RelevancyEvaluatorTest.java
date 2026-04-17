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
 * Unit tests for RelevancyEvaluator.
 */
@ExtendWith(MockitoExtension.class)
class RelevancyEvaluatorTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private EvaluationProperties properties;
    private RelevancyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new EvaluationProperties();
        properties.getRelevancy().setEnabled(true);
        properties.getRelevancy().setThreshold(0.7);
        evaluator = new RelevancyEvaluator(chatClient, properties);
    }

    @Test
    @DisplayName("Should evaluate relevant response as passed")
    void testRelevantResponsePasses() {
        String query = "What is machine learning?";
        String response = "Machine learning is a subset of AI that enables systems to learn from data.";
        String llmResponse = "{\"score\": 0.9, \"explanation\": \"Highly relevant response\"}";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertTrue(result.isPassed());
        assertEquals(0.9, result.getScore(), 0.01);
        assertEquals("relevancy", result.getEvaluatorName());
    }

    @Test
    @DisplayName("Should evaluate irrelevant response as failed")
    void testIrrelevantResponseFails() {
        String query = "What is machine learning?";
        String response = "The weather today is sunny and warm.";
        String llmResponse = "{\"score\": 0.2, \"explanation\": \"Response is not related to the query\"}";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertFalse(result.isPassed());
        assertEquals(0.2, result.getScore(), 0.01);
    }

    @Test
    @DisplayName("Should use custom threshold from context")
    void testCustomThreshold() {
        String query = "What is Python?";
        String response = "Python is a programming language.";
        String llmResponse = "{\"score\": 0.6, \"explanation\": \"Moderately relevant\"}";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext context = EvaluationContext.builder()
                .threshold(0.5) // Lower threshold
                .build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertTrue(result.isPassed()); // 0.6 > 0.5
    }

    @Test
    @DisplayName("Should handle LLM errors gracefully")
    void testHandlesLlmError() {
        String query = "Test query";
        String response = "Test response";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM unavailable"));

        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore());
    }

    @Test
    @DisplayName("Should fail on malformed LLM response instead of defaulting")
    void testMalformedResponseFails() {
        String query = "Test query";
        String response = "Test response";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("I cannot evaluate this.");

        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore());
    }

    @Test
    @DisplayName("Should parse markdown-fenced JSON response")
    void testMarkdownFencedResponse() {
        String query = "What is AI?";
        String response = "AI is artificial intelligence.";
        String llmResponse = "```json\n{\"score\": 0.8, \"explanation\": \"Relevant\"}\n```";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertTrue(result.isPassed());
        assertEquals(0.8, result.getScore(), 0.01);
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("relevancy", evaluator.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(EvaluationType.RELEVANCY, evaluator.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(evaluator.requiresLlm());
    }
}
