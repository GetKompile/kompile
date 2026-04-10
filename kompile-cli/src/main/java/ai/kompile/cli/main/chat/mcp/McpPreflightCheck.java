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

package ai.kompile.cli.main.chat.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Preflight check utility for MCP server setup.
 * 
 * This utility:
 * 1. Checks if kompile-app is running (and thus its MCP server)
 * 2. If not running, starts the CLI's stdio MCP server as a subprocess
 * 3. Provides the MCP configuration for Qwen Code to connect to
 * 
 * Designed to be called before starting Qwen Code or during resumed sessions
 * to ensure non-kompile tools (task, role_manager, resume, etc.) are available.
 */
public class McpPreflightCheck {

    private static final Logger logger = LoggerFactory.getLogger(McpPreflightCheck.class);

    // ANSI color codes
    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String DIM = "\033[2m";

    // Ports to probe for kompile-app
    private static final int[] KOMPILE_APP_PORTS = {8080, 8443, 9090, 3000};

    // Process handle for the stdio MCP server
    private Process stdioMcpProcess;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicReference<String> mcpConfigPath = new AtomicReference<>();

    /**
     * Runs the preflight check and returns the MCP configuration path.
     * 
     * @param projectDir The project directory (for stdio server working directory)
     * @return Path to MCP config file, or null if kompile-app is already running
     */
    public String runPreflight(Path projectDir) {
        // Step 1: Check if kompile-app is already running
        String kompileAppUrl = probeForKompileApp();
        
        if (kompileAppUrl != null) {
            System.out.println(GREEN + "✓ kompile-app detected at " + kompileAppUrl + RESET);
            System.out.println(DIM + "  Using kompile-app's MCP server (no stdio server needed)" + RESET);
            return null; // kompile-app is running, no need for stdio server
        }

        // Step 2: kompile-app not found, start CLI stdio MCP server
        System.out.println(YELLOW + "⚠ kompile-app not detected. Starting CLI stdio MCP server..." + RESET);
        return startStdioMcpServer(projectDir);
    }

    /**
     * Probes common ports to find a running kompile-app instance.
     */
    private String probeForKompileApp() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();

