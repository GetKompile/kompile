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
 * Unit tests for HyDEQueryTransformer.
 */
@ExtendWith(MockitoExtension.class)
class HyDEQueryTransformerTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private QueryTransformerProperties properties;
    private HyDEQueryTransformer transformer;

    @BeforeEach
    void setUp() {
        properties = new QueryTransformerProperties();
        transformer = new HyDEQueryTransformer(chatClient, properties);
    }

    @Test
    @DisplayName("Should generate hypothetical document for query")
    void testTransformGeneratesHypotheticalDocument() {
        // Arrange
        String originalQuery = "What causes global warming?";
        String hypotheticalDoc = """
                Global warming is primarily caused by the increase of greenhouse gases in Earth's
                atmosphere, particularly carbon dioxide from burning fossil fuels. These gases trap
                heat from the sun, leading to a rise in global temperatures...
                """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(hypotheticalDoc);

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(originalQuery, context);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("Should include original query when configured")
    void testIncludesOriginalQuery() {
        // Arrange
        String originalQuery = "How does photosynthesis work?";
        String hypotheticalDoc = "Photosynthesis is the process by which plants convert sunlight...";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(hypotheticalDoc);

        properties.setIncludeOriginal(true);
        QueryTransformContext context = QueryTransformContext.builder()
                .includeOriginal(true)
                .build();

        // Act
        List<String> result = transformer.transform(originalQuery, context);

        // Assert
        assertTrue(result.contains(originalQuery));
    }

    @Test
    @DisplayName("Should fallback to original query on error")
    void testFallbackOnError() {
        // Arrange
        String originalQuery = "What is quantum computing?";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM unavailable"));

        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(originalQuery, context);

        // Assert
        assertEquals(1, result.size());
        assertEquals(originalQuery, result.get(0));
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("hyde", transformer.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(QueryTransformer.QueryTransformationType.HYPOTHETICAL_DOCUMENT, transformer.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(transformer.requiresLlm());
    }
}
