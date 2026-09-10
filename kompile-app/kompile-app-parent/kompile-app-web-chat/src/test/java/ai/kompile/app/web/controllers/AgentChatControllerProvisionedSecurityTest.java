/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentRegistryService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.app.web.security.IntegrationControlCredentials;
import ai.kompile.channel.api.ChannelControlHeaders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AgentChatControllerProvisionedSecurityTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String TURN_ID = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path tempDir;

    @Test
    void provisionedTurnsRequireAdminBearerAndMutationProofWhileLegacyChatStaysCompatible()
            throws Exception {
        AgentChatService chatService = mock(AgentChatService.class);
        IntegrationControlCredentials credentials = new IntegrationControlCredentials(
                TOKEN, tempDir.resolve("security").toString());
        MockMvc mvc = standaloneSetup(new AgentChatController(
                chatService, mock(AgentRegistryService.class), credentials)).build();
        String provisioned = """
                {"agentName":"provider","message":"hello",
                 "provisionedAgentId":"01234567-89ab-cdef-0123-456789abcdef",
                 "externalConversationKey":"web:window-1","turnId":"%s"}
                """.formatted(TURN_ID);

        mvc.perform(post("/api/agents/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(provisioned))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agents/chat/stream")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(provisioned))
                .andExpect(status().isForbidden());
        verify(chatService, never()).executeChat(any(AgentChatRequest.class), any());

        mvc.perform(post("/api/agents/chat/stream")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON).content(provisioned))
                .andExpect(status().isOk());

        String missingTurn = """
                {"agentName":"provider","message":"hello",
                 "provisionedAgentId":"01234567-89ab-cdef-0123-456789abcdef",
                 "externalConversationKey":"web:window-1"}
                """;
        mvc.perform(post("/api/agents/chat/stream")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON).content(missingTurn))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/agents/chat/stream")
                        .header(ChannelControlHeaders.TOKEN_HEADER, TOKEN)
                        .header(ChannelControlHeaders.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingTurn.replace(
                                "\"externalConversationKey\":\"web:window-1\"",
                                "\"externalConversationKey\":\"web:window-1\",\"turnId\":\"BAD\"")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/agents/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentName\":\"provider\",\"message\":\"legacy\",\"turnId\":\"BAD\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/agents/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentName\":\"provider\",\"message\":\"legacy\"}"))
                .andExpect(status().isOk());
        verify(chatService, times(3)).executeChat(any(AgentChatRequest.class), any());
    }
}
