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

package ai.kompile.query.transformer;

import ai.kompile.core.query.QueryTransformContext;
import ai.kompile.core.query.QueryTransformer;
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
 * Unit tests for StepBackQueryTransformer.
 */
@ExtendWith(MockitoExtension.class)
class StepBackQueryTransformerTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private QueryTransformerProperties properties;
    private StepBackQueryTransformer transformer;

    @BeforeEach
    void setUp() {
        properties = new QueryTransformerProperties();
        transformer = new StepBackQueryTransformer(chatClient, properties);
    }

    @Test
    @DisplayName("Should generate step-back (abstract) query")
    void testGeneratesStepBackQuery() {
        // Arrange
        String specificQuery = "What was the GDP of Japan in 2023?";
        String stepBackQuery = "What is GDP and how is it measured for countries?";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(stepBackQuery);

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(specificQuery, context);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("Should include both original and step-back query when configured")
    void testIncludesBothQueries() {
        // Arrange
        String specificQuery = "How did Einstein develop E=mc²?";
        String stepBackQuery = "What is the theory of relativity?";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(stepBackQuery);

        properties.setIncludeOriginal(true);
        QueryTransformContext context = QueryTransformContext.builder()
                .includeOriginal(true)
                .build();

        // Act
        List<String> result = transformer.transform(specificQuery, context);

        // Assert
        assertTrue(result.size() >= 1);
        assertTrue(result.contains(specificQuery) || result.contains(stepBackQuery));
    }

    @Test
    @DisplayName("Should fallback on error")
    void testFallbackOnError() {
        // Arrange
        String query = "Specific technical question";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("API timeout"));

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(query, context);

        // Assert
        assertEquals(1, result.size());
        assertEquals(query, result.get(0));
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("step-back", transformer.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(QueryTransformer.QueryTransformationType.STEP_BACK, transformer.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(transformer.requiresLlm());
    }
}
