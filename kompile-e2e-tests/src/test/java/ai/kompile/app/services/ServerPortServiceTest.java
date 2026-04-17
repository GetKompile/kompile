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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ServerPortService}.
 * Verifies port resolution, base URL, and endpoint URL generation
 * without loading Spring context or ND4J.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServerPortService")
class ServerPortServiceTest {

    /**
     * Creates a ServerPortService with no WebServer context and a mocked Environment.
     */
    private ServerPortService createService(Environment environment) {
        return new ServerPortService(null, environment);
    }

    private Environment mockEnvironmentWithPort(String serverPort, String localPort) {
        Environment env = mock(Environment.class);
        lenient().when(env.getProperty("local.server.port")).thenReturn(localPort);
        lenient().when(env.getProperty("server.port", "8080")).thenReturn(serverPort != null ? serverPort : "8080");
        return env;
    }

    // =========================================================================
    // getActualPort
    // =========================================================================

    @Nested
    @DisplayName("getActualPort")
    class GetActualPort {

        @Test
        @DisplayName("returns configured port when no WebServer and no local.server.port")
        void returnsConfiguredPort() {
            Environment env = mockEnvironmentWithPort("9090", null);
            ServerPortService service = createService(env);

            assertEquals(9090, service.getActualPort());
        }

        @Test
        @DisplayName("returns default 8080 when no port is configured")
        void returnsDefault8080() {
            Environment env = mockEnvironmentWithPort("8080", null);
            ServerPortService service = createService(env);

            assertEquals(8080, service.getActualPort());
        }

        @Test
        @DisplayName("prefers local.server.port over configured port")
        void prefersLocalServerPort() {
            Environment env = mockEnvironmentWithPort("8080", "9999");
            ServerPortService service = createService(env);

            assertEquals(9999, service.getActualPort());
        }

        @Test
        @DisplayName("falls back to configured port when local.server.port is empty")
        void fallsBackWhenLocalPortEmpty() {
            Environment env = mockEnvironmentWithPort("7070", "");
            ServerPortService service = createService(env);

            assertEquals(7070, service.getActualPort());
        }

        @Test
        @DisplayName("returns 8080 when configured port is 0")
        void returns8080WhenPortIsZero() {
            Environment env = mockEnvironmentWithPort("0", null);
            ServerPortService service = createService(env);

            assertEquals(8080, service.getActualPort());
        }

        @Test
        @DisplayName("handles non-numeric configured port gracefully")
        void handlesNonNumericPortGracefully() {
            Environment env = mockEnvironmentWithPort("not-a-number", null);
            ServerPortService service = createService(env);

            assertEquals(8080, service.getActualPort());
        }

        @Test
        @DisplayName("handles non-numeric local.server.port gracefully")
        void handlesNonNumericLocalPortGracefully() {
            Environment env = mockEnvironmentWithPort("9090", "abc");
            ServerPortService service = createService(env);

            // Falls through to configured port
            assertEquals(9090, service.getActualPort());
        }
    }

    // =========================================================================
    // URL generation
    // =========================================================================

    @Nested
    @DisplayName("URL generation")
    class UrlGeneration {

        @Test
        @DisplayName("getBaseUrl returns correct URL with configured port")
        void getBaseUrlWithConfiguredPort() {
            Environment env = mockEnvironmentWithPort("9090", null);
            ServerPortService service = createService(env);

            assertEquals("http://localhost:9090", service.getBaseUrl());
        }

        @Test
        @DisplayName("getBaseUrl returns correct URL with default port")
        void getBaseUrlWithDefaultPort() {
            Environment env = mockEnvironmentWithPort("8080", null);
            ServerPortService service = createService(env);

            assertEquals("http://localhost:8080", service.getBaseUrl());
        }

        @Test
        @DisplayName("getMcpApiUrl returns correct URL")
        void getMcpApiUrl() {
            Environment env = mockEnvironmentWithPort("9090", null);
            ServerPortService service = createService(env);

            assertEquals("http://localhost:9090/api/mcp", service.getMcpApiUrl());
        }

        @Test
        @DisplayName("getToolsInvokeUrl returns correct URL")
        void getToolsInvokeUrl() {
            Environment env = mockEnvironmentWithPort("9090", null);
            ServerPortService service = createService(env);

            assertEquals("http://localhost:9090/api/mcp/tools/invoke-direct", service.getToolsInvokeUrl());
        }

        @Test
        @DisplayName("all URLs are consistent with the same port")
        void urlsAreConsistent() {
            Environment env = mockEnvironmentWithPort("5555", null);
            ServerPortService service = createService(env);

            String baseUrl = service.getBaseUrl();
            assertTrue(service.getMcpApiUrl().startsWith(baseUrl));
            assertTrue(service.getToolsInvokeUrl().startsWith(baseUrl));
        }
    }
}
