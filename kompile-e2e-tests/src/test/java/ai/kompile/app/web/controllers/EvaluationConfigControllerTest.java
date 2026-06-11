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

import ai.kompile.core.evaluation.EvaluationService;
import ai.kompile.core.evaluation.RagEvaluator;
import ai.kompile.core.evaluation.EvaluationType;
import ai.kompile.evaluation.EvaluationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationConfigControllerTest {

    @Mock
    private EvaluationService evaluationService;

    private EvaluationProperties properties;
    private EvaluationConfigController controller;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // EvaluationProperties is a @Data class (not an interface) — use a real instance
        properties = new EvaluationProperties();
        properties.setEnabled(true);
        properties.setAsync(false);
        properties.setDefaultThreshold(0.7);

        controller = new EvaluationConfigController(properties, evaluationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getConfig_withProperties_returnsAvailableTrue() throws Exception {
        mockMvc.perform(get("/api/evaluation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.evaluators").exists());
    }

    @Test
    void getConfig_withNullProperties_returnsNotAvailable() throws Exception {
        EvaluationConfigController noPropsController = new EvaluationConfigController(null, evaluationService);
        MockMvc noPropsKMvc = MockMvcBuilders.standaloneSetup(noPropsController).build();

        noPropsKMvc.perform(get("/api/evaluation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void getConfig_containsEvaluatorsSection() throws Exception {
        mockMvc.perform(get("/api/evaluation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluators.relevancy").exists())
                .andExpect(jsonPath("$.evaluators.faithfulness").exists())
                .andExpect(jsonPath("$.evaluators.answerCorrectness").exists())
                .andExpect(jsonPath("$.evaluators.contextRelevancy").exists())
                .andExpect(jsonPath("$.evaluators.hallucination").exists());
    }

    @Test
    void updateConfig_withNullProperties_returns503() throws Exception {
        EvaluationConfigController noPropsController = new EvaluationConfigController(null, evaluationService);
        MockMvc noPropsKMvc = MockMvcBuilders.standaloneSetup(noPropsController).build();

        noPropsKMvc.perform(put("/api/evaluation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void updateConfig_withEnabledFlag_updatesProperty() throws Exception {
        mockMvc.perform(put("/api/evaluation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void updateConfig_withDefaultThreshold_updatesProperty() throws Exception {
        mockMvc.perform(put("/api/evaluation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("defaultThreshold", 0.8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultThreshold").value(0.8));
    }

    @Test
    void updateConfig_withAsyncFlag_updatesProperty() throws Exception {
        mockMvc.perform(put("/api/evaluation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("async", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.async").value(true));
    }

    @Test
    void getAvailableEvaluators_withNullService_returnsNotAvailable() throws Exception {
        EvaluationConfigController noSvcController = new EvaluationConfigController(properties, null);
        MockMvc noSvcMvc = MockMvcBuilders.standaloneSetup(noSvcController).build();

        noSvcMvc.perform(get("/api/evaluation/evaluators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void getAvailableEvaluators_withService_returnsEvaluatorList() throws Exception {
        RagEvaluator mockEvaluator = mock(RagEvaluator.class);
        when(mockEvaluator.getName()).thenReturn("relevancy");
        when(mockEvaluator.getType()).thenReturn(EvaluationType.RELEVANCY);
        when(evaluationService.isEnabled()).thenReturn(true);
        when(evaluationService.getEvaluators()).thenReturn(List.of(mockEvaluator));

        mockMvc.perform(get("/api/evaluation/evaluators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.evaluators").isArray());
    }

    @Test
    void toggleEvaluation_withNullProperties_returns503() throws Exception {
        EvaluationConfigController noPropsController = new EvaluationConfigController(null, evaluationService);
        MockMvc noPropsKMvc = MockMvcBuilders.standaloneSetup(noPropsController).build();

        noPropsKMvc.perform(post("/api/evaluation/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void toggleEvaluation_withEnabledTrue_updatesProperty() throws Exception {
        mockMvc.perform(post("/api/evaluation/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void toggleEvaluation_withEnabledFalse_disablesEvaluation() throws Exception {
        mockMvc.perform(post("/api/evaluation/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void getEvaluationTypes_returnsAllTypes() throws Exception {
        mockMvc.perform(get("/api/evaluation/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].type").exists())
                .andExpect(jsonPath("$[0].description").exists());
    }

    @Test
    void getEvaluationTypes_containsKnownTypes() throws Exception {
        mockMvc.perform(get("/api/evaluation/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'RELEVANCY')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'FAITHFULNESS')]").exists());
    }
}
