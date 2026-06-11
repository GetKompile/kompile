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

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.DeviceRoutingConfig.ServiceDeviceConfig;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceRoutingControllerTest {

    @Mock
    private DeviceRoutingConfigService deviceRoutingConfigService;

    @InjectMocks
    private DeviceRoutingController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeviceRoutingConfig sampleConfig;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        sampleConfig = DeviceRoutingConfig.defaults();
        when(deviceRoutingConfigService.getConfiguration()).thenReturn(sampleConfig);
    }

    @Test
    void getConfiguration_returnsOk() throws Exception {
        mockMvc.perform(get("/api/device-routing"))
                .andExpect(status().isOk());

        verify(deviceRoutingConfigService).getConfiguration();
    }

    @Test
    void saveConfiguration_validConfig_returnsOk() throws Exception {
        doNothing().when(deviceRoutingConfigService).saveConfiguration(any());

        mockMvc.perform(post("/api/device-routing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DeviceRoutingConfig.defaults())))
                .andExpect(status().isOk());

        verify(deviceRoutingConfigService).saveConfiguration(any());
    }

    @Test
    void saveConfiguration_serviceThrows_returns500() throws Exception {
        doThrow(new RuntimeException("Save failed")).when(deviceRoutingConfigService).saveConfiguration(any());

        mockMvc.perform(post("/api/device-routing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DeviceRoutingConfig.defaults())))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void getServiceConfig_found_returnsConfig() throws Exception {
        ServiceDeviceConfig serviceConfig = new ServiceDeviceConfig(null, null, null, null);
        when(deviceRoutingConfigService.getServiceConfig("embedding")).thenReturn(serviceConfig);

        mockMvc.perform(get("/api/device-routing/services/embedding"))
                .andExpect(status().isOk());
    }

    @Test
    void getServiceConfig_notFound_returns404() throws Exception {
        when(deviceRoutingConfigService.getServiceConfig("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/device-routing/services/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateServiceConfig_validConfig_returnsUpdated() throws Exception {
        doNothing().when(deviceRoutingConfigService).updateServiceConfig(anyString(), any());

        mockMvc.perform(put("/api/device-routing/services/embedding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ServiceDeviceConfig(null, null, null, null))))
                .andExpect(status().isOk());
    }

    @Test
    void updateServiceConfig_serviceThrows_returns500() throws Exception {
        doThrow(new RuntimeException("Update failed")).when(deviceRoutingConfigService)
                .updateServiceConfig(anyString(), any());

        mockMvc.perform(put("/api/device-routing/services/embedding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ServiceDeviceConfig(null, null, null, null))))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void removeServiceConfig_success_returnsOk() throws Exception {
        doNothing().when(deviceRoutingConfigService).removeServiceConfig(anyString());

        mockMvc.perform(delete("/api/device-routing/services/embedding"))
                .andExpect(status().isOk());

        verify(deviceRoutingConfigService).removeServiceConfig("embedding");
    }

    @Test
    void resetToDefaults_returnsOk() throws Exception {
        doNothing().when(deviceRoutingConfigService).resetToDefaults();

        mockMvc.perform(post("/api/device-routing/reset"))
                .andExpect(status().isOk());

        verify(deviceRoutingConfigService).resetToDefaults();
    }

    @Test
    void previewServiceConfig_returnsNd4jConfig() throws Exception {
        Nd4jEnvironmentConfig nd4jConfig = Nd4jEnvironmentConfig.defaults();
        when(deviceRoutingConfigService.resolveNd4jConfigForService("embedding")).thenReturn(nd4jConfig);

        mockMvc.perform(get("/api/device-routing/preview/embedding"))
                .andExpect(status().isOk());
    }

    @Test
    void previewServiceConfig_serviceThrows_returns500() throws Exception {
        when(deviceRoutingConfigService.resolveNd4jConfigForService("embedding"))
                .thenThrow(new RuntimeException("Preview failed"));

        mockMvc.perform(get("/api/device-routing/preview/embedding"))
                .andExpect(status().isInternalServerError());
    }
}
