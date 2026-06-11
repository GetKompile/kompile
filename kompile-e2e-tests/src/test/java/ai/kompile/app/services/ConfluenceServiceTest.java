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

package ai.kompile.app.services;

import ai.kompile.app.web.dto.confluence.ConfluenceConnectionConfig;
import ai.kompile.app.web.dto.confluence.ConfluenceConnectionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfluenceServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private DocumentIngestService documentIngestService;

    private ObjectMapper objectMapper;
    private ConfluenceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ConfluenceService(restTemplate, objectMapper, documentIngestService);
    }

    // ── getConnectionStatus ────────────────────────────────────────────────────

    @Test
    void testGetConnectionStatus_initiallyDisconnected() {
        ConfluenceConnectionStatus status = service.getConnectionStatus();
        assertNotNull(status);
        assertFalse(status.isConnected());
    }

    // ── connect ───────────────────────────────────────────────────────────────

    @Test
    void testConnect_success() throws Exception {
        String userJson = "{\"displayName\": \"John Doe\", \"publicName\": \"johndoe\"}";
        ResponseEntity<String> userResponse = ResponseEntity.ok(userJson);

        // Server version check for deployment type
        ResponseEntity<String> serverInfo = ResponseEntity.ok("{\"version\": {\"major\": 8}}");

        when(restTemplate.exchange(
                contains("/rest/api/user/current"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(userResponse);

        when(restTemplate.exchange(
                contains("/rest/api/space"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(serverInfo);

        ConfluenceConnectionConfig config = new ConfluenceConnectionConfig();
        config.setBaseUrl("https://mycompany.atlassian.net");
        config.setEmail("user@example.com");
        config.setApiToken("secret-token");

        ConfluenceConnectionStatus status = service.connect(config);
        assertTrue(status.isConnected());
        assertEquals("John Doe", status.getDisplayName());
    }

    @Test
    void testConnect_authFailure() {
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8
        ));

        ConfluenceConnectionConfig config = new ConfluenceConnectionConfig();
        config.setBaseUrl("https://mycompany.atlassian.net");
        config.setEmail("user@example.com");
        config.setApiToken("bad-token");

        ConfluenceConnectionStatus status = service.connect(config);
        assertFalse(status.isConnected());
        assertNotNull(status.getErrorMessage());
    }

    @Test
    void testConnect_connectionError() {
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenThrow(new RuntimeException("Connection refused"));

        ConfluenceConnectionConfig config = new ConfluenceConnectionConfig();
        config.setBaseUrl("https://mycompany.atlassian.net");
        config.setEmail("user@example.com");
        config.setApiToken("token");

        ConfluenceConnectionStatus status = service.connect(config);
        assertFalse(status.isConnected());
        assertNotNull(status.getErrorMessage());
    }

    // ── disconnect ────────────────────────────────────────────────────────────

    @Test
    void testDisconnect_clearsState() throws Exception {
        // First connect successfully
        String userJson = "{\"displayName\": \"User\"}";
        when(restTemplate.exchange(
                contains("/rest/api/user/current"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok(userJson));

        ConfluenceConnectionConfig config = new ConfluenceConnectionConfig();
        config.setBaseUrl("https://test.atlassian.net");
        config.setEmail("u@e.com");
        config.setApiToken("t");
        service.connect(config);

        // Now disconnect
        service.disconnect();
        assertFalse(service.getConnectionStatus().isConnected());
    }

    @Test
    void testDisconnect_whenAlreadyDisconnected_noError() {
        // Should not throw
        assertDoesNotThrow(() -> service.disconnect());
        assertFalse(service.getConnectionStatus().isConnected());
    }

    // ── ConfluenceConnectionStatus builder ────────────────────────────────────

    @Test
    void testConnectionStatusBuilder() {
        ConfluenceConnectionStatus status = ConfluenceConnectionStatus.builder()
                .connected(true)
                .baseUrl("https://test.atlassian.net")
                .username("user@test.com")
                .displayName("Test User")
                .deploymentType("cloud")
                .build();

        assertTrue(status.isConnected());
        assertEquals("https://test.atlassian.net", status.getBaseUrl());
        assertEquals("Test User", status.getDisplayName());
        assertEquals("cloud", status.getDeploymentType());
    }

    @Test
    void testConnectionStatusBuilder_disconnected() {
        ConfluenceConnectionStatus status = ConfluenceConnectionStatus.builder()
                .connected(false)
                .errorMessage("Connection failed")
                .build();

        assertFalse(status.isConnected());
        assertEquals("Connection failed", status.getErrorMessage());
    }

    // ── ConfluenceConnectionConfig ────────────────────────────────────────────

    @Test
    void testConnectionConfig_settersAndGetters() {
        ConfluenceConnectionConfig config = new ConfluenceConnectionConfig();
        config.setBaseUrl("https://example.atlassian.net");
        config.setEmail("admin@example.com");
        config.setApiToken("secret");

        assertEquals("https://example.atlassian.net", config.getBaseUrl());
        assertEquals("admin@example.com", config.getEmail());
        assertEquals("secret", config.getApiToken());
    }
}
