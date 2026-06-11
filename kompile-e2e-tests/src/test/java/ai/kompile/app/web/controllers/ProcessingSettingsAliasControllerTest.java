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

import ai.kompile.app.web.dto.ProcessingSettingsRequest;
import ai.kompile.app.web.dto.ProcessingSettingsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessingSettingsAliasControllerTest {

    @Mock
    private ProcessingSettingsController processingSettingsController;

    private ProcessingSettingsAliasController controller;

    private ProcessingSettingsResponse mockResponse;

    @BeforeEach
    void setUp() {
        controller = new ProcessingSettingsAliasController(processingSettingsController);
        mockResponse = new ProcessingSettingsResponse(
                2, 0, 0, true, 100, 10, 500, false, 80, 95, null, 4, 8, 0, 0);
    }

    @Test
    void getSettingsAliasReversed_delegatesToDelegate() {
        when(processingSettingsController.getSettings())
                .thenReturn(ResponseEntity.ok(mockResponse));

        ResponseEntity<ProcessingSettingsResponse> resp = controller.getSettingsAliasReversed();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(mockResponse, resp.getBody());
        verify(processingSettingsController).getSettings();
    }

    @Test
    void updateSettingsAliasReversed_delegatesToDelegate() {
        ProcessingSettingsRequest request = new ProcessingSettingsRequest(4, 200, 70, true);
        when(processingSettingsController.updateSettings(any()))
                .thenReturn(ResponseEntity.ok(mockResponse));

        ResponseEntity<ProcessingSettingsResponse> resp = controller.updateSettingsAliasReversed(request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(mockResponse, resp.getBody());
        verify(processingSettingsController).updateSettings(request);
    }

    @Test
    void getSettingsAliasHyphenated_delegatesToDelegate() {
        when(processingSettingsController.getSettings())
                .thenReturn(ResponseEntity.ok(mockResponse));

        ResponseEntity<ProcessingSettingsResponse> resp = controller.getSettingsAliasHyphenated();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(mockResponse, resp.getBody());
        verify(processingSettingsController).getSettings();
    }

    @Test
    void updateSettingsAliasHyphenated_delegatesToDelegate() {
        ProcessingSettingsRequest request = new ProcessingSettingsRequest(4, 200, 70, false);
        when(processingSettingsController.updateSettings(any()))
                .thenReturn(ResponseEntity.ok(mockResponse));

        ResponseEntity<ProcessingSettingsResponse> resp = controller.updateSettingsAliasHyphenated(request);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(mockResponse, resp.getBody());
        verify(processingSettingsController).updateSettings(request);
    }
}
