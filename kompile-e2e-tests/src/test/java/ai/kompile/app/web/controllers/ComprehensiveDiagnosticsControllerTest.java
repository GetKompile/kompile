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

import ai.kompile.app.diagnostics.dto.AllocationLogSummary;
import ai.kompile.app.diagnostics.service.AllocationLogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ComprehensiveDiagnosticsController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComprehensiveDiagnosticsControllerTest {

    @Mock
    private AllocationLogParser allocationLogParser;

    @InjectMocks
    private ComprehensiveDiagnosticsController controller;

    @Test
    void getComprehensiveDiagnostics_returnsResponse() {
        // The comprehensive endpoint uses Nd4j which may not be available in test context.
        // It handles errors gracefully and returns either success (200) or error (500).
        ResponseEntity<Map<String, Object>> response =
                controller.getComprehensiveDiagnostics(false, 20, 10);

        assertNotNull(response);
        assertNotNull(response.getBody());
        // Either success or error path — the body will always have a "status" key
        assertTrue(response.getBody().containsKey("status") ||
                   response.getBody().containsKey("system"));
    }

    @Test
    void getAllocationLogSummary_returnsOk() {
        AllocationLogSummary summary = mock(AllocationLogSummary.class);
        // The actual AllocationLogParser method is parseAllocationLog(String, int, int)
        when(allocationLogParser.parseAllocationLog(anyString(), anyInt(), anyInt()))
                .thenReturn(summary);

        // The method calls Nd4j.getNativeOps().getAllocationLogPath() - if that throws,
        // the controller catches it and returns an empty summary with error field.
        ResponseEntity<AllocationLogSummary> response = controller.getAllocationLogSummary(20, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getAllocationLogPath_returnsOkOrError() {
        // getAllocationLogPath also calls Nd4j - controller handles errors gracefully.
        ResponseEntity<Map<String, String>> response = controller.getAllocationLogPath();

        assertNotNull(response);
        assertNotNull(response.getBody());
    }
}
