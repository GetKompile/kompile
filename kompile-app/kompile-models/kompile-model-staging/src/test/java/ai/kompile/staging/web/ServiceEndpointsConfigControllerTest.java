/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.staging.web;

import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ServiceEndpointsConfigControllerTest {

    @Test
    void readsAndPersistsServingDependencyInManagedProjectConfig(@TempDir Path projectDir) {
        ServiceEndpointsConfigManager manager = ServiceEndpointsConfigManager.forProjectDirectory(projectDir);
        ServiceEndpointsConfigController controller = new ServiceEndpointsConfigController(manager);

        ResponseEntity<?> update = controller.updateConfig(Map.of(
                ServiceEndpointsConfigManager.SERVING_URL_KEY, "http://localhost:19091/"));

        assertEquals(HttpStatus.OK, update.getStatusCode());
        assertEquals("http://localhost:19091", manager.current().effectiveServingUrl());
        ResponseEntity<Map<String, Object>> read = controller.getConfig();
        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertNotNull(read.getBody());
        assertEquals("http://localhost:19091",
                read.getBody().get(ServiceEndpointsConfigManager.SERVING_URL_KEY));
    }

    @Test
    void rejectsNonLoopbackServingDependency(@TempDir Path projectDir) {
        ServiceEndpointsConfigController controller = new ServiceEndpointsConfigController(
                ServiceEndpointsConfigManager.forProjectDirectory(projectDir));

        ResponseEntity<?> response = controller.updateConfig(Map.of(
                ServiceEndpointsConfigManager.SERVING_URL_KEY, "http://192.0.2.10:19091"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
