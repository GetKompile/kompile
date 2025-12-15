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

package ai.kompile.app.services.mcp;

import ai.kompile.core.mcp.server.ExternalMcpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runtime manager for STDIO-based MCP client connections.
 * Spawns external processes and manages their lifecycle.
 *
 * This class handles:
 * - Spawning the MCP server process using ProcessBuilder
 * - Managing the STDIO streams
 * - Tracking process lifecycle and status
 * - Clean shutdown of processes
 */
public class StdioMcpClientRuntime {

    private static final Logger logger = LoggerFactory.getLogger(StdioMcpClientRuntime.class);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final ExternalMcpServerConfig config;
    private Process process;
    private Thread stdoutReader;
    private Thread stderrReader;
    private volatile boolean running = false;

    public StdioMcpClientRuntime(ExternalMcpServerConfig config) {
        this.config = config;
    }

    /**
     * Starts the MCP server process.
     */
    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Server is already running");
        }

        List<String> command = buildCommand();
        logger.info("Starting external MCP server '{}' with command: {}", config.getId(), String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false);

        // Set environment variables
        if (config.getEnv() != null && !config.getEnv().isEmpty()) {
            Map<String, String> env = processBuilder.environment();
            env.putAll(config.getEnv());
            logger.debug("Set {} environment variables for server '{}'", config.getEnv().size(), config.getId());
        }

        // Start the process
        process = processBuilder.start();
        running = true;

        // Start stream readers to prevent buffer overflow and log output
        stdoutReader = new Thread(() -> readStream(process.getInputStream(), "stdout"),
                "mcp-" + config.getId() + "-stdout");
        stderrReader = new Thread(() -> readStream(process.getErrorStream(), "stderr"),
                "mcp-" + config.getId() + "-stderr");

        stdoutReader.setDaemon(true);
        stderrReader.setDaemon(true);
        stdoutReader.start();
        stderrReader.start();

        // Check if process started successfully
        try {
            // Give the process a moment to potentially fail
            Thread.sleep(500);
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                running = false;
                throw new IOException("Process exited immediately with code: " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting process", e);
        }

        logger.info("External MCP server '{}' started with PID: {}", config.getId(), getPid());
    }

    /**
     * Stops the MCP server process.
     */
    public synchronized void stop() {
        if (!running || process == null) {
            return;
        }

        logger.info("Stopping external MCP server '{}' (PID: {})", config.getId(), getPid());

        try {
            // First, try graceful shutdown
            process.destroy();

            // Wait for process to terminate
            boolean terminated = process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!terminated) {
                // Force kill if graceful shutdown failed
                logger.warn("Server '{}' did not terminate gracefully, forcing shutdown", config.getId());
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while stopping server '{}'", config.getId());
            process.destroyForcibly();
        } finally {
            running = false;
            process = null;

            // Interrupt stream readers
            if (stdoutReader != null) {
                stdoutReader.interrupt();
            }
            if (stderrReader != null) {
                stderrReader.interrupt();
            }
        }

        logger.info("External MCP server '{}' stopped", config.getId());
    }

    /**
     * Checks if the process is still alive.
     */
    public boolean isAlive() {
        return running && process != null && process.isAlive();
    }

    /**
     * Gets the process ID.
     */
    public Long getPid() {
        if (process != null && process.isAlive()) {
            return process.pid();
        }
        return null;
    }

    /**
     * Gets the process exit code, or null if still running.
     */
    public Integer getExitCode() {
        if (process != null && !process.isAlive()) {
            return process.exitValue();
        }
        return null;
    }

    /**
     * Gets the STDOUT stream of the process.
     * Useful for MCP protocol communication.
     */
    public InputStream getStdout() {
        return process != null ? process.getInputStream() : null;
    }

    /**
     * Gets the STDIN stream of the process.
     * Useful for MCP protocol communication.
     */
    public OutputStream getStdin() {
        return process != null ? process.getOutputStream() : null;
    }

    /**
     * Gets the STDERR stream of the process.
     */
    public InputStream getStderr() {
        return process != null ? process.getErrorStream() : null;
    }

    /**
     * Builds the command list for ProcessBuilder.
     */
    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());

        if (config.getArgs() != null) {
            command.addAll(config.getArgs());
        }

        return command;
    }

    /**
     * Reads from a stream and logs output.
     */
    private void readStream(InputStream stream, String streamName) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("stderr".equals(streamName)) {
                    logger.warn("[{}] {}: {}", config.getId(), streamName, line);
                } else {
                    logger.debug("[{}] {}: {}", config.getId(), streamName, line);
                }
            }
        } catch (IOException e) {
            if (running) {
                logger.debug("Stream {} closed for server '{}'", streamName, config.getId());
            }
        }
    }

    /**
     * Gets the server configuration.
     */
    public ExternalMcpServerConfig getConfig() {
        return config;
    }
}
