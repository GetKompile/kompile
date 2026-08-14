/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.CliProcessLauncher;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Resolves project-owned models and bootstraps missing artifacts by invoking the
 * standalone model-staging component for exactly one request.
 *
 * <p>Both the native and executable-JAR tiers use the same CLI ABI. There is no
 * application server, app-main process, Maven execution, or development
 * classpath fallback.</p>
 */
public final class LocalProjectModelBootstrap {
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final String RESULT_PREFIX = "MODEL_BOOTSTRAP_RESULT:";
    private static final String DEFAULT_CATALOG_MODEL = "lfm2.5-1.2b-instruct";

    private LocalProjectModelBootstrap() {
    }

    public record ResolvedProjectModel(
            String modelId,
            Path modelPath,
            Path tokenizerPath,
            Path stagingRuntime,
            boolean bootstrapped,
            String disposition) {
    }

    record LauncherArtifact(Path path, boolean nativeExecutable) {
        LauncherArtifact {
            path = path.toAbsolutePath().normalize();
        }
    }

    public static ResolvedProjectModel ensure(
            Path projectRoot,
            String selection,
            Map<String, Object> runtimeOptions) throws IOException, InterruptedException {
        Path root = projectRoot.toAbsolutePath().normalize();
        KompileProjectStore store = new KompileProjectStore();
        ensureProject(store, root);
        KompileProjectManifest manifest = store.load(root);
        KompileProjectModel model = selectModel(manifest, selection);
        Map<String, Object> options = runtimeOptions == null ? Map.of() : runtimeOptions;
        boolean explicitProvisioning = firstNonBlank(
                stringOption(options, "localPath", null),
                stringOption(options, "source", null),
                stringOption(options, "repository", null)) != null
                || booleanOption(options, "forceBootstrap", false);
        Path existing = resolveManifestArtifact(root, model);
        if (existing != null && !explicitProvisioning) {
            return new ResolvedProjectModel(
                    modelId(model), existing, tokenizerBeside(existing), null, false, "existing");
        }

        if (!booleanOption(options, "autoBootstrap", true)) {
            throw new IOException("Project model '" + modelId(model)
                    + "' has no local artifact and modelRuntime.autoBootstrap is false");
        }

        Map<String, Object> result = runStaging(root, model, options);
        Path modelPath = requireProjectArtifact(root, stringValue(result.get("modelPath")));
        Path tokenizerPath = optionalProjectArtifact(root, stringValue(result.get("tokenizerPath")));
        Path runtimePath = Path.of(stringValue(result.get("runtimePath")));

        registerResolvedModel(store, root, model, modelPath, result);
        return new ResolvedProjectModel(
                modelId(model),
                modelPath,
                tokenizerPath,
                runtimePath,
                true,
                stringValue(result.getOrDefault("disposition", "staged")));
    }

    public static List<Map<String, Object>> inventory(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        KompileProjectStore store = new KompileProjectStore();
        ensureProject(store, root);
        KompileProjectManifest manifest = store.load(root);
        List<Map<String, Object>> result = new ArrayList<>();
        for (KompileProjectModel model : manifest.getModels()) {
            Path artifact = resolveManifestArtifact(root, model);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", model.getId());
            item.put("modelId", model.getModelId());
            item.put("registryModelId", model.getRegistryModelId());
            item.put("role", model.getRole());
            item.put("source", model.getSource());
            item.put("sourceRepository", model.getSourceRepository());
            item.put("path", model.getPath());
            item.put("resolvedArtifact", artifact == null ? null : artifact.toString());
            item.put("ready", artifact != null);
            result.add(item);
        }
        return result;
    }

    private static void ensureProject(KompileProjectStore store, Path root) {
        if (Files.isRegularFile(root.resolve(KompileProjectStore.MANIFEST_FILE))) {
            return;
        }
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        Path name = root.getFileName();
        request.setName(name == null ? "kompile-project" : name.toString());
        request.setDescription("Folder-local Kompile project metadata");
        request.setInitializeGit(false);
        store.init(root, request);
    }

