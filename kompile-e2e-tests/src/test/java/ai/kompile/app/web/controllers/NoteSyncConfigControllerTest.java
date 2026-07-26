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

import ai.kompile.app.sync.config.NoteSyncConfig;
import ai.kompile.app.sync.config.NoteSyncConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteSyncConfigControllerTest {

    @Mock
    private NoteSyncConfigService configService;

    private NoteSyncConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new NoteSyncConfigController(configService);
    }

    private NoteSyncConfig makeConfig() {
        return new NoteSyncConfig();
    }

    @Test
    void getConfig_returnsCurrentConfig() {
        NoteSyncConfig config = makeConfig();
        when(configService.getConfiguration()).thenReturn(config);

        ResponseEntity<NoteSyncConfigController.NoteSyncConfigResponse> resp =
                controller.getConfig();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(config.getNotionEnabled(), resp.getBody().notionEnabled());
    }

    @Test
    void updateConfig_returnsUpdatedConfig() {
        NoteSyncConfigController.NoteSyncConfigUpdateRequest update =
                new NoteSyncConfigController.NoteSyncConfigUpdateRequest(
                        false, null, null, true, false, true, 30_000L);
        NoteSyncConfig updated = makeConfig();
        when(configService.updateConfiguration(any())).thenReturn(updated);

        ResponseEntity<NoteSyncConfigController.NoteSyncConfigResponse> resp =
                controller.updateConfig(update);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        verify(configService).updateConfiguration(any(NoteSyncConfig.class));
    }

    @Test
    void resetConfig_returnsResetConfig() {
        NoteSyncConfig reset = makeConfig();
        when(configService.resetConfiguration()).thenReturn(reset);

        ResponseEntity<NoteSyncConfigController.NoteSyncConfigResponse> resp =
                controller.resetConfig();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        verify(configService).resetConfiguration();
    }
}
