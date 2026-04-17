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
 * Unit tests for AnswerCorrectnessEvaluator.
 */
@ExtendWith(MockitoExtension.class)
class AnswerCorrectnessEvaluatorTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private EvaluationProperties properties;
    private AnswerCorrectnessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new EvaluationProperties();
        properties.getAnswerCorrectness().setEnabled(true);
        properties.getAnswerCorrectness().setThreshold(0.7);
        properties.getAnswerCorrectness().setSemanticWeight(0.5);
        properties.getAnswerCorrectness().setFactualWeight(0.5);
        evaluator = new AnswerCorrectnessEvaluator(chatClient, properties);
    }

    @Test
    @DisplayName("Should evaluate correct answer as passed")
    void testCorrectAnswerPasses() {
        String query = "What is the capital of France?";
        String response = "The capital of France is Paris.";
        String groundTruth = "Paris is the capital city of France.";
        String llmResponse = """
                {"semanticSimilarity": 0.95, "factualAccuracy": 0.98,
                "completeness": 0.9, "overallScore": 0.95, "explanation": "Highly accurate answer"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext context = EvaluationContext.builder()
                .groundTruth(groundTruth)
                .build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertTrue(result.isPassed());
        assertTrue(result.getScore() >= 0.7);
    }

    @Test
    @DisplayName("Should evaluate incorrect answer as failed")
    void testIncorrectAnswerFails() {
        String query = "What is the capital of France?";
        String response = "The capital of France is London.";
        String groundTruth = "Paris is the capital of France.";
        String llmResponse = """
                {"semanticSimilarity": 0.3, "factualAccuracy": 0.0,
                "completeness": 0.5, "overallScore": 0.2, "explanation": "Factually incorrect"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext context = EvaluationContext.builder()
                .groundTruth(groundTruth)
                .build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertFalse(result.isPassed());
        assertTrue(result.getScore() < 0.7);
    }

    @Test
    @DisplayName("Should skip evaluation when no ground truth provided")
    void testNoGroundTruthSkips() {
        String query = "Any question";
        String response = "Any response";

        EvaluationContext context = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertTrue(result.isPassed());
        assertEquals(1.0, result.getScore());
        assertTrue(result.getExplanation().contains("Skipped"));
    }

    @Test
    @DisplayName("Should skip evaluation when ground truth is empty")
    void testEmptyGroundTruthSkips() {
        String query = "Any question";
        String response = "Any response";

        EvaluationContext context = EvaluationContext.builder()
                .groundTruth("   ")
                .build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("Should calculate weighted score correctly")
    void testWeightedScoreCalculation() {
        String query = "Test query";
        String response = "Test response";
        String groundTruth = "Expected answer";
        String llmResponse = """
                {"semanticSimilarity": 0.8, "factualAccuracy": 0.6,
                "completeness": 0.7, "overallScore": 0.7, "explanation": "Mixed results"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext context = EvaluationContext.builder()
                .groundTruth(groundTruth)
                .build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        // Weighted score = (0.8 * 0.5) + (0.6 * 0.5) = 0.7
        assertEquals(0.7, result.getScore(), 0.01);
    }

    @Test
    @DisplayName("Should include component scores in metrics")
    void testMetricsIncluded() {
        String query = "Test query";
        String response = "Test response";
        String groundTruth = "Expected answer";
        String llmResponse = """
                {"semanticSimilarity": 0.85, "factualAccuracy": 0.9,
                "completeness": 0.8, "overallScore": 0.85, "explanation": "Good answer"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext context = EvaluationContext.builder()
                .groundTruth(groundTruth)
                .build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertNotNull(result.getMetrics());
        assertTrue(result.getMetrics().containsKey("semanticSimilarity"));
        assertTrue(result.getMetrics().containsKey("factualAccuracy"));
    }

    @Test
    @DisplayName("Should handle LLM errors gracefully")
    void testHandlesLlmError() {
        String query = "Test query";
        String response = "Test response";
        String groundTruth = "Expected answer";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("Service error"));

        EvaluationContext context = EvaluationContext.builder()
                .groundTruth(groundTruth)
                .build();
        EvaluationResult result = evaluator.evaluate(query, response, List.of(), context);

        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("answer-correctness", evaluator.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(EvaluationType.ANSWER_CORRECTNESS, evaluator.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(evaluator.requiresLlm());
    }

    @Test
    @DisplayName("Should require ground truth")
    void testRequiresGroundTruth() {
        assertTrue(evaluator.requiresGroundTruth());
    }
}
