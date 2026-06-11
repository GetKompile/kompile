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

import ai.kompile.app.config.AppIndexConfig;
import ai.kompile.app.services.AppIndexConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FrontendConfigControllerTest {

    @Mock
    private AppIndexConfigService configService;

    private FrontendConfigController buildController(AppIndexConfigService svc) {
        FrontendConfigController ctrl = new FrontendConfigController(svc);
        ReflectionTestUtils.setField(ctrl, "fallbackAppTitle", "Kompile RAG Console");
        ReflectionTestUtils.setField(ctrl, "applicationName", "kompile-rag-app");
        return ctrl;
    }

    @Test
    void getConfig_noConfigService_returnsFallbackTitle() {
        FrontendConfigController ctrl = buildController(null);

        ResponseEntity<Map<String, Object>> resp = ctrl.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body.get("appTitle")).isEqualTo("Kompile RAG Console");
        assertThat(body.get("applicationName")).isEqualTo("kompile-rag-app");
    }

    @Test
    void getConfig_configServiceReturnsNull_returnsFallback() {
        when(configService.getActualConfiguration()).thenReturn(null);
        FrontendConfigController ctrl = buildController(configService);

        ResponseEntity<Map<String, Object>> resp = ctrl.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("appTitle")).isEqualTo("Kompile RAG Console");
    }

    @Test
    void getConfig_configServiceReturnsTitleBlank_returnsFallback() {
        AppIndexConfig cfg = new AppIndexConfig();
        cfg.setAppTitle("   ");
        when(configService.getActualConfiguration()).thenReturn(cfg);
        FrontendConfigController ctrl = buildController(configService);

        ResponseEntity<Map<String, Object>> resp = ctrl.getConfig();

        assertThat(resp.getBody().get("appTitle")).isEqualTo("Kompile RAG Console");
    }

    @Test
    void getConfig_configServiceReturnsCustomTitle_returnsCustomTitle() {
        AppIndexConfig cfg = new AppIndexConfig();
        cfg.setAppTitle("My Custom RAG App");
        when(configService.getActualConfiguration()).thenReturn(cfg);
        FrontendConfigController ctrl = buildController(configService);

        ResponseEntity<Map<String, Object>> resp = ctrl.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("appTitle")).isEqualTo("My Custom RAG App");
    }
}
