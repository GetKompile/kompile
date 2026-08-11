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
import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.util.OSResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Starts Kompile's first-party, low-overhead LLM serving subprocess for
 * Standard Chat when no Kompile application instance is selected.
 *
 * <p>The child is the same {@code ServingSubprocessMain} used by the higher
 * level application. It owns the ND4J backend and exposes only the bounded
 * loopback LLM API; it is not a full Kompile instance and is stopped with the
 * CLI chat session.</p>
 */
final class KompileLocalServingBootstrap {

    static final String DEFAULT_MODEL = "Qwen2.5-0.5B-Instruct";
    static final String SERVING_EXECUTABLE_PROPERTY = "kompile.chat.serving.executable";
    static final String SERVING_EXECUTABLE_ENV = "KOMPILE_CHAT_SERVING_EXECUTABLE";
    static final String SERVING_JAR_PROPERTY = "kompile.chat.serving.jar";
    static final String SERVING_JAR_ENV = "KOMPILE_CHAT_SERVING_JAR";
    static final String JAVA_EXECUTABLE_PROPERTY = "kompile.chat.serving.java";
    static final String PORT_PROPERTY = "kompile.chat.serving.port";
    static final String MODEL_ENV = "KOMPILE_CHAT_MODEL_PATH";
    static final String TOKENIZER_ENV = "KOMPILE_CHAT_TOKENIZER_PATH";

    private static final String HOST = "127.0.0.1";
    private static final int OUTPUT_TAIL_LIMIT = 80;
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private KompileLocalServingBootstrap() {
    }

    record ResolvedModel(String modelId, Path modelPath, Path tokenizerPath) {
    }

    record LauncherArtifact(Path path, boolean nativeExecutable) {
        LauncherArtifact {
            path = path.toAbsolutePath().normalize();
        }
    }

