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
        try (ServerSocket socket = new ServerSocket(0)) {
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
            // A corrupt installed jar (truncated/empty zip — the disk-full install
            // casualty) also reports as missing; name it precisely instead.
            List<File> corrupt = registry.corruptJarCandidates(ComponentRegistry.KOMPILE_APP_CHAT);
            if (!corrupt.isEmpty()) {
                throw new BootstrapException("The installed Kompile chat artifact is corrupt "
                        + "(truncated or empty — often the result of a disk-full install): "
                        + String.join(", ", corrupt.stream().map(File::getAbsolutePath).toList())
                        + ". Free disk space and reinstall it with: kompile install "
                        + ComponentRegistry.KOMPILE_APP_CHAT);
            }
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
            // Unreachable while missingDistributionComponents gates above; kept as a guard.
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
                    // Use the CHAT distribution's all-interface default and honor operator
                    // overrides (KOMPILE_CHAT_ADDRESS / SERVER_ADDRESS), rather than forcing loopback.
                    List.of(),
                    logDirectory,
                    false);
        } catch (IOException e) {
            throw new BootstrapException("Could not launch the installed Kompile chat subprocess: "
                    + e.getMessage(), e);
        }

        int timeoutSeconds = Math.max(1, startupTimeoutSeconds);
        System.out.println("  Waiting for readiness (timeout: " + timeoutSeconds
                + "s; a cold JVM boot typically takes 30-90s, progress every 15s)...");
        if (!serviceManager.waitForHealth(chatPort, timeoutSeconds, process) || !process.isAlive()) {
            boolean diedOnBoot = !process.isAlive();
            int exitCode = diedOnBoot ? process.exitValue() : -1;
            if (!diedOnBoot) {
                process.destroyForcibly();
            }
            InstanceRegistry.unregister(instanceName);
            // Surface the launch failure directly: BOTH logs name it — the JDBC URL and
            // Spring banner land on stdout while the exception lands on stderr — and a
            // bare timeout message hides the real cause.
            String errorTail = tailLines(new File(logDirectory, instanceName + ".err.log"), 20);
            String outTail = tailLines(new File(logDirectory, instanceName + ".out.log"), 20);
            String diagnosis = diagnoseStartupFailure(errorTail + "\n" + outTail);
            throw new BootstrapException("The Kompile chat subprocess did not become ready at "
                    + requestedChatUrl + " within " + timeoutSeconds + " seconds"
                    + (diedOnBoot
                        ? " (the process exited before accepting connections, exit code " + exitCode + ")"
                        : "")
                    + ". Logs: " + logDirectory.getAbsolutePath()
                    + diagnosis
                    + (errorTail.isEmpty() ? "" : "\nLast error output:\n" + errorTail)
                    + (outTail.isEmpty() ? "" : "\nLast startup output:\n" + outTail));
        }

        System.out.println("  Chat subprocess ready (PID: " + process.pid() + ")");
        System.out.println("  Logs: " + logDirectory.getAbsolutePath());
        return new StartupResult(requestedChatUrl, true);
    }

    /**
     * Targeted next-step guidance for the boot failure classes we have actually seen
     * (corrupt embedded H2 file, full disk, corrupt installed artifact). Returns ""
     * when the logs match nothing known.
     */
    static String diagnoseStartupFailure(String logText) {
        if (logText == null || logText.isBlank()) {
            return "";
        }
        if (logText.contains("MVStoreException") && logText.contains("File is corrupted")) {
            java.util.regex.Matcher url = java.util.regex.Pattern
                    .compile("jdbc:h2:file:([^;\\s\"]+)").matcher(logText);
            String remedy = url.find()
                    ? "Delete \"" + url.group(1) + ".mv.db\" (plus its .trace.db) — it is recreated "
                        + "empty on the next start."
                    : "Find the *.mv.db named after 'Creating primary data source' in the startup "
                        + "log and delete it (plus its .trace.db).";
            return "\nLikely cause: the embedded H2 database file is corrupt, which aborts startup "
                    + "at the datasource. " + remedy;
        }
        if (logText.toLowerCase(Locale.ROOT).contains("no space left on device")) {
            return "\nLikely cause: the disk is full. Free space and retry.";
        }
        if (logText.contains("Invalid or corrupt jarfile")) {
            return "\nLikely cause: the installed artifact is corrupt. Reinstall with: kompile install "
                    + ComponentRegistry.KOMPILE_APP_CHAT;
        }
        return "";
    }

    /** Last {@code maxLines} lines of a log file, each capped, or empty when unreadable. */
    private static String tailLines(File logFile, int maxLines) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath());
            StringBuilder out = new StringBuilder();
            for (String line : lines.subList(Math.max(0, lines.size() - maxLines), lines.size())) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line.length() > 500 ? line.substring(0, 500) + "…" : line);
            }
            return out.toString();
        } catch (IOException | RuntimeException e) {
            return "";
        }
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
