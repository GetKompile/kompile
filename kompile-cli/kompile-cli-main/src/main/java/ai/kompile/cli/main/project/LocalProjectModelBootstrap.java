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
import java.util.stream.Stream;

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
        return ensure(projectRoot, selection, runtimeOptions, true);
    }

    public static ResolvedProjectModel ensure(
            Path projectRoot,
            String selection,
            Map<String, Object> runtimeOptions,
            boolean allowProjectMutation) throws IOException, InterruptedException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Map<String, Object> options = runtimeOptions == null ? Map.of() : runtimeOptions;

        // A configured localPath is already the model artifact. Resolve it directly so
        // local-only pipelines never create a project manifest, invoke staging, or
        // register anything in the project model inventory.
        Path localPath = resolveLocalPath(root, options);
        if (localPath != null) {
            String localModelId = firstNonBlank(
                    selection,
                    stringOption(options, "modelId", null),
                    localPath.getFileName() == null ? "local-model" : localPath.getFileName().toString());
            return new ResolvedProjectModel(
                    localModelId, localPath, tokenizerForLocalPath(localPath), null, false, "local");
        }

        KompileProjectStore store = new KompileProjectStore();
        if (allowProjectMutation) {
            ensureProject(store, root);
        } else if (!Files.isRegularFile(root.resolve(KompileProjectStore.MANIFEST_FILE))) {
            throw new IOException("Read-only pipeline resolution cannot proceed because the project has no "
                    + KompileProjectStore.MANIFEST_FILE
                    + "; initialize the project and provision the model with model_runtime first.");
        }
        KompileProjectManifest manifest = store.load(root);
        KompileProjectModel model = selectModel(manifest, selection, options, allowProjectMutation);
        if (model == null) {
            throw new IOException("Read-only pipeline resolution cannot use project model '"
                    + firstNonBlank(selection, DEFAULT_CATALOG_MODEL)
                    + "' is not registered; provision it with model_runtime first.");
        }
        boolean explicitProvisioning = booleanOption(options, "forceBootstrap", false)
                || modelDefinitionChanged(model, options);
        Path existing = resolveManifestArtifact(root, model);
        if (existing != null && !explicitProvisioning) {
            if (allowProjectMutation) {
                applyModelDefinition(model, options);
                registerResolvedModel(store, root, model, existing, Map.of());
            }
            return new ResolvedProjectModel(
                    modelId(model), existing, tokenizerBeside(existing), null, false, "existing");
        }

        if (!allowProjectMutation) {
            String reason = explicitProvisioning
                    ? "the requested model definition requires provisioning"
                    : "the registered model has no local artifact";
            throw new IOException("Read-only pipeline resolution failed because " + reason
                    + " for project model '" + modelId(model)
                    + "'; provision it with model_runtime before testing.");
        }
        if (!booleanOption(options, "autoBootstrap", true)) {
            throw new IOException("Project model '" + modelId(model)
                    + "' has no local artifact and modelRuntime.autoBootstrap is false");
        }

        Map<String, Object> result = runStaging(root, model, options);
        Path modelPath = requireProjectArtifact(
                root, stringValue(result.get("modelPath")), isVlmPipeline(model));
        Path tokenizerPath = optionalProjectArtifact(root, stringValue(result.get("tokenizerPath")));
        Path runtimePath = Path.of(stringValue(result.get("runtimePath")));

        applyModelDefinition(model, options);
        registerResolvedModel(store, root, model, modelPath, result);
        return new ResolvedProjectModel(
                modelId(model),
                modelPath,
                tokenizerPath,
                runtimePath,
                true,
                stringValue(result.getOrDefault("disposition", "staged")));
    }

    private static boolean modelDefinitionChanged(
            KompileProjectModel model, Map<String, Object> options) {
        return differs(stringOption(options, "source", null), model.getSource(), true)
                || differs(stringOption(options, "repository", null), model.getSourceRepository(), false)
                || differs(stringOption(options, "revision", null), model.getSourceRevision(), false)
                || differs(stringOption(options, "type", null),
                model.getMetadata().get("registry.type"), true);
    }

    private static boolean differs(String requested, String existing, boolean ignoreCase) {
        if (requested == null || requested.isBlank()) return false;
        if (existing == null) return true;
        return ignoreCase ? !requested.equalsIgnoreCase(existing) : !requested.equals(existing);
    }

    private static void applyModelDefinition(
            KompileProjectModel model, Map<String, Object> options) {
        String source = stringOption(options, "source", null);
        String repository = stringOption(options, "repository", null);
        String revision = stringOption(options, "revision", null);
        String type = stringOption(options, "type", null);
        if (source != null && !source.isBlank()) model.setSource(source);
        if (repository != null && !repository.isBlank()) model.setSourceRepository(repository);
        if (revision != null && !revision.isBlank()) model.setSourceRevision(revision);
        if (type != null && !type.isBlank()) model.getMetadata().put("registry.type", type);
    }

    public static List<Map<String, Object>> inventory(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        KompileProjectStore store = new KompileProjectStore();
        if (!Files.isRegularFile(root.resolve(KompileProjectStore.MANIFEST_FILE))) {
            return List.of();
        }
        KompileProjectManifest manifest = store.load(root);
        List<Map<String, Object>> result = new ArrayList<>();
        for (KompileProjectModel model : manifest.getModels()) {
            Path artifact = resolveManifestArtifact(root, model);
            boolean artifactReady = artifact != null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", model.getId());
            item.put("modelId", model.getModelId());
            item.put("registryModelId", model.getRegistryModelId());
            item.put("role", model.getRole());
            item.put("source", model.getSource());
            item.put("sourceRepository", model.getSourceRepository());
            item.put("path", model.getPath());
            item.put("resolvedArtifact", artifactReady ? artifact.toString() : null);
            item.put("artifactReady", artifactReady);
            item.put("artifactStatus", artifactReady ? "AVAILABLE" : "MISSING");
            item.put("runtimeStatus", "NOT_PROBED");
            item.put("runtimeProbeRequired", true);
            item.put("ready", artifactReady);
            item.put("readyMeaning", "legacy alias for artifactReady; it does not prove runtime initialization");
            item.put("recommendedAction", artifactReady
                    ? "Run pipeline test/run to initialize the runtime and receive structured diagnostics."
                    : "Provision the artifact with model_runtime action=bootstrap or import.");
            result.add(item);
        }
        return result;
    }

    /**
     * Convert one local model by invoking the standalone model CLI's real
     * `convert` command. The model CLI then resolves the configured staging
     * converter, so MCP does not embed importer/backend policy.
     */
    public static Map<String, Object> convert(
            Path root,
            Path inputPath,
            Path outputPath,
            String format,
            Map<String, Object> options) throws IOException, InterruptedException {
        LauncherArtifact launcher = resolveModelCliLauncher(options);
        List<String> command = new ArrayList<>();
        if (launcher.nativeExecutable()) {
            command.add(launcher.path().toString());
        } else {
            command.add(resolveJava(options));
            command.add("-Dfile.encoding=UTF-8");
            command.add("-jar");
            command.add(launcher.path().toString());
        }
        command.add("convert");
        command.add("--input=" + inputPath.toAbsolutePath().normalize());
        command.add("--output=" + outputPath.toAbsolutePath().normalize());
        addOption(command, "--format=", format);
        addOption(command, "--staging-executable=", stringOption(options, "stagingExecutable", null));
        addOption(command, "--staging-jar=", stringOption(options, "stagingJar", null));
        addOption(command, "--java=", stringOption(options, "javaExecutable", null));
        command.add("--timeout-minutes=" + longOption(options, "timeoutMinutes", 60L));

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
            reader = new Thread(() -> drain(child, output), "kompile-model-convert");
            reader.setDaemon(true);
            reader.start();
            long timeout = Math.max(1L, longOption(options, "timeoutMinutes", 60L));
            if (!process.waitFor(timeout, TimeUnit.MINUTES)) {
                throw new IOException("Model conversion timed out after " + timeout + " minute(s)");
            }
            reader.join(1000);
            if (process.exitValue() != 0) {
                throw new IOException("Model CLI exited with " + process.exitValue()
                        + outputTail(output));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("command", "kompile-model convert");
            result.put("inputPath", inputPath.toAbsolutePath().normalize().toString());
            result.put("outputPath", outputPath.toAbsolutePath().normalize().toString());
            if (format != null && !format.isBlank()) {
                result.put("format", format.trim());
            }
            result.put("runtimePath", launcher.path().toString());
            String diagnostics = outputTail(output);
            if (!diagnostics.isBlank()) {
                result.put("diagnostics", diagnostics);
            }
            return result;
        } finally {
            stopProcess(process);
            if (reader != null) {
                reader.join(1000);
            }
        }
    }

    /**
     * Optimize a local or catalog model by invoking the standalone model CLI's
     * real `optimize` command. The model CLI selects the configured staging
     * runtime, preserving native-image/JAR distribution policy in one place.
     */
    public static Map<String, Object> optimize(
            Path root,
            Path inputPath,
            Path outputPath,
            String modelId,
            Map<String, Object> options) throws IOException, InterruptedException {
        options = options == null ? Map.of() : options;
        if (inputPath == null && (modelId == null || modelId.isBlank())) {
            throw new IOException("Optimization requires a local input path or model id");
        }
        LauncherArtifact launcher = resolveModelCliLauncher(options);
        List<String> command = new ArrayList<>();
        if (launcher.nativeExecutable()) {
            command.add(launcher.path().toString());
        } else {
            command.add(resolveJava(options));
            command.add("-Dfile.encoding=UTF-8");
            command.add("-jar");
            command.add(launcher.path().toString());
        }
        command.add("optimize");
        addOption(command, "--input=", inputPath == null ? null : inputPath.toAbsolutePath().normalize().toString());
        addOption(command, "--model-id=", modelId);
        addOption(command, "--output=", outputPath == null ? null : outputPath.toAbsolutePath().normalize().toString());
        List<String> selectedPasses = listOption(options, "selectedPasses");
        if (!selectedPasses.isEmpty()) {
            addOption(command, "--passes=", String.join(",", selectedPasses));
        }
        addOption(command, "--profile=", stringOption(options, "profile", "BASIC"));
        command.add("--max-iterations=" + Math.max(1L, longOption(options, "maxIterations", 3L)));
        addOption(command, "--quantization-type=", stringOption(options, "quantizationType", null));
        command.add("--force=" + booleanOption(options, "force", false));
        command.add("--create-backup=" + booleanOption(options, "createBackup", true));
        command.add("--dry-run=" + booleanOption(options, "dryRun", false));
        addOption(command, "--staging-executable=", stringOption(options, "stagingExecutable", null));
        addOption(command, "--staging-jar=", stringOption(options, "stagingJar", null));
        addOption(command, "--java=", stringOption(options, "javaExecutable", null));
        command.add("--timeout-minutes=" + longOption(options, "timeoutMinutes", 60L));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toAbsolutePath().normalize().toFile())
                .redirectErrorStream(true);
        Object envOption = options.get("environment");
        if (envOption instanceof Map<?, ?> environment) {
            for (Map.Entry<?, ?> entry : environment.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.environment().put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }

        Process process = null;
        Thread reader = null;
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        try {
            process = builder.start();
            Process child = process;
            reader = new Thread(() -> drain(child, output), "kompile-model-optimize");
            reader.setDaemon(true);
            reader.start();
            long timeout = Math.max(1L, longOption(options, "timeoutMinutes", 60L));
            if (!process.waitFor(timeout, TimeUnit.MINUTES)) {
                throw new IOException("Model optimization timed out after " + timeout + " minute(s)");
            }
            reader.join(1000);
            if (process.exitValue() != 0) {
                throw new IOException("Model CLI exited with " + process.exitValue() + outputTail(output));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("command", "kompile-model optimize");
            if (inputPath != null) {
                result.put("inputPath", inputPath.toAbsolutePath().normalize().toString());
            }
            if (outputPath != null) {
                result.put("outputPath", outputPath.toAbsolutePath().normalize().toString());
            } else if (inputPath != null && !booleanOption(options, "dryRun", false)) {
                result.put("outputPath", inputPath.toAbsolutePath().normalize().toString());
            }
            if (modelId != null && !modelId.isBlank()) {
                result.put("modelId", modelId.trim());
            }
            result.put("runtimePath", launcher.path().toString());
            result.put("optimized", !booleanOption(options, "dryRun", false));
            if (!selectedPasses.isEmpty()) {
                result.put("selectedPasses", selectedPasses);
            }
            String diagnostics = outputTail(output);
            if (!diagnostics.isBlank()) {
                result.put("diagnostics", diagnostics);
            }
            return result;
        } finally {
            stopProcess(process);
            if (reader != null) {
                reader.join(1000);
            }
        }
    }

    private static LauncherArtifact resolveModelCliLauncher(Map<String, Object> options)
            throws IOException {
        String explicitExecutable = firstNonBlank(
                stringOption(options, "modelExecutable", null),
                System.getProperty("kompile.model.cli.executable"),
                System.getenv("KOMPILE_MODEL_CLI_EXECUTABLE"));
        if (explicitExecutable != null) {
            Path path = Path.of(explicitExecutable).toAbsolutePath().normalize();
            requireExecutable(path, "model CLI executable");
            return new LauncherArtifact(path, true);
        }
        String explicitJar = firstNonBlank(
                stringOption(options, "modelJar", null),
                System.getProperty("kompile.model.cli.jar"),
                System.getenv("KOMPILE_MODEL_CLI_JAR"));
        if (explicitJar != null) {
            Path path = Path.of(explicitJar).toAbsolutePath().normalize();
            requireFile(path, "model CLI executable JAR");
            CliProcessLauncher.requireCompatibleChild("kompile-model", false, path);
            return new LauncherArtifact(path, false);
        }

        List<Path> roots = new ArrayList<>();
        addRoot(roots, System.getProperty("kompile.dist.home"));
        addRoot(roots, System.getenv("KOMPILE_INSTALL_DIR"));
        addRoot(roots, System.getenv("KOMPILE_DIST_HOME"));
        String commandPath = ProcessHandle.current().info().command().orElse(null);
        if (commandPath != null && !commandPath.isBlank()) {
            Path command = Path.of(commandPath).toAbsolutePath().normalize();
            if (command.getParent() != null && command.getParent().getParent() != null) {
                roots.add(command.getParent().getParent());
            }
        }
        for (Path root : roots) {
            Path executable = root.resolve("bin").resolve(isWindows()
                    ? "kompile-model.exe" : "kompile-model");
            if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                return new LauncherArtifact(executable, true);
            }
        }
        if (CliProcessLauncher.requiresNativeChildren()) {
            throw new IOException("The native Kompile distribution is missing its native model CLI. "
                    + "Configure modelExecutable or package bin/kompile-model.");
        }
        for (Path root : roots) {
            Path jar = root.resolve("lib").resolve("kompile-model.jar");
            if (Files.isRegularFile(jar)) {
                CliProcessLauncher.requireCompatibleChild("kompile-model", false, jar);
                return new LauncherArtifact(jar, false);
            }
        }
        Path developmentJar = findDevelopmentModelJar();
        if (developmentJar != null) {
            CliProcessLauncher.requireCompatibleChild("kompile-model", false, developmentJar);
            return new LauncherArtifact(developmentJar, false);
        }
        throw new IOException("No standalone model CLI runtime found. Configure modelExecutable/modelJar.");
    }

    private static Path findDevelopmentModelJar() {
        Path cursor = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && cursor != null; i++, cursor = cursor.getParent()) {
            Path target = cursor.resolve("kompile-cli").resolve("kompile-model-cli").resolve("target");
            if (!Files.isDirectory(target)) {
                continue;
            }
            try (Stream<Path> files = Files.list(target)) {
                Path match = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-shaded.jar"))
                        .findFirst()
                        .orElse(null);
                if (match != null) {
                    return match.toAbsolutePath().normalize();
                }
            } catch (IOException ignored) {
                // Continue toward the workspace root.
            }
        }
        return null;
    }

    private static void addRoot(List<Path> roots, String value) {
        if (value != null && !value.isBlank()) {
            roots.add(Path.of(value).toAbsolutePath().normalize());
        }
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
            String selection,
            Map<String, Object> options,
            boolean allowCreate) {
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

        if (!allowCreate) return null;
        KompileProjectModel created = new KompileProjectModel();
        String id = requested == null ? DEFAULT_CATALOG_MODEL : requested;
        created.setId(id);
        created.setModelId(id);
        created.setRegistryModelId(id);
        String registryType = firstNonBlank(
                stringOption(options, "type", null), "llm_ggml");
        created.setRole(roleForRegistryType(registryType));
        created.setSource(firstNonBlank(
                stringOption(options, "source", null), "CATALOG"));
        created.setSourceRepository(stringOption(options, "repository", null));
        created.setSourceRevision(stringOption(options, "revision", null));
        created.setRequired(true);
        created.setCreatedAt(Instant.now());
        created.setUpdatedAt(Instant.now());
        created.getMetadata().put("registry.type", registryType);
        manifest.getModels().add(created);
        return created;
    }

    private static boolean matches(KompileProjectModel model, String requested) {
        return requested.equals(model.getId())
                || requested.equals(model.getModelId())
                || requested.equals(model.getRegistryModelId());
    }

    private static String roleForRegistryType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("vlm")) return "VLM";
        if (normalized.startsWith("ocr")) return "OCR";
        if (normalized.contains("reranker") || normalized.contains("cross_encoder")) {
            return "RERANKER";
        }
        if (normalized.contains("encoder") || normalized.equals("embedding")) {
            return "ENCODER";
        }
        return "LLM";
    }

    private static Path resolveLocalPath(Path root, Map<String, Object> options) throws IOException {
        String configured = firstNonBlank(
                stringOption(options, "localPath", null),
                stringOption(options, "path", null));
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        path = path.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new IOException("Configured local model path does not exist: " + path);
        }
        if (!Files.isDirectory(path) && !Files.isRegularFile(path)) {
            throw new IOException("Configured local model path is not a file or directory: " + path);
        }
        return path;
    }

    private static Path tokenizerForLocalPath(Path artifact) {
        Path directory = Files.isDirectory(artifact) ? artifact : artifact.getParent();
        if (directory == null) {
            return null;
        }
        Path tokenizer = directory.resolve("tokenizer.json");
        return Files.isRegularFile(tokenizer) ? tokenizer : null;
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
        boolean vlmPipeline = isVlmPipeline(model);
        candidates.add(root.resolve("data/models").resolve(typeDirectory).resolve(id));
        candidates.add(root.resolve("data/models").resolve(id));

        for (Path candidate : candidates) {
            Path artifact = findArtifact(candidate.toAbsolutePath().normalize(), vlmPipeline);
            if (artifact != null && artifact.startsWith(root)) {
                return artifact;
            }
        }
        return null;
    }

    private static Path findArtifact(Path candidate, boolean vlmPipeline) {
        if (Files.isRegularFile(candidate) && supportedArtifact(candidate, vlmPipeline)) {
            return candidate;
        }
        if (!Files.isDirectory(candidate)) {
            return null;
        }
        try (var files = Files.walk(candidate, 3)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> supportedArtifact(path, vlmPipeline))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean supportedArtifact(Path path, boolean vlmPipeline) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (vlmPipeline) {
            if (name.equals("pipeline.json")) {
                return true;
            }
            if (!(name.endsWith(".sdz") || name.endsWith(".fb") || name.endsWith(".sdnb"))) {
                return false;
            }
            return hasVlmRuntimeComponents(path.getParent());
        }
        return name.endsWith(".gguf") || name.endsWith(".ggml")
                || name.endsWith(".sdz") || name.endsWith(".fb")
                || name.endsWith(".onnx") || name.equals("pipeline.json");
    }

    private static boolean hasVlmRuntimeComponents(Path directory) {
        if (directory == null) {
            return false;
        }
        boolean decoder = Files.isRegularFile(directory.resolve("decoder.sdz"))
                || Files.isRegularFile(directory.resolve("decoder_model.sdz"))
                || Files.isRegularFile(directory.resolve("decoder_model_merged.sdz"))
                || Files.isRegularFile(directory.resolve("language_model.sdz"))
                || Files.isRegularFile(directory.resolve("model.sdz"));
        boolean vision = Files.isRegularFile(directory.resolve("vision_encoder.sdz"))
                || Files.isRegularFile(directory.resolve("encoder.sdz"));
        return decoder && vision;
    }

    private static boolean isVlmPipeline(KompileProjectModel model) {
        String type = model.getMetadata().getOrDefault("registry.type", "");
        return type.toLowerCase(Locale.ROOT).startsWith("vlm")
                || "VLM".equalsIgnoreCase(model.getRole());
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
        // ComponentRegistry's compatibility lookup intentionally returns the
        // distribution-native form before a JAR. Preserve that form when a JVM
        // parent is running against a mixed/native install; do not pass a native
        // executable to java -jar.
        java.io.File installedArtifact = registry.findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING);
        if (installedArtifact != null && installedArtifact.isFile()) {
            Path artifact = installedArtifact.toPath().toAbsolutePath().normalize();
            boolean nativeExecutable = !artifact.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
            CliProcessLauncher.requireCompatibleChild(
                    "kompile-model-staging", nativeExecutable, artifact);
            return new LauncherArtifact(artifact, nativeExecutable);
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

    private static Path requireProjectArtifact(
            Path root, String value, boolean vlmPipeline) throws IOException {
        Path path = optionalProjectArtifact(root, value);
        if (path == null || !supportedArtifact(path, vlmPipeline)) {
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

    private static List<String> listOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) {
                String text = stringValue(item);
                if (text != null) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = stringValue(value);
        if (text == null) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(","))
                .map(LocalProjectModelBootstrap::blankToNull)
                .filter(java.util.Objects::nonNull)
                .toList();
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
