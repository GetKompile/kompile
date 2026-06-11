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

import ai.kompile.app.services.GraphExtractionConfigService;
import ai.kompile.app.services.GraphExtractionConfigService.GraphExtractionConfig;
import ai.kompile.app.services.GraphSchemaPresetService;
import ai.kompile.orchestrator.api.LlmIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphExtractionControllerTest {

    @Mock
    private GraphExtractionConfigService configService;

    @Mock
    private LlmIntegrationService llmIntegrationService;

    @Mock
    private GraphSchemaPresetService schemaPresetService;

    private GraphExtractionController controller;

    @BeforeEach
    void setUp() {
        controller = new GraphExtractionController(configService, llmIntegrationService, schemaPresetService);
    }

    private GraphExtractionConfig config(Boolean enabled) {
        GraphExtractionConfig c = new GraphExtractionConfig();
        c.enabled = enabled;
        c.batchSize = 10;
        c.schemaEnforcement = "LENIENT";
        return c;
    }

    // ─── getConfig ────────────────────────────────────────────────────────────

    @Test
    void getConfig_returnsOk() {
        when(configService.getConfig()).thenReturn(config(true));

        ResponseEntity<GraphExtractionConfig> resp = controller.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().enabled).isTrue();
    }

    // ─── updateConfig ─────────────────────────────────────────────────────────

    @Test
    void updateConfig_returnsUpdated() {
        GraphExtractionConfig updated = config(false);
        when(configService.updateConfig(any())).thenReturn(updated);

        ResponseEntity<GraphExtractionConfig> resp = controller.updateConfig(config(false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().enabled).isFalse();
    }

    // ─── patchConfig ──────────────────────────────────────────────────────────

    @Test
    void patchConfig_delegatesToUpdateConfig() {
        GraphExtractionConfig patched = config(true);
        when(configService.updateConfig(any())).thenReturn(patched);

        ResponseEntity<GraphExtractionConfig> resp = controller.patchConfig(config(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── resetConfig ──────────────────────────────────────────────────────────

    @Test
    void resetConfig_returnsDefaults() {
        GraphExtractionConfig defaults = config(false);
        when(configService.resetToDefaults()).thenReturn(defaults);

        ResponseEntity<GraphExtractionConfig> resp = controller.resetConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── toggleEnabled ────────────────────────────────────────────────────────

    @Test
    void toggleEnabled_enabledToDisabled() {
        when(configService.getConfig()).thenReturn(config(true));
        GraphExtractionConfig toggled = config(false);
        when(configService.updateConfig(any())).thenReturn(toggled);

        ResponseEntity<GraphExtractionConfig> resp = controller.toggleEnabled();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().enabled).isFalse();
    }

    @Test
    void toggleEnabled_disabledToEnabled() {
        when(configService.getConfig()).thenReturn(config(false));
        GraphExtractionConfig toggled = config(true);
        when(configService.updateConfig(any())).thenReturn(toggled);

        ResponseEntity<GraphExtractionConfig> resp = controller.toggleEnabled();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().enabled).isTrue();
    }

    // ─── getStatus ────────────────────────────────────────────────────────────

    @Test
    void getStatus_returnsEnabledState() {
        when(configService.getConfig()).thenReturn(config(true));

        ResponseEntity<Map<String, Object>> resp = controller.getStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("enabled")).isEqualTo(true);
        assertThat(resp.getBody()).containsKey("batchSize");
    }

    // ─── getSchemaModes ───────────────────────────────────────────────────────

    @Test
    void getSchemaModes_returnsThreeModes() {
        ResponseEntity<List<Map<String, String>>> resp = controller.getSchemaModes();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(3);
        List<String> values = resp.getBody().stream().map(m -> m.get("value")).toList();
        assertThat(values).containsExactlyInAnyOrder("NONE", "LENIENT", "STRICT");
    }

    // ─── getSuggestedEntityTypes ──────────────────────────────────────────────

    @Test
    void getSuggestedEntityTypes_returnsNonEmptyList() {
        ResponseEntity<List<String>> resp = controller.getSuggestedEntityTypes();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody()).contains("PERSON", "ORGANIZATION");
    }

    // ─── getSuggestedRelationshipTypes ───────────────────────────────────────

    @Test
    void getSuggestedRelationshipTypes_returnsNonEmptyList() {
        ResponseEntity<List<String>> resp = controller.getSuggestedRelationshipTypes();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody()).contains("WORKS_AT", "LOCATED_IN");
    }

    // ─── getModelProviders ────────────────────────────────────────────────────

    @Test
    void getModelProviders_noLlmService_returnsDefaultOnly() {
        GraphExtractionController ctrl = new GraphExtractionController(configService, null, null);

        ResponseEntity<List<Map<String, Object>>> resp = ctrl.getModelProviders();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).get("id")).isEqualTo("default");
    }

    @Test
    void getModelProviders_withLlmService_returnsDefaultPlusProviders() {
        when(llmIntegrationService.getAllProviders()).thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> resp = controller.getModelProviders();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1); // at least "default"
    }

    // ─── listSchemaPresets ────────────────────────────────────────────────────

    @Test
    void listSchemaPresets_noService_returnsEmptyList() {
        GraphExtractionController ctrl = new GraphExtractionController(configService, null, null);

        ResponseEntity<?> resp = ctrl.listSchemaPresets();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody()).isEmpty();
    }

    @Test
    void listSchemaPresets_withService_returnsPresets() {
        when(schemaPresetService.listPresets()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.listSchemaPresets();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── getSchemaPreset ──────────────────────────────────────────────────────

    @Test
    void getSchemaPreset_noService_returnsNotFound() {
        GraphExtractionController ctrl = new GraphExtractionController(configService, null, null);

        ResponseEntity<?> resp = ctrl.getSchemaPreset("p1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getSchemaPreset_notFound_returnsNotFound() {
        when(schemaPresetService.getPreset("ghost")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getSchemaPreset("ghost");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── applySchemaPreset ────────────────────────────────────────────────────

    @Test
    void applySchemaPreset_noService_returnsBadRequest() {
        GraphExtractionController ctrl = new GraphExtractionController(configService, null, null);

        ResponseEntity<?> resp = ctrl.applySchemaPreset("p1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void applySchemaPreset_presetNotFound_returnsNotFound() {
        when(schemaPresetService.getPresetTypeNames("ghost")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.applySchemaPreset("ghost");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void applySchemaPreset_success_returnsOk() {
        Map<String, List<String>> typeNames = Map.of(
                "entityTypes", List.of("PERSON"),
                "relationshipTypes", List.of("WORKS_AT"));
        when(schemaPresetService.getPresetTypeNames("domain")).thenReturn(Optional.of(typeNames));
        GraphExtractionConfig updated = config(true);
        when(configService.updateConfig(any())).thenReturn(updated);

        ResponseEntity<?> resp = controller.applySchemaPreset("domain");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("presetId")).isEqualTo("domain");
    }
}
