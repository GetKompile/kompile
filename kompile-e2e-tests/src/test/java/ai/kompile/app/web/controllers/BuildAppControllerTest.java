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
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BuildAppControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // BuildAppController has no dependencies
        mockMvc = MockMvcBuilders.standaloneSetup(new BuildAppController()).build();
    }

    @Test
    void getModules_returnsCategories() throws Exception {
        mockMvc.perform(get("/api/build/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.categories[0].name").exists())
                .andExpect(jsonPath("$.categories[0].modules").isArray());
    }

    @Test
    void getPresets_returnsPresetList() throws Exception {
        mockMvc.perform(get("/api/build/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].modules").isArray());
    }

    @Test
    void getPresets_containsHostedLlmRag() throws Exception {
        mockMvc.perform(get("/api/build/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'hosted-llm-rag')]").exists());
    }

    @Test
    void submitBuild_missingConfigName_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("preset", "minimal");

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("configName is required"));
    }

    @Test
    void submitBuild_blankConfigName_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("configName", "  ");
        body.put("preset", "minimal");

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void submitBuild_validPreset_returnsAccepted() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("configName", "my-app");
        body.put("preset", "minimal");

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.buildId").exists())
                .andExpect(jsonPath("$.configName").value("my-app"));
    }

    @Test
    void submitBuild_unknownPreset_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("configName", "my-app");
        body.put("preset", "nonexistent-preset");

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void submitBuild_noPresetAndNoModules_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("configName", "my-app");

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Either 'preset' or 'modules' must be specified"));
    }

    @Test
    void submitBuild_explicitModules_returnsAccepted() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("configName", "my-app");
        body.put("modules", List.of("app-main", "app-core", "vectorstore-anserini"));

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.moduleCount").value(3));
    }

    @Test
    void submitBuild_unknownModule_returnsBadRequest() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("configName", "my-app");
        body.put("modules", List.of("app-main", "nonexistent-module"));

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void submitBuild_withBuildNativeTrue_includesInResponse() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("configName", "native-app");
        body.put("preset", "minimal");
        body.put("buildNative", true);

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.buildNative").value(true));
    }

    @Test
    void submitBuild_caseInsensitivePreset_resolves() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("configName", "my-app");
        body.put("preset", "MINIMAL");

        mockMvc.perform(post("/api/build/app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted());
    }

    @Test
    void getBuildStatus_returnsStatusResponse() throws Exception {
        mockMvc.perform(get("/api/build/status/some-build-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildId").value("some-build-id"))
                .andExpect(jsonPath("$.status").value("pending"));
    }
}
