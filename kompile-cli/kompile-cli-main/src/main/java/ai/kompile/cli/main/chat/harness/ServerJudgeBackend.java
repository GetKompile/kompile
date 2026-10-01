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
import ai.kompile.cli.main.chat.config.JudgeDefaults;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;

/**
 * Judge backend that delegates to an already-running kompile-model-staging server
 * through its OpenAI-compatible {@code /v1} endpoint.
 * <p>
 * The staging server is managed by the existing subprocess infrastructure
 * ({@code StagingServerLifecycleService}, {@code InitProjectCommand}).
 * This backend does NOT start it — it checks for an already-running instance.
 */
public class ServerJudgeBackend implements JudgeBackend {

    private static final int HEALTH_CHECK_TIMEOUT_MS = 3000;
    private static final int DEFAULT_PORT = 8090;
    /** kompile-model-staging is a Spring Boot app with /actuator/health. */
    private static final String HEALTH_PATH = "/actuator/health";

    private final String model;
    private final String thinking;
    private final int port;
    private final ObjectMapper objectMapper;

    private volatile RemoteJudgeBackend delegate;
    private volatile boolean initialized;
    private volatile String initError;

    /**
     * @param model        model name to use (e.g. "default")
     * @param port         staging server port (0 = default 8090)
     * @param objectMapper shared mapper for DirectLlmClient
     */
    public ServerJudgeBackend(String model, int port, ObjectMapper objectMapper) {
        this(model, port, objectMapper, null);
    }

    public ServerJudgeBackend(String model, int port, ObjectMapper objectMapper, Path workingDirectory) {
        var selected = JudgeDefaults.resolve("kompile", workingDirectory, model, "default");
        this.model = selected.model();
        this.thinking = selected.thinking();
        this.port = port > 0 ? port : DEFAULT_PORT;
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
        // Only available if staging is already running
        return checkHealth(port, HEALTH_PATH);
    }

    @Override
    public void close() {
        delegate = null;
    }

    @Override
    public String describe() {
        return "kompile-staging(model:" + model + ":" + port + ")";
    }

    private synchronized void ensureInitialized() throws Exception {
        if (initialized) return;
        initialized = true;

        try {
            if (!checkHealth(port, HEALTH_PATH)) {
                // Don't try to start staging — it's managed by the existing infra
                throw new RuntimeException(
                        "kompile-model-staging is not running on port " + port + ". "
                                + "Start it with: kompile init-project --start, "
                                + "or run the staging JAR directly.");
            }
            System.err.println("[Judge] kompile-model-staging already running on port " + port);

            // Staging serves an OpenAI-compatible /v1 endpoint
            String baseUrl = "http://localhost:" + port + "/v1";
            ChatConfig judgeConfig = new ChatConfig("kompile", null, model, baseUrl);
            judgeConfig.setThinking(thinking);
            DirectLlmClient client = new DirectLlmClient(judgeConfig, objectMapper);
            this.delegate = new RemoteJudgeBackend(client, null, "kompile");

            System.err.println("[Judge] Auto-server ready: kompile-model-staging on port " + port
                    + " with model " + model);

        } catch (Exception e) {
            this.initError = e.getMessage();
            throw e;
        }
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
}