    private static KompileProjectModel selectModel(
            KompileProjectManifest manifest,
            String selection) {
        String requested = blankToNull(selection);
        for (KompileProjectModel candidate : manifest.getModels()) {
            if (requested != null && matches(candidate, requested)) {
                return candidate;
            }
        }
        if (requested == null) {
            for (KompileProjectModel candidate : manifest.getModels()) {
                if ("LLM".equalsIgnoreCase(blankToNull(candidate.getRole()))) {
                    return candidate;
                }
            }
            if (!manifest.getModels().isEmpty()) {
                return manifest.getModels().get(0);
            }
        }

        KompileProjectModel created = new KompileProjectModel();
        String id = requested == null ? DEFAULT_CATALOG_MODEL : requested;
        created.setId(id);
        created.setModelId(id);
        created.setRegistryModelId(id);
        created.setRole("LLM");
        created.setSource("CATALOG");
        created.setRequired(true);
        created.setCreatedAt(Instant.now());
        created.setUpdatedAt(Instant.now());
        created.getMetadata().put("registry.type", "llm_ggml");
        manifest.getModels().add(created);
        return created;
    }

    private static boolean matches(KompileProjectModel model, String requested) {
        return requested.equals(model.getId())
                || requested.equals(model.getModelId())
                || requested.equals(model.getRegistryModelId());
    }

    private static Path resolveManifestArtifact(Path root, KompileProjectModel model) {
        String configured = blankToNull(model.getPath());
        List<Path> candidates = new ArrayList<>();
        if (configured != null) {
            Path path = Path.of(configured);
            candidates.add(path.isAbsolute() ? path : root.resolve(path));
            candidates.add(root.resolve("data/models").resolve(configured));
        }
        String id = modelId(model);
        String type = model.getMetadata().getOrDefault(
                "registry.type", "LLM".equalsIgnoreCase(model.getRole()) ? "llm_ggml" : "");
        String typeDirectory = registryDirectory(type);
        candidates.add(root.resolve("data/models").resolve(typeDirectory).resolve(id));
        candidates.add(root.resolve("data/models").resolve(id));

        for (Path candidate : candidates) {
            Path artifact = findArtifact(candidate.toAbsolutePath().normalize());
            if (artifact != null && artifact.startsWith(root)) {
                return artifact;
            }
        }
        return null;
    }

