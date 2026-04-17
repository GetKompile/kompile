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
 * Unit tests for MultiQueryTransformer.
 */
@ExtendWith(MockitoExtension.class)
class MultiQueryTransformerTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private QueryTransformerProperties properties;
    private MultiQueryTransformer transformer;

    @BeforeEach
    void setUp() {
        properties = new QueryTransformerProperties();
        properties.setMaxQueries(5);
        transformer = new MultiQueryTransformer(chatClient, properties);
    }

    @Test
    @DisplayName("Should decompose complex query into sub-questions")
    void testDecomposeComplexQuery() {
        // Arrange
        String complexQuery = "Compare the economic policies and environmental impacts of the US and EU";
        String llmResponse = """
                1. What are the main economic policies of the US?
                2. What are the main economic policies of the EU?
                3. What are the environmental policies of the US?
                4. What are the environmental policies of the EU?
                5. How do these policies compare in terms of impact?
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(complexQuery, context);

        // Assert
        assertNotNull(result);
        assertTrue(result.size() > 1);
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("Should handle simple queries")
    void testSimpleQueryNotDecomposed() {
        // Arrange
        String simpleQuery = "What is Python?";
        String llmResponse = "1. What is Python?";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(simpleQuery, context);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("Should fallback to original query on error")
    void testFallbackOnError() {
        // Arrange
        String query = "Complex multi-part question";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("Service error"));

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
        assertEquals("multi-query", transformer.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(QueryTransformer.QueryTransformationType.DECOMPOSITION, transformer.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(transformer.requiresLlm());
    }
}
