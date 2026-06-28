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

import ai.kompile.app.web.dto.reactagent.EvalSuiteDto;
import ai.kompile.app.web.dto.reactagent.EvalTestCaseDto;
import ai.kompile.app.web.dto.reactagent.EvaluationTypeDto;
import ai.kompile.app.web.dto.reactagent.FactSheetMetricsDto;
import ai.kompile.app.web.dto.reactagent.ReActConfigDto;
import ai.kompile.app.web.dto.reactagent.StatusDto;
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
        ResponseEntity<ReActConfigDto> resp = controller.getConfig();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(10, resp.getBody().getMaxSteps());
        assertEquals("SEQUENTIAL", resp.getBody().getExecutionMode());
    }

    // ── getStatus ─────────────────────────────────────────────────────────

    @Test
    void getStatus_returnsStatus() {
        ResponseEntity<StatusDto> resp = controller.getStatus();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(0, resp.getBody().getTestCaseCount());
        assertEquals(0, resp.getBody().getSuiteCount());
    }

    // ── createTestCase ────────────────────────────────────────────────────

    @Test
    void createTestCase_assignsIdAndReturns200() {
        EvalTestCaseDto tc = EvalTestCaseDto.builder()
                .name("My Test")
                .query("What is X?")
                .factSheetId(1L)
                .build();

        ResponseEntity<EvalTestCaseDto> resp = controller.createTestCase(tc);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().getId());
        assertEquals("My Test", resp.getBody().getName());
    }

    @Test
    void createTestCase_withProvidedId_keepsId() {
        EvalTestCaseDto tc = EvalTestCaseDto.builder()
                .id("custom-id")
                .name("My Test")
                .build();

        ResponseEntity<EvalTestCaseDto> resp = controller.createTestCase(tc);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("custom-id", resp.getBody().getId());
    }

    // ── getAllTestCases ───────────────────────────────────────────────────

    @Test
    void getAllTestCases_noFilter_returnsAll() {
        controller.createTestCase(EvalTestCaseDto.builder()
                .name("TC1").factSheetId(1L).build());
        controller.createTestCase(EvalTestCaseDto.builder()
                .name("TC2").factSheetId(2L).build());

        ResponseEntity<List<EvalTestCaseDto>> resp =
                controller.getAllTestCases(null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().size() >= 2);
    }

    @Test
    void getAllTestCases_withFactSheetId_filtersById() {
        controller.createTestCase(EvalTestCaseDto.builder()
                .name("TC1").factSheetId(1L).build());
        controller.createTestCase(EvalTestCaseDto.builder()
                .name("TC2").factSheetId(99L).build());

        ResponseEntity<List<EvalTestCaseDto>> resp =
                controller.getAllTestCases(1L, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().stream().allMatch(tc -> tc.getFactSheetId().equals(1L)));
    }

    // ── getTestCase ───────────────────────────────────────────────────────

    @Test
    void getTestCase_found_returns200() {
        EvalTestCaseDto created = controller.createTestCase(
                EvalTestCaseDto.builder().name("TC").build()).getBody();

        ResponseEntity<EvalTestCaseDto> resp =
                controller.getTestCase(created.getId());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(created.getId(), resp.getBody().getId());
    }

    @Test
    void getTestCase_notFound_returns404() {
        ResponseEntity<EvalTestCaseDto> resp =
                controller.getTestCase("nonexistent");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── updateTestCase ────────────────────────────────────────────────────

    @Test
    void updateTestCase_found_updates() {
        EvalTestCaseDto created = controller.createTestCase(
                EvalTestCaseDto.builder().name("Old").build()).getBody();

        EvalTestCaseDto update =
                EvalTestCaseDto.builder().name("New").build();

        ResponseEntity<EvalTestCaseDto> resp =
                controller.updateTestCase(created.getId(), update);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("New", resp.getBody().getName());
    }

    @Test
    void updateTestCase_notFound_returns404() {
        EvalTestCaseDto update =
                EvalTestCaseDto.builder().name("New").build();

        ResponseEntity<EvalTestCaseDto> resp =
                controller.updateTestCase("nonexistent", update);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── deleteTestCase ────────────────────────────────────────────────────

    @Test
    void deleteTestCase_removes() {
        EvalTestCaseDto created = controller.createTestCase(
                EvalTestCaseDto.builder().name("TC").build()).getBody();

        ResponseEntity<Void> resp = controller.deleteTestCase(created.getId());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getTestCase(created.getId()).getStatusCode());
    }

    // ── createSuite ───────────────────────────────────────────────────────

    @Test
    void createSuite_assignsIdAndReturns200() {
        EvalSuiteDto suite =
                EvalSuiteDto.builder().name("Suite A").build();

        ResponseEntity<EvalSuiteDto> resp = controller.createSuite(suite);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().getId());
    }

    // ── addTestCaseToSuite ────────────────────────────────────────────────

    @Test
    void addTestCaseToSuite_addsMembership() {
        EvalSuiteDto suite = controller.createSuite(
                EvalSuiteDto.builder().name("Suite").build()).getBody();
        EvalTestCaseDto tc = controller.createTestCase(
                EvalTestCaseDto.builder().name("TC").build()).getBody();

        ResponseEntity<EvalSuiteDto> resp =
                controller.addTestCaseToSuite(suite.getId(), tc.getId());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().getTestCaseIds().contains(tc.getId()));
    }

    @Test
    void addTestCaseToSuite_suiteNotFound_returns404() {
        EvalTestCaseDto tc = controller.createTestCase(
                EvalTestCaseDto.builder().name("TC").build()).getBody();

        ResponseEntity<EvalSuiteDto> resp =
                controller.addTestCaseToSuite("nonexistent", tc.getId());

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void addTestCaseToSuite_testCaseNotFound_returns400() {
        EvalSuiteDto suite = controller.createSuite(
                EvalSuiteDto.builder().name("Suite").build()).getBody();

        ResponseEntity<EvalSuiteDto> resp =
                controller.addTestCaseToSuite(suite.getId(), "nonexistent-tc");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ── getEvaluationTypes ────────────────────────────────────────────────

    @Test
    void getEvaluationTypes_returnsNonEmptyList() {
        ResponseEntity<List<EvaluationTypeDto>> resp =
                controller.getEvaluationTypes();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertFalse(resp.getBody().isEmpty());
        assertTrue(resp.getBody().stream()
                .anyMatch(t -> "RELEVANCY".equals(t.getType())));
    }

    // ── getMetricsForFactSheet ────────────────────────────────────────────

    @Test
    void getMetricsForFactSheet_returnsMetrics() {
        ResponseEntity<FactSheetMetricsDto> resp =
                controller.getMetricsForFactSheet(1L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1L, resp.getBody().getFactSheetId());
        assertEquals(0.0, resp.getBody().getPassRate());
    }
}
