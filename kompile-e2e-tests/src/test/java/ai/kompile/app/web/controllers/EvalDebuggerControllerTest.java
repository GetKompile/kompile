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

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for EvalDebuggerController.
 * All dependencies are optional so most tests focus on the no-service behavior paths.
 */
class EvalDebuggerControllerTest {

    private MockMvc mockMvc;
    private EvalDebuggerController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // All dependencies are @Autowired(required=false) - create with no services
        controller = new EvalDebuggerController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getStatus_withNoServices_returnsAvailableFalse() throws Exception {
        mockMvc.perform(get("/api/eval-debugger/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.llmJudgeAvailable").value(false));
    }

    @Test
    void getEvaluatorTypes_withNoEvaluationService_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/eval-debugger/evaluator-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getLlmProviders_withNoServices_returnsNoneEntry() throws Exception {
        mockMvc.perform(get("/api/eval-debugger/llm-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].available").value(false));
    }

    @Test
    void runLlmJudge_withNoLlm_returnsSuccessFalse() throws Exception {
        Map<String, Object> body = Map.of(
                "query", "What is AI?",
                "expectedAnswer", "Artificial intelligence",
                "actualAnswer", "AI is a technology"
        );

        mockMvc.perform(post("/api/eval-debugger/run-llm-judge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void runSingleTest_withNoRagService_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of(
                "id", "test-1",
                "prompt", "What is AI?",
                "expectedAnswer", "Artificial intelligence"
        );

        mockMvc.perform(post("/api/eval-debugger/run-single")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runBatchTests_withNoRagService_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of(
                "testCases", List.of(Map.of(
                        "id", "test-1",
                        "prompt", "What is AI?",
                        "expectedAnswer", "Artificial intelligence"
                ))
        );

        mockMvc.perform(post("/api/eval-debugger/run-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runCombined_withNoRagService_returnsSuccessFalse() throws Exception {
        Map<String, Object> body = Map.of(
                "id", "test-1",
                "prompt", "What is AI?",
                "expectedAnswer", "Artificial intelligence"
        );

        mockMvc.perform(post("/api/eval-debugger/run-combined")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void runBatchCombined_withNoRagService_returnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of(
                "testCases", List.of(Map.of("id", "test-1", "prompt", "test"))
        );

        mockMvc.perform(post("/api/eval-debugger/run-batch-combined")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveTestSuite_newSuite_returnsWithGeneratedId() throws Exception {
        Map<String, Object> suite = Map.of(
                "name", "My Test Suite",
                "description", "A suite for testing",
                "testCases", List.of()
        );

        mockMvc.perform(post("/api/eval-debugger/suites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(suite)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("My Test Suite"));
    }

    @Test
    void saveTestSuite_withExistingId_preservesId() throws Exception {
        Map<String, Object> suite = Map.of(
                "id", "existing-suite-id",
                "name", "My Test Suite",
                "testCases", List.of()
        );

        mockMvc.perform(post("/api/eval-debugger/suites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(suite)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("existing-suite-id"));
    }

    @Test
    void getTestSuites_returnsEmptyInitially() throws Exception {
        mockMvc.perform(get("/api/eval-debugger/suites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getTestSuite_afterSave_returnsSuite() throws Exception {
        // First save a suite
        Map<String, Object> suite = Map.of(
                "id", "test-suite-123",
                "name", "My Suite",
                "testCases", List.of()
        );

        mockMvc.perform(post("/api/eval-debugger/suites")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(suite)));

        // Then retrieve it
        mockMvc.perform(get("/api/eval-debugger/suites/test-suite-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("test-suite-123"));
    }

    @Test
    void getTestSuite_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/eval-debugger/suites/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTestSuite_returnsOk() throws Exception {
        mockMvc.perform(delete("/api/eval-debugger/suites/any-id"))
                .andExpect(status().isOk());
    }

    @Test
    void runTestSuite_suiteNotFound_returns404() throws Exception {
        mockMvc.perform(post("/api/eval-debugger/suites/nonexistent/run"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRunHistory_returnsEmptyInitially() throws Exception {
        mockMvc.perform(get("/api/eval-debugger/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getRunResult_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/eval-debugger/runs/nonexistent"))
                .andExpect(status().isNotFound());
    }
}
