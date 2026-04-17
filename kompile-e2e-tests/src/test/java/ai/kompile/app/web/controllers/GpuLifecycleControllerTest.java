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

import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.services.GpuResourceManager;
import ai.kompile.app.services.ModelLifecycleManager;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link GpuLifecycleController}.
 * Uses standalone MockMvc to avoid loading Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GpuLifecycleController Tests")
class GpuLifecycleControllerTest {

    private static final long ONE_GB = 1024L * 1024L * 1024L;
    private static final GpuDevice GPU_4090 = GpuDevice.local(0, 1, "RTX 4090", 24L * ONE_GB);

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private GpuResourceManager gpuResourceManager;

    @Mock
    private ModelLifecycleManager modelLifecycleManager;

    @InjectMocks
    private GpuLifecycleController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    // ==================== GET /api/gpu-lifecycle/status ====================

    @Nested
    @DisplayName("GET /api/gpu-lifecycle/status")
    class GetStatus {

        @Test
        @DisplayName("should return 200 with lifecycle status")
        void returnsStatus() throws Exception {
            Map<String, Object> lifecycleStatus = new LinkedHashMap<>();
            lifecycleStatus.put("running", true);
            lifecycleStatus.put("managedServices", List.of());
            lifecycleStatus.put("totalActiveJobs", 0);

            when(modelLifecycleManager.getStatus()).thenReturn(lifecycleStatus);

            mockMvc.perform(get("/api/gpu-lifecycle/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lifecycle.running", is(true)))
                    .andExpect(jsonPath("$.lifecycle.totalActiveJobs", is(0)));
        }
    }

    // ==================== GET /api/gpu-lifecycle/devices ====================

    @Nested
    @DisplayName("GET /api/gpu-lifecycle/devices")
    class GetDevices {

