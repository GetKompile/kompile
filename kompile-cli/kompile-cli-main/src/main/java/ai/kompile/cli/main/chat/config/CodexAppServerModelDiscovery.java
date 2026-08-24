/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Discovers the authenticated Codex catalog from the official app-server
 * {@code model/list} method.
 */
final class CodexAppServerModelDiscovery {
    static final String ATTEMPTED_RESOURCE = "native:codex app-server/model/list";
    static final String CODEX_SHIM_ENV = "KOMPILE_CODEX_APP_SERVER_SHIM";
    static final String CODEX_HOME_ENV = "CODEX_HOME";
    private static final ObjectMapper MAPPER =
            ai.kompile.cli.common.util.JsonUtils.standardMapper();

    private CodexAppServerModelDiscovery() {
    }

    static ModelDiscovery.Result discover(ModelDiscovery.Context context) {
        String executable = System.getProperty("kompile.codex.executable", "codex");
        LaunchSpec launch = appServerCommand(executable);
        if (!launch.available()) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.UNSUPPORTED,
                    launch.error(),
                    List.of(ATTEMPTED_RESOURCE));
        }
        return discover(context, launch.command(), launch.environment());
    }

    static LaunchSpec appServerCommand(String executable) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        return appServerCommand(
                executable,
                windows,
                environment("PATH", "Path"),
                environment("PATHEXT", "PathExt"),
                environment("ComSpec", "COMSPEC"));
    }

    /**
     * Builds a platform-safe Codex app-server command.
     *
     * <p>npm exposes Codex as {@code codex.cmd} on Windows. Windows cannot
     * execute that shim directly through {@link ProcessBuilder}, so resolve it
     * using {@code PATH}/{@code PATHEXT} and invoke it through {@code ComSpec}.
     * Native executables remain direct child processes.</p>
     */
    static LaunchSpec appServerCommand(
            String executable,
            boolean windows,
            String path,
            String pathExt,
            String commandInterpreter) {
        String configured = executable == null || executable.isBlank()
                ? "codex" : executable.trim();
        String discovered = windows
                ? resolveWindowsExecutable(configured, path, pathExt) : null;
        if (windows && discovered == null) {
            return LaunchSpec.unavailable(
                    "Unable to start Codex app-server: configured Codex executable '"
                            + configured + "' was not found at its configured path or on PATH");
        }
        String resolved = discovered == null ? configured : discovered;
        if (windows && discovered != null && isBatchFile(resolved)) {
            String interpreter = commandInterpreter == null ? "" : commandInterpreter.trim();
            if (!isAbsoluteWindowsPath(interpreter)) {
                return LaunchSpec.unavailable(
                        "Unable to start Codex app-server: Windows ComSpec is unavailable");
            }
            return new LaunchSpec(
                    List.of(interpreter, "/d", "/v:off", "/s", "/c",
                            "\"%" + CODEX_SHIM_ENV + "%\" app-server"),
                    Map.of(CODEX_SHIM_ENV, resolved), "");
        }
        return new LaunchSpec(List.of(resolved, "app-server"), Map.of(), "");
    }

    private static String resolveWindowsExecutable(
            String executable, String path, String pathExt) {
        List<String> extensions = windowsExecutableExtensions(pathExt);
        if (isPathReference(executable)) {
            return existingWindowsExecutable(path(executable), extensions);
        }
        if (path != null && !path.isBlank()) {
            for (String entry : path.split(";")) {
                String directory = stripOuterQuotes(entry.trim());
                if (directory.isBlank()) {
                    continue;
                }
                Path root = path(directory);
                if (root == null) {
                    continue;
                }
                String resolved = existingWindowsExecutable(resolve(root, executable), extensions);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private static String existingWindowsExecutable(Path candidate, List<String> extensions) {
        if (candidate == null) {
            return null;
        }
        String fileName = candidate.getFileName() == null
                ? "" : candidate.getFileName().toString();
        if (fileName.lastIndexOf('.') > 0 && Files.isRegularFile(candidate)) {
            return candidate.toAbsolutePath().normalize().toString();
        }
        for (String extension : extensions) {
            Path withExtension = candidate.resolveSibling(fileName + extension);
            if (Files.isRegularFile(withExtension)) {
                return withExtension.toAbsolutePath().normalize().toString();
            }
        }
        return Files.isRegularFile(candidate)
                ? candidate.toAbsolutePath().normalize().toString() : null;
    }

    private static List<String> windowsExecutableExtensions(String pathExt) {
        String configured = pathExt == null || pathExt.isBlank()
                ? ".com;.exe;.bat;.cmd" : pathExt;
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        for (String value : configured.split(";")) {
            String extension = value.trim().toLowerCase(Locale.ROOT);
            if (extension.isBlank()) {
                continue;
            }
            String normalized = extension.startsWith(".") ? extension : "." + extension;
            if (normalized.matches("\\.[a-z0-9]+")) {
                extensions.add(normalized);
            }
        }
        return List.copyOf(extensions);
    }

    private static boolean isPathReference(String executable) {
        Path value = path(executable);
        return executable.contains("/") || executable.contains("\\")
                || value != null && value.isAbsolute();
    }

    private static boolean isBatchFile(String executable) {
        String normalized = executable.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".cmd") || normalized.endsWith(".bat");
    }

    private static boolean isAbsoluteWindowsPath(String value) {
        Path candidate = path(value);
        return candidate != null && candidate.isAbsolute()
                || value.matches("^[A-Za-z]:[\\\\/].+")
                || value.startsWith("\\\\");
    }

    private static Path path(String value) {
        try {
            return value == null || value.isBlank() ? null : Path.of(value);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static Path resolve(Path root, String child) {
        try {
            return root.resolve(child);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static String stripOuterQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static String environment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    record LaunchSpec(List<String> command, Map<String, String> environment, String error) {
        LaunchSpec {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
            error = error == null ? "" : error;
        }

        static LaunchSpec unavailable(String error) {
            return new LaunchSpec(List.of(), Map.of(), error);
        }

        boolean available() {
            return error.isBlank();
        }
    }

    static ModelDiscovery.Result discover(
            ModelDiscovery.Context context, List<String> command) {
        return discover(context, command, Map.of());
    }

    static ModelDiscovery.Result discover(
            ModelDiscovery.Context context,
            List<String> command,
            Map<String, String> environment) {
        Duration timeout = context == null || context.timeout() == null
                ? Duration.ofSeconds(15) : context.timeout();
        OAuthProviderFlow.RequestAuth auth = context == null ? null : context.auth();
        if (auth == null || !auth.oauth()) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.AUTH_REQUIRED,
                    "OpenAI subscription model discovery requires the selected Kompile OAuth credential; sign in again and retry",
                    List.of(ATTEMPTED_RESOURCE));
        }
        String accountId = header(auth.headers(), "chatgpt-account-id");
        if (accountId == null || accountId.isBlank()) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.AUTH_REQUIRED,
                    "The selected OpenAI OAuth credential has no ChatGPT account id; sign in again and retry",
                    List.of(ATTEMPTED_RESOURCE));
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = null;
        ExecutorService readerExecutor = null;
        Path discoveryHome = null;
        List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
        String runtime = launchDescription(command, environment);
        String stage = "starting the Codex app-server";
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(List.copyOf(command))
                    .redirectErrorStream(false);
            processBuilder.environment().putAll(environment);
            discoveryHome = Files.createTempDirectory("kompile-codex-model-discovery-");
            processBuilder.environment().put(CODEX_HOME_ENV, discoveryHome.toString());
            process = processBuilder.start();
            BlockingQueue<ReadEvent> events = new LinkedBlockingQueue<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8));
            readerExecutor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "kompile-codex-model-list");
                thread.setDaemon(true);
                return thread;
            });
            readerExecutor.submit(() -> pump(reader, events));
            readerExecutor.submit(() -> pumpDiagnostics(errorReader, diagnostics));

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8))) {
                stage = "initializing the Codex app-server";
                ObjectNode initialize = MAPPER.createObjectNode();
                initialize.put("method", "initialize");
                initialize.put("id", 1);
                ObjectNode initializeParams = initialize.putObject("params");
                ObjectNode clientInfo = initializeParams.putObject("clientInfo");
                clientInfo.put("name", "kompile");
                clientInfo.put("title", "Kompile CLI");
                clientInfo.put("version", "0.1.0");
                initializeParams.putObject("capabilities").put("experimentalApi", true);
                send(writer, initialize);
                JsonNode initializeResponse = awaitResponse(events, 1, deadline, diagnostics);
                runtime = runtimeDescription(initializeResponse, runtime);

                ObjectNode initialized = MAPPER.createObjectNode();
                initialized.put("method", "initialized");
                initialized.putObject("params");
                send(writer, initialized);

                stage = "binding the selected Kompile OAuth credential";
                ObjectNode login = MAPPER.createObjectNode();
                login.put("method", "account/login/start");
                login.put("id", 2);
                ObjectNode loginParams = login.putObject("params");
                loginParams.put("type", "chatgptAuthTokens");
                loginParams.put("accessToken", auth.token());
                loginParams.put("chatgptAccountId", accountId);
                send(writer, login);
                JsonNode loginResponse = awaitResponse(events, 2, deadline, diagnostics);
                String authType = loginResponse.path("result").path("type").asText("");
                if (!"chatgptAuthTokens".equals(authType)) {
                    throw new RpcFailure("Codex app-server returned unexpected authentication mode '"
                            + (authType.isBlank() ? "unknown" : authType) + "'");
                }

                ObjectNode accountRead = MAPPER.createObjectNode();
                accountRead.put("method", "account/read");
                accountRead.put("id", 3);
                accountRead.putObject("params").put("refreshToken", false);
                send(writer, accountRead);
                JsonNode accountResponse = awaitResponse(events, 3, deadline, diagnostics);
                JsonNode account = accountResponse.path("result").path("account");
                if (account.isMissingNode() || account.isNull() || !account.isObject()) {
                    throw new RpcFailure(
                            "Codex app-server did not activate the selected OpenAI subscription account");
                }
                String planType = account.path("planType").asText("");

                stage = "listing models for the selected OpenAI subscription";
                Map<String, LiveModelDiscovery.Model> models = new LinkedHashMap<>();
                String cursor = null;
                int requestId = 4;
                for (int page = 0; page < 20; page++) {
                    ObjectNode request = MAPPER.createObjectNode();
                    request.put("method", "model/list");
                    request.put("id", requestId);
                    ObjectNode params = request.putObject("params");
                    params.put("limit", 100);
                    params.put("includeHidden", false);
                    if (cursor != null && !cursor.isBlank()) {
                        params.put("cursor", cursor);
                    }
                    send(writer, request);
                    JsonNode response = awaitResponse(events, requestId, deadline, diagnostics);
                    JsonNode result = response.path("result");
                    for (LiveModelDiscovery.Model model : parseModels(result)) {
                        models.merge(model.id(), model, LiveModelDiscovery::merge);
                    }
                    cursor = result.path("nextCursor").asText("");
                    if (cursor.isBlank()) {
                        if (usedBundledOrCachedFallback(diagnostics)) {
                            return ModelDiscovery.Result.failure(
                                    ModelDiscovery.Status.UNAVAILABLE,
                                    failureMessage(stage,
                                            "Codex could not refresh the online model catalog and returned cached or bundled models",
                                            runtime, process, diagnostics, false),
                                    List.of(ATTEMPTED_RESOURCE));
                        }
                        List<LiveModelDiscovery.Model> discovered = new ArrayList<>(models.values());
                        return new ModelDiscovery.Result(
                                discovered.isEmpty()
                                        ? ModelDiscovery.Status.SUCCESS_EMPTY
                                        : ModelDiscovery.Status.SUCCESS,
                                discovered,
                                successMessage(runtime, planType, discovered.size()),
                                List.of(ATTEMPTED_RESOURCE));
                    }
                    requestId++;
                }
                if (usedBundledOrCachedFallback(diagnostics)) {
                    return ModelDiscovery.Result.failure(
                            ModelDiscovery.Status.UNAVAILABLE,
                            failureMessage(stage,
                                    "Codex could not refresh the online model catalog and returned cached or bundled models",
                                    runtime, process, diagnostics, false),
                            List.of(ATTEMPTED_RESOURCE));
                }
                return new ModelDiscovery.Result(
                        ModelDiscovery.Status.SUCCESS,
                        new ArrayList<>(models.values()),
                        successMessage(runtime, planType, models.size())
                                + "; pagination stopped after 20 pages",
                        List.of(ATTEMPTED_RESOURCE));
            }
        } catch (RpcTimeout error) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.TIMEOUT,
                    failureMessage(stage, "timed out", runtime, process, diagnostics, false),
                    List.of(ATTEMPTED_RESOURCE));
        } catch (RpcFailure error) {
            return ModelDiscovery.Result.failure(
                    rpcFailureStatus(stage, error.getMessage()),
                    failureMessage(stage, error.getMessage(), runtime, process, diagnostics,
                            stage.contains("OAuth credential")),
                    List.of(ATTEMPTED_RESOURCE));
        } catch (IOException error) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.UNSUPPORTED,
                    failureMessage(stage, message(error), runtime, process, diagnostics, true),
                    List.of(ATTEMPTED_RESOURCE));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.TIMEOUT,
                    failureMessage(stage, "interrupted", runtime, process, diagnostics, false),
                    List.of(ATTEMPTED_RESOURCE));
        } finally {
            if (readerExecutor != null) {
                readerExecutor.shutdownNow();
            }
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            deleteRecursively(discoveryHome);
        }
    }

    static List<LiveModelDiscovery.Model> parseModels(JsonNode result) {
        JsonNode data = result == null ? null : result.path("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<LiveModelDiscovery.Model> models = new ArrayList<>();
        for (JsonNode node : data) {
            String id = firstText(node, "id", "model");
            if (id == null || id.isBlank()) {
                continue;
            }
            List<String> variants = new ArrayList<>();
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode efforts = node.path("supportedReasoningEfforts");
            if (efforts.isArray()) {
                for (JsonNode effort : efforts) {
                    String value = firstText(effort, "reasoningEffort", "value", "id");
                    if (value == null || value.isBlank() || variants.contains(value)) {
                        continue;
                    }
                    variants.add(value);
                    String description = effort.path("description").asText("");
                    labels.put(value, description.isBlank()
                            ? capitalize(value)
                            : capitalize(value) + " — " + description);
                }
            }
            models.add(new LiveModelDiscovery.Model(
                    id,
                    variants,
                    labels,
                    node.path("defaultReasoningEffort").asText(""),
                    false,
                    ATTEMPTED_RESOURCE));
        }
        return List.copyOf(models);
    }

    private static void send(BufferedWriter writer, JsonNode request) throws IOException {
        writer.write(MAPPER.writeValueAsString(request));
        writer.newLine();
        writer.flush();
    }

    private static JsonNode awaitResponse(
            BlockingQueue<ReadEvent> events,
            int id,
            long deadline,
            List<String> diagnostics)
            throws InterruptedException, RpcTimeout, RpcFailure {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new RpcTimeout();
            }
            ReadEvent event = events.poll(remaining, TimeUnit.NANOSECONDS);
            if (event == null) {
                throw new RpcTimeout();
            }
            if (event.error() != null) {
                throw new RpcFailure("Codex app-server output failed: " + message(event.error()));
            }
            if (event.eof()) {
                throw new RpcFailure("Codex app-server exited before request " + id + " completed");
            }
            JsonNode response;
            try {
                response = MAPPER.readTree(event.line());
            } catch (IOException ignored) {
                rememberDiagnostic(diagnostics, event.line());
                continue;
            }
            String method = response == null ? "" : response.path("method").asText("");
            if ("account/chatgptAuthTokens/refresh".equals(method)) {
                throw new RpcFailure(
                        "Codex rejected the selected OpenAI OAuth token and requested refreshed credentials");
            }
            if ("account/login/completed".equals(method)
                    && !response.path("params").path("success").asBoolean(false)) {
                String detail = response.path("params").path("error").asText("unknown error");
                throw new RpcFailure("Codex app-server failed to activate external OAuth: " + detail);
            }
            if (response == null || response.path("id").asInt(Integer.MIN_VALUE) != id) {
                continue;
            }
            if (response.has("error")) {
                String detail = response.path("error").path("message").asText(
                        response.path("error").toString());
                throw new RpcFailure("Codex app-server rejected request " + id + ": " + detail);
            }
            if (!response.has("result")) {
                throw new RpcFailure("Codex app-server returned a response without result");
            }
            return response;
        }
    }

    private static void pump(BufferedReader reader, BlockingQueue<ReadEvent> events) {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                events.offer(new ReadEvent(line, null, false));
            }
            events.offer(new ReadEvent("", null, true));
        } catch (IOException error) {
            events.offer(new ReadEvent("", error, false));
        }
    }

    private static void pumpDiagnostics(BufferedReader reader, List<String> diagnostics) {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                rememberDiagnostic(diagnostics, line);
            }
        } catch (IOException error) {
            rememberDiagnostic(diagnostics, "stderr reader failed: " + message(error));
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String launchDescription(List<String> command, Map<String, String> environment) {
        String executable = environment == null ? null : environment.get(CODEX_SHIM_ENV);
        if ((executable == null || executable.isBlank()) && command != null && !command.isEmpty()) {
            executable = command.get(0);
        }
        return executable == null || executable.isBlank()
                ? "unknown Codex executable"
                : "Codex executable " + executable;
    }

    private static String runtimeDescription(JsonNode initializeResponse, String fallback) {
        String userAgent = initializeResponse == null
                ? null : firstText(initializeResponse.path("result"), "userAgent", "user_agent");
        return userAgent == null || userAgent.isBlank()
                ? fallback
                : userAgent + " via " + fallback;
    }

    private static String successMessage(String runtime, String planType, int modelCount) {
        return "Live OpenAI subscription catalog via " + runtime
                + (planType == null || planType.isBlank() ? "" : " (plan " + planType + ")")
                + ": " + modelCount + " model" + (modelCount == 1 ? "" : "s");
    }

    private static ModelDiscovery.Status rpcFailureStatus(String stage, String detail) {
        String value = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (value.contains("oauth token")
                || value.contains("external oauth")
                || value.contains("refreshed credentials")) {
            return ModelDiscovery.Status.AUTH_REQUIRED;
        }
        if (value.contains("unsupported")
                || value.contains("unknown variant")
                || value.contains("method not found")
                || value.contains("experimental")) {
            return ModelDiscovery.Status.UNSUPPORTED;
        }
        return stage != null && stage.contains("OAuth credential")
                ? ModelDiscovery.Status.AUTH_REQUIRED
                : ModelDiscovery.Status.INVALID_RESPONSE;
    }

    private static String failureMessage(
            String stage,
            String detail,
            String runtime,
            Process process,
            List<String> diagnostics,
            boolean updateHint) {
        StringBuilder message = new StringBuilder("Codex model discovery failed while ")
                .append(stage == null || stage.isBlank() ? "running" : stage)
                .append(": ")
                .append(detail == null || detail.isBlank()
                        ? "unknown error" : sanitizeDiagnostic(detail))
                .append(". Runtime: ")
                .append(runtime == null || runtime.isBlank() ? "unknown" : runtime);
        if (process != null && !process.isAlive()) {
            message.append(" (exit code ").append(process.exitValue()).append(')');
        }
        String output = diagnosticSummary(diagnostics);
        if (!output.isBlank()) {
            message.append(". Codex output: ").append(output);
        }
        if (updateHint) {
            message.append(". Update the resolved Codex CLI if it does not support app-server external ChatGPT tokens");
        }
        return message.toString();
    }

    private static void rememberDiagnostic(List<String> diagnostics, String line) {
        String safe = sanitizeDiagnostic(line);
        if (diagnostics == null || safe.isBlank()) {
            return;
        }
        synchronized (diagnostics) {
            if (diagnostics.size() == 5) {
                int removable = 0;
                while (removable < diagnostics.size()
                        && isFallbackDiagnostic(diagnostics.get(removable))) {
                    removable++;
                }
                diagnostics.remove(removable == diagnostics.size() ? 0 : removable);
            }
            diagnostics.add(safe);
        }
    }

    static String sanitizeDiagnostic(String line) {
        if (line == null) {
            return "";
        }
        String safe = line.replaceAll("\\u001B\\[[;0-9]*[ -/]*[@-~]", "")
                .replaceAll("(?i)Bearer[ ]+[^ ]+", "Bearer <redacted>")
                .replaceAll("(?i)(access[_-]?token|refresh[_-]?token|authorization)([ ]*[:=][ ]*)[^ ,;]+",
                        "$1$2<redacted>")
                .replaceAll("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}",
                        "<redacted-jwt>")
                .trim();
        return safe.length() <= 300 ? safe : safe.substring(0, 300) + "…";
    }

    private static String diagnosticSummary(List<String> diagnostics) {
        if (diagnostics == null) {
            return "";
        }
        synchronized (diagnostics) {
            return String.join(" | ", diagnostics);
        }
    }

    static boolean usedBundledOrCachedFallback(List<String> diagnostics) {
        if (diagnostics == null) {
            return false;
        }
        synchronized (diagnostics) {
            return diagnostics.stream()
                    .anyMatch(CodexAppServerModelDiscovery::isFallbackDiagnostic);
        }
    }

    private static boolean isFallbackDiagnostic(String diagnostic) {
        String value = diagnostic == null ? "" : diagnostic.toLowerCase(Locale.ROOT);
        return value.contains("failed to refresh available models")
                || value.contains("using cached models for onlineifuncached");
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The OS temp directory can safely reap a file still held by a terminated child.
                }
            });
        } catch (IOException ignored) {
            // Discovery already has its result; cleanup failure must not hide it.
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String capitalize(String value) {
        return value == null || value.isBlank()
                ? "" : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String message(Exception error) {
        return error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "unknown error" : error.getMessage();
    }

    private record ReadEvent(String line, IOException error, boolean eof) {
    }

    private static final class RpcTimeout extends Exception {
    }

    private static final class RpcFailure extends Exception {
        private RpcFailure(String message) {
            super(message);
        }
    }
}