    private static Path findArtifact(Path candidate) {
        if (Files.isRegularFile(candidate) && supportedArtifact(candidate)) {
            return candidate;
        }
        if (!Files.isDirectory(candidate)) {
            return null;
        }
        try (var files = Files.walk(candidate, 3)) {
            return files.filter(Files::isRegularFile)
                    .filter(LocalProjectModelBootstrap::supportedArtifact)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean supportedArtifact(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gguf") || name.endsWith(".ggml")
                || name.endsWith(".sdz") || name.endsWith(".fb");
    }

    private static Map<String, Object> runStaging(
            Path root,
            KompileProjectModel model,
            Map<String, Object> options) throws IOException, InterruptedException {
        LauncherArtifact launcher = resolveStagingLauncher(options);
        List<String> command = new ArrayList<>();
        if (launcher.nativeExecutable()) {
            command.add(launcher.path().toString());
        } else {
            command.add(resolveJava(options));
            command.add("-Dfile.encoding=UTF-8");
            command.add("-jar");
            command.add(launcher.path().toString());
        }

        command.add("bootstrap");
        command.add("--model-id=" + modelId(model));
        addOption(command, "--local-path=", stringOption(options, "localPath", null));
        String manifestSource = model.getSource();
        if ("CATALOG".equalsIgnoreCase(manifestSource)
                || "BUILT_IN".equalsIgnoreCase(manifestSource)) {
            manifestSource = null;
        }
        addOption(command, "--source=", firstNonBlank(
                stringOption(options, "source", null), manifestSource));
        addOption(command, "--repository=", firstNonBlank(
                stringOption(options, "repository", null), model.getSourceRepository()));
        addOption(command, "--revision=", firstNonBlank(
                stringOption(options, "revision", null), model.getSourceRevision()));
        addOption(command, "--format=", stringOption(options, "format", null));
        addOption(command, "--type=", firstNonBlank(
                stringOption(options, "type", null), model.getMetadata().get("registry.type")));
        command.add("--timeout-minutes=" + longOption(options, "timeoutMinutes", 60L));
        // kompile.data.dir is the project root; model-manager appends data/models.
        // Passing <root>/data here produced the incorrect <root>/data/data/models cache.
        command.add("--kompile.data.dir=" + root);
        command.add("--kompile.staging.models-dir=" + root.resolve("data/models"));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true);
        Object envOption = options.get("environment");
        if (envOption instanceof Map<?, ?> environment) {
            for (Map.Entry<?, ?> entry : environment.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.environment().put(
                            String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }

        Process process = null;
        Thread reader = null;
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        try {
            process = builder.start();
            Process child = process;
            reader = new Thread(() -> drain(child, output), "kompile-model-staging-bootstrap");
            reader.setDaemon(true);
            reader.start();
            long timeout = Math.max(1L, longOption(options, "timeoutMinutes", 60L));
            if (!process.waitFor(timeout, TimeUnit.MINUTES)) {
                throw new IOException("Model staging timed out after " + timeout + " minute(s)");
            }
            reader.join(1000);
            if (process.exitValue() != 0) {
                throw new IOException("Model staging exited with " + process.exitValue()
                        + outputTail(output));
            }
            Map<String, Object> result = parseResult(output);
            result.put("runtimePath", launcher.path().toString());
            return result;
        } finally {
            stopProcess(process);
            if (reader != null) {
                reader.join(1000);
            }
        }
    }

    private static LauncherArtifact resolveStagingLauncher(Map<String, Object> options)
            throws IOException {
        String explicitExecutable = firstNonBlank(
                stringOption(options, "stagingExecutable", null),
                System.getProperty("kompile.model.staging.executable"),
                System.getenv("KOMPILE_MODEL_STAGING_EXECUTABLE"));
        if (explicitExecutable != null) {
            Path path = Path.of(explicitExecutable).toAbsolutePath().normalize();
            requireExecutable(path, "model-staging executable");
            return new LauncherArtifact(path, true);
        }
        String explicitJar = firstNonBlank(
                stringOption(options, "stagingJar", null),
                System.getProperty("kompile.model.staging.jar"),
                System.getenv("KOMPILE_MODEL_STAGING_JAR"));
        if (explicitJar != null) {
            Path path = Path.of(explicitJar).toAbsolutePath().normalize();
            requireFile(path, "model-staging executable JAR");
            CliProcessLauncher.requireCompatibleChild("kompile-model-staging", false, path);
            return new LauncherArtifact(path, false);
        }

        ComponentRegistry registry = new ComponentRegistry();
        Path componentDirectory = registry.getInstallDirectory(ComponentRegistry.KOMPILE_MODEL_STAGING)
                .toPath().toAbsolutePath().normalize();
        String executableName = isWindows()
                ? "kompile-model-staging.exe" : "kompile-model-staging";
        Path installHome = componentInstallHome(componentDirectory);
        for (Path candidate : List.of(
                componentDirectory.resolve(executableName),
                installHome.resolve("bin").resolve(executableName))) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return new LauncherArtifact(candidate, true);
            }
        }
        if (CliProcessLauncher.requiresNativeChildren()) {
            throw new IOException("The native Kompile distribution is missing its native "
                    + "model-staging worker: bin/" + executableName + ". Refusing to fall back "
                    + "to the executable Spring Boot JAR from a native MCP process.");
        }
        java.io.File installedJar = registry.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING);
        if (installedJar != null && installedJar.isFile()) {
            return new LauncherArtifact(installedJar.toPath(), false);
        }
        Path developmentJar = findDevelopmentStagingJar();
        if (developmentJar != null) {
            return new LauncherArtifact(developmentJar, false);
        }
        throw new IOException("No standalone model-staging runtime found. Install "
                + executableName + ", package the executable JAR, or configure "
                + "modelRuntime.stagingExecutable / modelRuntime.stagingJar.");
    }

