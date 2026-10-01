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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StagingServerLifecycleService}.
 * Tests status checking and result DTOs without actually starting processes.
 * The service looks for kompile-model-staging under {@code <user.dir>/staging} and
 * {@code <user.home>/.kompile/components}, so every test points both properties at empty
 * temporary directories. With no install to find, {@code startServer} fails before it
 * launches a server, writes {@code data/logs/subprocess-classpath.txt} or registers in
 * {@code ~/.kompile/instances}.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class StagingServerLifecycleServiceTest {

    @TempDir
    Path temporaryHome;

    @TempDir
    Path temporaryWorkingDir;

    private String originalUserHome;
    private String originalUserDir;

    private StagingServerLifecycleService service;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.home", temporaryHome.toString());
        System.setProperty("user.dir", temporaryWorkingDir.toString());
        service = new StagingServerLifecycleService();
    }

    @AfterEach
    void restoreSystemProperties() {
        System.setProperty("user.home", originalUserHome);
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void testGetStatus_notRunning() {
        // No staging server running on a random high port
        StagingServerLifecycleService.StagingServerStatus status = service.getStatus(59999);
        assertNotNull(status);
        assertEquals("kompile-model-staging", status.getComponentId());
        // Nothing is installed in the temporary home or working directory
        assertEquals("not_installed", status.getStatus());
        assertNull(status.getUrl());
    }

    @Test
    void testIsRunning_noServerOnPort() {
        // Nothing running on a random port
        assertFalse(service.isRunning(59998));
    }

    @Test
    void testStartResult_successBuilder() {
        StagingServerLifecycleService.StartResult result = StagingServerLifecycleService.StartResult.builder()
                .success(true)
                .message("Started successfully")
                .port(8081)
                .pid(12345L)
                .alreadyRunning(false)
                .build();

        assertTrue(result.isSuccess());
        assertEquals("Started successfully", result.getMessage());
        assertEquals(8081, result.getPort());
        assertEquals(12345L, result.getPid());
        assertFalse(result.isAlreadyRunning());
    }

    @Test
    void testStartResult_errorBuilder() {
        StagingServerLifecycleService.StartResult result = StagingServerLifecycleService.StartResult.builder()
                .success(false)
                .message("Not installed")
                .build();

        assertFalse(result.isSuccess());
        assertEquals("Not installed", result.getMessage());
        assertNull(result.getPort());
        assertNull(result.getPid());
    }

    @Test
    void testStopResult_builder() {
        StagingServerLifecycleService.StopResult result = StagingServerLifecycleService.StopResult.builder()
                .success(true)
                .message("Stopped")
                .pid(12345L)
                .build();

        assertTrue(result.isSuccess());
        assertEquals("Stopped", result.getMessage());
        assertEquals(12345L, result.getPid());
    }

    @Test
    void testStagingServerStatus_builder() {
        StagingServerLifecycleService.StagingServerStatus status = StagingServerLifecycleService.StagingServerStatus.builder()
                .componentId("kompile-model-staging")
                .status("running")
                .installed(true)
                .port(8081)
                .pid(9999L)
                .url("http://localhost:8081")
                .build();

        assertEquals("kompile-model-staging", status.getComponentId());
        assertEquals("running", status.getStatus());
        assertTrue(status.isInstalled());
        assertEquals(8081, status.getPort());
        assertEquals(9999L, status.getPid());
        assertEquals("http://localhost:8081", status.getUrl());
    }

    @Test
    void testStartServer_notInstalled() {
        // Start on a port where nothing is running, with no JAR installed
        // This tests the "not installed" path
        StagingServerLifecycleService.StartResult result = service.startServer(59997);
        // Neither temporary directory holds an install, so it must fail without launching anything
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("kompile-model-staging not found"));
        assertNull(result.getPid());
    }

    @Test
    void testStopServer_noManagedProcess() {
        // No process was started, so stop should fail gracefully
        StagingServerLifecycleService.StopResult result = service.stopServer();
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("No managed"));
    }

    @Test
    void testGetStatus_defaultPort() {
        // Default port test
        StagingServerLifecycleService.StagingServerStatus status = service.getStatus();
        assertNotNull(status);
        assertEquals("kompile-model-staging", status.getComponentId());
        // Port should be the default (8090)
        assertEquals(8090, status.getPort());
    }
}