        for (int port : KOMPILE_APP_PORTS) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://localhost:" + port + "/mcp/status"))
                        .timeout(Duration.ofMillis(1000))
                        .GET()
                        .build();
                
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    return "http://localhost:" + port + "/mcp/sse";
                }
            } catch (Exception e) {
                // Port not available, try next
                logger.debug("Port {} not available: {}", port, e.getMessage());
            }
        }

        return null; // kompile-app not found
    }

    /**
     * Starts the CLI stdio MCP server as a subprocess.
     * 
     * @param projectDir The working directory for the server
     * @return Path to the MCP configuration file for Qwen Code
     */
    private String startStdioMcpServer(Path projectDir) {
        try {
            List<String> command = McpToolInjectionSupport.buildStdioServerProcessCommand(projectDir);
            if (command == null || command.isEmpty()) {
                System.err.println(YELLOW + "Warning: Could not resolve a kompile-cli launcher. Stdio MCP server not started." + RESET);
                return null;
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            
            // Start the process
            stdioMcpProcess = pb.start();
            isRunning.set(true);

            // Add shutdown hook to clean up
            Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer));

            // Wait a moment for the server to initialize
            Thread.sleep(500);

            // Check if process is still running
            if (!stdioMcpProcess.isAlive()) {
                int exitCode = stdioMcpProcess.exitValue();
                logger.error("Stdio MCP server exited with code {}", exitCode);
                String output = readProcessOutput(stdioMcpProcess);
                logger.error("Output: {}", output);
                isRunning.set(false);
                return null;
            }

            System.out.println(GREEN + "✓ CLI stdio MCP server started (PID: " + stdioMcpProcess.pid() + ")" + RESET);

            // Create MCP configuration for Qwen Code
            String configPath = writeMcpConfigForStdioServer(projectDir);
            mcpConfigPath.set(configPath);

            return configPath;

        } catch (Exception e) {
            logger.error("Failed to start stdio MCP server", e);
            System.err.println(YELLOW + "Warning: Failed to start CLI stdio MCP server: " + e.getMessage() + RESET);
            return null;
        }
    }

    private String writeMcpConfigForStdioServer(Path projectDir) {
        try {
            McpToolInjectionSupport.McpConfig config = McpToolInjectionSupport.createStdioConfig(projectDir);
            if (config == null) {
                return null;
            }

            logger.info("Wrote MCP config to: {}", config.path());
            System.out.println(DIM + "  MCP config written to: " + config.path() + RESET);
            System.out.println(DIM + "  Add this to your ~/.qwen/settings.json or .qwen/settings.json" + RESET);

            return config.path();

        } catch (Exception e) {
            logger.error("Failed to write MCP config", e);
            return null;
        }
    }

    /**
     * Reads process output (for error logging).
     */
    private String readProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            return reader.lines().limit(20).reduce("", (a, b) -> a + "\n" + b);
        } catch (Exception e) {
            return "Could not read output: " + e.getMessage();
        }
    }

    /**
     * Stops the stdio MCP server.
     */
    public void stopServer() {
        if (isRunning.compareAndSet(true, false) && stdioMcpProcess != null) {
            try {
                stdioMcpProcess.destroy();
                stdioMcpProcess.waitFor();
                System.out.println(DIM + "CLI stdio MCP server stopped" + RESET);
            } catch (Exception e) {
                logger.error("Error stopping stdio MCP server", e);
                stdioMcpProcess.destroyForcibly();
            }
        }
    }

    /**
     * Checks if the stdio MCP server is currently running.
     */
    public boolean isServerRunning() {
        return isRunning.get() && stdioMcpProcess != null && stdioMcpProcess.isAlive();
    }

    /**
     * Gets the path to the MCP configuration file.
     */
    public String getMcpConfigPath() {
        return mcpConfigPath.get();
    }

    /**
     * Prints instructions for integrating with Qwen Code.
     */
    public static void printQwenIntegrationInstructions(String configPath) {
        if (configPath == null) {
            System.out.println(DIM + "kompile-app is running. Qwen Code will use its MCP server." + RESET);
            return;
        }

        System.out.println("\n" + GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        System.out.println(GREEN + "  Qwen Code MCP Integration Setup" + RESET);
        System.out.println(GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        System.out.println();
        System.out.println("To enable kompile tools in Qwen Code, add this to your");
        System.out.println(YELLOW + "~/.qwen/settings.json" + RESET + " or " + YELLOW + ".qwen/settings.json" + RESET + ":\n");
        System.out.println(DIM + "{");
        System.out.println(DIM + "  \"mcpServers\": {");
        System.out.println(DIM + "    \"kompile\": {");
        System.out.println(DIM + "      \"command\": \"<kompile-or-java>\",");
        System.out.println(DIM + "      \"args\": [\"...\", \"mcp-stdio\", \"--work-dir\",");
        System.out.println(DIM + "                \"<your-project-directory>\"],");
        System.out.println(DIM + "      \"cwd\": \"<your-project-directory>\"");
        System.out.println(DIM + "    }");
        System.out.println(DIM + "  }");
        System.out.println(DIM + "}" + RESET);
        System.out.println();
        System.out.println("Or use the auto-generated config file:");
        System.out.println(YELLOW + "  " + configPath + RESET);
        System.out.println();
        System.out.println("Available tools:");
        System.out.println("  " + GREEN + "File I/O:" + RESET + "      read, write, edit, patch");
        System.out.println("  " + GREEN + "Search:" + RESET + "        grep, glob, list");
        System.out.println("  " + GREEN + "Execution:" + RESET + "     bash");
        System.out.println("  " + GREEN + "Network:" + RESET + "       webfetch, websearch");
        System.out.println("  " + GREEN + "Workflow:" + RESET + "       todoread, todowrite");
        System.out.println("  " + GREEN + "Knowledge:" + RESET + "     memory, transcript_search, conversation_import");
        System.out.println("  " + GREEN + "Delegation:" + RESET + "    task, role_manager");
        System.out.println("  " + GREEN + "Management:" + RESET + "    process, resume");
        System.out.println();
        System.out.println(GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        System.out.println();
    }

    /**
     * Main entry point for running preflight check standalone.
     * 
     * Usage:
     *   java -cp kompile-cli.jar ai.kompile.cli.main.chat.mcp.McpPreflightCheck
     */
    public static void main(String[] args) {
        Path projectDir = Paths.get(System.getProperty("user.dir"));
        
        McpPreflightCheck preflight = new McpPreflightCheck();
        String configPath = preflight.runPreflight(projectDir);

        printQwenIntegrationInstructions(configPath);

        // Keep running if stdio server was started
        if (preflight.isServerRunning()) {
            System.out.println(DIM + "Stdio MCP server running. Press Ctrl+C to stop." + RESET);
            
            // Block until interrupted
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                preflight.stopServer();
            }
        }
    }
}
