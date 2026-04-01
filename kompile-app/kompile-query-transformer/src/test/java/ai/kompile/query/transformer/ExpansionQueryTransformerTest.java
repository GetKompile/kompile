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
 * Unit tests for ExpansionQueryTransformer.
 */
@ExtendWith(MockitoExtension.class)
class ExpansionQueryTransformerTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private QueryTransformerProperties properties;
    private ExpansionQueryTransformer transformer;

    @BeforeEach
    void setUp() {
        properties = new QueryTransformerProperties();
        properties.setMaxQueries(3);
        properties.setIncludeOriginal(true);
        transformer = new ExpansionQueryTransformer(chatClient, properties);
    }

    @Test
    @DisplayName("Should expand query into multiple variants")
    void testTransformExpandsQuery() {
        // Arrange
        String originalQuery = "What is machine learning?";
        String llmResponse = """
                1. How does machine learning work?
                2. Can you explain ML?
                3. What are the basics of machine learning algorithms?
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(originalQuery, context);

        // Assert
        assertNotNull(result);
        assertTrue(result.size() >= 1);
        assertTrue(result.contains(originalQuery)); // Original should be included
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("Should include original query when configured")
    void testIncludesOriginalQuery() {
        // Arrange
        String originalQuery = "Test query";
        String llmResponse = "1. Variant one\n2. Variant two";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        QueryTransformContext context = QueryTransformContext.builder()
                .includeOriginal(true)
                .build();

        // Act
        List<String> result = transformer.transform(originalQuery, context);

        // Assert
        assertEquals(originalQuery, result.get(0));
    }

    @Test
    @DisplayName("Should return original query on LLM failure")
    void testFallbackOnError() {
        // Arrange
        String originalQuery = "What is AI?";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("API error"));

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(originalQuery, context);

        // Assert
        assertEquals(1, result.size());
        assertEquals(originalQuery, result.get(0));
    }

    @Test
    @DisplayName("Should return original query on empty LLM response")
    void testFallbackOnEmptyResponse() {
        // Arrange
        String originalQuery = "What is deep learning?";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("");

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(originalQuery, context);

        // Assert
        assertEquals(1, result.size());
        assertEquals(originalQuery, result.get(0));
    }

    @Test
    @DisplayName("Should respect maxQueries limit")
    void testMaxQueriesLimit() {
        // Arrange
        String originalQuery = "Test query";
        String llmResponse = """
                1. Variant one
                2. Variant two
                3. Variant three
                4. Variant four
                5. Variant five
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(llmResponse);

        // Reset includeOriginal in both properties AND context to test maxQueries limit alone
        properties.setIncludeOriginal(false);
        QueryTransformContext context = QueryTransformContext.builder()
                .maxQueries(2)
                .includeOriginal(false)
                .build();

        // Act
        List<String> result = transformer.transform(originalQuery, context);

        // Assert
        assertTrue(result.size() <= 2, "Result size should be at most maxQueries: " + result.size());
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("expansion", transformer.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(QueryTransformer.QueryTransformationType.EXPANSION, transformer.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(transformer.requiresLlm());
    }
}
