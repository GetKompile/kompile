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
package ai.kompile.cli.main.graph;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LLM completion client for CLI-side graph extraction. Resolves an LLM
 * through a priority chain:
 * <ol>
 *   <li>Explicit provider/model/apiKey passed via CLI flags</li>
 *   <li>ChatConfig (persisted ~/.kompile/chat-config.json or env vars)</li>
 *   <li>Probe localhost:11434 for a running local server</li>
 *   <li>Auto-start: download a GGUF model via the optional SameDiff LLM downloader
 *       and launch kompile-sdk-serving as a separate runner process</li>
 * </ol>
 *
 * <p>The CLI never loads ND4J itself — step 4 delegates inference to
 * kompile-sdk-serving which has its own ND4J backend. The CLI only uses
 * reflection for the optional downloader so the base CLI remains buildable
 * without SameDiff LLM classes on the compile classpath.
 */
public class CliExtractionLlmClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LOCAL_PORT = 11434;

    private final DirectLlmClient llmClient;
    private final String model;
    private String resolvedFrom;

    // Managed server process (if we started one)
    private Process managedServer;

    private CliExtractionLlmClient(ChatConfig config, String model) {
        this.llmClient = new DirectLlmClient(config, MAPPER);
        this.model = model;
    }

    /**
     * Resolve an LLM endpoint using the priority chain.
     *
     * @param explicitProvider explicit provider override (or null)
     * @param explicitModel    explicit model override (or null)
     * @param explicitApiKey   explicit API key override (or null)
     * @param autoStart        if true, download GGUF and launch kompile-sdk-serving
     * @return a ready-to-use client, or null if no LLM could be resolved
     */
    public static CliExtractionLlmClient resolve(String explicitProvider,
                                                   String explicitModel,
                                                   String explicitApiKey,
                                                   boolean autoStart) {
        // 1. Explicit flags
        if (explicitProvider != null && !explicitProvider.isBlank()) {
            ChatConfig config = new ChatConfig(explicitProvider, explicitApiKey,
                    explicitModel, ChatConfig.getDefaultBaseUrl(explicitProvider));
            CliExtractionLlmClient client = new CliExtractionLlmClient(config, explicitModel);
            client.resolvedFrom = "explicit flags (provider=" + explicitProvider + ")";
            return client;
        }

        // 2. ChatConfig (file or env vars)
        ChatConfig chatConfig = ChatConfig.loadOrFromEnv();
        if (chatConfig != null && chatConfig.isValid() && !chatConfig.isKompileServer()) {
            String effectiveModel = explicitModel != null ? explicitModel : chatConfig.getModel();
            CliExtractionLlmClient client = new CliExtractionLlmClient(chatConfig, effectiveModel);
            client.resolvedFrom = "chat config (provider=" + chatConfig.getProvider() + ")";
            return client;
        }

        // 3. Probe for running local kompile server on localhost
        if (isLocalServerRunning()) {
            String localModel = explicitModel != null ? explicitModel : pickLocalModel();
            if (localModel != null) {
                ChatConfig config = new ChatConfig("local", null, localModel, null);
                CliExtractionLlmClient client = new CliExtractionLlmClient(config, localModel);
                client.resolvedFrom = "running local server (model=" + localModel + ")";
                return client;
            }
        }

        // 4. Auto-start: download GGUF and launch kompile-sdk-serving
        if (autoStart) {
            try {
                return autoStartLocalServer(explicitModel);
            } catch (Exception e) {
                System.err.println("Warning: Could not auto-start local model: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        return null;
    }

    /**
     * Returns a human-readable description of how this client was resolved.
     */
    public String getResolvedFrom() {
        return resolvedFrom;
    }

    /**
     * Send a prompt and return the complete text response.
     * Uses {@link DirectLlmClient#streamOneShot} which handles both
     * OpenAI-compatible and Anthropic API formats.
     */
    public String complete(String prompt) {
        DirectLlmClient.StreamResult result = llmClient.streamOneShot(
                prompt, null, model);
        return result.text;
    }

    // ========================================================================
    // Auto-start: download GGUF model and launch kompile-sdk-serving
    // ========================================================================

    private static CliExtractionLlmClient autoStartLocalServer(String explicitModel) throws Exception {
        Class<?> downloaderClass = Class.forName(
                "org.eclipse.deeplearning4j.llm.data.LLMModelDownloader");
        Class<?> llmModelClass = Class.forName(
                "org.eclipse.deeplearning4j.llm.data.LLMModelDownloader$LLMModel");
        Class<?> quantTypeClass = Class.forName(
                "org.eclipse.deeplearning4j.llm.data.LLMModelDownloader$QuantType");

        // Resolve which model to use
        Object llmModel = resolveModel(explicitModel, llmModelClass);
        Object quant = enumValue(quantTypeClass, "Q4_K_M");

        String modelName = getModelName(llmModel);
        String quantSuffix = getQuantSuffix(quant);
        System.err.println("Downloading model: " + modelName
                + " (" + quantSuffix + ") ...");

        // Download GGUF via optional LLMModelDownloader (HTTP only, no ND4J)
        Method download = downloaderClass.getMethod("download", llmModelClass, quantTypeClass);
        Object downloadResult = download.invoke(null, llmModel, quant);
        File ggufFile = (File) downloadResult.getClass().getMethod("getModelFile").invoke(downloadResult);
        boolean downloadedNow = (Boolean) downloadResult.getClass()
                .getMethod("isDownloadedNow")
                .invoke(downloadResult);

        System.err.println(downloadedNow
                ? "Model downloaded: " + ggufFile.getAbsolutePath()
                : "Using cached model: " + ggufFile.getAbsolutePath());

        // Prepare model directory for kompile-sdk-serving
        // Needs: GGUF file + tokenizer.json in same directory
        Path modelDir = prepareModelDirectory(ggufFile, llmModel);

        // Launch kompile-sdk-serving as separate runner
        String modelId = modelName.replace("/", "_");
        Process server = launchServingProcess(modelDir, modelId);

        ChatConfig config = new ChatConfig("local", null, modelId, null);
        CliExtractionLlmClient client = new CliExtractionLlmClient(config, modelId);
        client.managedServer = server;
        client.resolvedFrom = "auto-started kompile server ("
                + modelName + "/" + quantSuffix + ")";

        if (!waitForHealth("http://localhost:" + DEFAULT_LOCAL_PORT, 180)) {
            client.close();
            System.err.println("Error: Local model server did not become ready within 180 seconds.");
            return null;
        }
        System.err.println("Local model server ready.");
        return client;
    }

    /**
     * Resolve a model from user input or default to Qwen3.5-0.8B.
     */
    private static Object resolveModel(String modelId, Class<?> llmModelClass) throws Exception {
        if (modelId == null || modelId.isBlank()) {
            return enumValue(llmModelClass, "QWEN35_0_8B");
        }

        // Try direct enum name: "qwen3.5-0.8b" -> "QWEN35_0_8B"
        String normalized = modelId.toUpperCase()
                .replace(".", "")
                .replace("-", "_");
        try {
            return enumValue(llmModelClass, normalized);
        } catch (IllegalArgumentException ignored) {
        }

        // Try fromSizeLabel
        try {
            String[] parts = modelId.split("-");
            if (parts.length >= 2) {
                Method fromSize = llmModelClass.getMethod("fromSizeLabel", String.class);
                return fromSize.invoke(null, parts[parts.length - 1].toUpperCase());
            }
        } catch (Exception ignored) {
        }

        // Try matching by name
        String lower = modelId.toLowerCase();
        for (Object m : llmModelClass.getEnumConstants()) {
            String name = getModelName(m).toLowerCase();
            if (name.contains(lower) || lower.contains(name.replace("-", ""))) {
                return m;
            }
        }

        // Default
        System.err.println("Warning: Unknown model '" + modelId
                + "', defaulting to Qwen3.5-0.8B");
        return enumValue(llmModelClass, "QWEN35_0_8B");
    }

    /**
     * Prepare a model directory that kompile-sdk-serving can load.
     * Needs the GGUF file plus tokenizer.json in the same directory.
     */
    private static Path prepareModelDirectory(File ggufFile, Object llmModel) throws Exception {
        File cacheDir = ggufFile.getParentFile();
        Path modelDir = cacheDir.toPath().resolve(getModelName(llmModel) + "-serving");
        Files.createDirectories(modelDir);

        // Symlink or copy the GGUF file
        Path ggufLink = modelDir.resolve(ggufFile.getName());
        if (!Files.exists(ggufLink)) {
            try {
                Files.createSymbolicLink(ggufLink, ggufFile.toPath().toAbsolutePath());
            } catch (IOException e) {
                // Symlinks may not be supported — copy instead
                Files.copy(ggufFile.toPath(), ggufLink, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Download tokenizer files from the base model's HuggingFace repo
        String baseRepoUrl = getBaseModelRepoUrl(llmModel);
        for (String filename : new String[]{"tokenizer.json", "tokenizer_config.json"}) {
            Path filePath = modelDir.resolve(filename);
            if (!Files.exists(filePath)) {
                String fileUrl = baseRepoUrl + "/resolve/main/" + filename;
                System.err.println("Downloading " + filename + " ...");
                try {
                    downloadFile(fileUrl, filePath);
                } catch (IOException e) {
                    // tokenizer_config.json may not exist for all models
                    if (filename.equals("tokenizer.json")) throw e;
                    System.err.println("  (not available, skipping)");
                }
            }
        }

        return modelDir;
    }

    /**
     * Get the base model HuggingFace repo URL. The GGUF repo usually doesn't
     * contain tokenizer files, so we fetch from the base model repo.
     */
    private static String getBaseModelRepoUrl(Object model) throws Exception {
        String name = getModelName(model);
        // Map model families to their HuggingFace org
        String org;
        switch (getModelFamilyName(model)) {
            case "QWEN35":
                org = "Qwen";
                break;
            case "GEMMA3":
            case "GEMMA4":
                org = "google";
                break;
            case "LFM2":
                org = "LiquidAI";
                break;
            case "NEMOTRON":
                org = "nvidia";
                break;
            case "OLMO":
                org = "allenai";
                break;
            case "PHI":
                org = "microsoft";
                break;
            case "MISTRAL":
                org = "mistralai";
                break;
            default:
                org = "unsloth";
        }
        return "https://huggingface.co/" + org + "/" + name;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private static String getModelName(Object model) throws Exception {
        return (String) model.getClass().getMethod("getName").invoke(model);
    }

    private static String getModelFamilyName(Object model) throws Exception {
        Object family = model.getClass().getMethod("getFamily").invoke(model);
        return family != null ? family.toString() : "";
    }

    private static String getQuantSuffix(Object quant) throws Exception {
        return (String) quant.getClass().getMethod("getSuffix").invoke(quant);
    }

    private static void downloadFile(String urlStr, Path destination) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Kompile-CLI/1.0");

        int code = conn.getResponseCode();
        // Handle redirects
        if (code == 301 || code == 302 || code == 307 || code == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location != null) {
                downloadFile(location, destination);
                return;
            }
        }
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " downloading " + urlStr);
        }

        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    // ========================================================================
    // Launch kompile-sdk-serving as separate runner
    // ========================================================================

    private static Process launchServingProcess(Path modelDir, String modelId) throws IOException {
        File shadedJar = findServingJar();
        if (shadedJar == null) {
            throw new IOException("kompile-sdk-serving JAR not found. "
                    + "Build it with: cd kompile-sdk-serving && mvn clean package -DskipTests "
                    + "&& cp target/*-shaded.jar ~/.kompile/lib/");
        }

        String javaExec = System.getProperty("java.home") + File.separator
                + "bin" + File.separator + "java";
        List<String> cmd = List.of(
                javaExec,
                "-Dorg.bytedeco.javacpp.pathsFirst=true",
                "-Dorg.bytedeco.javacpp.logger.debug=false",
                "-Dorg.bytedeco.javacpp.nopointergc=true",
                "-jar", shadedJar.getAbsolutePath(),
                "--model-path", modelDir.toAbsolutePath().toString(),
                "--port", String.valueOf(DEFAULT_LOCAL_PORT),
                "--host", "127.0.0.1",
                "--temperature", "0.1",
                "--max-tokens", "4096",
                "--chat-template", "auto",
                "--model-id", modelId
        );

        System.err.println("Starting kompile model server...");
        System.err.println("  Model dir: " + modelDir.toAbsolutePath());
        System.err.println("  Port: " + DEFAULT_LOCAL_PORT);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    private static File findServingJar() {
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

    // ========================================================================
    // Health check and local server probing
    // ========================================================================

    private static boolean waitForHealth(String serverUrl, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        String healthUrl = serverUrl + "/health";

        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(healthUrl).openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) return true;
            } catch (IOException ignored) {
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean isLocalServerRunning() {
        // Check /health first (kompile-sdk-serving primary health endpoint)
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + DEFAULT_LOCAL_PORT + "/health").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200) return true;
        } catch (IOException ignored) {
        }
        // Fall back to /v1/models (OpenAI-compatible endpoint)
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + DEFAULT_LOCAL_PORT + "/v1/models").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static String pickLocalModel() {
        // Query /v1/models (OpenAI-compatible endpoint) to find available models
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://localhost:" + DEFAULT_LOCAL_PORT + "/v1/models").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            String body;
            try (var is = conn.getInputStream()) {
                body = new String(is.readAllBytes());
            }
            conn.disconnect();

            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                return data.get(0).path("id").asText(null);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    @Override
    public void close() {
        if (managedServer != null && managedServer.isAlive()) {
            managedServer.destroy();
            try {
                if (!managedServer.waitFor(5, TimeUnit.SECONDS)) {
                    managedServer.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                managedServer.destroyForcibly();
            }
            managedServer = null;
        }
    }
}
