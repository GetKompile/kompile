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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.config;

import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceEndpointsConfigCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void configRootRegistersManagedEndpointCommand() {
        CommandLine config = new CommandLine(new ConfigMain());

        assertTrue(config.getSubcommands().containsKey("endpoints"));
    }

    @Test
    void writesEveryComponentEndpointToTheUiManagedFile() {
        ServiceEndpointsConfigManager manager = manager();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ServiceEndpointsConfigCommand command = new ServiceEndpointsConfigCommand(
                manager, new PrintWriter(output, true, StandardCharsets.UTF_8));

        int exitCode = new CommandLine(command).execute(
                "--admin-url", "http://admin.example:18080/",
                "--chat-url", "http://chat.example:18081/",
                "--crawl-url", "http://crawl.example:18082/",
                "--staging-url", "http://staging.example:18090/",
                "--serving-url", "http://127.0.0.1:18091/");

        ServiceEndpointsConfigManager.ServiceEndpointsConfig current = manager.current();
        assertEquals(0, exitCode);
        assertEquals("http://admin.example:18080", current.url(KompileService.ADMIN));
        assertEquals("http://chat.example:18081", current.url(KompileService.CHAT));
        assertEquals("http://crawl.example:18082", current.url(KompileService.CRAWL));
        assertEquals("http://staging.example:18090", current.stagingUrl());
        assertEquals("http://127.0.0.1:18091", current.servingUrl());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"stagingUrl\""));
    }

    @Test
    void rejectsRemoteServingChildAddress() {
        ServiceEndpointsConfigManager manager = manager();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new ServiceEndpointsConfigCommand(
                manager, new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        commandLine.setErr(new PrintWriter(errors, true, StandardCharsets.UTF_8));

        int exitCode = commandLine.execute("--serving-url", "http://remote.example:8091");

        assertNotEquals(0, exitCode);
        assertNull(manager.current().servingUrl());
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("loopback"));
    }

    @Test
    void rootOptionUpdatesTheProjectFileConsumedByProjectComponents() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ServiceEndpointsConfigCommand command = new ServiceEndpointsConfigCommand(
                null, new PrintWriter(output, true, StandardCharsets.UTF_8));

        int exitCode = new CommandLine(command).execute(
                "--root", tempDir.toString(),
                "--chat-url", "http://localhost:19081");

        ServiceEndpointsConfigManager projectManager =
                ServiceEndpointsConfigManager.forProjectDirectory(tempDir);
        assertEquals(0, exitCode);
        assertEquals("http://localhost:19081",
                projectManager.current().url(KompileService.CHAT));
    }

    private ServiceEndpointsConfigManager manager() {
        return new ServiceEndpointsConfigManager(
                tempDir.resolve(ServiceEndpointsConfigManager.FILENAME));
    }
}