        @Test
        @DisplayName("should return 200 with GPU device info")
        void returnsDevices() throws Exception {
            Map<String, Object> gpuStatus = new LinkedHashMap<>();
            gpuStatus.put("deviceCount", 2);
            gpuStatus.put("totalReservations", 1);

            when(gpuResourceManager.getStatus()).thenReturn(gpuStatus);

            mockMvc.perform(get("/api/gpu-lifecycle/devices"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deviceCount", is(2)))
                    .andExpect(jsonPath("$.totalReservations", is(1)));
        }
    }

    // ==================== GET /api/gpu-lifecycle/jobs ====================

    @Nested
    @DisplayName("GET /api/gpu-lifecycle/jobs")
    class GetActiveJobs {

        @Test
        @DisplayName("should return 200 with empty job list when no active jobs")
        void emptyJobs() throws Exception {
            when(modelLifecycleManager.getActiveJobHolds()).thenReturn(Collections.emptyMap());

            mockMvc.perform(get("/api/gpu-lifecycle/jobs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalActiveJobs", is(0)))
                    .andExpect(jsonPath("$.jobs", hasSize(0)));
        }

        @Test
        @DisplayName("should return 200 with active job holds")
        void activeJobs() throws Exception {
            Instant now = Instant.now();
            ModelLifecycleManager.JobGpuHold hold =
                    new ModelLifecycleManager.JobGpuHold(
                            "vlm-1", "vlm", GPU_4090, now, "VLM test: doc.pdf");

            when(modelLifecycleManager.getActiveJobHolds())
                    .thenReturn(Map.of("vlm-1", hold));

            mockMvc.perform(get("/api/gpu-lifecycle/jobs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalActiveJobs", is(1)))
                    .andExpect(jsonPath("$.jobs", hasSize(1)))
                    .andExpect(jsonPath("$.jobs[0].jobId", is("vlm-1")))
                    .andExpect(jsonPath("$.jobs[0].serviceType", is("vlm")))
                    .andExpect(jsonPath("$.jobs[0].device", is("RTX 4090")))
                    .andExpect(jsonPath("$.jobs[0].description", is("VLM test: doc.pdf")));
        }
    }

    // ==================== DELETE /api/gpu-lifecycle/jobs/{jobId} ====================

    @Nested
    @DisplayName("DELETE /api/gpu-lifecycle/jobs/{jobId}")
    class ForceReleaseJob {

        @Test
        @DisplayName("should return 200 when job exists and is released")
        void releasesExistingJob() throws Exception {
            when(modelLifecycleManager.hasJobGpuHold("vlm-1")).thenReturn(true);

            mockMvc.perform(delete("/api/gpu-lifecycle/jobs/vlm-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId", is("vlm-1")))
                    .andExpect(jsonPath("$.status", is("released")))
                    .andExpect(jsonPath("$.warning", containsString("force-released")));

            verify(modelLifecycleManager).releaseGpuForJob("vlm-1");
        }

        @Test
        @DisplayName("should return 404 when job does not exist")
        void notFoundForMissingJob() throws Exception {
            when(modelLifecycleManager.hasJobGpuHold("nonexistent")).thenReturn(false);

            mockMvc.perform(delete("/api/gpu-lifecycle/jobs/nonexistent"))
                    .andExpect(status().isNotFound());

            verify(modelLifecycleManager, never()).releaseGpuForJob(anyString());
        }
    }

    // ==================== GET /api/gpu-lifecycle/budgets ====================

    @Nested
    @DisplayName("GET /api/gpu-lifecycle/budgets")
    class GetBudgets {

        @Test
        @DisplayName("should return 200 with budgets for all service types")
        void returnsBudgets() throws Exception {
            when(gpuResourceManager.getMemoryBudget("embedding")).thenReturn(5L * ONE_GB);
            when(gpuResourceManager.getMemoryBudget("vlm")).thenReturn(18L * ONE_GB);
            when(gpuResourceManager.getMemoryBudget("ingest")).thenReturn(2L * ONE_GB);
            when(gpuResourceManager.getMemoryBudget("vectorPopulation")).thenReturn(1L * ONE_GB);
            when(gpuResourceManager.getMemoryBudget("modelInit")).thenReturn(2L * ONE_GB);

            when(gpuResourceManager.getServicePriority("embedding")).thenReturn(10);
            when(gpuResourceManager.getServicePriority("vlm")).thenReturn(100);
            when(gpuResourceManager.getServicePriority("ingest")).thenReturn(50);
            when(gpuResourceManager.getServicePriority("vectorPopulation")).thenReturn(30);
            when(gpuResourceManager.getServicePriority("modelInit")).thenReturn(20);

            when(gpuResourceManager.hasReservationForService(anyString())).thenReturn(false);
            when(gpuResourceManager.getReservationCount(anyString())).thenReturn(0);

            mockMvc.perform(get("/api/gpu-lifecycle/budgets"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.embedding.budgetMb", is(5120)))
                    .andExpect(jsonPath("$.embedding.priority", is(10)))
                    .andExpect(jsonPath("$.embedding.hasReservation", is(false)))
                    .andExpect(jsonPath("$.embedding.reservationCount", is(0)))
                    .andExpect(jsonPath("$.vlm.budgetMb", is(18432)))
                    .andExpect(jsonPath("$.vlm.priority", is(100)));
        }
    }

    // ==================== POST /api/gpu-lifecycle/budgets/{serviceType} ====================

    @Nested
    @DisplayName("POST /api/gpu-lifecycle/budgets/{serviceType}")
    class UpdateBudget {

        @Test
        @DisplayName("should update budget and return 200")
        void updatesBudget() throws Exception {
            mockMvc.perform(post("/api/gpu-lifecycle/budgets/embedding")
                            .param("budgetMb", "8192"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.serviceType", is("embedding")))
                    .andExpect(jsonPath("$.budgetMb", is(8192)))
                    .andExpect(jsonPath("$.status", is("updated")));

            verify(gpuResourceManager).setMemoryBudget("embedding", 8192L * 1024 * 1024);
        }
    }

    // ==================== POST /api/gpu-lifecycle/priorities/{serviceType} ====================

    @Nested
    @DisplayName("POST /api/gpu-lifecycle/priorities/{serviceType}")
    class UpdatePriority {

        @Test
        @DisplayName("should update priority and return 200")
        void updatesPriority() throws Exception {
            mockMvc.perform(post("/api/gpu-lifecycle/priorities/embedding")
                            .param("priority", "200"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.serviceType", is("embedding")))
                    .andExpect(jsonPath("$.priority", is(200)))
                    .andExpect(jsonPath("$.status", is("updated")));

            verify(gpuResourceManager).setServicePriority("embedding", 200);
        }
    }
}
