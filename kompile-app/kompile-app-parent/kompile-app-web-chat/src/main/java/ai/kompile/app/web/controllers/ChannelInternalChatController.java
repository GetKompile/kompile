/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.core.rag.ConversationalRagOptions;
import ai.kompile.core.rag.ConversationalRagResult;
import ai.kompile.core.rag.ConversationalRagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Private execution seam from external channels into the web-chat conversation engine. */
@RestController
public class ChannelInternalChatController {

    private final ConversationalRagService ragService;
    private final ChannelInternalChatSecurity security;
    private final ObjectMapper mapper;

    public ChannelInternalChatController(
            @Qualifier("kompileRagOrchestrator") ConversationalRagService ragService,
            ChannelInternalChatSecurity security,
            ObjectMapper mapper) {
        this.ragService = ragService;
        this.security = security;
        this.mapper = mapper;
    }

    @PostMapping("/api/chat/channel")
    public ResponseEntity<ChannelChatResponse> chat(
            @RequestBody byte[] rawBody,
            HttpServletRequest servletRequest) {
        security.verify(servletRequest, rawBody);
        try {
            ChannelChatRequest request = mapper.readValue(rawBody, ChannelChatRequest.class);
            if (request.conversationId() == null
                    || !request.conversationId().matches("channel-[A-Za-z0-9_-]{43}")) {
                return ResponseEntity.badRequest().body(
                        new ChannelChatResponse(null, "Invalid opaque conversation id"));
            }
            if (request.message() == null || request.message().isBlank()) {
                return ResponseEntity.badRequest().body(
                        new ChannelChatResponse(null, "Message cannot be empty"));
            }
            ConversationalRagResult result = ragService.chat(
                    request.conversationId(),
                    request.message(),
                    ConversationalRagOptions.defaults());
            if (result.isError()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new ChannelChatResponse(null, result.answer()));
            }
            return ResponseEntity.ok(new ChannelChatResponse(result.answer(), null));
        } catch (java.io.IOException invalidJson) {
            return ResponseEntity.badRequest().body(
                    new ChannelChatResponse(null, "Invalid channel chat request"));
        } catch (RuntimeException executionFailure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ChannelChatResponse(null, "Channel chat execution failed"));
        }
    }

    @PostMapping("/api/chat/channel/status")
    public ResponseEntity<java.util.Map<String, Boolean>> status(
            @RequestBody byte[] rawBody,
            HttpServletRequest servletRequest) {
        security.verify(servletRequest, rawBody);
        return ResponseEntity.ok(java.util.Map.of("available", ragService.isAvailable()));
    }

    public record ChannelChatRequest(String conversationId, String message) {
    }

    public record ChannelChatResponse(String answer, String error) {
    }
}
