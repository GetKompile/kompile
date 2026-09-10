/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.channel.api.ChannelInternalAuthentication;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.rag.ConversationalRagResult;
import ai.kompile.core.rag.ConversationalRagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelInternalChatControllerTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void acceptsSignedOpaqueConversationAndRejectsReplay() throws Exception {
        var mapper = JsonUtils.standardMapper();
        ConversationalRagService rag = mock(ConversationalRagService.class);
        String conversationId = ChannelInternalAuthentication.opaqueConversationId(KEY, "telegram:42:7");
        byte[] body = mapper.writeValueAsBytes(Map.of(
                "conversationId", conversationId,
                "message", "hello"));
        when(rag.chat(eq(conversationId), eq("hello"), any()))
                .thenReturn(ConversationalRagResult.builder().answer("private answer").build());
        ChannelInternalChatController controller = new ChannelInternalChatController(
                rag, new ChannelInternalChatSecurity(KEY, tempDir.toString()), mapper);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        MockHttpServletRequest request = signed(body, timestamp, nonce);

        var response = controller.chat(body, request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("private answer", response.getBody().answer());
        verify(rag).chat(eq(conversationId), eq("hello"), any());
        assertThrows(ResponseStatusException.class, () -> controller.chat(body, request));
    }

    @Test
    void rejectsUnsignedChannelChat() throws Exception {
        var mapper = JsonUtils.standardMapper();
        ChannelInternalChatController controller = new ChannelInternalChatController(
                mock(ConversationalRagService.class),
                new ChannelInternalChatSecurity(KEY, tempDir.toString()),
                mapper);
        byte[] body = mapper.writeValueAsBytes(Map.of(
                "conversationId", ChannelInternalAuthentication.opaqueConversationId(KEY, "session"),
                "message", "hello"));

        assertThrows(ResponseStatusException.class,
                () -> controller.chat(body, new MockHttpServletRequest()));
    }

    private static MockHttpServletRequest signed(byte[] body, long timestamp, String nonce) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ChannelInternalAuthentication.TIMESTAMP_HEADER, Long.toString(timestamp));
        request.addHeader(ChannelInternalAuthentication.NONCE_HEADER, nonce);
        request.addHeader(ChannelInternalAuthentication.SIGNATURE_HEADER,
                ChannelInternalAuthentication.sign(KEY, timestamp, nonce, body));
        return request;
    }
}
