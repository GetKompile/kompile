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

import ai.kompile.modelmanager.KompileModelManager;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Process serverProcess;

    @Override
    public Integer call() throws Exception {
        // 1. Resolve model to a local path
        Path modelPath;
        try {
            modelPath = resolveModelPath();
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        // 2. Find the serving JAR
        File shadedJar = findShadedJar();
        if (shadedJar == null) {
            System.err.println("Error: kompile-sdk-serving shaded JAR not found.");
            System.err.println();
            System.err.println("Searched:");
            System.err.println("  ~/.kompile/lib/");
            System.err.println("  kompile-sdk-serving/target/");
            System.err.println("  current directory");
            System.err.println();
            System.err.println("Build it with:");
            System.err.println("  cd kompile-sdk-serving && mvn clean package -DskipTests");
            System.err.println("Then copy to ~/.kompile/lib/");
            return 1;
        }

        // 3. Launch server subprocess
        System.out.println("Loading model...");
        try {
            serverProcess = launchServer(modelPath, shadedJar);
        } catch (IOException e) {
            System.err.println("Error launching server: " + e.getMessage());
            return 1;
        }

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer, "kompile-run-shutdown"));

        // 4. Wait for server to become healthy
        String serverUrl = "http://" + host + ":" + port;
        if (!waitForServer(serverUrl, 180)) {
            if (serverProcess != null && !serverProcess.isAlive()) {
                System.err.println("Error: Server process exited with code " + serverProcess.exitValue());
            } else {
                System.err.println("Error: Server did not become ready within 180 seconds.");
                System.err.println("The model may require more memory or a faster machine.");
            }
            stopServer();
            return 1;
        }

        // 5. Print endpoint info
        String modelId = model.contains("/") ? model : new File(model).getName();
        System.out.println();
        System.out.println("Model ready: " + modelId);
        System.out.println("  OpenAI API: " + serverUrl + "/v1/chat/completions");
        System.out.println("  Health:     " + serverUrl + "/health");
        System.out.println("  Models:     " + serverUrl + "/v1/models");

        if (serveOnly) {
            System.out.println();
            System.out.println("Running as API server. Press Ctrl+C to stop.");
            serverProcess.waitFor();
            return serverProcess.exitValue();
        }

        // 6. Enter interactive chat REPL
        runRepl(serverUrl, modelId);

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

    private Process launchServer(Path modelPath, File shadedJar) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(getJavaExecutable());

        // Backend-specific JVM args
        if ("cuda".equalsIgnoreCase(backend)) {
            cmd.add("-Dnd4j.backend=nd4j-cuda");
        }

        cmd.add("-jar");
        cmd.add(shadedJar.getAbsolutePath());
        cmd.add("--model-path");
        cmd.add(modelPath.toAbsolutePath().toString());
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--host");
        cmd.add(host);
        cmd.add("--temperature");
        cmd.add(String.valueOf(temperature));
        cmd.add("--max-tokens");
        cmd.add(String.valueOf(maxTokens));
        cmd.add("--chat-template");
        cmd.add(chatTemplate);
        cmd.add("--model-id");
        cmd.add(model.replace("/", "_"));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // Let server logs flow to our stderr so user sees loading progress
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        // Propagate relevant env vars
        Map<String, String> env = pb.environment();
        propagateEnv(env, "ND4J_BACKEND", "CUDA_VISIBLE_DEVICES", "OMP_NUM_THREADS",
                "MKL_NUM_THREADS", "KOMPILE_MODELS_DIR", "JAVACPP_PLATFORM");

        return pb.start();
    }

    private void propagateEnv(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String val = System.getenv(key);
            if (val != null && !val.isEmpty()) {
                env.put(key, val);
            }
        }
        // Also propagate all ND4J_ and KOMPILE_ prefixed vars
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if ((e.getKey().startsWith("ND4J_") || e.getKey().startsWith("KOMPILE_"))
                    && !env.containsKey(e.getKey())) {
                env.put(e.getKey(), e.getValue());
            }
        }
    }

    private File findShadedJar() {
        String home = System.getProperty("user.home");
        String[] searchPaths = {
                home + "/.kompile/lib",
                home + "/.kompile/jars",
                "kompile-sdk-serving/target",
                "."
        };

        for (String path : searchPaths) {
            File dir = new File(path);
            if (dir.isDirectory()) {
                File[] matches = dir.listFiles((d, name) ->
                        name.startsWith("kompile-sdk-serving") && name.endsWith("-shaded.jar"));
                if (matches != null && matches.length > 0) {
                    Arrays.sort(matches, Comparator.comparingLong(File::lastModified).reversed());
                    return matches[0];
                }
            }
        }
        return null;
    }

    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            return javaHome + File.separator + "bin" + File.separator + "java";
        }
        return "java";
    }

    // ==================== Health Polling ====================

    private boolean waitForServer(String serverUrl, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        String healthUrl = serverUrl + "/health";
        boolean firstAttempt = true;

        while (System.currentTimeMillis() < deadline) {
            // Check if process died
            if (serverProcess != null && !serverProcess.isAlive()) {
                return false;
            }

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(healthUrl).openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    return true;
                }
            } catch (IOException ignored) {
                // Server not ready yet
            }

            if (firstAttempt) {
                firstAttempt = false;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ==================== Interactive Chat REPL ====================

    private void runRepl(String serverUrl, String modelId) {
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

            String response = sendChatCompletion(serverUrl, modelId, history);
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

    private String sendChatCompletion(String serverUrl, String modelId,
                                       List<Map<String, String>> messages) {
        try {
            String url = serverUrl + "/v1/chat/completions";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(120_000); // LLM generation can be slow
            conn.setRequestProperty("Content-Type", "application/json");

            // Build request body
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelId);
            body.put("messages", messages);
            body.put("stream", false);

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

            // Parse response: {"choices": [{"message": {"content": "..."}}]}
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }

            System.err.println("Unexpected response format: " + responseBody);
            return null;

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
        Process p = serverProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                boolean exited = p.waitFor(5, TimeUnit.SECONDS);
                if (!exited) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }
}
