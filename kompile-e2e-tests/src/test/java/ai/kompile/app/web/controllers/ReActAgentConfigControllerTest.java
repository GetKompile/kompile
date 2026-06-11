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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReActAgentConfigControllerTest {

    @Mock
    private Environment environment;

    private ReActAgentConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new ReActAgentConfigController();
        ReflectionTestUtils.setField(controller, "environment", environment);

        // Default properties
        when(environment.getProperty(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    // ── getConfig ─────────────────────────────────────────────────────────

    @Test
    void getConfig_returnsConfigWithDefaults() {
        ResponseEntity<ReActAgentConfigController.ReActConfigDto> resp = controller.getConfig();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(10, resp.getBody().getMaxSteps());
        assertEquals("SEQUENTIAL", resp.getBody().getExecutionMode());
    }

    // ── getStatus ─────────────────────────────────────────────────────────

    @Test
    void getStatus_returnsStatus() {
        ResponseEntity<ReActAgentConfigController.StatusDto> resp = controller.getStatus();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getTestCaseCount());
        assertEquals(0, resp.getBody().getSuiteCount());
    }

    // ── createTestCase ────────────────────────────────────────────────────

    @Test
    void createTestCase_assignsIdAndReturns200() {
        ReActAgentConfigController.EvalTestCaseDto tc = ReActAgentConfigController.EvalTestCaseDto.builder()
                .name("My Test")
                .query("What is X?")
                .factSheetId(1L)
                .build();

        ResponseEntity<ReActAgentConfigController.EvalTestCaseDto> resp = controller.createTestCase(tc);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().getId());
        assertEquals("My Test", resp.getBody().getName());
    }

    @Test
    void createTestCase_withProvidedId_keepsId() {
        ReActAgentConfigController.EvalTestCaseDto tc = ReActAgentConfigController.EvalTestCaseDto.builder()
                .id("custom-id")
                .name("My Test")
                .build();

        ResponseEntity<ReActAgentConfigController.EvalTestCaseDto> resp = controller.createTestCase(tc);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("custom-id", resp.getBody().getId());
    }

    // ── getAllTestCases ───────────────────────────────────────────────────

    @Test
    void getAllTestCases_noFilter_returnsAll() {
        controller.createTestCase(ReActAgentConfigController.EvalTestCaseDto.builder()
                .name("TC1").factSheetId(1L).build());
        controller.createTestCase(ReActAgentConfigController.EvalTestCaseDto.builder()
                .name("TC2").factSheetId(2L).build());

        ResponseEntity<List<ReActAgentConfigController.EvalTestCaseDto>> resp =
                controller.getAllTestCases(null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().size() >= 2);
    }

    @Test
    void getAllTestCases_withFactSheetId_filtersById() {
        controller.createTestCase(ReActAgentConfigController.EvalTestCaseDto.builder()
                .name("TC1").factSheetId(1L).build());
        controller.createTestCase(ReActAgentConfigController.EvalTestCaseDto.builder()
                .name("TC2").factSheetId(99L).build());

        ResponseEntity<List<ReActAgentConfigController.EvalTestCaseDto>> resp =
                controller.getAllTestCases(1L, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().stream().allMatch(tc -> tc.getFactSheetId().equals(1L)));
    }

    // ── getTestCase ───────────────────────────────────────────────────────

    @Test
    void getTestCase_found_returns200() {
        ReActAgentConfigController.EvalTestCaseDto created = controller.createTestCase(
                ReActAgentConfigController.EvalTestCaseDto.builder().name("TC").build()).getBody();

        ResponseEntity<ReActAgentConfigController.EvalTestCaseDto> resp =
                controller.getTestCase(created.getId());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(created.getId(), resp.getBody().getId());
    }

    @Test
    void getTestCase_notFound_returns404() {
        ResponseEntity<ReActAgentConfigController.EvalTestCaseDto> resp =
                controller.getTestCase("nonexistent");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── updateTestCase ────────────────────────────────────────────────────

    @Test
    void updateTestCase_found_updates() {
        ReActAgentConfigController.EvalTestCaseDto created = controller.createTestCase(
                ReActAgentConfigController.EvalTestCaseDto.builder().name("Old").build()).getBody();

        ReActAgentConfigController.EvalTestCaseDto update =
                ReActAgentConfigController.EvalTestCaseDto.builder().name("New").build();

        ResponseEntity<ReActAgentConfigController.EvalTestCaseDto> resp =
                controller.updateTestCase(created.getId(), update);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("New", resp.getBody().getName());
    }

    @Test
    void updateTestCase_notFound_returns404() {
        ReActAgentConfigController.EvalTestCaseDto update =
                ReActAgentConfigController.EvalTestCaseDto.builder().name("New").build();

        ResponseEntity<ReActAgentConfigController.EvalTestCaseDto> resp =
                controller.updateTestCase("nonexistent", update);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── deleteTestCase ────────────────────────────────────────────────────

    @Test
    void deleteTestCase_removes() {
        ReActAgentConfigController.EvalTestCaseDto created = controller.createTestCase(
                ReActAgentConfigController.EvalTestCaseDto.builder().name("TC").build()).getBody();

        ResponseEntity<Void> resp = controller.deleteTestCase(created.getId());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getTestCase(created.getId()).getStatusCode());
    }

    // ── createSuite ───────────────────────────────────────────────────────

    @Test
    void createSuite_assignsIdAndReturns200() {
        ReActAgentConfigController.EvalSuiteDto suite =
                ReActAgentConfigController.EvalSuiteDto.builder().name("Suite A").build();

        ResponseEntity<ReActAgentConfigController.EvalSuiteDto> resp = controller.createSuite(suite);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().getId());
    }

    // ── addTestCaseToSuite ────────────────────────────────────────────────

    @Test
    void addTestCaseToSuite_addsMembership() {
        ReActAgentConfigController.EvalSuiteDto suite = controller.createSuite(
                ReActAgentConfigController.EvalSuiteDto.builder().name("Suite").build()).getBody();
        ReActAgentConfigController.EvalTestCaseDto tc = controller.createTestCase(
                ReActAgentConfigController.EvalTestCaseDto.builder().name("TC").build()).getBody();

        ResponseEntity<ReActAgentConfigController.EvalSuiteDto> resp =
                controller.addTestCaseToSuite(suite.getId(), tc.getId());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getTestCaseIds().contains(tc.getId()));
    }

    @Test
    void addTestCaseToSuite_suiteNotFound_returns404() {
        ReActAgentConfigController.EvalTestCaseDto tc = controller.createTestCase(
                ReActAgentConfigController.EvalTestCaseDto.builder().name("TC").build()).getBody();

        ResponseEntity<ReActAgentConfigController.EvalSuiteDto> resp =
                controller.addTestCaseToSuite("nonexistent", tc.getId());

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void addTestCaseToSuite_testCaseNotFound_returns400() {
        ReActAgentConfigController.EvalSuiteDto suite = controller.createSuite(
                ReActAgentConfigController.EvalSuiteDto.builder().name("Suite").build()).getBody();

        ResponseEntity<ReActAgentConfigController.EvalSuiteDto> resp =
                controller.addTestCaseToSuite(suite.getId(), "nonexistent-tc");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ── getEvaluationTypes ────────────────────────────────────────────────

    @Test
    void getEvaluationTypes_returnsNonEmptyList() {
        ResponseEntity<List<ReActAgentConfigController.EvaluationTypeDto>> resp =
                controller.getEvaluationTypes();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertFalse(resp.getBody().isEmpty());
        assertTrue(resp.getBody().stream()
                .anyMatch(t -> "RELEVANCY".equals(t.getType())));
    }

    // ── getMetricsForFactSheet ────────────────────────────────────────────

    @Test
    void getMetricsForFactSheet_returnsMetrics() {
        ResponseEntity<ReActAgentConfigController.FactSheetMetricsDto> resp =
                controller.getMetricsForFactSheet(1L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1L, resp.getBody().getFactSheetId());
        assertEquals(0.0, resp.getBody().getPassRate());
    }
}