    /**
     * Transient runtime values for one CLI chat session. Closing the result
     * stops only the child started by this bootstrap.
     */
    record StartupResult(
            String modelId,
            Path modelPath,
            Path tokenizerPath,
            URI baseUrl,
            LauncherArtifact launcher,
            Process process,
            Path argsFile,
            Thread outputReader) implements AutoCloseable {

        void applyTo(ChatConfig config) {
            config.setModel(modelId);
            config.setBaseUrl(baseUrl.toString());
        }

        @Override
        public void close() {
            stopProcess(process);
            if (outputReader != null) {
                try {
                    outputReader.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (argsFile != null) {
                try {
                    Files.deleteIfExists(argsFile);
                } catch (IOException ignored) {
                    // The OS will remove its temporary directory eventually.
                }
            }
        }
    }

    static StartupResult ensureReady(ChatConfig config, int timeoutSeconds)
            throws BootstrapException {
        if (config == null || !config.isKompileLocalServing()) {
            throw new BootstrapException(
                    "Kompile local serving requires provider 'kompile-local'.");
        }

        Path componentDirectory = new ComponentRegistry()
                .getInstallDirectory(ComponentRegistry.KOMPILE_APP_MAIN)
                .toPath().toAbsolutePath().normalize();
        Path installHome = resolveInstallHome(componentDirectory);
        Process process = null;
        Path argsFile = null;
        Thread outputReader = null;
        List<String> outputTail = new ArrayList<>();

        try {
            ResolvedModel model = resolveModel(
                    config.getModel(), installHome, KompileHome.homeDirectory().toPath(),
                    System.getenv());
            LauncherArtifact launcher = resolveLauncher(
                    installHome, componentDirectory, System.getProperties(), System.getenv());
            int port = resolvePort(System.getProperties());
            URI baseUrl = URI.create("http://" + HOST + ":" + port);
            argsFile = writeServingArgs(model, port);

            List<String> command = buildCommand(
                    launcher, argsFile, System.getProperties());
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            outputReader = drainOutput(process, outputTail);

            waitForReady(
                    process, baseUrl, model.modelId(), Math.max(1, timeoutSeconds), outputTail);

            System.out.println("Using Kompile's first-party serving subprocess:");
            System.out.println("  Runtime: " + launcher.path());
            System.out.println("  Model: " + model.modelPath());
            System.out.println("  Endpoint: " + baseUrl);
            if (model.tokenizerPath() != null) {
                System.out.println("  Tokenizer: " + model.tokenizerPath());
            } else {
                System.out.println("  Tokenizer: model-owned / GGUF embedded");
            }

            return new StartupResult(
                    model.modelId(), model.modelPath(), model.tokenizerPath(), baseUrl,
                    launcher, process, argsFile, outputReader);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopProcess(process);
            deleteQuietly(argsFile);
            throw new BootstrapException(
                    "Interrupted while starting the Kompile serving subprocess", e);
        } catch (IOException | RuntimeException e) {
            stopProcess(process);
            deleteQuietly(argsFile);
            throw new BootstrapException(e.getMessage(), e);
        }
    }

    static Path resolveInstallHome(Path componentDirectory) {
        Path cursor = componentDirectory;
        for (int i = 0; i < 3 && cursor != null; i++) {
            cursor = cursor.getParent();
        }
        return cursor == null
                ? KompileHome.homeDirectory().toPath().toAbsolutePath().normalize()
                : cursor.toAbsolutePath().normalize();
    }

    static LauncherArtifact resolveLauncher(
            Path installHome,
            Path componentDirectory,
            Properties properties,
            Map<String, String> environment) throws IOException {
        String explicitExecutable = firstNonBlank(
                property(properties, SERVING_EXECUTABLE_PROPERTY),
                environment == null ? null : environment.get(SERVING_EXECUTABLE_ENV));
        if (explicitExecutable != null) {
            Path path = expandPath(explicitExecutable);
            if (!isRunnable(path)) {
                throw new IOException("Configured " + SERVING_EXECUTABLE_ENV
                        + " is not an executable file: " + path);
            }
            return new LauncherArtifact(path, true);
        }

        String explicitJar = firstNonBlank(
                property(properties, SERVING_JAR_PROPERTY),
                environment == null ? null : environment.get(SERVING_JAR_ENV));
        if (explicitJar != null) {
            Path path = expandPath(explicitJar);
            if (!Files.isRegularFile(path)) {
                throw new IOException("Configured " + SERVING_JAR_ENV
                        + " is not a readable JAR: " + path);
            }
            return new LauncherArtifact(path, false);
        }

        if (installHome != null) {
            String suffix = OSResolver.isWindows() ? ".exe" : "";
            Path bin = installHome.toAbsolutePath().normalize().resolve("bin");
            for (String name : List.of(
                    "kompile-app-main" + suffix,
                    "kompile-app" + suffix,
                    "kompile-server" + suffix)) {
                Path candidate = bin.resolve(name);
                if (isRunnable(candidate)) {
                    return new LauncherArtifact(candidate, true);
                }
            }
        }

        Path componentJar = findAppJar(componentDirectory);
        if (componentJar != null) {
            return new LauncherArtifact(componentJar, false);
        }

        if (installHome != null) {
            for (String name : List.of("kompile-app-main.jar", "kompile-server.jar")) {
                Path candidate = installHome.resolve("bin").resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return new LauncherArtifact(candidate, false);
                }
            }
        }

        Path developmentJar = findDevelopmentExecJar();
        if (developmentJar != null) {
            return new LauncherArtifact(developmentJar, false);
        }

        throw new IOException(
                "The installed Kompile distribution does not contain its serving runtime. "
                        + "Expected the kompile-app native executable or kompile-app-main fat JAR. "
                        + "Install a full Kompile distribution, or set "
                        + SERVING_EXECUTABLE_ENV + " / " + SERVING_JAR_ENV + ".");
    }

    static List<String> buildCommand(
            LauncherArtifact launcher, Path argsFile, Properties properties) throws IOException {
        List<String> command = new ArrayList<>();
        if (launcher.nativeExecutable()) {
            command.add(launcher.path().toString());
        } else {
            String configuredJava = property(properties, JAVA_EXECUTABLE_PROPERTY);
            if (configuredJava != null && !configuredJava.isBlank()) {
                Path java = expandPath(configuredJava);
                if (!isRunnable(java)) {
                    throw new IOException(
                            "Configured " + JAVA_EXECUTABLE_PROPERTY
                                    + " is not an executable file: " + java);
                }
                command.add(java.toString());
            } else {
                // Shared distribution-aware lookup: bundled jlink runtime first,
                // then JAVA_HOME/current JVM/PATH. This also works from native CLI.
                command.add(JavaRuntimeLocator.javaExecutable());
            }
        }

        command.add("-Xmx8g");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dorg.bytedeco.javacpp.pathsFirst=true");
        command.add("-Dorg.bytedeco.javacpp.nopointergc=true");
        if (!launcher.nativeExecutable()) {
            command.add("-jar");
            command.add(launcher.path().toString());
        }
        command.add("--subprocess=serving");
        command.add(argsFile.toAbsolutePath().normalize().toString());
        return command;
    }

    static Path writeServingArgs(ResolvedModel model, int port) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("port", port);
        root.put("host", HOST);
        root.put("stagingUrl", "http://127.0.0.1:8090");
        root.put("modelId", model.modelId());
        root.put("modelPath", model.modelPath().toAbsolutePath().normalize().toString());
        if (model.tokenizerPath() == null) {
            root.putNull("tokenizerPath");
        } else {
            root.put("tokenizerPath",
                    model.tokenizerPath().toAbsolutePath().normalize().toString());
        }
        root.putNull("nd4jConfigJson");
        root.put("memoryThresholdPercent", 85);
        root.put("memoryCriticalPercent", 90);
        root.put("memoryKillThresholdPercent", 95);
        root.put("memoryCheckIntervalMs", 5000L);
        root.put("gpuMemoryThresholdPercent", 85);
        root.put("gpuMemoryCriticalPercent", 90);
        root.put("gpuMemoryKillThresholdPercent", 95);
        root.put("gpuSoftLimitPercent", 80);
        root.put("offHeapThresholdPercent", 85);
        root.put("offHeapCriticalPercent", 90);
        root.put("offHeapKillThresholdPercent", 95);
        root.put("maxNewTokens", 1024);
        root.put("temperature", 0.7);
        root.put("topK", 0);
        root.putNull("dspEnabled");
        root.putNull("optimizerEnabled");
        root.putNull("optimizerFp16");

        Path argsFile = Files.createTempFile("kompile-chat-serving-", ".json");
        MAPPER.writeValue(argsFile.toFile(), root);
        return argsFile;
    }

