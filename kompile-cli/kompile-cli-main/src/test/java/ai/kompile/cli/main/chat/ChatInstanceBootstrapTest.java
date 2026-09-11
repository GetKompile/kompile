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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.manage.ServiceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatInstanceBootstrapTest {

    @Test
    void distributionRequiresOnlyTheChatComponent(@TempDir Path tempDir) throws Exception {
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());

        assertEquals(List.of(ComponentRegistry.KOMPILE_APP_CHAT),
                ChatInstanceBootstrap.missingDistributionComponents(registry));

        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("kompile-chat.jar"), "test");

        assertTrue(ChatInstanceBootstrap.missingDistributionComponents(registry).isEmpty());
    }

    @Test
    void fallbackLaunchesOnlyTheInstalledChatPersona(@TempDir Path tempDir) throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        File chatJar = Files.writeString(lib.resolve("kompile-chat.jar"), "test").toFile();
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        CapturingServiceManager services = new CapturingServiceManager(false);

        ChatInstanceBootstrap.StartupResult result = ChatInstanceBootstrap.ensureReady(
                "http://localhost:9181", 7, registry, services, tempDir.toFile());

        assertTrue(result.started());
        assertEquals("http://localhost:9181", result.chatUrl());
        assertEquals(ChatInstanceBootstrap.INSTANCE_NAME, services.instanceName);
        assertEquals(ComponentRegistry.KOMPILE_APP_CHAT, services.type);
        assertEquals(chatJar.getAbsoluteFile(), services.artifact.getAbsoluteFile());
        assertEquals(9181, services.port);
        assertEquals(tempDir.toFile().getAbsoluteFile(), services.workDirectory.getAbsoluteFile());
        assertEquals(tempDir.resolve("logs").toFile().getAbsoluteFile(),
                services.logDirectory.getAbsoluteFile());
        assertFalse(services.foreground);
        assertEquals(7, services.healthTimeoutSeconds);
    }

    @Test
    void nativeCliFallbackPrefersSiblingNativeChatExecutable(@TempDir Path tempDir)
            throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Files.createDirectories(tempDir.resolve("lib"));
        File nativeChat = Files.writeString(bin.resolve("kompile-chat"), "native").toFile();
        assertTrue(nativeChat.setExecutable(true));
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        CapturingServiceManager services = new CapturingServiceManager(false);

        ChatInstanceBootstrap.StartupResult result = ChatInstanceBootstrap.ensureReady(
                "http://localhost:9281", 7, registry, services, tempDir.toFile());

        assertTrue(result.started());
        assertEquals(nativeChat.getAbsoluteFile(), services.artifact.getAbsoluteFile());
        assertEquals(ComponentRegistry.KOMPILE_APP_CHAT, services.type);
        assertEquals(9281, services.port);
    }

    @Test
    void runningChatPersonaIsReusedWithoutLaunchingAnotherProcess(@TempDir Path tempDir)
            throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("kompile-chat.jar"), "test");
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        CapturingServiceManager services = new CapturingServiceManager(true);

        ChatInstanceBootstrap.StartupResult result = ChatInstanceBootstrap.ensureReady(
                "http://localhost:8081", 7, registry, services, tempDir.toFile());

        assertFalse(result.started());
        assertFalse(services.started);
    }

    @Test
    void automaticStartupOnlyAcceptsLoopbackHttpUrls() throws Exception {
        assertTrue(ChatInstanceBootstrap.isLoopbackHttpUrl("http://localhost:8081"));
        assertTrue(ChatInstanceBootstrap.isLoopbackHttpUrl("http://127.0.0.1:9000"));
        assertTrue(ChatInstanceBootstrap.isLoopbackHttpUrl("http://[::1]:8081"));
        assertFalse(ChatInstanceBootstrap.isLoopbackHttpUrl("https://localhost:8081"));
        assertFalse(ChatInstanceBootstrap.isLoopbackHttpUrl("http://example.com:8081"));

        assertEquals(9000, ChatInstanceBootstrap.portForLocalChatUrl("http://localhost:9000"));
        assertEquals(8081, ChatInstanceBootstrap.portForLocalChatUrl("http://localhost"));
    }

    @Test
    void webLaunchCarriesExactContextAndNeverReusesHealthyServer(@TempDir Path tempDir) throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("kompile-chat.jar"), "test");
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        Path nested = Files.createDirectories(tempDir.resolve("nested project"));
        CapturingServiceManager services = new CapturingServiceManager(false);
        ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, services,
                nested.toFile(), true, true);
        assertEquals(nested.toRealPath().toFile(), services.workDirectory);
        assertTrue(services.jvmArgs.contains("-Dkompile.chat.handoff.working-directory=" + nested.toRealPath()));
        assertTrue(services.jvmArgs.contains("-Dkompile.chat.handoff.config-scope=global"));
        assertEquals(List.of("--server.address=127.0.0.1"), services.appArgs);
        assertEquals("kompile-chat-web-9181", services.instanceName);
        CapturingServiceManager healthy = new CapturingServiceManager(true);
        org.junit.jupiter.api.Assertions.assertThrows(ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, healthy,
                        nested.toFile(), true, false));
        assertFalse(healthy.started);
    }

    @Test
    void webNativeTierFailsExplicitly(@TempDir Path tempDir) throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        File nativeChat = Files.writeString(bin.resolve("kompile-chat"), "native").toFile();
        assertTrue(nativeChat.setExecutable(true));
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        CapturingServiceManager services = new CapturingServiceManager(false);
        var error = org.junit.jupiter.api.Assertions.assertThrows(ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, services,
                        tempDir.toFile(), true, false));
        assertTrue(error.getMessage().contains("JAR tier"));
        assertFalse(services.started);
    }

    private static final class CapturingServiceManager extends ServiceManager {
        private final boolean initiallyHealthy;
        private boolean started;
        private String instanceName;
        private String type;
        private File artifact;
        private int port;
        private File workDirectory;
        private File logDirectory;
        private boolean foreground;
        private List<String> jvmArgs;
        private List<String> appArgs;
        private int healthTimeoutSeconds;

        private CapturingServiceManager(boolean initiallyHealthy) {
            this.initiallyHealthy = initiallyHealthy;
        }

        @Override
        public boolean checkHealth(int port) {
            return initiallyHealthy;
        }

        @Override
        public Process startProjectComponent(String instanceName, String type, File artifact,
                                             int port, File workDirectory,
                                             List<String> jvmArgs, List<String> appArgs,
                                             File logDirectory, boolean foreground)
                throws IOException {
            this.jvmArgs = jvmArgs;
            this.appArgs = appArgs;
            this.started = true;
            this.instanceName = instanceName;
            this.type = type;
            this.artifact = artifact;
            this.port = port;
            this.workDirectory = workDirectory;
            this.logDirectory = logDirectory;
            this.foreground = foreground;
            return new StubProcess();
        }

        @Override
        public boolean waitForHealth(int port, int timeoutSeconds) {
            this.healthTimeoutSeconds = timeoutSeconds;
            return true;
        }
    }

    private static final class StubProcess extends Process {
        private boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return 4242L;
        }
    }
}
