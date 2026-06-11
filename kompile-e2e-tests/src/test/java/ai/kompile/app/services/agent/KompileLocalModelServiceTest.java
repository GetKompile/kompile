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

package ai.kompile.app.services.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link KompileLocalModelService} — discovery, connection state, disconnect,
 * and getStatus behaviour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KompileLocalModelServiceTest {

    @Mock
    private AgentRegistryService agentRegistryService;

    private KompileLocalModelService service;

    @BeforeEach
    void setUp() throws Exception {
        // Stub out registry calls to avoid side effects
        when(agentRegistryService.getAgent(anyString())).thenReturn(Optional.empty());
        doNothing().when(agentRegistryService).registerAgent(any());
        when(agentRegistryService.unregisterAgent(anyString())).thenReturn(false);

        service = new KompileLocalModelService(agentRegistryService);

        // Inject a non-existent staging URL so network calls fail immediately
        var field = KompileLocalModelService.class.getDeclaredField("stagingUrl");
        field.setAccessible(true);
        field.set(service, "http://localhost:19990");
    }

    // ── initial state ────────────────────────────────────────────────────────────

    @Test
    void initialState_notConnected() {
        assertFalse(service.isConnected());
        assertNull(service.getCurrentModelId());
        assertEquals("http://localhost:19990", service.getStagingUrl());
    }

    // ── getStatus ────────────────────────────────────────────────────────────────

    @Test
    void getStatus_initialState_reflectsNotConnected() {
        Map<String, Object> status = service.getStatus();
        assertNotNull(status);
        assertFalse((Boolean) status.get("connected"));
        assertFalse((Boolean) status.get("modelLoaded"));
        assertEquals("Not connected", status.get("message"));
    }

    @Test
    void getStatus_containsAllExpectedKeys() {
        Map<String, Object> status = service.getStatus();
        assertTrue(status.containsKey("connected"));
        assertTrue(status.containsKey("stagingUrl"));
        assertTrue(status.containsKey("modelId"));
        assertTrue(status.containsKey("modelLoaded"));
        assertTrue(status.containsKey("agentRegistered"));
        assertTrue(status.containsKey("message"));
    }

    // ── discoverAndRegister — unreachable host ────────────────────────────────────

    @Test
    void discoverAndRegister_unreachableHost_returnsFalse() {
        Map<String, Object> result = service.discoverAndRegister();
        assertNotNull(result);
        assertFalse((Boolean) result.get("success"),
                "Discovery should fail when staging is unreachable");
        assertTrue(result.containsKey("message"));
        assertFalse(service.isConnected());
    }

    // ── connectTo ────────────────────────────────────────────────────────────────

    @Test
    void connectTo_updatesUrlAndAttempsDiscovery() {
        service.connectTo("http://localhost:19991");
        assertEquals("http://localhost:19991", service.getStagingUrl());
        // Discovery will fail since no server, but url should be updated
        assertFalse(service.isConnected());
    }

    // ── disconnect ───────────────────────────────────────────────────────────────

    @Test
    void disconnect_whenNotConnected_doesNotThrow() {
        assertDoesNotThrow(() -> service.disconnect());
        assertFalse(service.isConnected());
        verify(agentRegistryService, atLeastOnce()).unregisterAgent("kompile-local");
    }

    @Test
    void disconnect_resetsState() {
        // Simulate being connected by using reflection
        try {
            var connectedField = KompileLocalModelService.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.setBoolean(service, true);

            var modelIdField = KompileLocalModelService.class.getDeclaredField("currentModelId");
            modelIdField.setAccessible(true);
            modelIdField.set(service, "test-model");
        } catch (Exception e) {
            fail("Failed to set up test state: " + e.getMessage());
        }

        assertTrue(service.isConnected());
        assertEquals("test-model", service.getCurrentModelId());

        service.disconnect();

        assertFalse(service.isConnected());
        assertNull(service.getCurrentModelId());
    }

    @Test
    void getStatus_afterDisconnect_showsNotConnected() {
        service.disconnect();
        Map<String, Object> status = service.getStatus();
        assertFalse((Boolean) status.get("connected"));
        assertFalse((Boolean) status.get("modelLoaded"));
        assertEquals("Not connected", status.get("message"));
    }

    // ── getStatus — connected with model ─────────────────────────────────────────

    @Test
    void getStatus_connectedWithModel_showsConnectedMessage() {
        try {
            var connectedField = KompileLocalModelService.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.setBoolean(service, true);

            var modelIdField = KompileLocalModelService.class.getDeclaredField("currentModelId");
            modelIdField.setAccessible(true);
            modelIdField.set(service, "llama3");
        } catch (Exception e) {
            fail("Failed to set up test state: " + e.getMessage());
        }

        Map<String, Object> status = service.getStatus();
        assertTrue((Boolean) status.get("connected"));
        assertTrue((Boolean) status.get("modelLoaded"));
        assertEquals("llama3", status.get("modelId"));
        assertTrue(((String) status.get("message")).contains("llama3"));
    }

    @Test
    void getStatus_connectedWithoutModel_showsConnectedNoModel() {
        try {
            var connectedField = KompileLocalModelService.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.setBoolean(service, true);
        } catch (Exception e) {
            fail("Failed to set up test state: " + e.getMessage());
        }

        Map<String, Object> status = service.getStatus();
        assertTrue((Boolean) status.get("connected"));
        assertFalse((Boolean) status.get("modelLoaded"));
        assertEquals("Connected but no model loaded", status.get("message"));
    }
}
