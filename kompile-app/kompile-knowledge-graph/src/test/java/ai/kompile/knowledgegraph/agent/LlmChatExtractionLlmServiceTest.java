/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LlmChatExtractionLlmService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmChatExtractionLlmServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatResponse chatResponse;

    @Mock
    private Generation generation;

    @Mock
    private AssistantMessage assistantMessage;

    private LlmChatExtractionLlmService service;

    @BeforeEach
    void setUp() {
        service = new LlmChatExtractionLlmService();
    }

    @Test
    void getIdReturnsLlmChat() {
        assertEquals("llm-chat", service.getId());
    }

    @Test
    void getDescriptionIsNonEmpty() {
        String desc = service.getDescription();
        assertNotNull(desc);
        assertFalse(desc.isBlank());
    }

    @Test
    void isAvailableReturnsFalseWhenNoChatModel() {
        assertFalse(service.isAvailable());
    }

    @Test
    void isAvailableReturnsTrueAfterChatModelSet() {
        service.setChatModel(chatModel);
        assertTrue(service.isAvailable());
    }

    @Test
    void completeThrowsWhenNoChatModel() {
        assertThrows(ExtractionLlmService.ExtractionLlmException.class,
                () -> service.complete("some prompt"));
    }

    @Test
    void completeReturnsTextFromChatModel() {
        service.setChatModel(chatModel);

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(chatResponse.getResults()).thenReturn(List.of(generation));
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(assistantMessage.getText()).thenReturn("extracted JSON response");

        String result = service.complete("extract entities from: hello world");

        assertNotNull(result);
        assertEquals("extracted JSON response", result);
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void completeReturnsNullWhenResponseIsNull() {
        service.setChatModel(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(null);

        String result = service.complete("prompt");
        assertNull(result);
    }

    @Test
    void completeReturnsNullWhenResultsEmpty() {
        service.setChatModel(chatModel);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(chatResponse.getResults()).thenReturn(List.of());

        String result = service.complete("prompt");
        assertNull(result);
    }

    @Test
    void completeWrapsExceptionInExtractionLlmException() {
        service.setChatModel(chatModel);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model error"));

        ExtractionLlmService.ExtractionLlmException ex = assertThrows(
                ExtractionLlmService.ExtractionLlmException.class,
                () -> service.complete("prompt"));
        assertTrue(ex.getMessage().contains("model error"));
    }

    @Test
    void completeDoesNotRewrapExtractionLlmException() {
        service.setChatModel(chatModel);
        ExtractionLlmService.ExtractionLlmException original =
                new ExtractionLlmService.ExtractionLlmException("already wrapped");
        when(chatModel.call(any(Prompt.class))).thenThrow(original);

        ExtractionLlmService.ExtractionLlmException ex = assertThrows(
                ExtractionLlmService.ExtractionLlmException.class,
                () -> service.complete("prompt"));
        assertSame(original, ex);
    }

    @Test
    void setChatModelNullDoesNotCrash() {
        service.setChatModel(null);
        assertFalse(service.isAvailable());
    }
}
