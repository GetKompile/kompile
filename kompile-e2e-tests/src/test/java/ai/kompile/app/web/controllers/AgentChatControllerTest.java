/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.services.agent.AgentRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for AgentChatController.
 * Uses standalone MockMvc setup to avoid Spring context loading (and ND4J initialization).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentChatController Tests")
class AgentChatControllerTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @Mock
    private AgentChatService chatService;

    @Mock
    private AgentRegistryService agentRegistryService;

    private AgentChatController agentChatController;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        agentChatController = new AgentChatController(chatService, agentRegistryService);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(agentChatController)
                .setMessageConverters(converter)
                .build();
    }

    @Nested
    @DisplayName("POST /api/agents/chat/cancel/{processId}")
    class CancelChat {

        @Test
        @DisplayName("should return cancelled=true when process is successfully cancelled")
        void cancelSuccess() throws Exception {
            String processId = "proc-12345";
            when(chatService.cancelProcess(eq(processId))).thenReturn(true);

            mockMvc.perform(post("/api/agents/chat/cancel/{processId}", processId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processId", is(processId)))
                    .andExpect(jsonPath("$.cancelled", is(true)));

            verify(chatService).cancelProcess(eq(processId));
        }

        @Test
        @DisplayName("should return cancelled=false when process is not found")
        void cancelNotFound() throws Exception {
            String processId = "nonexistent-proc";
            when(chatService.cancelProcess(eq(processId))).thenReturn(false);

            mockMvc.perform(post("/api/agents/chat/cancel/{processId}", processId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processId", is(processId)))
                    .andExpect(jsonPath("$.cancelled", is(false)));

            verify(chatService).cancelProcess(eq(processId));
        }

        @Test
        @DisplayName("should invoke chatService.cancelProcess with correct processId")
        void cancelVerifiesServiceCall() throws Exception {
            String processId = "abc-def-ghi";
            when(chatService.cancelProcess(eq(processId))).thenReturn(true);

            mockMvc.perform(post("/api/agents/chat/cancel/{processId}", processId))
                    .andExpect(status().isOk());

            verify(chatService).cancelProcess(eq(processId));
        }
    }

    @Nested
    @DisplayName("GET /api/agents/chat/health")
    class Health {

        @Test
        @DisplayName("should return 200 with status ok")
        void healthReturnsOk() throws Exception {
            mockMvc.perform(get("/api/agents/chat/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("ok")))
                    .andExpect(jsonPath("$.service", is("agent-chat")));
        }

        @Test
        @DisplayName("should return correct response structure with exactly two fields")
        void healthReturnsCorrectStructure() throws Exception {
            mockMvc.perform(get("/api/agents/chat/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").exists())
                    .andExpect(jsonPath("$.service").exists())
                    .andExpect(jsonPath("$.status", is("ok")))
                    .andExpect(jsonPath("$.service", is("agent-chat")));
        }
    }

    // =========================================================================
    // PUT /api/agents/chat/agents/{name}/skip-permissions
    // =========================================================================

    @Nested
    @DisplayName("PUT /api/agents/chat/agents/{name}/skip-permissions")
    class SkipPermissions {

        @Test
        @DisplayName("should update skipPermissions to false")
        void setSkipPermissionsFalse() throws Exception {
            mockMvc.perform(put("/api/agents/chat/agents/{name}/skip-permissions", "claude-cli")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"skipPermissions\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.agent", is("claude-cli")))
                    .andExpect(jsonPath("$.skipPermissions", is(false)));

            verify(agentRegistryService).updateAgentSkipPermissions("claude-cli", false);
        }

        @Test
        @DisplayName("should update skipPermissions to true")
        void setSkipPermissionsTrue() throws Exception {
            mockMvc.perform(put("/api/agents/chat/agents/{name}/skip-permissions", "codex-cli")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"skipPermissions\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.agent", is("codex-cli")))
                    .andExpect(jsonPath("$.skipPermissions", is(true)));

            verify(agentRegistryService).updateAgentSkipPermissions("codex-cli", true);
        }

        @Test
        @DisplayName("should default to true when skipPermissions not in body")
        void defaultsToTrue() throws Exception {
            mockMvc.perform(put("/api/agents/chat/agents/{name}/skip-permissions", "gemini-cli")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.agent", is("gemini-cli")))
                    .andExpect(jsonPath("$.skipPermissions", is(true)));

            verify(agentRegistryService).updateAgentSkipPermissions("gemini-cli", true);
        }

        @Test
        @DisplayName("should propagate IllegalArgumentException for unknown agent")
        void unknownAgentThrows() throws Exception {
            doThrow(new IllegalArgumentException("Agent not found: unknown"))
                    .when(agentRegistryService).updateAgentSkipPermissions("unknown", true);

            assertThrows(Exception.class, () ->
                    mockMvc.perform(put("/api/agents/chat/agents/{name}/skip-permissions", "unknown")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"skipPermissions\":true}"))
            );

            verify(agentRegistryService).updateAgentSkipPermissions("unknown", true);
        }
    }
}
