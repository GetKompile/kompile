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

package ai.kompile.cli.main.serve;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.enforcer.EnforcerRuntimePolicy;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Client-side connector to the kompile daemon Unix domain socket.
 * <p>
 * Used in two modes:
 * <ol>
 *   <li><b>MCP bridge</b>: {@code McpStdioCommand} connects here and bridges stdio ↔ socket,
 *       collapsing N separate MCP processes into one daemon process.</li>
 *   <li><b>Thin chat client</b>: {@code ChatCommand} connects here for shared-process chat sessions.</li>
 * </ol>
 */
public class DaemonClient implements Closeable {

    private final SocketChannel channel;
    private final BufferedReader reader;
    private final OutputStreamWriter writer;

    private DaemonClient(SocketChannel channel, BufferedReader reader, OutputStreamWriter writer) {
        this.channel = channel;
        this.reader = reader;
        this.writer = writer;
    }

    /**
     * Connect to the daemon and send the protocol header.
     *
     * @param type    connection type: "mcp" or "chat"
     * @param workDir working directory for this session
     * @return connected client, or null if daemon is not running
     */
    public static DaemonClient connect(String type, Path workDir) {
        Path socketPath = KompileHome.daemonSocketFile().toPath();
        if (!Files.exists(socketPath)) return null;

        try {
            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(addr);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            OutputStreamWriter writer = new OutputStreamWriter(
                    Channels.newOutputStream(channel), StandardCharsets.UTF_8);

            // Send protocol header
            StringBuilder headerBuilder = new StringBuilder();
            headerBuilder.append("{\"type\":\"").append(type).append("\",\"workDir\":\"")
                    .append(escapeJson(workDir.toAbsolutePath().toString())).append("\"");
            String active = System.getenv(EnforcerRuntimePolicy.ENV_ACTIVE);
            String policyFile = System.getenv(EnforcerRuntimePolicy.ENV_POLICY_FILE);
            if (Boolean.parseBoolean(active) && policyFile != null && !policyFile.isBlank()) {
                headerBuilder.append(",\"enforcerPolicyFile\":\"")
                        .append(escapeJson(policyFile)).append("\"");
            }
            headerBuilder.append("}\n");
            String header = headerBuilder.toString();
            writer.write(header);
            writer.flush();

            return new DaemonClient(channel, reader, writer);
        } catch (IOException e) {
            // Daemon socket exists but not responding — stale
            return null;
        }
    }

    /**
     * Connect to the daemon, starting one automatically if needed.
     * This is the primary entry point — callers never need to manage the daemon lifecycle.
     *
     * @param type    connection type: "mcp" or "chat"
     * @param workDir working directory for this session
     * @return connected client, or null if daemon could not be started
     */
    public static DaemonClient ensureDaemon(String type, Path workDir) {
        // Try existing daemon first
        DaemonClient client = connect(type, workDir);
        if (client != null) return client;

        // No daemon running — start one in the background
        if (!startDaemon()) return null;

        // Poll for readiness (daemon needs a moment to bind the socket).
        // 20 × 100ms = 2s max. Daemon typically binds within 500ms.
        for (int i = 0; i < 20; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            client = connect(type, workDir);
            if (client != null) return client;
        }

        System.err.println("[daemon] Daemon started but socket not ready after 2s");
        return null;
    }

