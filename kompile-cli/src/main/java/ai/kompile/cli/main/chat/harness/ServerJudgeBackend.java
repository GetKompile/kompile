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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Judge backend that uses a local inference server and delegates to it.
 * <p>
 * Supported server types:
 * <ul>
 *   <li><b>ollama</b> — starts {@code ollama serve}, pulls model if needed, uses OpenAI-compatible API</li>
 *   <li><b>kompile</b> — uses kompile-model-staging (must already be running on port 8090)</li>
 * </ul>
 * <p>
 * For the KOMPILE type, the staging server is managed by the existing subprocess
 * infrastructure ({@code StagingServerLifecycleService}, {@code InitProjectCommand}).
 * This backend does NOT start it — it checks for an already-running instance.
 */
public class ServerJudgeBackend implements JudgeBackend {

    public enum ServerType { OLLAMA, KOMPILE }

    private static final int HEALTH_CHECK_TIMEOUT_MS = 3000;
    private static final int STARTUP_TIMEOUT_SECONDS = 120;

    private final ServerType serverType;
    private final String model;
    private final int port;
    private final ObjectMapper objectMapper;

    private volatile RemoteJudgeBackend delegate;
    private volatile Process serverProcess;
    private volatile boolean initialized;
    private volatile String initError;
    private volatile boolean weStartedServer;

    /**
     * @param serverType   which server to manage (ollama or kompile)
     * @param model        model name to use (e.g. "llama3.3", "default")
     * @param port         port to run the server on (0 = default: 11434 for ollama, 8090 for kompile)
     * @param objectMapper shared mapper for DirectLlmClient
     */
    public ServerJudgeBackend(ServerType serverType, String model, int port,
                               ObjectMapper objectMapper) {
        this.serverType = serverType;
        this.model = model != null ? model : defaultModelFor(serverType);
        this.port = port > 0 ? port : defaultPortFor(serverType);
        this.objectMapper = objectMapper;
    }

    @Override
    public String generate(String userPrompt, String systemPrompt) throws Exception {
        ensureInitialized();
        if (delegate == null) {
            throw new IllegalStateException("Server judge backend not initialized: "
                    + (initError != null ? initError : "unknown error"));
        }
        return delegate.generate(userPrompt, systemPrompt);
    }

    @Override
    public boolean isAvailable() {
        if (serverType == ServerType.KOMPILE) {
            // Only available if staging is already running
            return checkHealth(port, healthCheckPath());
        }
        return true;
    }

    @Override
    public void close() {
        if (delegate != null) {
            delegate = null;
        }
        if (weStartedServer && serverProcess != null && serverProcess.isAlive()) {
            System.err.println("[Judge] Stopping auto-started " + serverType.name().toLowerCase() + " server");
            serverProcess.destroy();
            try {
                serverProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (serverProcess.isAlive()) {
                serverProcess.destroyForcibly();
            }
            serverProcess = null;
        }
    }

    @Override
    public String describe() {
        if (serverType == ServerType.KOMPILE) {
            return "kompile-staging(model:" + model + ":" + port + ")";
        }
        return "auto-server(" + serverType.name().toLowerCase() + "/" + model + ":" + port + ")";
    }

    private synchronized void ensureInitialized() throws Exception {
        if (initialized) return;
        initialized = true;

        try {
            String healthPath = healthCheckPath();

            // Step 1: Check if already running
            if (!checkHealth(port, healthPath)) {
                if (serverType == ServerType.KOMPILE) {
                    // Don't try to start staging — it's managed by the existing infra
                    throw new RuntimeException(
                            "kompile-model-staging is not running on port " + port + ". "
                                    + "Start it with: kompile init-project --start, "
                                    + "or run the staging JAR directly.");
                }

                // For ollama, auto-start is still supported
                startServer();

                if (!waitForHealth(port, healthPath, STARTUP_TIMEOUT_SECONDS)) {
                    throw new RuntimeException("Server failed to start within " + STARTUP_TIMEOUT_SECONDS + "s");
                }
            } else {
                System.err.println("[Judge] " + serverType.name().toLowerCase()
                        + " already running on port " + port);
            }

            // For ollama, ensure model is pulled
            if (serverType == ServerType.OLLAMA) {
                pullOllamaModel();
            }

            // Build the remote delegate — both use OpenAI-compatible /v1 endpoint
            String baseUrl = "http://localhost:" + port + "/v1";
            String provider = serverType == ServerType.OLLAMA ? "ollama" : "kompile";

            ChatConfig judgeConfig = new ChatConfig(provider, null, model, baseUrl);
            DirectLlmClient client = new DirectLlmClient(judgeConfig, objectMapper);
            this.delegate = new RemoteJudgeBackend(client, null, provider);

            System.err.println("[Judge] Auto-server ready: " + serverType.name().toLowerCase()
                    + " on port " + port + " with model " + model);

        } catch (Exception e) {
            this.initError = e.getMessage();
            throw e;
        }
    }

    private void startServer() throws Exception {
        switch (serverType) {
            case OLLAMA -> startOllama();
            case KOMPILE -> throw new IllegalStateException(
                    "kompile-model-staging must already be running (start with: kompile init-project --start). "
                    + "Auto-start is not supported — the staging server is managed by the existing subprocess infrastructure.");
        }
        weStartedServer = true;
    }

    private void startOllama() throws IOException {
        System.err.println("[Judge] Starting ollama serve on port " + port + "...");

        List<String> command = new ArrayList<>();
        command.add("ollama");
        command.add("serve");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("OLLAMA_HOST", "0.0.0.0:" + port);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        serverProcess = pb.start();
    }

    private void pullOllamaModel() {
        try {
            System.err.println("[Judge] Ensuring model '" + model + "' is available...");
            ProcessBuilder pb = new ProcessBuilder("ollama", "pull", model);
            pb.inheritIO();
            Process p = pb.start();
            if (!p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                System.err.println("[Judge] Warning: ollama pull timed out after 10 minutes");
                return;
            }
            int exit = p.exitValue();
            if (exit != 0) {
                System.err.println("[Judge] Warning: ollama pull returned exit code " + exit);
            }
        } catch (Exception e) {
            System.err.println("[Judge] Warning: Could not pull model '" + model + "': " + e.getMessage());
        }
    }

    /**
     * Health check path differs by server type.
     * kompile-model-staging is a Spring Boot app with /actuator/health.
     * ollama responds on /.
     */
    private String healthCheckPath() {
        return switch (serverType) {
            case KOMPILE -> "/actuator/health";
            case OLLAMA -> "/";
        };
    }

    private static boolean checkHealth(int port, String path) {
        try {
            URL url = new URL("http://localhost:" + port + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
            conn.setReadTimeout(HEALTH_CHECK_TIMEOUT_MS);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean waitForHealth(int port, String path, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (checkHealth(port, path)) return true;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static String defaultModelFor(ServerType type) {
        return switch (type) {
            case OLLAMA -> "llama3.3";
            case KOMPILE -> "default";
        };
    }

    private static int defaultPortFor(ServerType type) {
        return switch (type) {
            case OLLAMA -> 11434;
            case KOMPILE -> 8090;
        };
    }
}
