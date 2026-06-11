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

import ai.kompile.app.eval.domain.EvalCaseEntity;
import ai.kompile.app.eval.domain.EvalDatasetEntity;
import ai.kompile.app.eval.domain.ExperimentEntity;
import ai.kompile.app.eval.domain.ExperimentRunEntity;
import ai.kompile.app.eval.service.ExperimentService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExperimentControllerTest {

    @Mock
    private ExperimentService experimentService;

    @InjectMocks
    private ExperimentController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExperimentEntity sampleExperiment;
    private ExperimentRunEntity sampleRun;
    private EvalDatasetEntity sampleDataset;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        sampleExperiment = ExperimentEntity.builder()
                .id("exp-1")
                .name("Test Experiment")
                .description("A test")
                .suiteId("suite-1")
                .datasetId("ds-1")
                .status("PENDING")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        sampleRun = ExperimentRunEntity.builder()
                .id("run-1")
                .experiment(sampleExperiment)
                .modelId("bge-base-en-v1.5")
                .modelVariant("default")
                .modelType("dense")
                .status("PENDING")
                .build();

        sampleDataset = EvalDatasetEntity.builder()
                .id("ds-1")
                .name("Test Dataset")
                .description("Dataset for testing")
                .format("csv")
                .sampleCount(10)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void createExperiment_validRequest_returnsCreated() throws Exception {
        when(experimentService.createExperiment(any(), any(), any(), any(), any()))
                .thenReturn(sampleExperiment);

        Map<String, Object> body = Map.of(
                "name", "Test Experiment",
                "description", "A test",
                "suiteId", "suite-1",
                "datasetId", "ds-1"
        );

        mockMvc.perform(post("/api/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("exp-1"))
                .andExpect(jsonPath("$.name").value("Test Experiment"));
    }

    @Test
    void createExperiment_illegalArg_returnsBadRequest() throws Exception {
        when(experimentService.createExperiment(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Name required"));

        Map<String, Object> body = Map.of("name", "");

        mockMvc.perform(post("/api/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createExperiment_serviceThrows_returns500() throws Exception {
        when(experimentService.createExperiment(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        mockMvc.perform(post("/api/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "test"))))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void listExperiments_returnsEmptyList() throws Exception {
        when(experimentService.listExperiments()).thenReturn(List.of());

        mockMvc.perform(get("/api/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listExperiments_returnsExperiments() throws Exception {
        when(experimentService.listExperiments()).thenReturn(List.of(sampleExperiment));

        mockMvc.perform(get("/api/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("exp-1"));
    }

    @Test
    void getExperiment_found_returnsExperiment() throws Exception {
        sampleExperiment.setRuns(List.of(sampleRun));
        when(experimentService.getExperiment("exp-1")).thenReturn(Optional.of(sampleExperiment));

        mockMvc.perform(get("/api/experiments/exp-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("exp-1"));
    }

    @Test
    void getExperiment_notFound_returns404() throws Exception {
        when(experimentService.getExperiment("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/experiments/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteExperiment_found_returnsOk() throws Exception {
        when(experimentService.deleteExperiment("exp-1")).thenReturn(true);

        mockMvc.perform(delete("/api/experiments/exp-1"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteExperiment_notFound_returns404() throws Exception {
        when(experimentService.deleteExperiment("missing")).thenReturn(false);

        mockMvc.perform(delete("/api/experiments/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void compareRuns_returnsComparisonMap() throws Exception {
        when(experimentService.compareRuns("exp-1")).thenReturn(Map.of("result", "comparison"));

        mockMvc.perform(get("/api/experiments/exp-1/compare"))
                .andExpect(status().isOk());
    }

    @Test
    void addRun_validRequest_returnsCreated() throws Exception {
        when(experimentService.addRun(eq("exp-1"), any(), any(), any()))
                .thenReturn(sampleRun);

        Map<String, Object> body = Map.of(
                "modelId", "bge-base-en-v1.5",
                "modelVariant", "default",
                "modelType", "dense"
        );

        mockMvc.perform(post("/api/experiments/exp-1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("run-1"));
    }

    @Test
    void addRun_illegalArg_returnsBadRequest() throws Exception {
        when(experimentService.addRun(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Experiment not found"));

        mockMvc.perform(post("/api/experiments/missing/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("modelId", "model"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void executeRun_success_returnsRun() throws Exception {
        when(experimentService.executeRun("exp-1", "run-1")).thenReturn(sampleRun);

        mockMvc.perform(post("/api/experiments/exp-1/runs/run-1/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"));
    }

    @Test
    void executeRun_illegalArg_returnsBadRequest() throws Exception {
        when(experimentService.executeRun(any(), any()))
                .thenThrow(new IllegalArgumentException("Run not found"));

        mockMvc.perform(post("/api/experiments/exp-1/runs/missing/execute"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listRuns_returnsRunsForExperiment() throws Exception {
        when(experimentService.getRunsForExperiment("exp-1")).thenReturn(List.of(sampleRun));

        mockMvc.perform(get("/api/experiments/exp-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("run-1"));
    }

    @Test
    void getRun_found_returnsRun() throws Exception {
        when(experimentService.getRun("run-1")).thenReturn(Optional.of(sampleRun));

        mockMvc.perform(get("/api/experiments/exp-1/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"));
    }

    @Test
    void getRun_notFound_returns404() throws Exception {
        when(experimentService.getRun("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/experiments/exp-1/runs/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getModelHistory_returnsRuns() throws Exception {
        when(experimentService.getModelHistory("bge-base-en-v1.5")).thenReturn(List.of(sampleRun));

        mockMvc.perform(get("/api/experiments/models/bge-base-en-v1.5/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("run-1"));
    }

    @Test
    void uploadDataset_csvFile_returnsCreated() throws Exception {
        when(experimentService.importDatasetCsv(any(), any(), any())).thenReturn(sampleDataset);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", "id,query\n1,test query".getBytes());

        mockMvc.perform(multipart("/api/experiments/eval-datasets")
                        .file(file)
                        .param("name", "Test Dataset"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ds-1"));
    }

    @Test
    void uploadDataset_jsonlFile_returnsCreated() throws Exception {
        when(experimentService.importDatasetJsonl(any(), any(), any())).thenReturn(sampleDataset);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jsonl", "application/json", "{\"query\":\"test\"}".getBytes());

        mockMvc.perform(multipart("/api/experiments/eval-datasets")
                        .file(file)
                        .param("name", "Test Dataset"))
                .andExpect(status().isCreated());
    }

    @Test
    void listDatasets_returnsDatasets() throws Exception {
        when(experimentService.listDatasets()).thenReturn(List.of(sampleDataset));

        mockMvc.perform(get("/api/experiments/eval-datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("ds-1"));
    }

    @Test
    void getDataset_found_returnsDataset() throws Exception {
        when(experimentService.getDataset("ds-1")).thenReturn(Optional.of(sampleDataset));

        mockMvc.perform(get("/api/experiments/eval-datasets/ds-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ds-1"));
    }

    @Test
    void getDataset_notFound_returns404() throws Exception {
        when(experimentService.getDataset("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/experiments/eval-datasets/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteDataset_found_returnsOk() throws Exception {
        when(experimentService.deleteDataset("ds-1")).thenReturn(true);

        mockMvc.perform(delete("/api/experiments/eval-datasets/ds-1"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteDataset_notFound_returns404() throws Exception {
        when(experimentService.deleteDataset("missing")).thenReturn(false);

        mockMvc.perform(delete("/api/experiments/eval-datasets/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewDataset_returnsRows() throws Exception {
        EvalCaseEntity evalCase = new EvalCaseEntity();
        evalCase.setId("case-1");
        evalCase.setName("Case 1");
        evalCase.setQuery("What is AI?");
        evalCase.setExpectedAnswer("AI is artificial intelligence.");
        when(experimentService.previewDataset("ds-1", 20)).thenReturn(List.of(evalCase));

        mockMvc.perform(get("/api/experiments/eval-datasets/ds-1/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("case-1"))
                .andExpect(jsonPath("$[0].query").value("What is AI?"));
    }
}
