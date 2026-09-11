/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.WebChatContext;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.net.InetAddress;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.manage.ServiceManager;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Starts only the installed Kompile chat persona when no CLI chat instance is configured.
 *
 * <p>The native executable or runnable JAR is launched through {@link ServiceManager}, so the
 * fallback uses the normal distribution environment, logs, and instance registry. It deliberately
 * does not create a project or start app-main, model staging, crawling, or a serving child.</p>
 */
final class ChatInstanceBootstrap {

    static final String INSTANCE_NAME = "kompile-chat-cli";

    private ChatInstanceBootstrap() {
    }

    record StartupResult(String chatUrl, boolean started) {
    }

    static boolean isDistributionInstalled() {
        return missingDistributionComponents(new ComponentRegistry()).isEmpty();
    }

    static List<String> missingDistributionComponents(ComponentRegistry registry) {
        return registry.isInstalled(ComponentRegistry.KOMPILE_APP_CHAT)
                ? List.of()
                : List.of(ComponentRegistry.KOMPILE_APP_CHAT);
    }

    static StartupResult ensureReady(String requestedChatUrl, int startupTimeoutSeconds)
            throws BootstrapException {
        return ensureReady(requestedChatUrl, startupTimeoutSeconds,
                new ComponentRegistry(), new ServiceManager(), KompileHome.homeDirectory());
    }

    static StartupResult ensureReady(String requestedChatUrl, int startupTimeoutSeconds,
                                     ComponentRegistry registry, ServiceManager serviceManager,
                                     File dataDirectory) throws BootstrapException {
        return ensureReady(requestedChatUrl, startupTimeoutSeconds, registry, serviceManager,
                dataDirectory, false, false);
    }

    static StartupResult startWeb(Path workingDirectory, boolean globalConfig, int timeout)
            throws BootstrapException, IOException {
        // A fresh port/instance avoids reusing an admin persona or another project's harness.
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        return ensureReady("http://127.0.0.1:" + port, timeout, new ComponentRegistry(),
                new ServiceManager(), workingDirectory.toRealPath().toFile(), true, globalConfig);
    }

    static StartupResult ensureReady(String requestedChatUrl, int startupTimeoutSeconds,
                                     ComponentRegistry registry, ServiceManager serviceManager,
                                     File dataDirectory, boolean webHandoff, boolean globalConfig)
            throws BootstrapException {
        List<String> missing = missingDistributionComponents(registry);
        if (!missing.isEmpty()) {
            throw new BootstrapException("The installed distribution is missing required component: "
                    + String.join(", ", missing));
        }
        if (!isLoopbackHttpUrl(requestedChatUrl)) {
            throw new BootstrapException("Automatic startup only supports a local HTTP chat URL: "
                    + requestedChatUrl);
        }

        int chatPort = portForLocalChatUrl(requestedChatUrl);
        if (serviceManager.checkHealth(chatPort)) {
            if (webHandoff) throw new BootstrapException("Web handoff will not reuse an existing server; retry for a fresh port.");
            return new StartupResult(requestedChatUrl, false);
        }

        File chatArtifact = registry.findInstalledJar(ComponentRegistry.KOMPILE_APP_CHAT);
        if (chatArtifact == null) {
            throw new BootstrapException("The installed Kompile chat executable was not found.");
        }

        if (webHandoff && !chatArtifact.getName().endsWith(".jar")) {
            throw new BootstrapException("--web currently requires the installed CHAT JAR tier; native CHAT handoff is not supported.");
        }
        File workDirectory = dataDirectory.getAbsoluteFile();
        if (!workDirectory.isDirectory() && !workDirectory.mkdirs()) {
            throw new BootstrapException("Could not create Kompile data directory: " + workDirectory);
        }
        File logDirectory = new File(webHandoff ? KompileHome.homeDirectory() : workDirectory, "logs");
        String instanceName = webHandoff ? "kompile-chat-web-" + chatPort : INSTANCE_NAME;

        System.out.println(webHandoff ? "Starting an isolated installed CHAT web subprocess..."
                : "No Kompile chat instance is configured; starting the installed chat subprocess...");
        System.out.println("  Component: " + chatArtifact.getAbsolutePath());
        System.out.println("  URL: " + requestedChatUrl);

        Process process;
        try {
            process = serviceManager.startProjectComponent(
                    instanceName,
                    ComponentRegistry.KOMPILE_APP_CHAT,
                    chatArtifact,
                    chatPort,
                    workDirectory,
                    webHandoff ? WebChatContext.jvmArguments(workDirectory.toPath(), globalConfig) : List.of(),
                    webHandoff ? List.of("--server.address=127.0.0.1") : List.of(),
                    logDirectory,
                    false);
        } catch (IOException e) {
            throw new BootstrapException("Could not launch the installed Kompile chat subprocess: "
                    + e.getMessage(), e);
        }

        int timeoutSeconds = Math.max(1, startupTimeoutSeconds);
        if (!serviceManager.waitForHealth(chatPort, timeoutSeconds) || !process.isAlive()) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            InstanceRegistry.unregister(instanceName);
            throw new BootstrapException("The Kompile chat subprocess did not become ready at "
                    + requestedChatUrl + " within " + timeoutSeconds + " seconds. Logs: "
                    + logDirectory.getAbsolutePath());
        }

        System.out.println("  Chat subprocess ready (PID: " + process.pid() + ")");
        System.out.println("  Logs: " + logDirectory.getAbsolutePath());
        return new StartupResult(requestedChatUrl, true);
    }

    static boolean isLoopbackHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(normalized)
                    || "127.0.0.1".equals(normalized)
                    || "0.0.0.0".equals(normalized)
                    || "::1".equals(normalized)
                    || "[::1]".equals(normalized);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static int portForLocalChatUrl(String value) throws BootstrapException {
        try {
            URI uri = URI.create(value.trim());
            return uri.getPort() >= 0 ? uri.getPort() : KompileService.CHAT.defaultPort();
        } catch (RuntimeException e) {
            throw new BootstrapException("Invalid local chat URL: " + value, e);
        }
    }

    static final class BootstrapException extends Exception {
        BootstrapException(String message) {
            super(message);
        }

        BootstrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
