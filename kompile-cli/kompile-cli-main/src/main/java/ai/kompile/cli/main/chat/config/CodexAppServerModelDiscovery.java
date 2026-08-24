/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

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
                    "Unable to start Codex app-server: Codex executable was not found at its configured path or on PATH");
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
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = null;
        ExecutorService readerExecutor = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(List.copyOf(command))
                    .redirectErrorStream(true);
            processBuilder.environment().putAll(environment);
            process = processBuilder.start();
            BlockingQueue<ReadEvent> events = new LinkedBlockingQueue<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kompile-codex-model-list");
                thread.setDaemon(true);
                return thread;
            });
            readerExecutor.submit(() -> pump(reader, events));

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8))) {
                ObjectNode initialize = MAPPER.createObjectNode();
                initialize.put("method", "initialize");
                initialize.put("id", 1);
                ObjectNode initializeParams = initialize.putObject("params");
                ObjectNode clientInfo = initializeParams.putObject("clientInfo");
                clientInfo.put("name", "kompile");
                clientInfo.put("title", "Kompile CLI");
                clientInfo.put("version", "0.1.0");
                send(writer, initialize);
                awaitResponse(events, 1, deadline);

                ObjectNode initialized = MAPPER.createObjectNode();
                initialized.put("method", "initialized");
                initialized.putObject("params");
                send(writer, initialized);

                Map<String, LiveModelDiscovery.Model> models = new LinkedHashMap<>();
                String cursor = null;
                int requestId = 2;
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
                    JsonNode response = awaitResponse(events, requestId, deadline);
                    JsonNode result = response.path("result");
                    for (LiveModelDiscovery.Model model : parseModels(result)) {
                        models.merge(model.id(), model, LiveModelDiscovery::merge);
                    }
                    cursor = result.path("nextCursor").asText("");
                    if (cursor.isBlank()) {
                        return ModelDiscovery.Result.success(
                                new ArrayList<>(models.values()),
                                List.of(ATTEMPTED_RESOURCE));
                    }
                    requestId++;
                }
                return new ModelDiscovery.Result(
                        ModelDiscovery.Status.SUCCESS,
                        new ArrayList<>(models.values()),
                        "Codex model pagination stopped after 20 pages",
                        List.of(ATTEMPTED_RESOURCE));
            }
        } catch (RpcTimeout error) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.TIMEOUT,
                    "Timed out while reading Codex app-server model/list",
                    List.of(ATTEMPTED_RESOURCE));
        } catch (RpcFailure error) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.INVALID_RESPONSE,
                    error.getMessage(),
                    List.of(ATTEMPTED_RESOURCE));
        } catch (IOException error) {
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.UNSUPPORTED,
                    "Unable to start Codex app-server: " + message(error),
                    List.of(ATTEMPTED_RESOURCE));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return ModelDiscovery.Result.failure(
                    ModelDiscovery.Status.TIMEOUT,
                    "Interrupted while reading Codex app-server model/list",
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
            BlockingQueue<ReadEvent> events, int id, long deadline)
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
                throw new RpcFailure("Codex app-server exited before model/list completed");
            }
            JsonNode response;
            try {
                response = MAPPER.readTree(event.line());
            } catch (IOException ignored) {
                continue;
            }
            if (response == null || response.path("id").asInt(Integer.MIN_VALUE) != id) {
                continue;
            }
            if (response.has("error")) {
                String detail = response.path("error").path("message").asText(
                        response.path("error").toString());
                throw new RpcFailure("Codex app-server rejected model/list: " + detail);
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
