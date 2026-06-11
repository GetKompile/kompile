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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailValueExtractionControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        EmailValueExtractionController controller = new EmailValueExtractionController(objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void extractValues_validText_returnsOk() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", "The total amount due is $1,500.00. Please pay by December 31, 2025.");

        mockMvc.perform(post("/api/email/extract-values/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").exists())
                .andExpect(jsonPath("$.values").isArray());
    }

    @Test
    void extractValues_missingText_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("metadata", Map.of("from", "test@example.com"));

        mockMvc.perform(post("/api/email/extract-values/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request must include 'text' field"));
    }

    @Test
    void extractValues_blankText_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", "   ");

        mockMvc.perform(post("/api/email/extract-values/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void extractValues_withMetadata_returnsOk() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", "Invoice total: $250");
        body.put("metadata", Map.of("from", "billing@company.com", "subject", "Invoice"));

        mockMvc.perform(post("/api/email/extract-values/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void mapToCells_missingText_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("spreadsheetGraphJson", "{}");

        mockMvc.perform(post("/api/email/extract-values/map-to-cells")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request must include 'text' field"));
    }

    @Test
    void mapToCells_missingSpreadsheetJson_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", "Invoice total: $250");

        mockMvc.perform(post("/api/email/extract-values/map-to-cells")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request must include 'spreadsheetGraphJson' field"));
    }

    @Test
    void mapToCells_invalidJson_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", "Invoice total: $250");
        body.put("spreadsheetGraphJson", "not valid json");

        mockMvc.perform(post("/api/email/extract-values/map-to-cells")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
