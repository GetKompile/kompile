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

package ai.kompile.cli.main.run;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.KompileLocalServingBootstrap;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * Run a local LLM with a single command — download (if needed) and serve.
 *
 * <p>Usage:</p>
 * <pre>
 *   kompile run Qwen/Qwen3-0.6B
 *   kompile run Qwen/Qwen3-0.6B --serve
 *   kompile run Qwen/Qwen3-0.6B --port 9090 --backend cuda
 *   kompile run /path/to/local/model
 * </pre>
 */
@CommandLine.Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Download (if needed) and run a local LLM.%n%n" +
                "Examples:%n" +
                "  kompile run Qwen/Qwen3-0.6B%n" +
                "  kompile run Qwen/Qwen3-0.6B --serve%n" +
                "  kompile run Qwen/Qwen3-0.6B --port 9090 --backend cuda%n" +
                "  kompile run /path/to/local/model%n"
)
public class RunCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0",
            description = "Model ID (e.g. Qwen/Qwen3-0.6B) or local directory path")
    private String model;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "11434",
            description = "Server port (default: ${DEFAULT-VALUE})")
    private int port;

    @CommandLine.Option(names = {"--host"}, defaultValue = "127.0.0.1",
            description = "Server bind host (default: ${DEFAULT-VALUE})")
    private String host;

    @CommandLine.Option(names = {"--backend"},
            description = "ND4J backend: cpu or cuda")
    private String backend;

    @CommandLine.Option(names = {"--temperature"}, defaultValue = "0.7",
            description = "Default sampling temperature (default: ${DEFAULT-VALUE})")
    private double temperature;

    @CommandLine.Option(names = {"--max-tokens"}, defaultValue = "512",
            description = "Default max tokens (default: ${DEFAULT-VALUE})")
    private int maxTokens;

    @CommandLine.Option(names = {"--chat-template"}, defaultValue = "auto",
            description = "Chat template: auto, chatml, llama2, vicuna, alpaca (default: ${DEFAULT-VALUE})")
    private String chatTemplate;

    @CommandLine.Option(names = {"--revision"}, defaultValue = "main",
            description = "HuggingFace revision/branch (default: ${DEFAULT-VALUE})")
    private String revision;

    @CommandLine.Option(names = {"--hf-token"},
            description = "HuggingFace auth token (or set HF_TOKEN env var)")
    private String hfToken;

    @CommandLine.Option(names = {"--serve"},
            description = "Start API server only — do not enter interactive chat")
    private boolean serveOnly;

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private volatile Process serverProcess;
    private volatile KompileLocalServingBootstrap.StartupResult servingRuntime;

    @Override
    public Integer call() throws Exception {
        try {
            validateRequestedBackend(
                    backend, KompileHome.installDirectory().toPath().toAbsolutePath().normalize());
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        // 1. Resolve model to a local path.
        Path modelPath;
        try {
            modelPath = resolveModelPath();
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        if (!"auto".equalsIgnoreCase(chatTemplate)) {
            System.err.println("Warning: --chat-template is ignored by the current serving runtime; "
                    + "the model/tokenizer metadata selects the template.");
        }

        // 2. Launch the distribution's canonical model-serving component. The
        // bootstrap writes the current JSON argument contract and waits for the
        // model-specific status endpoint to report ready.
        System.out.println("Loading model...");
        try {
            servingRuntime = KompileLocalServingBootstrap.ensureReady(
                    resolvedModelId(modelPath), modelPath, 180, runtimeOptions());
            serverProcess = servingRuntime.process();
        } catch (KompileLocalServingBootstrap.BootstrapException e) {
            System.err.println("Error launching server: " + e.getMessage());
            return 1;
        }

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer, "kompile-run-shutdown"));

        // 3. Print the endpoints actually exposed by ServingSubprocessMain.
        String serverUrl = servingRuntime.baseUrl().toString();
        String modelId = servingRuntime.modelId();
        System.out.println();
        System.out.println("Model ready: " + modelId);
        System.out.println("  Chat:       " + serverUrl + "/api/llm/chat");
        System.out.println("  Generate:   " + serverUrl + "/api/llm/generate");
        System.out.println("  Status:     " + serverUrl + "/api/llm/status");

        if (serveOnly) {
            System.out.println();
            System.out.println("Running as API server. Press Ctrl+C to stop.");
            serverProcess.waitFor();
            int exitCode = serverProcess.exitValue();
            stopServer();
            return exitCode;
        }

        // 4. Enter interactive chat REPL.
        runRepl(serverUrl);

        stopServer();
        return 0;
    }

    // ==================== Model Resolution ====================

    private Path resolveModelPath() throws IOException {
        // Check if it looks like a local path
        File localCandidate = new File(model);
        if (localCandidate.isAbsolute() || model.startsWith("./") || model.startsWith("../")
                || model.startsWith("~")) {
            // Expand ~ if needed
            String expanded = model;
            if (expanded.startsWith("~")) {
                expanded = System.getProperty("user.home") + expanded.substring(1);
                localCandidate = new File(expanded);
            }
            if (!localCandidate.isDirectory()) {
                throw new IOException("Local model path not found: " + model);
            }
            System.out.println("Using local model: " + localCandidate.getAbsolutePath());
            return localCandidate.toPath();
        }

        // Treat as HuggingFace repo ID
        if (!model.contains("/")) {
            throw new IOException("Invalid model identifier: " + model
                    + "\nExpected format: Owner/ModelName (e.g. Qwen/Qwen3-0.6B)"
                    + "\nOr a local directory path.");
        }

        KompileModelManager manager = new KompileModelManager();
        if (manager.isPipelineModelCached(model)) {
            Path cached = manager.getPipelineModelDirectory(model);
            System.out.println("Using cached model: " + cached);
            return cached;
        }

        // Download
        System.out.println("Pulling " + model + "...");
        String token = resolveHfToken();
        Path downloaded = manager.downloadPipelineModel(model, revision, token,
                msg -> System.out.println("  " + msg));
        System.out.println();
        return downloaded;
    }

    private String resolveHfToken() {
        if (hfToken != null && !hfToken.isBlank()) return hfToken;
        String envToken = System.getenv("HF_TOKEN");
        if (envToken != null && !envToken.isBlank()) return envToken;
        String altToken = System.getenv("HUGGING_FACE_HUB_TOKEN");
        if (altToken != null && !altToken.isBlank()) return altToken;
        return null;
    }

    // ==================== Server Launch ====================

    Map<String, Object> runtimeOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("port", port);
        options.put("host", host);
        options.put("maxNewTokens", maxTokens);
        options.put("temperature", temperature);
        return options;
    }

    private String resolvedModelId(Path modelPath) {
        if (!isLocalModelSelection()) {
            return model;
        }
        Path fileName = modelPath.getFileName();
        return fileName == null ? model : fileName.toString();
    }

    private boolean isLocalModelSelection() {
        File localCandidate = new File(model);
        return localCandidate.isAbsolute() || model.startsWith("./")
                || model.startsWith("../") || model.startsWith("~");
    }

    static void validateRequestedBackend(String requestedBackend, Path installHome)
            throws IOException {
        if (requestedBackend == null || requestedBackend.isBlank()) {
            return;
        }
        String requested = requestedBackend.trim().toLowerCase(Locale.ROOT);
        if (!"cpu".equals(requested) && !"cuda".equals(requested)) {
            throw new IOException("Unsupported backend '" + requestedBackend
                    + "'; expected cpu or cuda.");
        }

        Path metadata = installHome.resolve(".dist-info.json");
        if (!Files.isRegularFile(metadata)) {
            return;
        }
        String installed = JsonUtils.standardMapper()
                .readTree(metadata.toFile()).path("backend").asText("")
                .trim().toLowerCase(Locale.ROOT);
        if (installed.isBlank()) {
            return;
        }
        boolean matches = "cuda".equals(requested)
                ? installed.startsWith("nd4j-cuda")
                : installed.startsWith("nd4j-native");
        if (!matches) {
            throw new IOException("Requested " + requested + " backend, but this distribution "
                    + "contains " + installed + ". Install a matching distribution; "
                    + "the backend cannot be switched at runtime.");
        }
    }

    // ==================== Interactive Chat REPL ====================

    private void runRepl(String serverUrl) {
        System.out.println();
        System.out.println("Type your message. Commands:");
        System.out.println("  /exit   - quit");
        System.out.println("  /clear  - clear conversation history");
        System.out.println();

        List<Map<String, String>> history = new ArrayList<>();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print(">>> ");
            System.out.flush();
            if (!scanner.hasNextLine()) break;

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            if ("/exit".equals(input) || "/quit".equals(input)) {
                break;
            }
            if ("/clear".equals(input)) {
                history.clear();
                System.out.println("Conversation cleared.");
                System.out.println();
                continue;
            }

            // Check if server is still alive
            if (serverProcess != null && !serverProcess.isAlive()) {
                System.err.println("Server process exited unexpectedly.");
                break;
            }

            history.add(createMessage("user", input));

            String response = sendChatCompletion(serverUrl, history);
            if (response == null) {
                System.err.println("Error: Failed to get response from server.");
                // Remove the user message we just added
                history.remove(history.size() - 1);
                continue;
            }

            System.out.println();
            System.out.println(response);
            System.out.println();

            history.add(createMessage("assistant", response));
        }
    }

    private Map<String, String> createMessage(String role, String content) {
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    String sendChatCompletion(
            String serverUrl, List<Map<String, String>> messages) {
        try {
            String url = serverUrl + "/api/llm/chat";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(120_000); // LLM generation can be slow
            conn.setRequestProperty("Content-Type", "application/json");

            // Build the canonical standalone-serving chat request.
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("messages", messages);
            structured.put("tools", List.of());
            structured.put("addGenerationPrompt", true);
            structured.put("toolDefinitionFormat", "STANDARD");
            structured.put("toolCallFormat", "NATIVE");
            structured.put("toolChoice", "NONE");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("request", structured);
            body.put("maxTokens", maxTokens);

            byte[] requestBytes = objectMapper.writeValueAsBytes(body);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(requestBytes);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String errorBody = readStream(conn.getErrorStream());
                System.err.println("Server returned HTTP " + code + ": " + errorBody);
                conn.disconnect();
                return null;
            }

            String responseBody = readStream(conn.getInputStream());
            conn.disconnect();

            // Parse the canonical response: content is user-visible text, while
            // rawText preserves the unparsed model output as a fallback.
            JsonNode root = objectMapper.readTree(responseBody);
            String finishReason = root.path("finishReason").asText("");
            if (finishReason.startsWith("error")) {
                System.err.println("Serving error: " + finishReason);
                return null;
            }
            String content = root.path("content").asText("");
            return content.isBlank() ? root.path("rawText").asText("") : content;

        } catch (IOException e) {
            System.err.println("Request failed: " + e.getMessage());
            return null;
        }
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (Scanner s = new Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    // ==================== Server Lifecycle ====================

    private void stopServer() {
        KompileLocalServingBootstrap.StartupResult runtime = servingRuntime;
        servingRuntime = null;
        serverProcess = null;
        if (runtime != null) {
            runtime.close();
        }
    }
}
