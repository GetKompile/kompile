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

import ai.kompile.testmilestone.service.TestMilestoneService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TestMilestoneController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestMilestoneControllerTest {

    @Mock
    private TestMilestoneService milestoneService;

    private TestMilestoneController buildController(TestMilestoneService svc) {
        return new TestMilestoneController(svc);
    }

    @Test
    void status_withService_returnsAvailable() {
        TestMilestoneController controller = buildController(milestoneService);

        ResponseEntity<Map<String, Object>> response = controller.status();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("available"));
    }

    @Test
    void status_withoutService_returnsUnavailable() {
        TestMilestoneController controller = buildController(null);

        ResponseEntity<Map<String, Object>> response = controller.status();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().get("available"));
    }

    @Test
    void summary_withService_returnsOk() {
        TestMilestoneController controller = buildController(milestoneService);
        when(milestoneService.getSummary()).thenReturn(Map.of("total", 10));

        ResponseEntity<Map<String, Object>> response = controller.summary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10, response.getBody().get("total"));
    }

    @Test
    void summary_withoutService_returnsUnavailable() {
        TestMilestoneController controller = buildController(null);

        ResponseEntity<Map<String, Object>> response = controller.summary();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(false, response.getBody().get("available"));
    }

    @Test
    void list_withoutService_returnsEmptyPage() {
        TestMilestoneController controller = buildController(null);

        ResponseEntity<?> response = controller.list(null, null, 0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(((Page<?>) response.getBody()).isEmpty());
    }

    @Test
    void list_withService_returnsOk() {
        TestMilestoneController controller = buildController(milestoneService);
        @SuppressWarnings("unchecked")
        Page<?> page = mock(Page.class);
        when(milestoneService.listMilestones(null, null, 0, 20)).thenReturn((Page) page);

        ResponseEntity<?> response = controller.list(null, null, 0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void get_withoutService_returnsOk() {
        TestMilestoneController controller = buildController(null);

        ResponseEntity<?> response = controller.get("milestone-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getByCommit_withoutService_returnsEmpty() {
        TestMilestoneController controller = buildController(null);

        ResponseEntity<List<Map<String, Object>>> response = controller.getByCommit("abc123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void getLatest_withoutService_returnsOk() {
        TestMilestoneController controller = buildController(null);

        ResponseEntity<?> response = controller.getLatest("main", "kompile-cli");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
