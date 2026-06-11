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

import ai.kompile.core.guardrails.GuardrailCategory;
import ai.kompile.core.guardrails.GuardrailService;
import ai.kompile.core.guardrails.InputGuardrail;
import ai.kompile.core.guardrails.OutputGuardrail;
import ai.kompile.guardrails.GuardrailsProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuardrailsConfigControllerTest {

    @Mock
    private GuardrailService guardrailService;

    // ─── getConfig ────────────────────────────────────────────────────────────

    @Test
    void getConfig_whenPropertiesNull_returnsUnavailable() {
        GuardrailsConfigController ctrl = new GuardrailsConfigController(null, null);

        ResponseEntity<Map<String, Object>> resp = ctrl.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("available")).isEqualTo(false);
    }

    @Test
    void getConfig_withProperties_returnsConfig() {
        GuardrailsProperties props = new GuardrailsProperties();
        props.setEnabled(true);
        GuardrailsConfigController ctrl = new GuardrailsConfigController(props, null);

        ResponseEntity<Map<String, Object>> resp = ctrl.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("available")).isEqualTo(true);
        assertThat(resp.getBody().get("enabled")).isEqualTo(true);
        assertThat(resp.getBody()).containsKey("input");
        assertThat(resp.getBody()).containsKey("output");
    }

    // ─── updateConfig ─────────────────────────────────────────────────────────

    @Test
    void updateConfig_whenPropertiesNull_returns503() {
        GuardrailsConfigController ctrl = new GuardrailsConfigController(null, null);

        ResponseEntity<Map<String, Object>> resp = ctrl.updateConfig(Map.of());

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void updateConfig_updatesEnabledField() {
        GuardrailsProperties props = new GuardrailsProperties();
        props.setEnabled(false);
        GuardrailsConfigController ctrl = new GuardrailsConfigController(props, null);

        ResponseEntity<Map<String, Object>> resp = ctrl.updateConfig(Map.of("enabled", true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(props.isEnabled()).isTrue();
    }

    @Test
    void updateConfig_updatesMaxRetries() {
        GuardrailsProperties props = new GuardrailsProperties();
        GuardrailsConfigController ctrl = new GuardrailsConfigController(props, null);

        ctrl.updateConfig(Map.of("maxRetries", 5));

        assertThat(props.getMaxRetries()).isEqualTo(5);
    }

    @Test
    void updateConfig_updatesInputPromptInjection() {
        GuardrailsProperties props = new GuardrailsProperties();
        GuardrailsConfigController ctrl = new GuardrailsConfigController(props, null);
        Map<String, Object> input = Map.of("promptInjection", Map.of("enabled", true, "threshold", 0.9));

        ctrl.updateConfig(Map.of("input", input));

        assertThat(props.getInput().getPromptInjection().isEnabled()).isTrue();
        assertThat(props.getInput().getPromptInjection().getThreshold()).isEqualTo(0.9);
    }

    // ─── getAvailableGuardrails ───────────────────────────────────────────────

    @Test
    void getAvailableGuardrails_noService_returnsUnavailable() {
        GuardrailsConfigController ctrl = new GuardrailsConfigController(null, null);

        ResponseEntity<Map<String, Object>> resp = ctrl.getAvailableGuardrails();

        assertThat(resp.getBody().get("available")).isEqualTo(false);
    }

    @Test
    void getAvailableGuardrails_withService_returnsGuardrails() {
        InputGuardrail inputGuardrail = mock(InputGuardrail.class);
        when(inputGuardrail.getName()).thenReturn("prompt-injection");
        when(inputGuardrail.getCategories()).thenReturn(new GuardrailCategory[]{GuardrailCategory.PROMPT_INJECTION});
        when(inputGuardrail.getPriority()).thenReturn(10);
        when(inputGuardrail.requiresLlm()).thenReturn(false);

        OutputGuardrail outputGuardrail = mock(OutputGuardrail.class);
        when(outputGuardrail.getName()).thenReturn("hallucination");
        when(outputGuardrail.getCategories()).thenReturn(new GuardrailCategory[]{GuardrailCategory.HALLUCINATION});
        when(outputGuardrail.getPriority()).thenReturn(5);
        when(outputGuardrail.requiresLlm()).thenReturn(true);
        when(outputGuardrail.supportsRetry()).thenReturn(true);

        when(guardrailService.getInputGuardrails()).thenReturn(List.of(inputGuardrail));
        when(guardrailService.getOutputGuardrails()).thenReturn(List.of(outputGuardrail));

        GuardrailsConfigController ctrl = new GuardrailsConfigController(null, guardrailService);

        ResponseEntity<Map<String, Object>> resp = ctrl.getAvailableGuardrails();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("available")).isEqualTo(true);
        assertThat((List<?>) resp.getBody().get("inputGuardrails")).hasSize(1);
        assertThat((List<?>) resp.getBody().get("outputGuardrails")).hasSize(1);
    }

    // ─── toggleGuardrails ─────────────────────────────────────────────────────

    @Test
    void toggleGuardrails_whenPropertiesNull_returns503() {
        GuardrailsConfigController ctrl = new GuardrailsConfigController(null, null);

        ResponseEntity<Map<String, Object>> resp = ctrl.toggleGuardrails(Map.of("enabled", true));

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void toggleGuardrails_setsEnabled() {
        GuardrailsProperties props = new GuardrailsProperties();
        props.setEnabled(false);
        GuardrailsConfigController ctrl = new GuardrailsConfigController(props, null);

        ResponseEntity<Map<String, Object>> resp = ctrl.toggleGuardrails(Map.of("enabled", true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
        assertThat(resp.getBody().get("enabled")).isEqualTo(true);
        assertThat(props.isEnabled()).isTrue();
    }

    @Test
    void toggleGuardrails_noEnabledKey_doesNotChangeState() {
        GuardrailsProperties props = new GuardrailsProperties();
        props.setEnabled(true);
        GuardrailsConfigController ctrl = new GuardrailsConfigController(props, null);

        ctrl.toggleGuardrails(Map.of());

        assertThat(props.isEnabled()).isTrue();
    }
}
