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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CompressingQueryTransformer.
 */
@ExtendWith(MockitoExtension.class)
class CompressingQueryTransformerTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private QueryTransformerProperties properties;
    private CompressingQueryTransformer transformer;

    @BeforeEach
    void setUp() {
        properties = new QueryTransformerProperties();
        transformer = new CompressingQueryTransformer(chatClient, properties);
    }

    @Test
    @DisplayName("Should compress follow-up query with conversation context")
    void testCompressFollowUpQuery() {
        // Arrange
        String followUpQuery = "What about its performance?";
        List<Message> conversationHistory = List.of(
                new UserMessage("Tell me about Python"),
                new AssistantMessage("Python is a programming language...")
        );
        String compressedQuery = "What is the performance of Python programming language?";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(compressedQuery);

        QueryTransformContext context = QueryTransformContext.builder()
                .conversationHistory(conversationHistory)
                .build();

        // Act
        List<String> result = transformer.transform(followUpQuery, context);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("Should return original query when no conversation history")
    void testNoHistoryReturnsOriginal() {
        // Arrange
        String query = "What is machine learning?";

        // No history in context - should return original without calling LLM
        QueryTransformContext context = QueryTransformContext.builder().build();

        // Act
        List<String> result = transformer.transform(query, context);

        // Assert
        assertEquals(1, result.size());
        assertEquals(query, result.get(0));
    }

    @Test
    @DisplayName("Should fallback on LLM error")
    void testFallbackOnError() {
        // Arrange
        String query = "And what about that?";
        List<Message> history = List.of(
                new UserMessage("Previous question"),
                new AssistantMessage("Previous answer")
        );

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("Rate limit exceeded"));

        QueryTransformContext context = QueryTransformContext.builder()
                .conversationHistory(history)
                .build();

        // Act
        List<String> result = transformer.transform(query, context);

        // Assert
        assertEquals(1, result.size());
        assertEquals(query, result.get(0));
    }

    @Test
    @DisplayName("Should have correct name")
    void testGetName() {
        assertEquals("compression", transformer.getName());
    }

    @Test
    @DisplayName("Should have correct type")
    void testGetType() {
        assertEquals(QueryTransformer.QueryTransformationType.COMPRESSION, transformer.getType());
    }

    @Test
    @DisplayName("Should require LLM")
    void testRequiresLlm() {
        assertTrue(transformer.requiresLlm());
    }
}