    private static Path findDevelopmentStagingJar() {
        Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path target = cursor.resolve("kompile-app")
                    .resolve("kompile-models")
                    .resolve("kompile-model-staging")
                    .resolve("target");
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (var files = Files.list(target)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                        .findFirst().orElse(null);
                if (match != null) {
                    return match.toAbsolutePath().normalize();
                }
            } catch (IOException ignored) {
                // Continue toward the workspace root.
            }
        }
        return null;
    }

    private static String resolveJava(Map<String, Object> options) throws IOException {
        String configured = firstNonBlank(
                stringOption(options, "javaExecutable", null),
                System.getProperty("kompile.model.staging.java"));
        if (configured == null) {
            return JavaRuntimeLocator.javaExecutable();
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        requireExecutable(path, "Java runtime");
        return path.toString();
    }

    private static void registerResolvedModel(
            KompileProjectStore store,
            Path root,
            KompileProjectModel model,
            Path modelPath,
            Map<String, Object> result) {
        Path directory = modelPath.getParent();
        String relativeDirectory = root.relativize(directory).toString().replace('\\', '/');
        model.setPath(relativeDirectory);
        model.setModelId(modelId(model));
        model.setRegistryModelId(modelId(model));
        model.setStagingRegistryPath("data/models/registry.json");
        model.setUpdatedAt(Instant.now());
        if (model.getCreatedAt() == null) {
            model.setCreatedAt(model.getUpdatedAt());
        }
        model.getMetadata().put("registry.modelFile", modelPath.getFileName().toString());
        String type = stringValue(result.get("modelType"));
        if (type != null) {
            model.getMetadata().put("registry.type", type);
        }
        store.registerModel(root, model);
    }

    private static Path requireProjectArtifact(Path root, String value) throws IOException {
        Path path = optionalProjectArtifact(root, value);
        if (path == null || !supportedArtifact(path)) {
            throw new IOException("Model staging returned no supported project artifact: " + value);
        }
        return path;
    }

    private static Path optionalProjectArtifact(Path root, String value) throws IOException {
        if (value == null) {
            return null;
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        Path dataRoot = root.resolve("data").toAbsolutePath().normalize();
        if (!path.startsWith(dataRoot) || !Files.isRegularFile(path)) {
            throw new IOException("Model staging returned an artifact outside the project data folder: "
                    + path);
        }
        return path;
    }

    private static Map<String, Object> parseResult(List<String> output) throws IOException {
        synchronized (output) {
            for (int i = output.size() - 1; i >= 0; i--) {
                String line = output.get(i);
                int marker = line.indexOf(RESULT_PREFIX);
                if (marker >= 0) {
                    return new LinkedHashMap<>(MAPPER.readValue(
                            line.substring(marker + RESULT_PREFIX.length()),
                            new TypeReference<Map<String, Object>>() { }));
                }
            }
        }
        throw new IOException("Model staging produced no result marker" + outputTail(output));
    }

    private static void drain(Process process, List<String> output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        } catch (IOException ignored) {
            // Expected when a timed-out child is terminated.
        }
    }

    private static void stopProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String outputTail(List<String> output) {
        StringBuilder tail = new StringBuilder();
        synchronized (output) {
            int start = Math.max(0, output.size() - 30);
            for (int i = start; i < output.size(); i++) {
                tail.append(System.lineSeparator()).append(output.get(i));
            }
        }
        return tail.length() == 0 ? "" : System.lineSeparator() + "Output:" + tail;
    }

    private static Path tokenizerBeside(Path artifact) {
        Path parent = artifact.getParent();
        if (parent == null) {
            return null;
        }
        Path tokenizer = parent.resolve("tokenizer.json");
        return Files.isRegularFile(tokenizer) ? tokenizer : null;
    }

    private static Path componentInstallHome(Path componentDirectory) {
        Path cursor = componentDirectory;
        for (int i = 0; i < 3 && cursor != null; i++) {
            cursor = cursor.getParent();
        }
        return cursor == null
                ? Path.of(System.getProperty("user.home"), ".kompile")
                : cursor;
    }

    private static String registryDirectory(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "llm", "ggml", "llm_ggml" -> "llm-ggmls";
            case "cross_encoder", "reranker" -> "cross-encoders";
            case "vlm", "vlm_pipeline" -> "vlm-pipelines";
            case "ocr", "ocr_pipeline" -> "ocr-pipelines";
            case "sparse_encoder" -> "sparse-encoders";
            default -> "encoders";
        };
    }

    private static String modelId(KompileProjectModel model) {
        return firstNonBlank(model.getRegistryModelId(), model.getModelId(), model.getId());
    }

    private static void addOption(List<String> command, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            command.add(prefix + value.trim());
        }
    }

    private static String stringOption(Map<String, Object> options, String key, String fallback) {
        String value = stringValue(options.get(key));
        return value == null ? fallback : value;
    }

    private static long longOption(Map<String, Object> options, String key, long fallback) {
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean booleanOption(
            Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        String string = value == null ? null : String.valueOf(value);
        return blankToNull(string);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void requireExecutable(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IOException("Configured " + label + " is not executable: " + path);
        }
    }

    private static void requireFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Configured " + label + " does not exist: " + path);
        }
    }
}
