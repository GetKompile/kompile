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
        assertEquals(Boolean.FALSE, cors.getAllowCredentials());
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
        assertEquals(200, response.getStatus());
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