    static void waitForReady(
            Process process,
            URI baseUrl,
            String requestedModelId,
            int timeoutSeconds,
            List<String> outputTail) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        IOException lastError = null;

        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException(
                        "Kompile serving subprocess exited with " + process.exitValue()
                                + formatOutputTail(outputTail));
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                baseUrl.resolve("/api/llm/status"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode status = MAPPER.readTree(response.body());
                    boolean loaded = status.path("loaded").asBoolean(false);
                    String modelId = status.path("modelId").asText(null);
                    if (loaded && requestedModelId.equals(modelId)) {
                        return;
                    }
                    if (loaded) {
                        throw new IOException(
                                "Kompile serving subprocess loaded '" + modelId
                                        + "' instead of '" + requestedModelId + "'");
                    }
                }
            } catch (IOException e) {
                lastError = e;
            }
            Thread.sleep(250);
        }

        String detail = lastError == null ? "" : ": " + lastError.getMessage();
        throw new IOException(
                "Timed out after " + timeoutSeconds
                        + "s waiting for Kompile's serving subprocess" + detail
                        + formatOutputTail(outputTail));
    }

    private static Thread drainOutput(Process process, List<String> outputTail) {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    synchronized (outputTail) {
                        outputTail.add(line);
                        if (outputTail.size() > OUTPUT_TAIL_LIMIT) {
                            outputTail.remove(0);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Closing the child closes its output stream.
            }
        }, "kompile-chat-serving-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static String formatOutputTail(List<String> outputTail) {
        synchronized (outputTail) {
            if (outputTail.isEmpty()) return "";
            return "\nServing subprocess output:\n" + String.join("\n", outputTail);
        }
    }

    private static void stopProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup for a failed startup.
        }
    }

    private static int resolvePort(Properties properties) throws IOException {
        String configured = property(properties, PORT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                int port = Integer.parseInt(configured.trim());
                if (port < 1 || port > 65535) throw new NumberFormatException();
                return port;
            } catch (NumberFormatException e) {
                throw new IOException(PORT_PROPERTY + " must be a port from 1 to 65535");
            }
        }
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Path findAppJar(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return null;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("kompile-app-main-") && name.endsWith(".jar");
                    })
                    .sorted(Comparator.comparingLong(KompileLocalServingBootstrap::sizeOrZero)
                            .reversed())
                    .map(path -> path.toAbsolutePath().normalize())
                    .findFirst().orElse(null);
        }
    }

    private static long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Path findDevelopmentExecJar() {
        Path cursor = Path.of(System.getProperty("user.dir", "."))
                .toAbsolutePath().normalize();
        for (int i = 0; i < 7 && cursor != null; i++, cursor = cursor.getParent()) {
            Path target = cursor.resolve("kompile-app")
                    .resolve("kompile-app-parent")
                    .resolve("kompile-app-main")
                    .resolve("target");
            if (!Files.isDirectory(target)) continue;
            try (var files = Files.list(target)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                        .sorted(Comparator.comparingLong(KompileLocalServingBootstrap::sizeOrZero)
                                .reversed())
                        .findFirst().orElse(null);
                if (match != null) return match.toAbsolutePath().normalize();
            } catch (IOException ignored) {
                // Continue walking toward the workspace root.
            }
        }
        return null;
    }

    static ResolvedModel resolveModel(String selection,
                                      Path installHome,
                                      Path kompileHome,
                                      Map<String, String> environment)
            throws IOException {
        String requested = selection == null || selection.isBlank()
                ? DEFAULT_MODEL : selection.trim();
        Path requestedPath = expandPath(requested);
        if (looksLikePath(requested)) {
            if (!Files.exists(requestedPath)) {
                throw new IOException("Configured Kompile local model does not exist: "
                        + requestedPath);
            }
            return resolveLocalModel(
                    requestedPath, requested, installHome, kompileHome, environment);
        }

        String environmentModel = environment == null ? null : environment.get(MODEL_ENV);
        if (environmentModel != null && !environmentModel.isBlank()) {
            Path path = expandPath(environmentModel);
            if (!Files.exists(path)) {
                throw new IOException(MODEL_ENV + " does not exist: " + path);
            }
            return resolveLocalModel(
                    path, requested, installHome, kompileHome, environment);
        }

        Set<Path> roots = new LinkedHashSet<>();
        if (installHome != null) {
            roots.add(installHome.resolve("models").resolve("chat"));
            roots.add(installHome.resolve("sdx-sdk").resolve("models"));
        }
        if (kompileHome != null) {
            roots.add(kompileHome.resolve("models").resolve("chat"));
        }
        Path userHome = Path.of(System.getProperty("user.home", "."));
        roots.add(userHome.resolve(".cache").resolve("dl4j-llm-models"));

        String normalizedRequest = normalizeName(requested);
        List<Path> matches = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.walk(root, 3)) {
                files.filter(Files::isRegularFile)
                        .filter(KompileLocalServingBootstrap::isSupportedModelFile)
                        .filter(path -> matchesModel(
                                normalizeName(path.getFileName().toString()),
                                normalizedRequest))
                        .forEach(matches::add);
            }
        }
        matches.sort(Comparator.comparingInt(KompileLocalServingBootstrap::modelPriority)
                .thenComparing(path -> path.getFileName().toString()));

        if (matches.isEmpty()) {
            throw new IOException("No installed Kompile chat model matches '" + requested
                    + "'. Put a .gguf or .sdz model under ~/.kompile/models/chat, "
                    + "set " + MODEL_ENV + ", or choose Custom in 'kompile chat --setup'.");
        }
        return resolveLocalModel(
                matches.get(0), requested, installHome, kompileHome, environment);
    }

    static ResolvedModel resolveLocalModel(Path candidate, String modelId)
            throws IOException {
        return resolveLocalModel(candidate, modelId, null, null, System.getenv());
    }

    private static ResolvedModel resolveLocalModel(
            Path candidate,
            String modelId,
            Path installHome,
            Path kompileHome,
            Map<String, String> environment) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path model = normalized;
        if (Files.isDirectory(normalized)) {
            try (var files = Files.list(normalized)) {
                model = files.filter(Files::isRegularFile)
                        .filter(KompileLocalServingBootstrap::isSupportedModelFile)
                        .sorted(Comparator
                                .comparingInt(KompileLocalServingBootstrap::modelPriority)
                                .thenComparing(path -> path.getFileName().toString()))
                        .findFirst()
                        .orElseThrow(() -> new IOException(
                                "No .gguf or .sdz model found in " + normalized));
            }
        } else if (!isSupportedModelFile(normalized)) {
            throw new IOException(
                    "Kompile's serving subprocess requires a .gguf or .sdz model: "
                            + normalized);
        }

        String identity = modelId == null || modelId.isBlank()
                ? model.getFileName().toString() : modelId;
        Path tokenizer = resolveTokenizer(
                model, identity, installHome, kompileHome, environment);
        if (tokenizer == null) {
            throw new IOException("No tokenizer.json found for Kompile chat model '"
                    + identity + "'. Expected a sidecar next to " + model
                    + ", a matching packaged tokenizer under ~/.kompile/models/tokenizers, "
                    + "or " + TOKENIZER_ENV + ".");
        }
        return new ResolvedModel(identity, model, tokenizer);
    }

    private static Path resolveTokenizer(
            Path model,
            String modelId,
            Path installHome,
            Path kompileHome,
            Map<String, String> environment) throws IOException {
        String configured = environment == null ? null : environment.get(TOKENIZER_ENV);
        if (configured != null && !configured.isBlank()) {
            Path tokenizer = expandPath(configured);
            if (!Files.isRegularFile(tokenizer)) {
                throw new IOException(TOKENIZER_ENV + " does not exist: " + tokenizer);
            }
            return tokenizer.toAbsolutePath().normalize();
        }

        Path parent = model.getParent();
        if (parent != null) {
            Path sidecar = parent.resolve("tokenizer.json");
            if (Files.isRegularFile(sidecar)) return sidecar.toAbsolutePath().normalize();
        }

        Set<Path> roots = new LinkedHashSet<>();
        if (installHome != null) {
            roots.add(installHome.resolve("models").resolve("tokenizers"));
        }
        if (kompileHome != null) {
            roots.add(kompileHome.resolve("models").resolve("tokenizers"));
        }
        roots.add(Path.of(System.getProperty("user.home", "."))
                .resolve(".kompile").resolve("models").resolve("tokenizers"));

        String normalizedId = normalizeName(modelId);
        String normalizedFile = normalizeName(model.getFileName().toString());
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            List<Path> matches = new ArrayList<>();
            try (var files = Files.walk(root, 3)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> "tokenizer.json".equals(
                                path.getFileName().toString()))
                        .filter(path -> {
                            Path directory = path.getParent();
                            String normalizedDirectory = directory == null
                                    ? "" : normalizeName(directory.getFileName().toString());
                            return !normalizedDirectory.isBlank()
                                    && (normalizedId.contains(normalizedDirectory)
                                    || normalizedFile.contains(normalizedDirectory));
                        })
                        .forEach(matches::add);
            }
            matches.sort(Comparator
                    .comparingInt(KompileLocalServingBootstrap::tokenizerSpecificity)
                    .reversed()
                    .thenComparing(Path::toString));
            if (!matches.isEmpty()) {
                return matches.get(0).toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static int tokenizerSpecificity(Path tokenizer) {
        Path directory = tokenizer.getParent();
        return directory == null ? 0
                : normalizeName(directory.getFileName().toString()).length();
    }

    private static boolean matchesModel(String fileName, String requested) {
        if (requested == null || requested.isBlank()) return true;
        if (fileName.contains(requested)) return true;
        if (requested.contains("qwen2505binstruct")) {
            return fileName.contains("qwen2505binstruct");
        }
        if (requested.contains("qwen2515binstruct")) {
            return fileName.contains("qwen2515binstruct");
        }
        return requested.contains(fileName.replace("q4km", "")
                .replace("fp16", "").replace("gguf", "").replace("sdz", ""));
    }

    private static int modelPriority(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".gguf") && name.contains("q4_k_m")) return 0;
        if (name.endsWith(".gguf") && name.contains("q4")) return 1;
        if (name.endsWith(".gguf") && name.contains("fp16")) return 2;
        if (name.endsWith(".gguf")) return 3;
        return 4;
    }

    private static boolean isSupportedModelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gguf") || name.endsWith(".sdz");
    }

    private static boolean isRunnable(Path path) {
        return path != null && Files.isRegularFile(path)
                && (OSResolver.isWindows() || Files.isExecutable(path));
    }

    private static String normalizeName(String value) {
        return value == null ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean looksLikePath(String value) {
        return value != null && (value.startsWith(".")
                || value.startsWith("~")
                || value.contains("/")
                || value.contains("\\"));
    }

    private static Path expandPath(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if ("~".equals(normalized) || normalized.startsWith("~/")
                || normalized.startsWith("~" + java.io.File.separator)) {
            normalized = System.getProperty("user.home", ".") + normalized.substring(1);
        }
        return Path.of(normalized).toAbsolutePath().normalize();
    }

    private static String property(Properties properties, String name) {
        return properties == null ? null : properties.getProperty(name);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        return second == null || second.isBlank() ? null : second.trim();
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
