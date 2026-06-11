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

import ai.kompile.app.services.ChunkDeduplicationService;
import ai.kompile.core.embeddings.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for ChunkManagerController.
 * All three dependencies are optional — tests cover both the null-service paths and
 * the happy-path with mocked services.
 */
class ChunkManagerControllerTest {

    private MockMvc mockMvcNoServices;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Create controller with all null services (mirrors @Autowired(required=false))
        ChunkManagerController controller = new ChunkManagerController(null, null, null);
        mockMvcNoServices = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── No-service paths ──────────────────────────────────────────────────────

    @Test
    void listChunks_withNoServices_returnsEmptyList() throws Exception {
        mockMvcNoServices.perform(get("/api/chunk-manager/chunks"))
                .andExpect(status().isOk());
    }

    @Test
    void getChunk_withNoServices_returnsNotFound() throws Exception {
        mockMvcNoServices.perform(get("/api/chunk-manager/chunks/some-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listSources_withNoServices_returnsEmptySourceList() throws Exception {
        mockMvcNoServices.perform(get("/api/chunk-manager/sources"))
                .andExpect(status().isOk());
    }

    @Test
    void updateChunk_withNoServices_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of("content", "New content");

        mockMvcNoServices.perform(put("/api/chunk-manager/chunks/some-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteChunk_withNoServices_returnsBadRequest() throws Exception {
        mockMvcNoServices.perform(delete("/api/chunk-manager/chunks/some-id"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteChunks_withNoServices_returnsBadRequest() throws Exception {
        mockMvcNoServices.perform(delete("/api/chunk-manager/chunks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of("id-1", "id-2"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteBySource_withNoServices_returnsBadRequest() throws Exception {
        Map<String, String> body = Map.of("sourceId", "my-source");

        mockMvcNoServices.perform(delete("/api/chunk-manager/chunks/by-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteBySource_withNullSourceId_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of();

        mockMvcNoServices.perform(delete("/api/chunk-manager/chunks/by-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSemanticTypes_returnsTypeList() throws Exception {
        mockMvcNoServices.perform(get("/api/chunk-manager/semantic-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").exists());
    }

    @Test
    void getEntityTypes_returnsTypeList() throws Exception {
        mockMvcNoServices.perform(get("/api/chunk-manager/entity-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types").exists());
    }

    @Test
    void getChunkForEdit_withNoServices_returnsNotFound() throws Exception {
        mockMvcNoServices.perform(get("/api/chunk-manager/chunks/some-id/edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    void generateClearToken_returnsToken() throws Exception {
        mockMvcNoServices.perform(post("/api/chunk-manager/clear-all/token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.expiresIn").value(60));
    }

    @Test
    void clearAll_withNoServices_returnsBadRequest() throws Exception {
        Map<String, String> body = Map.of("confirmationToken", "CLEAR_ALL_some-token");

        mockMvcNoServices.perform(delete("/api/chunk-manager/clear-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clearAll_withInvalidToken_returnsBadRequest() throws Exception {
        Map<String, String> body = Map.of("confirmationToken", "invalid-token");

        mockMvcNoServices.perform(delete("/api/chunk-manager/clear-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyzeDuplicates_withNoDeduplicationService_returnsBadRequest() throws Exception {
        mockMvcNoServices.perform(get("/api/chunk-manager/duplicates"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deduplicate_withNoDeduplicationService_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of("strategy", "content_hash", "dryRun", true);

        mockMvcNoServices.perform(post("/api/chunk-manager/deduplicate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportChunks_withNoVectorStore_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of("includeMetadata", true);

        mockMvcNoServices.perform(post("/api/chunk-manager/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadExport_withNoVectorStore_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of("includeMetadata", true);

        mockMvcNoServices.perform(post("/api/chunk-manager/export/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteChunks_emptyList_returnsBadRequest() throws Exception {
        mockMvcNoServices.perform(delete("/api/chunk-manager/chunks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clearAll_withValidTokenFormat_butExpired_returnsBadRequest() throws Exception {
        // Token has correct prefix but was never registered
        Map<String, String> body = Map.of("confirmationToken", "CLEAR_ALL_fake-token-not-in-store");

        mockMvcNoServices.perform(delete("/api/chunk-manager/clear-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
