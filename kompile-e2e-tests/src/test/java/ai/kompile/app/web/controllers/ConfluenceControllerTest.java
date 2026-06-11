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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.ConfluenceService;
import ai.kompile.app.web.dto.confluence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfluenceControllerTest {

    @Mock
    private ConfluenceService confluenceService;

    @InjectMocks
    private ConfluenceController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getStatus_returnsConnectionStatus() throws Exception {
        ConfluenceConnectionStatus status = new ConfluenceConnectionStatus();
        status.setConnected(false);
        when(confluenceService.getConnectionStatus()).thenReturn(status);

        mockMvc.perform(get("/api/confluence/status"))
                .andExpect(status().isOk());

        verify(confluenceService).getConnectionStatus();
    }

    @Test
    void connect_withValidConfig_returnsOkWhenConnected() throws Exception {
        ConfluenceConnectionStatus connectedStatus = new ConfluenceConnectionStatus();
        connectedStatus.setConnected(true);

        ConfluenceConnectionConfig config = new ConfluenceConnectionConfig();
        config.setBaseUrl("https://mycompany.atlassian.net");

        when(confluenceService.connect(any())).thenReturn(connectedStatus);

        mockMvc.perform(post("/api/confluence/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());
    }

    @Test
    void connect_connectionFails_returnsBadRequest() throws Exception {
        ConfluenceConnectionStatus failedStatus = new ConfluenceConnectionStatus();
        failedStatus.setConnected(false);
        failedStatus.setErrorMessage("Invalid credentials");

        ConfluenceConnectionConfig config = new ConfluenceConnectionConfig();
        config.setBaseUrl("https://mycompany.atlassian.net");

        when(confluenceService.connect(any())).thenReturn(failedStatus);

        mockMvc.perform(post("/api/confluence/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disconnect_returnsSuccessTrue() throws Exception {
        doNothing().when(confluenceService).disconnect();

        mockMvc.perform(post("/api/confluence/disconnect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void listSpaces_returnsSpaceList() throws Exception {
        ConfluenceSpaceListResponse response = new ConfluenceSpaceListResponse();
        response.setSpaces(List.of());
        when(confluenceService.listSpaces(any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/confluence/spaces"))
                .andExpect(status().isOk());
    }

    @Test
    void listSpaces_notConnected_returns401() throws Exception {
        when(confluenceService.listSpaces(any(), any()))
                .thenThrow(new IllegalStateException("Not connected"));

        mockMvc.perform(get("/api/confluence/spaces"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSpace_found_returnsSpace() throws Exception {
        ConfluenceSpace space = new ConfluenceSpace();
        space.setKey("DEV");
        when(confluenceService.getSpace("DEV")).thenReturn(space);

        mockMvc.perform(get("/api/confluence/spaces/DEV"))
                .andExpect(status().isOk());
    }

    @Test
    void getSpace_notFound_returns404() throws Exception {
        when(confluenceService.getSpace("MISSING")).thenReturn(null);

        mockMvc.perform(get("/api/confluence/spaces/MISSING"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listPages_returnsPageList() throws Exception {
        ConfluencePageListResponse response = new ConfluencePageListResponse();
        response.setPages(List.of());
        when(confluenceService.listPages(anyString(), any(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/confluence/spaces/DEV/pages"))
                .andExpect(status().isOk());
    }

    @Test
    void getPage_found_returnsPage() throws Exception {
        ConfluencePage page = new ConfluencePage();
        page.setId("123");
        when(confluenceService.getPage("123")).thenReturn(page);

        mockMvc.perform(get("/api/confluence/pages/123"))
                .andExpect(status().isOk());
    }

    @Test
    void getPage_notFound_returns404() throws Exception {
        when(confluenceService.getPage("999")).thenReturn(null);

        mockMvc.perform(get("/api/confluence/pages/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ingestPages_success_returnsOk() throws Exception {
        ConfluenceIngestResponse response = ConfluenceIngestResponse.builder()
                .success(true)
                .pagesQueued(5)
                .build();
        when(confluenceService.ingestPages(any())).thenReturn(response);

        ConfluenceIngestRequest request = ConfluenceIngestRequest.builder()
                .pageIds(List.of("123", "456"))
                .build();

        mockMvc.perform(post("/api/confluence/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void ingestPages_notConnected_returns401() throws Exception {
        when(confluenceService.ingestPages(any())).thenThrow(new IllegalStateException("Not connected"));

        mockMvc.perform(post("/api/confluence/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
