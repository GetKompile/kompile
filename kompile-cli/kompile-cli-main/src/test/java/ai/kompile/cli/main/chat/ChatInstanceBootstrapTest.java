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
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import ai.kompile.cli.common.WebChatContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TemporaryUserHome
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
        assertEquals(ChatInstanceBootstrap.webApplicationArguments(System.getenv()), services.appArgs);
        assertEquals(Map.of(), services.environment, "JAR tier keeps its validated JVM context");
        assertEquals("kompile-chat-web-9181", services.instanceName);
        CapturingServiceManager healthy = new CapturingServiceManager(true);
        org.junit.jupiter.api.Assertions.assertThrows(ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, healthy,
                        nested.toFile(), true, false));
        assertFalse(healthy.started);
    }

    @Test
    void workspaceModeReachesTheChatServer(@TempDir Path tempDir) throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("kompile-chat.jar"), "test");
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        CapturingServiceManager services = new CapturingServiceManager(false);
        ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, services,
                tempDir.toFile(), true, false, null, true);
        assertTrue(services.jvmArgs.contains("-Dkompile.chat.handoff.mode=workspace"));
        assertTrue(services.jvmArgs.contains("-Dkompile.chat.handoff.config-scope=project"));
    }

    @Test
    void webNativeTierReceivesExactContextWithoutJarFallback(@TempDir Path tempDir) throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("kompile-chat.jar"), "unused fallback");
        File nativeChat = Files.writeString(bin.resolve("kompile-chat"), "native").toFile();
        assertTrue(nativeChat.setExecutable(true));
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        CapturingServiceManager services = new CapturingServiceManager(false);
        var result = ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, services,
                tempDir.toFile(), true, false, " team ", true);
        assertTrue(result.started());
        assertEquals(nativeChat.getAbsoluteFile(), services.artifact.getAbsoluteFile());
        assertEquals(List.of(), services.jvmArgs, "Native handoff cannot depend on JVM flags");
        assertEquals(WebChatContext.environment(tempDir, false, "team", true), services.environment);
        assertEquals(ChatInstanceBootstrap.webApplicationArguments(System.getenv()), services.appArgs);
        CapturingServiceManager healthy = new CapturingServiceManager(true);
        org.junit.jupiter.api.Assertions.assertThrows(ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, healthy,
                        tempDir.toFile(), true, true, "different-team", false));
        assertFalse(healthy.started, "Do not reuse a server with unknown workspace/context");
        CapturingServiceManager failing = new CapturingServiceManager(false);
        failing.launchFailure = new IOException("native launch failed");
        var failure = org.junit.jupiter.api.Assertions.assertThrows(ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, failing,
                        tempDir.toFile(), true, false));
        assertTrue(failure.getMessage().contains("native launch failed"));
        assertEquals(1, failing.launchCount, "A broken native launch must not fall back to JAR/global launch");
        assertEquals(nativeChat.getAbsoluteFile(), failing.artifact.getAbsoluteFile());
    }

    @Test
    void webBindingDefaultsToLoopbackButOrdinaryLaunchKeepsLegacyArguments(@TempDir Path tempDir)
            throws Exception {
        assertEquals(List.of("--server.address=127.0.0.1"), ChatInstanceBootstrap.webApplicationArguments(Map.of()));
        assertEquals(List.of("--server.address=0.0.0.0"), ChatInstanceBootstrap.webApplicationArguments(
                Map.of("KOMPILE_CHAT_ADDRESS", "0.0.0.0", "SERVER_ADDRESS", "127.0.0.2")));
        assertEquals(List.of("--server.address=127.0.0.2"), ChatInstanceBootstrap.webApplicationArguments(
                Map.of("SERVER_ADDRESS", "127.0.0.2")));
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("kompile-chat.jar"), "test");
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        CapturingServiceManager services = new CapturingServiceManager(false);
        ChatInstanceBootstrap.ensureReady("http://127.0.0.1:9181", 7, registry, services, tempDir.toFile());
        assertEquals(List.of(), services.appArgs);
        assertEquals(List.of(), services.jvmArgs);
        assertEquals(Map.of(), services.environment);
    }

    @Test
    void spinLogsStayInWorkspaceAndBrokenSpinContextFailsClosed(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        assertEquals(workspace.resolve("logs").toFile(), ChatInstanceBootstrap.logDirectory(
                workspace.toFile(), true, workspace.toString()));
        assertEquals(new File(ai.kompile.cli.common.KompileHome.homeDirectory(), "logs"),
                ChatInstanceBootstrap.logDirectory(workspace.toFile(), true, null));
        assertEquals(workspace.resolve("logs").toFile(),
                ChatInstanceBootstrap.logDirectory(workspace.toFile(), false, "/invalid/unused"));
        org.junit.jupiter.api.Assertions.assertThrows(ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.logDirectory(workspace.toFile(), true, tempDir.resolve("missing").toString()));
        org.junit.jupiter.api.Assertions.assertThrows(ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.logDirectory(tempDir.toFile(), true, workspace.toString()));
        Files.delete(workspace.resolve("logs"));
        Files.createSymbolicLink(workspace.resolve("logs"), tempDir);
        org.junit.jupiter.api.Assertions.assertThrows(ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.logDirectory(workspace.toFile(), true, workspace.toString()));
    }

    @Test
    void corruptInstalledChatJarFailsWithPreciseReinstallGuidance(@TempDir Path tempDir)
            throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        // Production failure signature: PK-prefixed truncated zip from a disk-full install.
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                lib.resolve("kompile-chat.jar").toFile())) {
            out.write(new byte[]{0x50, 0x4B, 0x03, 0x04});
            out.write(new byte[100]);
        }
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        CapturingServiceManager services = new CapturingServiceManager(false);

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.ensureReady(
                        "http://localhost:9181", 7, registry, services, tempDir.toFile()));

        assertTrue(error.getMessage().contains("corrupt"), error.getMessage());
        assertTrue(error.getMessage().contains("disk-full"), error.getMessage());
        assertTrue(error.getMessage().contains("kompile install "
                + ComponentRegistry.KOMPILE_APP_CHAT), error.getMessage());
        assertFalse(services.started, "a corrupt jar must never be launched");
    }

    @Test
    void deadProcessFailsFastWithExitCodeInsteadOfBurningTheTimeout(@TempDir Path tempDir)
            throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("kompile-chat.jar"), "test");
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        DeadOnBootServiceManager services = new DeadOnBootServiceManager();

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.ensureReady(
                        "http://localhost:9181", 7, registry, services, tempDir.toFile()));

        assertTrue(error.getMessage().contains("exited before accepting connections"),
                error.getMessage());
        assertTrue(error.getMessage().contains("exit code 3"), error.getMessage());
        assertFalse(services.process.destroyForciblyCalled,
                "a dead process must not be force-killed again");
    }

    @Test
    void corruptH2DatabaseFailureNamesTheExactFileToDelete(@TempDir Path tempDir)
            throws Exception {
        Path lib = Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(lib.resolve("kompile-chat.jar"), "test");
        ComponentRegistry registry = new ComponentRegistry();
        registry.setInstallBaseDir(tempDir.toFile());
        DeadOnBootServiceManager services = new DeadOnBootServiceManager();
        // Real failure signature (2026-09-22): JDBC URL on stdout, MVStore corruption on stderr.
        services.outLogText = "a.k.app.config.PrimaryDataSourceConfig   : Creating primary data "
                + "source: jdbc:h2:file:/tmp/project/data/orchestrator-db;DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE;AUTO_SERVER=TRUE\n";
        services.errLogText = "Caused by: org.h2.mvstore.MVStoreException: File is corrupted "
                + "- unable to recover a valid set of chunks [2.2.224/6]\n";

        var error = org.junit.jupiter.api.Assertions.assertThrows(
                ChatInstanceBootstrap.BootstrapException.class,
                () -> ChatInstanceBootstrap.ensureReady(
                        "http://localhost:9181", 7, registry, services, tempDir.toFile()));

        assertTrue(error.getMessage().contains("H2 database file is corrupt"), error.getMessage());
        assertTrue(error.getMessage().contains("/tmp/project/data/orchestrator-db.mv.db"),
                error.getMessage());
        assertTrue(error.getMessage().contains(".trace.db"), error.getMessage());
    }

    private static final class DeadOnBootServiceManager extends ServiceManager {
        private final DeadProcess process = new DeadProcess(3);
        private String outLogText = "";
        private String errLogText = "";

        @Override
        public boolean checkHealth(int port) {
            return false;
        }

        @Override
        public Process startProjectComponent(String instanceName, String type, File artifact,
                                             int port, File workDirectory,
                                             List<String> jvmArgs, List<String> appArgs,
                                             File logDirectory, boolean foreground, Map<String, String> environment)
                throws IOException {
            logDirectory.mkdirs();
            Files.writeString(new File(logDirectory, instanceName + ".out.log").toPath(), outLogText);
            Files.writeString(new File(logDirectory, instanceName + ".err.log").toPath(), errLogText);
            return process;
        }

        @Override
        public boolean waitForHealth(int port, int timeoutSeconds, Process process) {
            return false; // the child never became healthy
        }
    }

    private static final class DeadProcess extends Process {
        private final int code;
        private boolean destroyForciblyCalled;

        private DeadProcess(int code) {
            this.code = code;
        }

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
            return code;
        }

        @Override
        public int exitValue() {
            return code;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalled = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public long pid() {
            return 4242L;
        }
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
        private Map<String, String> environment;
        private IOException launchFailure;
        private int launchCount;
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
                                             File logDirectory, boolean foreground, Map<String, String> environment)
                throws IOException {
            this.jvmArgs = jvmArgs;
            this.appArgs = appArgs;
            this.environment = environment;
            this.started = true;
            this.instanceName = instanceName;
            this.type = type;
            this.artifact = artifact;
            this.port = port;
            this.workDirectory = workDirectory;
            this.logDirectory = logDirectory;
            this.foreground = foreground;
            this.launchCount++;
            if (launchFailure != null) throw launchFailure;
            return new StubProcess();
        }

        @Override
        public boolean waitForHealth(int port, int timeoutSeconds, Process process) {
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
