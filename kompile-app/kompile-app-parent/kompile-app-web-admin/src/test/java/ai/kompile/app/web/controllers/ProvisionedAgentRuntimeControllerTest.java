/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.ProvisionedAgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProvisionedAgentRuntimeControllerTest {

    private static final String AGENT_ID = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String REVISION = "0".repeat(64);

    @Test
    void acceptsStrictSelectorLimitedDtosAndRejectsOwnerOrPathFields() throws Exception {
        ProvisionedAgentRuntime runtime = mock(ProvisionedAgentRuntime.class);
        when(runtime.prepare(any())).thenReturn(context());
        MockMvc mvc = mvc(runtime);

        mvc.perform(post("/api/kclaw/runtime/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provisionedAgentId":"%s","externalConversationKey":"web:1","query":"hello"}
                                """.formatted(AGENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisionedAgentId").value(AGENT_ID));

        mvc.perform(post("/api/kclaw/runtime/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provisionedAgentId":"%s","externalConversationKey":"web:1",\
                                "query":"hello","ownerId":"foreign"}
                                """.formatted(AGENT_ID)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/kclaw/runtime/tool")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provisionedAgentId":"%s","arguments":{"action":"read"},\
                                "path":"/tmp/foreign"}
                                """.formatted(AGENT_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsOwnerBoundNotFoundAndAppendFailuresWithoutExposingStorage() throws Exception {
        ProvisionedAgentRuntime runtime = mock(ProvisionedAgentRuntime.class);
        doThrow(new ProvisionedAgentRuntime.RuntimeException(404, "Provisioned agent was not found"))
                .when(runtime).append(any());
        MockMvc mvc = mvc(runtime);

        mvc.perform(post("/api/kclaw/runtime/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provisionedAgentId":"%s","externalConversationKey":"web:1",\
                                "event":{"kind":"MESSAGE","role":"USER","content":"hello",\
                                "metadata":{},"idempotencyKey":"turn:test:user"}}
                                """.formatted(AGENT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Provisioned agent was not found"));
    }

    private static MockMvc mvc(ProvisionedAgentRuntime runtime) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return MockMvcBuilders.standaloneSetup(
                        new ProvisionedAgentRuntimeController(runtime, mapper))
                .build();
    }

    private static ProvisionedAgentRuntime.RuntimeContext context() {
        return new ProvisionedAgentRuntime.RuntimeContext(
                AGENT_ID,
                "web:1",
                "",
                REVISION,
                List.of(),
                0,
                false,
                new ProvisionedAgentRuntime.ToolDescriptor(
                        "agent_private_graph",
                        "bound graph",
                        Map.of("type", "object", "properties", Map.of("action", Map.of())),
                        true));
    }
}
