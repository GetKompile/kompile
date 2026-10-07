/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ai.kompile.app.web.config;

import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedServiceEndpointsCorsConfigurationTest {

    @Test
    void derivesOriginsFromManagedCustomPorts(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve(ServiceEndpointsConfigManager.FILENAME);
        Files.writeString(configFile, """
                {
                  "adminUrl": "http://localhost:19380",
                  "chatUrl": "http://localhost:19381",
                  "crawlUrl": "http://localhost:19382",
                  "stagingUrl": "http://localhost:19390"
                }
                """);

        ManagedServiceEndpointsCorsConfiguration configuration =
                new ManagedServiceEndpointsCorsConfiguration(
                        new ServiceEndpointsConfigManager(configFile));

        Set<String> origins = configuration.managedUiOrigins();
        assertEquals(Set.of(
                "http://localhost:19380",
                "http://localhost:19381",
                "http://localhost:19382",
                "http://localhost:19390"), origins);

        CorsConfiguration cors = configuration.currentConfiguration();
        assertTrue(cors.getAllowedMethods().contains("GET"));
        assertTrue(cors.getAllowedMethods().contains("POST"));
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
    }

    @Test
    void allowsConfiguredPersonaOriginForApiPreflight(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve(ServiceEndpointsConfigManager.FILENAME);
        Files.writeString(configFile, """
                {
                  "adminUrl": "http://localhost:19380",
                  "chatUrl": "http://localhost:19381",
                  "crawlUrl": "http://localhost:19382",
                  "stagingUrl": "http://localhost:19390"
                }
                """);
        ManagedServiceEndpointsCorsConfiguration configuration =
                new ManagedServiceEndpointsCorsConfiguration(
                        new ServiceEndpointsConfigManager(configFile));
        CorsFilter filter = configuration.managedServiceEndpointsCorsFilter();

        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/api/staging-config/configs/active");
        request.addHeader("Origin", "http://localhost:19381");
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("http://localhost:19381",
                response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void allowsUiServedFromTheHostTheBrowserUsed(@TempDir Path tempDir) throws Exception {
        CorsFilter filter = new ManagedServiceEndpointsCorsConfiguration(
                new ServiceEndpointsConfigManager(
                        tempDir.resolve(ServiceEndpointsConfigManager.FILENAME)))
                .managedServiceEndpointsCorsFilter();

        // Web chat on a random port, opened through Tailscale, calling the admin persona.
        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/api/models/registry/status");
        request.setServerName("kompile-box.tail1234.ts.net");
        request.setServerPort(8080);
        request.addHeader("Origin", "http://kompile-box.tail1234.ts.net:49173");
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("http://kompile-box.tail1234.ts.net:49173",
                response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
    }

    @Test
    void allowsLoopbackChatBehindTailscaleServeHttpsProxy(@TempDir Path tempDir) throws Exception {
        CorsFilter filter = new ManagedServiceEndpointsCorsConfiguration(
                new ServiceEndpointsConfigManager(
                        tempDir.resolve(ServiceEndpointsConfigManager.FILENAME)))
                .managedServiceEndpointsCorsFilter();

        // `tailscale serve` terminates TLS and proxies to http://127.0.0.1:<web-port>, keeping the
        // browser's Host. Spring sees http:80 vs Origin https:443 and treats it as cross-origin.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agents/chat");
        request.setScheme("http");
        request.setServerName("kompile-box.tail1234.ts.net");
        request.setServerPort(80);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("Origin", "https://kompile-box.tail1234.ts.net");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "chat request must reach the controller");
        assertEquals("https://kompile-box.tail1234.ts.net",
                response.getHeader("Access-Control-Allow-Origin"));

        MockHttpServletRequest foreign = new MockHttpServletRequest("POST", "/api/agents/chat");
        foreign.setServerName("kompile-box.tail1234.ts.net");
        foreign.addHeader("Origin", "https://attacker.example");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(foreign, rejected, new MockFilterChain());
        assertEquals(403, rejected.getStatus());
    }

    @Test
    void sameHostRuleUsesOnlyTheConnectedHost() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/models");
        request.setServerName("100.101.102.103");
        request.addHeader("X-Forwarded-Host", "evil.example");

        request.addHeader("Origin", "https://evil.example");
        assertNull(ManagedServiceEndpointsCorsConfiguration.sameHostOrigin(request));

        request.removeHeader("Origin");
        request.addHeader("Origin", "http://100.101.102.103:9181");
        assertEquals("http://100.101.102.103:9181",
                ManagedServiceEndpointsCorsConfiguration.sameHostOrigin(request));

        request.removeHeader("Origin");
        request.addHeader("Origin", "null");
        assertNull(ManagedServiceEndpointsCorsConfiguration.sameHostOrigin(request));

        MockHttpServletRequest ipv6 = new MockHttpServletRequest("GET", "/api/models");
        ipv6.setServerName("[fd7a:115c:a1e0::1]");
        ipv6.addHeader("Origin", "http://[fd7a:115c:a1e0::1]:8081");
        assertEquals("http://[fd7a:115c:a1e0::1]:8081",
                ManagedServiceEndpointsCorsConfiguration.sameHostOrigin(ipv6));
    }

    @Test
    void doesNotExposeNonApiOrUnknownOrigins(@TempDir Path tempDir) throws Exception {
        ManagedServiceEndpointsCorsConfiguration configuration =
                new ManagedServiceEndpointsCorsConfiguration(
                        new ServiceEndpointsConfigManager(
                                tempDir.resolve(ServiceEndpointsConfigManager.FILENAME)));
        CorsFilter filter = configuration.managedServiceEndpointsCorsFilter();

        MockHttpServletRequest request =
                new MockHttpServletRequest("OPTIONS", "/api/staging-config/configs/active");
        request.addHeader("Origin", "https://unmanaged.example");
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }
}