    /**
     * Start a daemon process in the background.
     * Uses the same binary as the current process (native image or JVM).
     * The daemon auto-stops after 30 minutes of inactivity.
     *
     * @return true if the process was launched (not necessarily ready yet)
     */
    public static boolean startDaemon() {
        // Don't start if one is already alive
        if (isDaemonAlive()) return true;

        File runtimeDir = KompileHome.runtimeDirectory();
        runtimeDir.mkdirs();

        try {
            String currentCommand = ProcessHandle.current().info().command().orElse("java");
            ProcessBuilder pb;

            if (!currentCommand.toLowerCase().contains("java")) {
                // Native image — re-exec ourselves
                pb = new ProcessBuilder(currentCommand, "serve",
                        "--idle-timeout", "30");
            } else {
                // JVM — need classpath
                String classPath = System.getProperty("java.class.path");
                pb = new ProcessBuilder(currentCommand, "-cp", classPath,
                        "ai.kompile.cli.main.MainCommand", "serve",
                        "--idle-timeout", "30");
            }

            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    new File(runtimeDir, "daemon.log")));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(
                    new File(runtimeDir, "daemon.log")));

            Process child = pb.start();
            System.err.println("[daemon] Auto-started daemon (PID " + child.pid() + ")");
            return true;

        } catch (IOException e) {
            System.err.println("[daemon] Failed to auto-start daemon: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if a daemon is running and reachable.
     */
    public static boolean isDaemonAlive() {
        Path socketPath = KompileHome.daemonSocketFile().toPath();
        if (!Files.exists(socketPath)) return false;

        try {
            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(addr);
            ch.close();
            return true;
        } catch (IOException e) {
            // Stale socket — clean it up
            try { Files.deleteIfExists(socketPath); } catch (IOException ignored) {}
            return false;
        }
    }

    /**
     * Get daemon status information for observability.
     *
     * @return human-readable status string, or null if no daemon
     */
    public static String statusReport() {
        Path socketPath = KompileHome.daemonSocketFile().toPath();
        Path pidFile = KompileHome.runtimeDirectory().toPath().resolve("kompile.pid");
        File logFile = new File(KompileHome.runtimeDirectory(), "daemon.log");

        StringBuilder sb = new StringBuilder();

        if (!Files.exists(socketPath)) {
            sb.append("Status:  not running\n");
            sb.append("Socket:  ").append(socketPath).append(" (absent)\n");
            return sb.toString();
        }

        boolean alive = isDaemonAlive();
        sb.append("Status:  ").append(alive ? "running" : "stale socket").append("\n");
        sb.append("Socket:  ").append(socketPath).append("\n");

        // PID
        try {
            if (Files.exists(pidFile)) {
                String pid = Files.readString(pidFile).trim();
                sb.append("PID:     ").append(pid);
                // Check if process actually exists
                try {
                    ProcessHandle.of(Long.parseLong(pid)).ifPresentOrElse(
                            ph -> {
                                ph.info().startInstant().ifPresent(start -> {
                                    long uptimeSec = java.time.Duration.between(start, java.time.Instant.now()).getSeconds();
                                    long hours = uptimeSec / 3600;
                                    long mins = (uptimeSec % 3600) / 60;
                                    sb.append(" (uptime: ");
                                    if (hours > 0) sb.append(hours).append("h ");
                                    sb.append(mins).append("m)");
                                });
                            },
                            () -> sb.append(" (process not found)")
                    );
                } catch (NumberFormatException ignored) {}
                sb.append("\n");

                // RSS
                try {
                    Path statusFile = Path.of("/proc/" + pid + "/status");
                    if (Files.exists(statusFile)) {
                        Files.readAllLines(statusFile).stream()
                                .filter(l -> l.startsWith("VmRSS:"))
                                .findFirst()
                                .ifPresent(l -> {
                                    String rss = l.replaceAll("\\s+", " ").trim();
                                    sb.append("Memory:  ").append(rss.replace("VmRSS: ", "")).append("\n");
                                });
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}

        sb.append("Log:     ").append(logFile.getAbsolutePath()).append("\n");

        return sb.toString();
    }

    /**
     * Send a line to the daemon (newline-terminated).
     */
    public void writeLine(String line) throws IOException {
        writer.write(line);
        if (!line.endsWith("\n")) writer.write("\n");
        writer.flush();
    }

    /**
     * Read a line from the daemon (blocks until available).
     */
    public String readLine() throws IOException {
        return reader.readLine();
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * Bridge mode: pipe stdin→daemon and daemon→stdout until either side closes.
     * This is the hot path for MCP bridge — two threads, zero parsing overhead.
     * <p>
     * When stdin closes (parent process is done sending), we shut down the socket's
     * output side but keep reading daemon responses until the daemon closes its end.
     * This ensures all pending responses are flushed to stdout.
     *
     * @throws IOException if the daemon closes the connection before any response
     *         is sent, indicating the daemon crashed or is not functioning properly.
     *         The caller should fall back to in-process mode when this happens.
     */
    public void bridgeStdio() throws IOException {
        // Track whether daemon sent at least one response.
        // If the daemon dies before responding, we must throw so the caller
        // can fall back to in-process mode instead of silently producing no output.
        java.util.concurrent.atomic.AtomicBoolean receivedAny = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean daemonClosed = new java.util.concurrent.atomic.AtomicBoolean(false);

        // daemon→stdout in a background thread
        Thread outPump = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    receivedAny.set(true);
                    System.out.println(line);
                    System.out.flush();
                }
            } catch (IOException ignored) {}
            daemonClosed.set(true);
        }, "daemon-bridge-out");
        outPump.setDaemon(true);
        outPump.start();

        // stdin→daemon in the calling thread
        try (BufferedReader stdinReader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = stdinReader.readLine()) != null) {
                // If daemon closed its end while we're still reading stdin,
                // break out so we can throw and trigger fallback.
                if (daemonClosed.get()) break;
                try {
                    writeLine(line);
                } catch (IOException e) {
                    // Broken pipe — daemon died mid-session
                    break;
                }
            }
        }

        // stdin closed — shut down socket write side so daemon sees EOF,
        // but keep reading responses until daemon closes its end
        try {
            channel.shutdownOutput();
        } catch (IOException ignored) {}

        try {
            outPump.join(5000); // wait up to 5s for pending responses
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }

        // If daemon closed without ever sending a response, throw so the caller
        // falls back to in-process mode. This catches stale daemons, crashed
        // daemons, and socket-exists-but-process-dead scenarios.
        if (!receivedAny.get()) {
            throw new IOException("Daemon closed connection without sending any response");
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
