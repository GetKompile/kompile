package ai.kompile.app.web.controllers;

import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KbConfigController")
class KbConfigControllerTest {

    @Mock
    private KbConfigManager kbConfigManager;

    private KbConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new KbConfigController(kbConfigManager);
    }

    // ── GET /api/kb-config ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/kb-config")
    class GetConfig {

        @Test
        @DisplayName("returns 200 with the map from currentAsMap()")
        void returnsCurrentConfig() {
            Map<String, Object> snapshot = KbConfig.defaults().toMap();
            when(kbConfigManager.currentAsMap()).thenReturn(snapshot);

            ResponseEntity<Map<String, Object>> response = controller.getConfig();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().containsKey("kbPslLearningRate"),
                    "response should contain kbPslLearningRate");
            assertTrue(response.getBody().containsKey("kbTrustLlmExtraction"),
                    "response should contain kbTrustLlmExtraction");
            verify(kbConfigManager).currentAsMap();
        }

        @Test
        @DisplayName("returns 500 when currentAsMap() throws")
        void returns500OnException() {
            when(kbConfigManager.currentAsMap()).thenThrow(new RuntimeException("disk error"));

            ResponseEntity<Map<String, Object>> response = controller.getConfig();

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    // ── GET /api/kb-config/defaults ───────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/kb-config/defaults")
    class GetDefaults {

        @Test
        @DisplayName("returns 200 with factory-default kb* keys")
        void returnsDefaults() {
            ResponseEntity<Map<String, Object>> response = controller.getDefaults();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            // Spot-check a few canonical keys from KbConfig.defaults()
            assertTrue(response.getBody().containsKey("kbPslLearningRate"));
            assertTrue(response.getBody().containsKey("kbLearningEnabled"));
            assertTrue(response.getBody().containsKey("kbPersonalEmailDomains"));
            // Verify default learning rate value
            assertEquals(0.1, (Double) response.getBody().get("kbPslLearningRate"), 1e-9);
            // Verifies no manager interaction needed for defaults
            verifyNoInteractions(kbConfigManager);
        }
    }

    // ── POST /api/kb-config ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/kb-config")
    class UpdateConfig {

        @Test
        @DisplayName("forwards body to update() and returns 200 with result")
        void forwardsUpdatesAndReturnsResult() throws IOException {
            Map<String, Object> updates = Map.of("kbPslLearningRate", 0.05, "kbLearningEnabled", false);
            Map<String, Object> result = KbConfig.defaults().toMap();
            when(kbConfigManager.update(updates)).thenReturn(result);

            ResponseEntity<?> response = controller.updateConfig(updates);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            verify(kbConfigManager).update(updates);
        }

        @Test
        @DisplayName("returns 500 with error message when update() throws IOException")
        void returns500WithMessageOnIOException() throws IOException {
            Map<String, Object> updates = Map.of("kbPslLearningRate", 0.05);
            when(kbConfigManager.update(any())).thenThrow(new IOException("disk full"));

            ResponseEntity<?> response = controller.updateConfig(updates);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertTrue(body.containsKey("error"), "body should contain 'error' key");
            assertEquals("disk full", body.get("error"));
        }

        @Test
        @DisplayName("accepts null body (no-op update)")
        void acceptsNullBody() throws IOException {
            Map<String, Object> result = KbConfig.defaults().toMap();
            when(kbConfigManager.update(null)).thenReturn(result);

            ResponseEntity<?> response = controller.updateConfig(null);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(kbConfigManager).update(null);
        }
    }
}
