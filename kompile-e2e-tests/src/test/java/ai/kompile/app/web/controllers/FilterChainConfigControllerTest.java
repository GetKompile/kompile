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

import ai.kompile.filterchain.config.FilterChainConfig;
import ai.kompile.filterchain.config.FilterConfig;
import ai.kompile.filterchain.service.FilterChainConfigService;
import ai.kompile.filterchain.service.FilterChainService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FilterChainConfigControllerTest {

    @Mock
    private FilterChainConfigService configService;

    @Mock
    private FilterChainService filterChainService;

    // ─── helper to build a minimal FilterChainConfig ────────────────────────

    private FilterChainConfig minimalConfig() {
        FilterChainConfig c = new FilterChainConfig();
        c.setEnabled(true);
        c.setFilters(new ArrayList<>());
        return c;
    }

    // ─── getConfig ────────────────────────────────────────────────────────────

    @Test
    void getConfig_whenServiceNull_returnsUnavailable() {
        FilterChainConfigController ctrl = new FilterChainConfigController(null, null);

        ResponseEntity<?> resp = ctrl.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("available")).isEqualTo(false);
    }

    @Test
    void getConfig_returnsConfiguration() {
        FilterChainConfig cfg = minimalConfig();
        when(configService.getConfiguration()).thenReturn(cfg);
        when(configService.getConfigFilePath()).thenReturn("/tmp/filter.json");
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("enabled")).isEqualTo(true);
    }

    // ─── updateConfig ─────────────────────────────────────────────────────────

    @Test
    void updateConfig_whenServiceNull_returns503() {
        FilterChainConfigController ctrl = new FilterChainConfigController(null, null);

        ResponseEntity<?> resp = ctrl.updateConfig(minimalConfig());

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void updateConfig_success_returnsOk() {
        FilterChainConfig cfg = minimalConfig();
        when(configService.updateConfiguration(any())).thenReturn(cfg);
        doNothing().when(filterChainService).refresh();
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, filterChainService);

        ResponseEntity<?> resp = ctrl.updateConfig(cfg);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
    }

    @Test
    void updateConfig_exception_returnsBadRequest() {
        when(configService.updateConfiguration(any())).thenThrow(new RuntimeException("parse error"));
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.updateConfig(minimalConfig());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── toggleEnabled ────────────────────────────────────────────────────────

    @Test
    void toggleEnabled_missingField_returnsBadRequest() {
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.toggleEnabled(Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void toggleEnabled_success_returnsOk() {
        FilterChainConfig cfg = minimalConfig();
        when(configService.setEnabled(true)).thenReturn(cfg);
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.toggleEnabled(Map.of("enabled", true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
    }

    // ─── getFilters ───────────────────────────────────────────────────────────

    @Test
    void getFilters_whenServiceNull_returns503() {
        FilterChainConfigController ctrl = new FilterChainConfigController(null, null);

        ResponseEntity<?> resp = ctrl.getFilters();

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void getFilters_returnsFiltersAndAvailable() {
        FilterConfig fc = new FilterConfig();
        fc.setId("f1");
        when(configService.getFilters()).thenReturn(List.of(fc));
        when(filterChainService.getAvailableFilters()).thenReturn(List.of());
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, filterChainService);

        ResponseEntity<?> resp = ctrl.getFilters();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> body = (Map<Object, Object>) resp.getBody();
        assertThat(body).containsKey("configuredFilters");
        assertThat(body).containsKey("availableFilters");
    }

    // ─── addFilter ────────────────────────────────────────────────────────────

    @Test
    void addFilter_success_returnsOk() {
        FilterConfig fc = new FilterConfig();
        fc.setId("new");
        FilterChainConfig cfg = minimalConfig();
        when(configService.addFilter(any())).thenReturn(cfg);
        doNothing().when(filterChainService).refresh();
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, filterChainService);

        ResponseEntity<?> resp = ctrl.addFilter(fc);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void addFilter_duplicate_returnsBadRequest() {
        FilterConfig fc = new FilterConfig();
        fc.setId("dup");
        when(configService.addFilter(any())).thenThrow(new IllegalArgumentException("dup filter"));
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.addFilter(fc);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── updateFilter ─────────────────────────────────────────────────────────

    @Test
    void updateFilter_success_returnsOk() {
        FilterConfig fc = new FilterConfig();
        fc.setId("f1");
        FilterChainConfig cfg = minimalConfig();
        when(configService.updateFilter(any())).thenReturn(cfg);
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.updateFilter("f1", fc);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateFilter_notFound_returnsBadRequest() {
        FilterConfig fc = new FilterConfig();
        fc.setId("ghost");
        when(configService.updateFilter(any())).thenThrow(new IllegalArgumentException("not found"));
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.updateFilter("ghost", fc);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── deleteFilter ─────────────────────────────────────────────────────────

    @Test
    void deleteFilter_success_returnsOk() {
        FilterChainConfig cfg = minimalConfig();
        when(configService.removeFilter("f1")).thenReturn(cfg);
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.deleteFilter("f1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deleteFilter_notFound_returnsNotFound() {
        when(configService.removeFilter("ghost")).thenThrow(new IllegalArgumentException("not found"));
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.deleteFilter("ghost");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── toggleFilter ─────────────────────────────────────────────────────────

    @Test
    void toggleFilter_success_returnsOk() {
        FilterChainConfig cfg = minimalConfig();
        when(configService.toggleFilter("f1")).thenReturn(cfg);
        // cfg.getFilter("f1") returns null naturally since the filter list is empty
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.toggleFilter("f1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
    }

    @Test
    void toggleFilter_notFound_returnsNotFound() {
        when(configService.toggleFilter("ghost")).thenThrow(new IllegalArgumentException("not found"));
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.toggleFilter("ghost");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── resetConfig ──────────────────────────────────────────────────────────

    @Test
    void resetConfig_returnsOk() {
        FilterChainConfig cfg = minimalConfig();
        when(configService.resetConfiguration()).thenReturn(cfg);
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, null);

        ResponseEntity<?> resp = ctrl.resetConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
    }

    // ─── getStatus ────────────────────────────────────────────────────────────

    @Test
    void getStatus_noServices_returnsModuleUnavailable() {
        FilterChainConfigController ctrl = new FilterChainConfigController(null, null);

        ResponseEntity<?> resp = ctrl.getStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("moduleAvailable")).isEqualTo(false);
    }

    @Test
    void getStatus_withServices_returnsDetails() {
        FilterChainConfig cfg = minimalConfig();
        when(configService.getConfiguration()).thenReturn(cfg);
        when(configService.getConfigFilePath()).thenReturn("/tmp/f.json");
        when(filterChainService.isEnabled()).thenReturn(true);
        when(filterChainService.getFilters()).thenReturn(List.of());
        FilterChainConfigController ctrl = new FilterChainConfigController(configService, filterChainService);

        ResponseEntity<?> resp = ctrl.getStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> body = (Map<Object, Object>) resp.getBody();
        assertThat(body.get("moduleAvailable")).isEqualTo(true);
        assertThat(body).containsKey("serviceActive");
    }
}
