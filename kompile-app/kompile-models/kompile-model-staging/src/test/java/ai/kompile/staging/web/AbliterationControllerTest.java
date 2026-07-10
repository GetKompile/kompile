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

package ai.kompile.staging.web;

import ai.kompile.staging.training.AbliterationService;
import ai.kompile.staging.web.dto.AbliterationRequest;
import ai.kompile.staging.web.dto.AbliterationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbliterationControllerTest {

    @Mock
    private AbliterationService abliterationService;

    private AbliterationController controller;

    @BeforeEach
    void setUp() {
        controller = new AbliterationController(abliterationService);
    }

    @Test
    void applyDelegatesValidRequest() {
        AbliterationRequest request = AbliterationRequest.builder()
                .modelId("base")
                .build();
        AbliterationResponse expected = AbliterationResponse.builder()
                .success(true)
                .outputModelId("base-abliterated")
                .build();
        when(abliterationService.applyAbliteration(request)).thenReturn(expected);

        ResponseEntity<AbliterationResponse> response = controller.apply(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
        verify(abliterationService).applyAbliteration(request);
    }

    @Test
    void applyRejectsMissingModelId() {
        AbliterationRequest request = AbliterationRequest.builder().build();

        ResponseEntity<AbliterationResponse> response = controller.apply(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("modelId is required", response.getBody().getError());
        verifyNoInteractions(abliterationService);
    }
}
