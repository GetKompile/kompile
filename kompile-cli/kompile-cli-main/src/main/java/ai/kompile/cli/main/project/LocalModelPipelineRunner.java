/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.VlmTestSubprocessArgs;
import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.main.CliProcessLauncher;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.launcher.PipelineSubprocessLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs request-scoped model and pipeline processing for an offline local crawl.
 *
 * <p>VLM/OCR support is a registered compatibility adapter, not the execution contract. Callers
 * may instead select any registered unified pipeline, Kompile subprocess component, executable,
 * or runnable JAR.
 */
public final class LocalModelPipelineRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private LocalModelPipelineRunner() {
    }

    public static boolean documentModelWorkerAvailable() {
        return documentModelWorkerStatus(Path.of("").toAbsolutePath().normalize(), null).available();
    }

    public static boolean documentModelWorkerAvailable(Path projectRoot, JsonNode request) {
        return documentModelWorkerStatus(projectRoot, request).available();
    }

    public static DocumentModelWorkerStatus documentModelWorkerStatus(Path projectRoot,
                                                                       JsonNode request) {
        Map<String, Object> runtimeOptions = new LinkedHashMap<>();
        JsonNode runtimeConfig = request == null ? null : request.get("runtimeConfig");
        if (runtimeConfig != null && runtimeConfig.isObject()) {
            runtimeConfig.fields().forEachRemaining(entry ->
                    runtimeOptions.put(entry.getKey(), jsonValue(entry.getValue())));
        }
        WorkerLocation worker = resolveWorker(projectRoot, runtimeOptions);
        return new DocumentModelWorkerStatus(
                worker.available(),
                worker.source(),
                worker.executable() == null ? null : worker.executable().toString(),
                worker.kind() == WorkerKind.UNIFIED);
    }

    public static String validateWorkerConfiguration(Path projectRoot, JsonNode request) {
        return validateWorkerConfiguration(
                projectRoot, request, CliProcessLauncher.requiresNativeChildren());
    }

    static String validateWorkerConfiguration(
            Path projectRoot, JsonNode request, boolean nativeParent) {
        JsonNode runtimeConfig = request == null ? null : request.get("runtimeConfig");
        if (runtimeConfig != null && !runtimeConfig.isNull()) {
            if (!runtimeConfig.isObject()) {
                return "runtimeConfig must be an object.";
            }
            JsonNode executable = runtimeConfig.get("documentModelExecutable");
            if (executable != null && !executable.isNull()) {
                String error = runnableError(
                        projectRoot, executable, "runtimeConfig.documentModelExecutable",
                        nativeParent);
                if (error != null) {
                    return error;
                }
                String mode = runtimeConfig.path("documentModelExecutableMode")
                        .asText("DEDICATED").trim().toUpperCase(Locale.ROOT);
                if (!Set.of("DEDICATED", "UNIFIED").contains(mode)) {
                    return "runtimeConfig.documentModelExecutableMode must be DEDICATED or UNIFIED.";
                }
            }
        }

        JsonNode executors = request == null
                ? null : request.path("pipelineRegistry").path("executors");
        if (executors != null && executors.isArray()) {
            for (int i = 0; i < executors.size(); i++) {
                JsonNode executable = executors.get(i).get("executable");
                if (executable == null || executable.isNull()) {
                    continue;
                }
                String error = runnableError(
                        projectRoot,
                        executable,
                        "pipelineRegistry.executors[" + i + "].executable",
                        nativeParent);
                if (error != null) {
                    return error;
                }
            }
        }
        return null;
    }

    private static String runnableError(
            Path projectRoot, JsonNode executable, String field, boolean nativeParent) {
        if (!executable.isTextual() || executable.asText().isBlank()) {
            return field + " must be a non-empty path.";
        }
        Path path;
        try {
            path = resolvePath(projectRoot, executable.asText());
        } catch (Exception e) {
            return field + " is not a valid path: " + e.getMessage();
        }
        if (!Files.isRegularFile(path)) {
            return field + " does not exist or is not a file: " + path;
        }
        if (!Files.isExecutable(path) && !isJar(path)) {
            return field + " is neither executable nor a JAR: " + path;
        }
        if (nativeParent && isJar(path)) {
            return field + " resolves to an executable JAR, but native Kompile execution "
                    + "requires a native child executable: " + path;
        }
        return null;
    }

    /**
     * Runs the processor selected by a resolved pipeline.
     */
    public static String extract(Path projectRoot,
                                 Path file,
                                 LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                 String loadedText) throws Exception {
        Map<String, Object> processor = new LinkedHashMap<>(pipeline.processor());
        Object definition = firstObject(
                processor.get("pipelineDefinition"),
                pipeline.chunkerOptions().get("pipelineDefinition"));
        Object definitionPath = firstObject(
                processor.get("pipelineDefinitionPath"),
                pipeline.chunkerOptions().get("pipelineDefinitionPath"));
        Object definitionId = firstObject(
                processor.get("pipelineDefinitionId"),
                pipeline.chunkerOptions().get("pipelineDefinitionId"));
        String type = first(
                stringValue(processor.get("type")),
                definition != null || definitionPath != null || definitionId != null
                        ? "UNIFIED_PIPELINE" : null);

        if ("UNIFIED_PIPELINE".equalsIgnoreCase(type)) {
            return runUnified(
                    projectRoot, file, pipeline, loadedText, definition, definitionPath, definitionId);
        }
        if ("vlm-test".equalsIgnoreCase(stringValue(processor.get("adapter")))) {
            return runDocumentModel(projectRoot, file, pipeline, processor);
        }
        if ("KOMPILE_SUBPROCESS".equalsIgnoreCase(type)
                || "EXECUTABLE".equalsIgnoreCase(type)) {
            return runConfiguredProcessor(projectRoot, file, pipeline, loadedText, processor);
        }
        throw new IllegalArgumentException(
                "Pipeline '" + pipeline.pipelineId()
                        + "' selected an unsupported processor type: " + type);
    }

    private static String runUnified(Path projectRoot,
                                     Path file,
                                     LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                     String loadedText,
                                     Object inlineDefinition,
                                     Object definitionPath,
                                     Object definitionId) throws Exception {
        UnifiedPipelineDefinition definition;
        if (inlineDefinition != null) {
            definition = inlineDefinition instanceof String json
                    ? MAPPER.readValue(json, UnifiedPipelineDefinition.class)
                    : MAPPER.convertValue(inlineDefinition, UnifiedPipelineDefinition.class);
        } else {
            Path path;
            if (definitionPath != null) {
                path = resolvePath(projectRoot, String.valueOf(definitionPath));
            } else {
                path = registeredDefinitionPath(projectRoot, String.valueOf(definitionId));
            }
            definition = MAPPER.readValue(path.toFile(), UnifiedPipelineDefinition.class);
        }

        if (definition.getPipelineId() == null || definition.getPipelineId().isBlank()) {
            definition.setPipelineId(pipeline.pipelineId());
        }
        if (definition.getPipelineSpec() == null || definition.getPipelineSpec().isEmpty()) {
            throw new IllegalArgumentException(
                    "Unified pipeline " + definition.getPipelineId()
                            + " has no executable pipelineSpec.");
        }

        ResolvedModelContext modelContext = resolveBoundModels(projectRoot, pipeline, definition);
        if (!modelContext.bindings().isEmpty()) {
            definition.setModelBindings(modelContext.bindings());
            definition.setResolvedModels(modelContext.resolvedModels());
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("filePath", file.toAbsolutePath().normalize().toString());
        input.put("path", file.toAbsolutePath().normalize().toString());
        input.put("source", file.toUri().toString());
        input.put("pipelineType", pipeline.pipelineType());
        input.put("text", loadedText == null ? "" : loadedText);
        input.put("optionsJson", MAPPER.writeValueAsString(pipeline.chunkerOptions()));
        if (!modelContext.bindings().isEmpty()) {
            input.put("modelBindings", modelContext.bindings());
            input.put("resolvedModels", modelContext.resolvedModels());
        }
        pipeline.chunkerOptions().forEach((key, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                input.put("option." + key, value);
            }
        });

        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher();
        Map<String, Object> result;
        try {
            result = launcher.launchOneShot(definition, input);
        } finally {
            launcher.shutdown();
        }

        String text = textualOutput(result.get("output"));
        if (text == null || text.isBlank()) {
            throw new IOException(
                    "Unified pipeline " + definition.getPipelineId()
                            + " completed without a text or markdown output.");
        }
        return text;
    }

    /**
     * Compatibility adapter for the built-in vlm-test preset.
     */
    private static String runDocumentModel(Path projectRoot,
                                           Path file,
                                           LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                           Map<String, Object> processor) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf")) {
            throw new IllegalArgumentException(
                    pipeline.pipelineType()
                            + " local document processing currently requires a PDF or an explicit "
                            + "pipelineDefinition.");
        }

        Map<String, Object> workerOptions = new LinkedHashMap<>(pipeline.chunkerOptions());
        workerOptions.putAll(processor);
        Object modelRuntime = workerOptions.remove("modelRuntime");
        WorkerLocation worker = resolveWorker(projectRoot, workerOptions);
        if (!worker.available()) {
            throw new IllegalStateException(
                    "The Kompile document-model subprocess is not installed. Install the model "
                            + "worker, set runtimeConfig.documentModelExecutable, or provide "
                            + "pipelineDefinition/pipelineDefinitionPath. " + worker.source());
        }

        Map<String, String> options = stringOptions(workerOptions);
        options.put("pipelineType", pipeline.pipelineType());
        String modelId = first(
                options.get("vlmModel"),
                options.get("modelId"),
                options.get("modelSetId"),
                stringValue(pipeline.chunkerOptions().get("vlmModel")));
        ResolvedModelContext modelContext = resolveBoundModels(projectRoot, pipeline, null);
        Map<String, Object> resolvedModel = preferredResolvedModel(modelContext.resolvedModels());
        if (resolvedModel != null) {
            modelId = first(stringValue(resolvedModel.get("modelId")), modelId);
            options.put("modelSourceType", "LOCAL");
            options.put("modelIdentifier", stringValue(resolvedModel.get("modelPath")));
        } else if (modelRuntime != null) {
            throw new IllegalArgumentException(
                    "A VLM pipeline with modelRuntime requires options.modelId, options.vlmModel, "
                            + "or modelBindings.");
        }

        VlmTestSubprocessArgs.Builder args = VlmTestSubprocessArgs.builder()
                .taskId("local-crawl-" + pipeline.pipelineId() + "-"
                        + Long.toUnsignedString(System.nanoTime()))
                .filePath(file.toAbsolutePath().normalize().toString())
                .modelId(modelId)
                .outputFormat(first(options.get("outputFormat"), "MARKDOWN"))
                .maxNewTokens(integer(
                        options, "maxNewTokens", VlmTestSubprocessArgs.DEFAULT_MAX_NEW_TOKENS))
                .temperature(decimal(
                        options, "temperature", VlmTestSubprocessArgs.DEFAULT_TEMPERATURE))
                .topP(decimal(options, "topP", VlmTestSubprocessArgs.DEFAULT_TOP_P))
                .beamSize(integer(options, "beamSize", VlmTestSubprocessArgs.DEFAULT_BEAM_SIZE))
                .doSample(bool(options, "doSample", false))
                .pdfRenderDpi(integer(
                        options, "pdfRenderDpi", VlmTestSubprocessArgs.DEFAULT_PDF_RENDER_DPI))
                .pageBatchSize(integer(
                        options, "pageBatchSize", VlmTestSubprocessArgs.DEFAULT_PAGE_BATCH_SIZE))
                .kvCacheStrategy(first(options.get("kvCacheStrategy"), "STATIC"))
                .maxKvLen(integer(options, "maxKvLen", 0))
                .maxPages(integer(options, "maxPages", 0))
                .pageRange(options.get("pageRange"))
                .modelSourceType(options.get("modelSourceType"))
                .modelIdentifier(options.get("modelIdentifier"))
                .stagingUrl(options.get("stagingUrl"))
                .stagingApiKey(options.get("stagingApiKey"))
                .archivePath(options.get("archivePath"))
                .options(options);

        Path argsFile = args.build().writeToTempFile();
        Path logFile = Files.createTempFile("kompile-local-model-", ".log");
        Process process = null;
        ExecutorService outputReader = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command(argsFile, options, worker))
                    .directory(file.toAbsolutePath().normalize().getParent().toFile())
                    .redirectError(logFile.toFile());
            applyEnvironment(builder.environment(), options.get("environment"));
            process = builder.start();
            Process running = process;
            outputReader = daemonExecutor("kompile-local-model-output");
            Future<WorkerOutput> outputFuture =
                    outputReader.submit(() -> readWorkerOutput(running.getInputStream()));

            long timeoutMinutes = Math.max(1, integer(options, "timeoutMinutes", 30));
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException(
                        "Document model subprocess timed out after "
                                + timeoutMinutes + " minute(s).");
            }

            WorkerOutput output;
            try {
                output = outputFuture.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IOException(
                        "Document model subprocess output did not close after completion.", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IOException("Unable to read document model subprocess output.", cause);
            }

            if (process.exitValue() != 0 || output.failure() != null) {
                throw new IOException(documentModelFailure(
                        process.exitValue(), output.failure(), tail(logFile)));
            }
            String text = pagesText(output.completion());
            if (text.isBlank()) {
                throw new IOException("Document model subprocess returned no extracted text.");
            }
            return text;
        } finally {
            stop(process, outputReader);
            Files.deleteIfExists(argsFile);
            Files.deleteIfExists(logFile);
        }
    }

    private static String runConfiguredProcessor(
            Path projectRoot,
            Path file,
            LocalCrawlCapabilities.ResolvedPipeline pipeline,
            String loadedText,
            Map<String, Object> processor) throws Exception {
        Path executable = configuredProcessorExecutable(projectRoot, processor);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("filePath", file.toAbsolutePath().normalize().toString());
        payload.put("path", file.toAbsolutePath().normalize().toString());
        payload.put("source", file.toUri().toString());
        payload.put("text", loadedText == null ? "" : loadedText);
        payload.put("pipelineId", pipeline.pipelineId());
        payload.put("pipelineType", pipeline.pipelineType());
        payload.put("options", pipeline.chunkerOptions());
        payload.put("processor", processor);

        Path argsFile = Files.createTempFile("kompile-local-pipeline-", ".json");
        Path logFile = Files.createTempFile("kompile-local-pipeline-", ".log");
        Process process = null;
        ExecutorService outputReader = null;
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(argsFile.toFile(), payload);
            ProcessBuilder builder = new ProcessBuilder(configuredCommand(
                    executable, projectRoot, file, pipeline, processor, argsFile))
                    .directory(projectRoot.toAbsolutePath().normalize().toFile())
                    .redirectError(logFile.toFile());
            applyEnvironment(builder.environment(), processor.get("environment"));
            process = builder.start();
            Process running = process;
            outputReader = daemonExecutor("kompile-local-pipeline-output");
            Future<String> stdout = outputReader.submit(() ->
                    new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

            long timeoutMinutes = Math.max(1, longValue(processor.get("timeoutMinutes"), 30));
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException(
                        "Registered pipeline subprocess timed out after "
                                + timeoutMinutes + " minute(s).");
            }

            String output;
            try {
                output = stdout.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IOException(
                        "Registered pipeline subprocess output did not close after completion.", e);
            } catch (ExecutionException e) {
                throw new IOException(
                        "Unable to read registered pipeline subprocess output.", e.getCause());
            }

            if (process.exitValue() != 0) {
                throw new IOException(first(
                        tail(logFile),
                        output.strip(),
                        "Registered pipeline subprocess exited with " + process.exitValue()));
            }
            String text = configuredOutput(output, processor);
            if (text == null || text.isBlank()) {
                throw new IOException(
                        "Registered pipeline subprocess returned no text-compatible output.");
            }
            return text;
        } finally {
            stop(process, outputReader);
            Files.deleteIfExists(argsFile);
            Files.deleteIfExists(logFile);
        }
    }

    private static Path configuredProcessorExecutable(
            Path projectRoot, Map<String, Object> processor) {
        String requested = stringValue(processor.get("executable"));
        if (requested != null && !requested.isBlank()) {
            Path executable = resolvePath(projectRoot, requested);
            if (!Files.isRegularFile(executable)
                    || (!Files.isExecutable(executable) && !isJar(executable))) {
                throw new IllegalArgumentException(
                        "Registered pipeline executable is neither executable nor a JAR: "
                                + executable);
            }
            return executable;
        }

        String componentId = stringValue(processor.get("componentId"));
        if (componentId != null && !componentId.isBlank()) {
            ComponentRegistry registry = new ComponentRegistry();
            Path executable = executablePath(registry.getDistributionBinaryPath(componentId));
            if (executable == null) {
                executable = runnablePath(registry.findInstalledJar(componentId));
            }
            if (executable != null) {
                return executable;
            }
        }
        throw new IllegalArgumentException(
                "Registered pipeline executor requires executable or an installed componentId.");
    }

    private static List<String> configuredCommand(
            Path executable,
            Path projectRoot,
            Path file,
            LocalCrawlCapabilities.ResolvedPipeline pipeline,
            Map<String, Object> processor,
            Path argsFile) {
        List<String> command = new ArrayList<>();
        addExecutable(command, executable, Map.of());

        List<String> configuredArguments = stringList(processor.get("arguments"));
        boolean argsReferenced = false;
        for (String argument : configuredArguments) {
            command.add(argument
                    .replace("{args}", argsFile.toString())
                    .replace("{file}", file.toAbsolutePath().normalize().toString())
                    .replace("{projectRoot}", projectRoot.toAbsolutePath().normalize().toString())
                    .replace("{pipelineId}", pipeline.pipelineId()));
            argsReferenced |= argument.contains("{args}");
        }

        String subprocessMode = stringValue(processor.get("subprocessMode"));
        boolean kompileSubprocess =
                "KOMPILE_SUBPROCESS".equalsIgnoreCase(stringValue(processor.get("type")))
                        || "UNIFIED".equalsIgnoreCase(
                                stringValue(processor.get("executableMode")));
        if (configuredArguments.isEmpty()
                && kompileSubprocess
                && subprocessMode != null
                && !subprocessMode.isBlank()) {
            command.add("--subprocess=" + subprocessMode);
        }
        if (!argsReferenced) {
            command.add(argsFile.toString());
        }
        return command;
    }

    private static String configuredOutput(String output, Map<String, Object> processor)
            throws IOException {
        String protocol = first(stringValue(processor.get("outputProtocol")), "AUTO")
                .toUpperCase(Locale.ROOT);
        String trimmed = output == null ? "" : output.strip();
        if ("TEXT".equals(protocol)) {
            return trimmed;
        }

        if ("KOMPILE_MESSAGE".equals(protocol)
                || trimmed.contains(SubprocessMessage.MESSAGE_PREFIX)) {
            String resultPrefix = first(
                    stringValue(processor.get("resultPrefix")), "PIPELINE_RESULTS:");
            String messageField = first(stringValue(processor.get("messageField")), "message");
            for (String line : trimmed.lines().toList()) {
                if (!line.startsWith(SubprocessMessage.MESSAGE_PREFIX)) {
                    continue;
                }
                JsonNode message =
                        MAPPER.readTree(line.substring(SubprocessMessage.MESSAGE_PREFIX.length()));
                String detail = message.path(messageField).asText("");
                if (!detail.startsWith(resultPrefix)) {
                    continue;
                }
                Object result = MAPPER.convertValue(
                        MAPPER.readTree(detail.substring(resultPrefix.length())), Object.class);
                String text = textualOutput(result);
                if (text != null) {
                    return text;
                }
            }
            if ("KOMPILE_MESSAGE".equals(protocol)) {
                return null;
            }
        }

        if ("JSON".equals(protocol) || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            JsonNode json = MAPPER.readTree(trimmed);
            String outputField = stringValue(processor.get("outputField"));
            if (outputField != null && !outputField.isBlank()) {
                for (String part : outputField.split("\\.")) {
                    json = json.path(part);
                }
            }
            String text = textualOutput(MAPPER.convertValue(json, Object.class));
            if (text != null) {
                return text;
            }
            if (json.isTextual()) {
                return json.asText();
            }
        }
        return trimmed;
    }

    private static Path registeredDefinitionPath(Path projectRoot, String definitionId) {
        if (definitionId == null
                || definitionId.isBlank()
                || definitionId.contains("/")
                || definitionId.contains("\\")) {
            throw new IllegalArgumentException(
                    "pipelineDefinitionId must be a registered pipeline identifier.");
        }

        String fileName = definitionId.endsWith(".json")
                ? definitionId : definitionId + ".json";
        List<Path> candidates = new ArrayList<>();
        candidates.add(projectRoot.resolve(".kompile/pipelines/unified").resolve(fileName));
        candidates.add(projectRoot.resolve("data/pipelines/unified").resolve(fileName));
        String dataDir = System.getProperty("kompile.data.dir");
        if (dataDir != null && !dataDir.isBlank()) {
            candidates.add(Path.of(dataDir).resolve("pipelines/unified").resolve(fileName));
        }
        candidates.add(Path.of(
                System.getProperty("user.home"),
                ".kompile", "pipelines", "unified", fileName));

        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException(
                "No registered UnifiedPipelineDefinition named '" + definitionId
                        + "' was found in the project or Kompile pipeline registry.");
    }

    private static WorkerOutput readWorkerOutput(InputStream stdout) throws IOException {
        JsonNode completion = null;
        String failure = null;
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SubprocessMessage.MESSAGE_PREFIX)) {
                    continue;
                }
                JsonNode message =
                        MAPPER.readTree(line.substring(SubprocessMessage.MESSAGE_PREFIX.length()));
                String type = message.path("type").asText();
                String detail = message.path("message").asText();
                if (detail.startsWith("VLM_RESULTS:")) {
                    completion = MAPPER.readTree(detail.substring("VLM_RESULTS:".length()));
                } else if ("FAILED".equals(type)) {
                    failure = message.path("errorMessage")
                            .asText("Document model pipeline failed");
                }
            }
        }
        return new WorkerOutput(completion, failure);
    }

    private static List<String> command(
            Path argsFile, Map<String, String> options, WorkerLocation worker) {
        List<String> command = new ArrayList<>();
        addExecutable(command, worker.executable(), options);
        if (worker.kind() == WorkerKind.UNIFIED) {
            command.add("--subprocess=vlm-test");
        }
        command.add(argsFile.toString());
        return command;
    }

    private static WorkerLocation resolveWorker(Path projectRoot, Map<String, ?> options) {
        return resolveWorker(projectRoot, options, CliProcessLauncher.requiresNativeChildren());
    }

    private static WorkerLocation resolveWorker(
            Path projectRoot, Map<String, ?> options, boolean nativeParent) {
        String requested = stringValue(options.get("documentModelExecutable"));
        if (requested != null && !requested.isBlank()) {
            Path path;
            try {
                path = resolvePath(projectRoot, requested);
            } catch (Exception e) {
                return WorkerLocation.unavailable(
                        "Invalid runtimeConfig.documentModelExecutable: " + e.getMessage());
            }
            if (!Files.isRegularFile(path)
                    || (!Files.isExecutable(path) && !isJar(path))) {
                return WorkerLocation.unavailable(
                        "runtimeConfig.documentModelExecutable is neither executable nor a JAR: "
                                + path);
            }
            if (nativeParent && isJar(path)) {
                return WorkerLocation.unavailable(
                        "runtimeConfig.documentModelExecutable resolves to an executable JAR, "
                                + "but native Kompile execution requires a native document-model "
                                + "worker: " + path);
            }
            boolean unified = "UNIFIED".equalsIgnoreCase(
                    stringValue(options.get("documentModelExecutableMode")));
            return new WorkerLocation(
                    path,
                    unified ? WorkerKind.UNIFIED : WorkerKind.DEDICATED,
                    "runtimeConfig.documentModelExecutable");
        }

        ComponentRegistry registry = new ComponentRegistry();
        Path dedicated = executablePath(
                registry.getDistributionBinaryPath("kompile-vlm-test"));
        if (dedicated == null && !nativeParent) {
            dedicated = runnablePath(registry.findInstalledJar("kompile-vlm-test"));
        }
        if (dedicated != null) {
            return new WorkerLocation(
                    dedicated, WorkerKind.DEDICATED, "component-registry:kompile-vlm-test");
        }

        Path configured = configuredExecutable();
        if (configured != null) {
            if (nativeParent && isJar(configured)) {
                return WorkerLocation.unavailable(
                        "The configured legacy document-model worker is an executable JAR, but "
                                + "native Kompile execution requires kompile-vlm-test as a native "
                                + "child executable: " + configured);
            }
            return new WorkerLocation(
                    configured, WorkerKind.DEDICATED, "legacy-property-or-environment");
        }
        return WorkerLocation.unavailable(
                nativeParent
                        ? "The native distribution is missing bin/kompile-vlm-test; executable-JAR "
                                + "and app-persona fallbacks are disabled for local MCP execution."
                        : "No standalone document-model executable or executable JAR was found.");
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void stop(Process process, ExecutorService outputReader) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        if (outputReader != null) {
            outputReader.shutdownNow();
        }
    }

    private static void addExecutable(
            List<String> command, Path executable, Map<String, String> options) {
        if (isJar(executable)) {
            command.add(JavaRuntimeLocator.javaExecutable());
            command.add("-Xmx" + first(options.get("heapSize"), "8g"));
            command.add("-XX:+UseG1GC");
            command.add("-XX:+ExitOnOutOfMemoryError");
            command.add("-Dorg.bytedeco.javacpp.nopointergc=true");
            command.add("-jar");
        }
        command.add(executable.toString());
    }

    private static Path executablePath(File file) {
        if (file == null) {
            return null;
        }
        Path path = file.toPath().toAbsolutePath().normalize();
        return Files.isRegularFile(path) && Files.isExecutable(path) ? path : null;
    }

    private static Path runnablePath(File file) {
        if (file == null) {
            return null;
        }
        Path path = file.toPath().toAbsolutePath().normalize();
        return Files.isRegularFile(path) && (Files.isExecutable(path) || isJar(path))
                ? path : null;
    }

    private static boolean isJar(Path path) {
        return path != null
                && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static Path resolvePath(Path projectRoot, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            Path base = projectRoot == null ? Path.of("").toAbsolutePath() : projectRoot;
            path = base.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path configuredExecutable() {
        String value = first(
                System.getProperty("kompile.subprocess.executable.vlm-test-path"),
                System.getenv("KOMPILE_VLM_SUBPROCESS_PATH"));
        if (value == null) {
            return null;
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        return Files.isRegularFile(path) && (Files.isExecutable(path) || isJar(path))
                ? path : null;
    }

    private static String pagesText(JsonNode completion) {
        if (completion == null || !completion.path("pages").isArray()) {
            return "";
        }
        List<String> pages = new ArrayList<>();
        for (JsonNode page : completion.path("pages")) {
            String text = page.path("text").asText("").strip();
            if (!text.isBlank()) {
                pages.add(text);
            }
        }
        return String.join("\n\n", pages);
    }

    private static String textualOutput(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("markdown", "text", "content", "output")) {
                String text = textualOutput(map.get(key));
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
            Object pages = map.get("pages");
            if (pages != null) {
                return pagesText(MAPPER.valueToTree(Map.of("pages", pages)));
            }
        }
        if (value instanceof List<?> values) {
            List<String> parts = new ArrayList<>();
            for (Object item : values) {
                String text = textualOutput(item);
                if (text != null && !text.isBlank()) {
                    parts.add(text);
                }
            }
            return parts.isEmpty() ? null : String.join("\n\n", parts);
        }
        return null;
    }

    static ResolvedModelContext resolveBoundModels(
            Path projectRoot,
            LocalCrawlCapabilities.ResolvedPipeline pipeline,
            UnifiedPipelineDefinition definition) throws IOException, InterruptedException {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (definition != null) {
            mergeBindings(bindings, definition.getModelBindings());
        }
        mergeBindings(bindings, pipeline.chunkerOptions().get("modelBindings"));
        mergeBindings(bindings, pipeline.processor().get("modelBindings"));
        addProjectModelRefs(projectRoot, bindings,
                pipeline.chunkerOptions().get("modelRefs"),
                pipeline.processor().get("modelRefs"));

        Map<String, Map<String, Object>> definitions = new LinkedHashMap<>();
        mergeModelDefinitions(definitions, pipeline.processor().get("registeredModelDefinitions"));
        mergeModelDefinitions(definitions, pipeline.chunkerOptions().get("modelDefinitions"));
        mergeModelDefinitions(definitions, pipeline.processor().get("modelDefinitions"));
        if (definition != null) {
            mergeModelDefinitions(definitions, definition.getModelDefinitions());
        }

        Object defaultRuntime = firstObject(
                pipeline.processor().get("modelRuntime"),
                pipeline.chunkerOptions().get("modelRuntime"));
        String modelSetId = first(
                stringValue(pipeline.chunkerOptions().get("modelSetId")),
                definition == null ? null : definition.getModelSetId());
        String legacyModelId = first(
                stringValue(pipeline.chunkerOptions().get("modelId")),
                stringValue(pipeline.chunkerOptions().get("vlmModel")),
                modelSetId);
        if (bindings.isEmpty() && legacyModelId != null
                && (modelSetId != null || defaultRuntime != null)) {
            bindings.put("default", legacyModelId);
        }
        if (bindings.isEmpty()) {
            if (defaultRuntime != null) {
                throw new IllegalArgumentException(
                        "modelRuntime requires a model binding, modelSetId, modelId, or vlmModel.");
            }
            return ResolvedModelContext.empty();
        }

        Map<String, Map<String, Object>> resolvedModels = new LinkedHashMap<>();
        Map<String, LocalProjectModelBootstrap.ResolvedProjectModel> resolvedByReference =
                new LinkedHashMap<>();
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            String role = binding.getKey();
            String reference = binding.getValue();
            Map<String, Object> modelDefinition = definitions.get(reference);
            String selection = first(
                    modelDefinition == null ? null : stringValue(modelDefinition.get("modelId")),
                    modelDefinition == null ? null : stringValue(modelDefinition.get("id")),
                    reference);
            Map<String, Object> runtimeOptions = modelRuntimeOptions(defaultRuntime, modelDefinition);
            LocalProjectModelBootstrap.ResolvedProjectModel resolved = resolvedByReference.get(reference);
            if (resolved == null) {
                resolved = LocalProjectModelBootstrap.ensure(projectRoot, selection, runtimeOptions);
                resolvedByReference.put(reference, resolved);
            }

            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("binding", role);
            descriptor.put("reference", reference);
            descriptor.put("modelId", resolved.modelId());
            descriptor.put("modelPath", resolved.modelPath().toString());
            putIfNonNull(descriptor, "tokenizerPath", resolved.tokenizerPath());
            putIfNonNull(descriptor, "stagingRuntime", resolved.stagingRuntime());
            descriptor.put("bootstrapped", resolved.bootstrapped());
            descriptor.put("disposition", resolved.disposition());
            descriptor.put("role", first(
                    modelDefinition == null ? null : stringValue(modelDefinition.get("role")), role));
            resolvedModels.put(role, Map.copyOf(descriptor));
        }
        return new ResolvedModelContext(Map.copyOf(bindings), Map.copyOf(resolvedModels));
    }

    private static void mergeBindings(Map<String, String> target, Object configured) {
        if (!(configured instanceof Map<?, ?> values)) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String role = entry.getKey() == null ? null : String.valueOf(entry.getKey()).trim();
            String model = entry.getValue() == null ? null : String.valueOf(entry.getValue()).trim();
            if (role == null || role.isBlank() || model == null || model.isBlank()) {
                throw new IllegalArgumentException(
                        "modelBindings must map non-empty roles to non-empty model ids.");
            }
            target.put(role, model);
        }
    }

    private static void mergeModelDefinitions(
            Map<String, Map<String, Object>> target, Object configured) {
        if (!(configured instanceof Map<?, ?> values)) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> definition)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            definition.forEach((key, value) -> {
                if (key != null && value != null) normalized.put(String.valueOf(key), value);
            });
            target.put(String.valueOf(entry.getKey()), Map.copyOf(normalized));
        }
    }

    private static void addProjectModelRefs(
            Path projectRoot,
            Map<String, String> bindings,
            Object firstRefs,
            Object secondRefs) {
        List<String> refs = new ArrayList<>(stringList(firstRefs));
        for (String ref : stringList(secondRefs)) {
            if (!refs.contains(ref)) refs.add(ref);
        }
        if (refs.isEmpty()) return;
        List<Map<String, Object>> inventory = LocalProjectModelBootstrap.inventory(projectRoot);
        for (String ref : refs) {
            if (bindings.containsValue(ref)) continue;
            Map<String, Object> model = inventory.stream()
                    .filter(item -> matchesModelReference(item, ref))
                    .findFirst().orElse(null);
            String role = model == null ? null : normalizeRole(stringValue(model.get("role")));
            if (role == null && refs.size() == 1) role = "default";
            if (role == null) {
                throw new IllegalArgumentException(
                        "Project pipeline modelRefs contains '" + ref
                                + "' without a resolvable role; use explicit modelBindings.");
            }
            String previous = bindings.putIfAbsent(role, ref);
            if (previous != null && !previous.equals(ref)) {
                throw new IllegalArgumentException(
                        "Project pipeline modelRefs assigns multiple models to role '" + role
                                + "'; use explicit modelBindings.");
            }
        }
    }

    private static boolean matchesModelReference(Map<String, Object> model, String reference) {
        return reference.equals(stringValue(model.get("id")))
                || reference.equals(stringValue(model.get("modelId")))
                || reference.equals(stringValue(model.get("registryModelId")));
    }

    private static String normalizeRole(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-");
        return normalized.isBlank() ? null : normalized;
    }

    private static Map<String, Object> modelRuntimeOptions(
            Object defaults, Map<String, Object> definition) {
        Map<String, Object> options = new LinkedHashMap<>();
        mergeObject(options, defaults);
        if (definition != null) {
            definition.forEach((key, value) -> {
                if (value != null && !Set.of("id", "modelId", "role", "runtime").contains(key)) {
                    options.put(key, value);
                }
            });
            mergeObject(options, definition.get("runtime"));
            copyAlias(options, "sourceRepository", "repository");
            copyAlias(options, "sourceRevision", "revision");
            copyAlias(options, "path", "localPath");
            copyAlias(options, "registryType", "type");
        }
        return options;
    }

    private static void mergeObject(Map<String, Object> target, Object configured) {
        if (!(configured instanceof Map<?, ?> values)) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
    }

    private static void copyAlias(Map<String, Object> values, String source, String target) {
        if (!values.containsKey(target) && values.containsKey(source)) {
            values.put(target, values.get(source));
        }
    }

    private static void putIfNonNull(Map<String, Object> target, String key, Path value) {
        if (value != null) target.put(key, value.toString());
    }

    private static Map<String, Object> preferredResolvedModel(
            Map<String, Map<String, Object>> resolvedModels) {
        for (String role : List.of("vision", "vlm", "default")) {
            Map<String, Object> model = resolvedModels.get(role);
            if (model != null) return model;
        }
        return resolvedModels.values().stream().findFirst().orElse(null);
    }

    private static Map<String, Object> modelRuntimeOptions(Object configured) {
        if (!(configured instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("modelRuntime must be an object.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private static Map<String, String> stringOptions(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value == null || "pipelineDefinition".equals(key)) {
                return;
            }
            try {
                result.put(key, value instanceof Map<?, ?> || value instanceof List<?>
                        ? MAPPER.writeValueAsString(value) : String.valueOf(value));
            } catch (Exception ignored) {
                result.put(key, String.valueOf(value));
            }
        });
        return result;
    }

    private static int integer(Map<String, String> options, String key, int fallback) {
        try {
            return Integer.parseInt(options.getOrDefault(key, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double decimal(Map<String, String> options, String key, double fallback) {
        try {
            return Double.parseDouble(options.getOrDefault(key, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean bool(Map<String, String> options, String key, boolean fallback) {
        String value = options.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static void applyEnvironment(Map<String, String> target, Object configured) {
        if (!(configured instanceof Map<?, ?> values)) {
            return;
        }
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
    }

    private static long longValue(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static Object firstObject(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Object jsonValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isFloatingPointNumber()) {
            return value.asDouble();
        }
        return MAPPER.convertValue(value, Object.class);
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String documentModelFailure(
            int exitCode, String protocolFailure, String stderrTail) {
        List<String> details = new ArrayList<>();
        if (protocolFailure != null && !protocolFailure.isBlank()) {
            details.add(protocolFailure.strip());
        }
        if (stderrTail != null && !stderrTail.isBlank()
                && (protocolFailure == null || !stderrTail.strip().equals(protocolFailure.strip()))) {
            details.add("Document model subprocess stderr:\n" + stderrTail.strip());
        }
        if (details.isEmpty()) {
            details.add("Document model subprocess exited with " + exitCode);
        } else if (exitCode != 0) {
            details.add("Document model subprocess exit code: " + exitCode);
        }
        return String.join("\n", details);
    }

    private static String tail(Path logFile) {
        final int maxBytes = 16 * 1024;
        try (SeekableByteChannel channel = Files.newByteChannel(
                logFile, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, maxBytes);
            channel.position(Math.max(0, size - length));
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read only the bounded tail. The worker may emit a large diagnostic log.
            }
            String value = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8)
                    .strip();
            return value.length() > 4_000
                    ? value.substring(value.length() - 4_000) : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    public record DocumentModelWorkerStatus(
            boolean available, String source, String executable, boolean unifiedExecutable) {
    }

    private record WorkerLocation(Path executable, WorkerKind kind, String source) {
        static WorkerLocation unavailable(String source) {
            return new WorkerLocation(null, WorkerKind.UNAVAILABLE, source);
        }

        boolean available() {
            return kind != WorkerKind.UNAVAILABLE;
        }
    }

    private enum WorkerKind {
        DEDICATED,
        UNIFIED,
        UNAVAILABLE
    }

    private record WorkerOutput(JsonNode completion, String failure) {
    }

    record ResolvedModelContext(
            Map<String, String> bindings,
            Map<String, Map<String, Object>> resolvedModels) {
        static ResolvedModelContext empty() {
            return new ResolvedModelContext(Map.of(), Map.of());
        }
    }
}
