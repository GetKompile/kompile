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

import ai.kompile.core.rag.RagQuery;
import ai.kompile.core.rag.RagResult;
import ai.kompile.core.rag.RagService;
import ai.kompile.core.rag.SearchType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for RagController.
 * Uses standalone MockMvc setup to avoid Spring context loading (and ND4J initialization).
 *
 * Note: RagQuery uses Lombok @Data @Builder without @NoArgsConstructor, so we register
 * a Jackson mixin to provide a @JsonCreator for deserialization in tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagController Tests")
class RagControllerTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @Mock
    private RagService ragService;

    @InjectMocks
    private RagController ragController;

    /**
     * Jackson mixin to enable deserialization of Lombok @Builder class RagQuery
     * which lacks a public no-args constructor.
     */
    abstract static class RagQueryMixin {
        @JsonCreator
        RagQueryMixin(
                @JsonProperty("query") String query,
                @JsonProperty("useToolCalling") boolean useToolCalling,
                @JsonProperty("searchType") SearchType searchType,
                @JsonProperty("k") int k) {
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.addMixIn(RagQuery.class, RagQueryMixin.class);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(ragController)
                .setMessageConverters(converter)
                .build();
    }

    @Nested
    @DisplayName("POST /api/rag/query")
    class QueryRag {

        @Test
        @DisplayName("should return 200 with answer on successful query")
        void querySuccess() throws Exception {
            RagResult result = new RagResult(
                    "Deep learning is a subset of machine learning.",
                    "Context from retrieved documents.",
                    Collections.emptyList()
            );

            when(ragService.answerQuery(any(RagQuery.class))).thenReturn(result);

            String requestJson = "{\"query\":\"What is deep learning?\",\"useToolCalling\":false,\"searchType\":\"LOCAL\",\"k\":5}";

            mockMvc.perform(post("/api/rag/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.query", is("What is deep learning?")))
                    .andExpect(jsonPath("$.answer.answer", is("Deep learning is a subset of machine learning.")));

            verify(ragService).answerQuery(any(RagQuery.class));
        }

        @Test
        @DisplayName("should return 400 when query string is empty")
        void queryEmptyString() throws Exception {
            String requestJson = "{\"query\":\"\",\"useToolCalling\":false,\"k\":10}";

            mockMvc.perform(post("/api/rag/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("Query cannot be empty.")));
        }

        @Test
        @DisplayName("should return 400 when query string is null")
        void queryNullString() throws Exception {
            String requestJson = "{\"useToolCalling\":false,\"k\":10}";

            mockMvc.perform(post("/api/rag/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", is("Query cannot be empty.")));
        }

        @Test
        @DisplayName("should return 500 when service returns null result")
        void queryServiceReturnsNull() throws Exception {
            when(ragService.answerQuery(any(RagQuery.class))).thenReturn(null);

            String requestJson = "{\"query\":\"What is deep learning?\",\"useToolCalling\":false,\"k\":10}";

            mockMvc.perform(post("/api/rag/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", is("Received null answer from RAG service.")));

            verify(ragService).answerQuery(any(RagQuery.class));
        }

        @Test
        @DisplayName("should return 500 when service throws exception")
        void queryServiceThrowsException() throws Exception {
            when(ragService.answerQuery(any(RagQuery.class)))
                    .thenThrow(new RuntimeException("Connection timeout"));

            String requestJson = "{\"query\":\"What is deep learning?\",\"useToolCalling\":false,\"k\":10}";

            mockMvc.perform(post("/api/rag/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", is("Failed to process RAG query due to an unexpected internal error.")));

            verify(ragService).answerQuery(any(RagQuery.class));
        }
    }
}
