/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.staging.web;

import ai.kompile.staging.execution.ChatTemplateService;
import ai.kompile.staging.web.dto.openai.OpenAiMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatControllerTest {

    private final OpenAiCompatController controller = new OpenAiCompatController(
            null, new ChatTemplateService(), null);

    @Test
    void formatsManagedServingPromptWithAssistantGenerationMarker() {
        String prompt = controller.formatMessagesForServing(List.of(
                OpenAiMessage.builder().role("system").content("Be concise").build(),
                OpenAiMessage.builder().role("user").content("Reply with one word: connected").build()));

        assertEquals("<|im_start|>system\nBe concise<|im_end|>\n"
                + "<|im_start|>user\nReply with one word: connected<|im_end|>\n"
                + "<|im_start|>assistant\n", prompt);
    }

    @Test
    void rejectsManagedServingPromptWithoutTextMessages() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.formatMessagesForServing(List.of(
                        OpenAiMessage.builder().role("user").content(" ").build())));
    }
}
