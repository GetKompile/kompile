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

import ai.kompile.app.config.AppIndexConfig;
import ai.kompile.app.services.AppIndexConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvironmentConfigControllerTest {

    @Mock
    private AppIndexConfigService appIndexConfigService;

    private EnvironmentConfigController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new EnvironmentConfigController(appIndexConfigService);

        // Set @Value fields via reflection
        ReflectionTestUtils.setField(controller, "subprocessJavaPath", "java");
        ReflectionTestUtils.setField(controller, "subprocessHeapSize", "4g");
        ReflectionTestUtils.setField(controller, "subprocessEnabled", true);
        ReflectionTestUtils.setField(controller, "vectorPopulationSubprocessEnabled", true);
        ReflectionTestUtils.setField(controller, "vectorPopulationHeapSize", "4g");
        ReflectionTestUtils.setField(controller, "denseRetrievalModel", "bge-base-en-v1.5");
        ReflectionTestUtils.setField(controller, "sparseRetrievalModel", "");
        ReflectionTestUtils.setField(controller, "rerankingModel", "ms-marco-MiniLM-L-6-v2");
        ReflectionTestUtils.setField(controller, "modelRegistryPath", System.getProperty("user.home") + "/.kompile/models/registry.json");
        ReflectionTestUtils.setField(controller, "backupPath", System.getProperty("user.home") + "/.kompile/backups");
        ReflectionTestUtils.setField(controller, "serverPort", 8080);

        AppIndexConfig config = AppIndexConfig.builder().vectorStorePath("/data/vector").build();
        when(appIndexConfigService.getConfiguration()).thenReturn(config);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getEnvironmentStatus_returnsOk() throws Exception {
        mockMvc.perform(get("/api/environment/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.directories").exists())
                .andExpect(jsonPath("$.environmentVariables").exists())
                .andExpect(jsonPath("$.subprocess").exists())
                .andExpect(jsonPath("$.modelRoles").exists())
                .andExpect(jsonPath("$.server").exists())
                .andExpect(jsonPath("$.diskSpace").exists());
    }

    @Test
    void getEnvironmentStatus_containsSubprocessConfig() throws Exception {
        mockMvc.perform(get("/api/environment/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subprocess.ingest.enabled").value(true))
                .andExpect(jsonPath("$.subprocess.ingest.javaPath").value("java"))
                .andExpect(jsonPath("$.subprocess.ingest.heapSize").value("4g"));
    }

    @Test
    void getEnvironmentStatus_containsModelRoles() throws Exception {
        mockMvc.perform(get("/api/environment/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelRoles.denseRetrieval.configuredModel").value("bge-base-en-v1.5"))
                .andExpect(jsonPath("$.modelRoles.reranking.configuredModel").value("ms-marco-MiniLM-L-6-v2"));
    }

    @Test
    void getEnvironmentStatus_containsServerInfo() throws Exception {
        mockMvc.perform(get("/api/environment/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.server.port").value(8080))
                .andExpect(jsonPath("$.server.javaVersion").exists());
    }

    @Test
    void getEnvironmentStatus_containsTimestamp() throws Exception {
        mockMvc.perform(get("/api/environment/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timestamp").isNumber());
    }
}
