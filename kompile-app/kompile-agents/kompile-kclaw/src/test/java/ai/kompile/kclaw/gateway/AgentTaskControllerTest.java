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
package ai.kompile.kclaw.gateway;

import ai.kompile.kclaw.task.AgentTaskService;
import ai.kompile.kclaw.task.AgentTaskStore;
import ai.kompile.kclaw.task.KompileCliRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Path;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc test of {@link AgentTaskController} — verifies the REST contract
 * (routing, JSON (de)serialization, status codes) without a Spring context or external
 * processes. Uses the ReAct engine with no agent wired so runs fail fast in-memory.
 */
class AgentTaskControllerTest {

    @TempDir
    Path tempDir;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        AgentTaskStore store = new AgentTaskStore(tempDir.toString(), mapper);
        KompileCliRunner runner = new KompileCliRunner(mapper, "/nonexistent/kompile", 1000);
        AgentTaskService service = new AgentTaskService(null, runner, store, "jarvis", null, null);
        mvc = MockMvcBuilders.standaloneSetup(new AgentTaskController(service)).build();
    }

    @Test
    void submitTask_returns202WithTaskRecord() throws Exception {
        mvc.perform(post("/api/kclaw/tasks")
                        .contentType(APPLICATION_JSON)
                        .content("{\"engine\":\"react\",\"task\":\"summarize the repo\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.task").value("summarize the repo"))
                .andExpect(jsonPath("$.engine").value("REACT"));
    }

    @Test
    void submitTask_missingTask_returns400() throws Exception {
        mvc.perform(post("/api/kclaw/tasks")
                        .contentType(APPLICATION_JSON)
                        .content("{\"engine\":\"react\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listTasks_returnsOk() throws Exception {
        mvc.perform(get("/api/kclaw/tasks")).andExpect(status().isOk());
    }

    @Test
    void getMissingTask_returns404() throws Exception {
        mvc.perform(get("/api/kclaw/tasks/does-not-exist")).andExpect(status().isNotFound());
    }
}
