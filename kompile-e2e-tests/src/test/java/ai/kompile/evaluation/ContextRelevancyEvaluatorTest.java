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
 * Unit tests for ContextRelevancyEvaluator.
 */
@ExtendWith(MockitoExtension.class)
class ContextRelevancyEvaluatorTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private EvaluationProperties properties;
    private ContextRelevancyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new EvaluationProperties();
        properties.getContextRelevancy().setEnabled(true);
        properties.getContextRelevancy().setThreshold(0.6);
        evaluator = new ContextRelevancyEvaluator(chatClient, properties);
    }

    @Test
    @DisplayName("Should evaluate relevant context as passed")
    void testRelevantContextPasses() {
        String query = "What is Python used for?";
        List<String> context = List.of(
                "Python is widely used for web development, data science, and automation.",
                "Python's syntax makes it suitable for beginners and experts alike."
        );
        String llmResponse = """
                {"score": 0.9, "totalDocuments": 2, "relevantDocuments": 2,
                "explanation": "Both documents are relevant to the query"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, "response", context, evalContext);

        assertTrue(result.isPassed());
        assertTrue(result.getScore() >= 0.6);
    }

    @Test
    @DisplayName("Should detect irrelevant context")
    void testIrrelevantContextFails() {
        String query = "What is Python used for?";
        List<String> context = List.of(
                "The weather forecast predicts rain tomorrow.",
                "Stock markets closed higher today."
        );
        String llmResponse = """
                {"score": 0.0, "totalDocuments": 2, "relevantDocuments": 0,
                "explanation": "Retrieved documents are not related to the query"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, "response", context, evalContext);

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore(), 0.01);
    }

    @Test
    @DisplayName("Should detect mixed relevancy")
    void testMixedRelevancy() {
        String query = "How does machine learning work?";
        List<String> context = List.of(
                "Machine learning uses algorithms to learn patterns from data.",
                "The restaurant serves excellent Italian food.",
                "Neural networks are a key component of deep learning."
        );
        String llmResponse = """
                {"score": 0.67, "totalDocuments": 3, "relevantDocuments": 2,
                "explanation": "2 of 3 documents are relevant"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, "response", context, evalContext);

        assertTrue(result.isPassed()); // 0.67 > 0.6 threshold
    }

    @Test
    @DisplayName("Should fail when no context provided")
    void testNoContextFails() {
        String query = "Any question";

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, "response", List.of(), evalContext);

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore());
    }

    @Test
    @DisplayName("Should include document count in metrics")
    void testMetricsIncluded() {
        String query = "Test query";
        List<String> context = List.of("Doc 1", "Doc 2", "Doc 3");
        String llmResponse = """
                {"score": 0.8, "totalDocuments": 3, "relevantDocuments": 2,
                "explanation": "Most documents relevant"}
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, "response", context, evalContext);

        assertNotNull(result.getMetrics());
        assertEquals(3.0, result.getMetrics().get("totalDocuments"));
        assertEquals(2.0, result.getMetrics().get("relevantDocuments"));
    }

    @Test
    @DisplayName("Should handle LLM errors gracefully")
    void testHandlesLlmError() {
        String query = "Test query";
        List<String> context = List.of("Some context");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("API unavailable"));

        EvaluationContext evalContext = EvaluationContext.builder().build();
        EvaluationResult result = evaluator.evaluate(query, "response", context, evalContext);

        assertFalse(result.isPassed());
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("context-relevancy", evaluator.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(EvaluationType.CONTEXT_RELEVANCY, evaluator.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(evaluator.requiresLlm());
    }
}
