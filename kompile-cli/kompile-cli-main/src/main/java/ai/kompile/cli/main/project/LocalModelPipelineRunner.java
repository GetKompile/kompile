/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.VlmTestSubprocessArgs;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.launcher.PipelineSubprocessLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs model-backed document extraction without requiring a running Kompile server. */
public final class LocalModelPipelineRunner {
    private static final String DOCUMENT_MODEL_MAIN = "ai.kompile.app.subprocess.VlmTestSubprocessMain";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private LocalModelPipelineRunner() {
    }

    public static boolean documentModelWorkerAvailable() {
        if (configuredExecutable() != null) return true;
        try {
            Class.forName(DOCUMENT_MODEL_MAIN, false, LocalModelPipelineRunner.class.getClassLoader());
            return !System.getProperty("java.class.path", "").isBlank();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Execute either a caller-supplied unified framework pipeline or Kompile's PDF OCR/VLM worker.
     * The returned value is document text/Markdown ready for normal local chunking and indexing.
     */
    public static String extract(Path projectRoot,
                                 Path file,
                                 LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                 String loadedText) throws Exception {
        Object definition = pipeline.chunkerOptions().get("pipelineDefinition");
        Object definitionPath = pipeline.chunkerOptions().get("pipelineDefinitionPath");
        if (definition != null || definitionPath != null) {
            return runUnified(projectRoot, file, pipeline, loadedText, definition, definitionPath);
        }
        return runDocumentModel(file, pipeline);
    }

    private static String runUnified(Path projectRoot,
                                     Path file,
                                     LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                     String loadedText,
                                     Object inlineDefinition,
                                     Object definitionPath) throws Exception {
        UnifiedPipelineDefinition definition;
        if (inlineDefinition != null) {
            definition = inlineDefinition instanceof String json
                    ? MAPPER.readValue(json, UnifiedPipelineDefinition.class)
                    : MAPPER.convertValue(inlineDefinition, UnifiedPipelineDefinition.class);
        } else {
            Path path = Path.of(String.valueOf(definitionPath));
            if (!path.isAbsolute()) path = projectRoot.resolve(path);
            definition = MAPPER.readValue(path.normalize().toFile(), UnifiedPipelineDefinition.class);
        }
        if (definition.getPipelineId() == null || definition.getPipelineId().isBlank()) {
            definition.setPipelineId(pipeline.pipelineId());
        }
        if (definition.getPipelineSpec() == null || definition.getPipelineSpec().isEmpty()) {
            throw new IllegalArgumentException("Unified pipeline " + definition.getPipelineId()
                    + " has no executable pipelineSpec.");
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("filePath", file.toAbsolutePath().normalize().toString());
        input.put("path", file.toAbsolutePath().normalize().toString());
        input.put("source", file.toUri().toString());
        input.put("pipelineType", pipeline.pipelineType());
        input.put("text", loadedText == null ? "" : loadedText);
        input.put("optionsJson", MAPPER.writeValueAsString(pipeline.chunkerOptions()));
        pipeline.chunkerOptions().forEach((key, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                input.put("option." + key, value);
            }
        });
        Map<String, Object> result = new PipelineSubprocessLauncher().launchOneShot(definition, input);
        String text = textualOutput(result.get("output"));
        if (text == null || text.isBlank()) {
            throw new IOException("Unified pipeline " + definition.getPipelineId()
                    + " completed without a text or markdown output.");
        }
        return text;
    }

    private static String runDocumentModel(Path file,
                                           LocalCrawlCapabilities.ResolvedPipeline pipeline) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf")) {
            throw new IllegalArgumentException(pipeline.pipelineType()
                    + " local document processing currently requires a PDF or an explicit pipelineDefinition.");
        }
        if (!documentModelWorkerAvailable()) {
            throw new IllegalStateException("The Kompile document-model subprocess is not installed. "
                    + "Install the model worker or provide pipelineDefinition/pipelineDefinitionPath.");
        }

        Map<String, String> options = stringOptions(pipeline.chunkerOptions());
        options.put("pipelineType", pipeline.pipelineType());
        String modelId = first(options.get("vlmModel"), options.get("modelId"),
                options.get("modelSetId"), stringValue(pipeline.chunkerOptions().get("vlmModel")));
        VlmTestSubprocessArgs.Builder args = VlmTestSubprocessArgs.builder()
                .taskId("local-crawl-" + pipeline.pipelineId() + "-" + Long.toUnsignedString(System.nanoTime()))
                .filePath(file.toAbsolutePath().normalize().toString())
                .modelId(modelId)
                .outputFormat(first(options.get("outputFormat"), "MARKDOWN"))
                .maxNewTokens(integer(options, "maxNewTokens", VlmTestSubprocessArgs.DEFAULT_MAX_NEW_TOKENS))
                .temperature(decimal(options, "temperature", VlmTestSubprocessArgs.DEFAULT_TEMPERATURE))
                .topP(decimal(options, "topP", VlmTestSubprocessArgs.DEFAULT_TOP_P))
                .beamSize(integer(options, "beamSize", VlmTestSubprocessArgs.DEFAULT_BEAM_SIZE))
                .doSample(bool(options, "doSample", false))
                .pdfRenderDpi(integer(options, "pdfRenderDpi", VlmTestSubprocessArgs.DEFAULT_PDF_RENDER_DPI))
                .pageBatchSize(integer(options, "pageBatchSize", VlmTestSubprocessArgs.DEFAULT_PAGE_BATCH_SIZE))
                .kvCacheStrategy(first(options.get("kvCacheStrategy"), "STATIC"))
                .maxKvLen(integer(options, "maxKvLen", 0))
                .maxPages(integer(options, "maxPages", 0))
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
            List<String> command = command(argsFile, options);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(file.toAbsolutePath().normalize().getParent().toFile())
                    .redirectError(logFile.toFile());
            process = builder.start();
            Process worker = process;
            outputReader = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kompile-local-model-output");
                thread.setDaemon(true);
                return thread;
            });
            Future<WorkerOutput> outputFuture =
                    outputReader.submit(() -> readWorkerOutput(worker.getInputStream()));
            long timeoutMinutes = Math.max(1, integer(options, "timeoutMinutes", 30));
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Document model subprocess timed out after " + timeoutMinutes + " minute(s).");
            }
            WorkerOutput output;
            try {
                output = outputFuture.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IOException("Document model subprocess output did not close after completion.", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw new IOException("Unable to read document model subprocess output.", cause);
            }
            if (process.exitValue() != 0 || output.failure() != null) {
                throw new IOException(first(output.failure(), tail(logFile),
                        "Document model subprocess exited with " + process.exitValue()));
            }
            String text = pagesText(output.completion());
            if (text.isBlank()) throw new IOException("Document model subprocess returned no extracted text.");
            return text;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (outputReader != null) outputReader.shutdownNow();
            Files.deleteIfExists(argsFile);
            Files.deleteIfExists(logFile);
        }
    }

    private static WorkerOutput readWorkerOutput(InputStream stdout) throws IOException {
        JsonNode completion = null;
        String failure = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SubprocessMessage.MESSAGE_PREFIX)) continue;
                JsonNode message = MAPPER.readTree(line.substring(SubprocessMessage.MESSAGE_PREFIX.length()));
                String type = message.path("type").asText();
                String detail = message.path("message").asText();
                if (detail.startsWith("VLM_RESULTS:")) {
                    completion = MAPPER.readTree(detail.substring("VLM_RESULTS:".length()));
                } else if ("FAILED".equals(type)) {
                    failure = message.path("errorMessage").asText("Document model pipeline failed");
                }
            }
        }
        return new WorkerOutput(completion, failure);
    }

    private static List<String> command(Path argsFile, Map<String, String> options) {
        Path executable = configuredExecutable();
        if (executable != null) return List.of(executable.toString(), argsFile.toString());
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java")
                .toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path", ""));
        return List.of(java, "-Xmx" + first(options.get("heapSize"), "8g"),
                "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError",
                "-Dorg.bytedeco.javacpp.nopointergc=true",
                "-cp", classpath, DOCUMENT_MODEL_MAIN, argsFile.toString());
    }

    private static Path configuredExecutable() {
        String value = first(System.getProperty("kompile.subprocess.executable.vlm-test-path"),
                System.getenv("KOMPILE_VLM_SUBPROCESS_PATH"));
        if (value == null) return null;
        Path path = Path.of(value).toAbsolutePath().normalize();
        return Files.isRegularFile(path) && Files.isExecutable(path) ? path : null;
    }

    private static String pagesText(JsonNode completion) {
        if (completion == null || !completion.path("pages").isArray()) return "";
        List<String> pages = new ArrayList<>();
        for (JsonNode page : completion.path("pages")) {
            String text = page.path("text").asText("").strip();
            if (!text.isBlank()) pages.add(text);
        }
        return String.join("\n\n", pages);
    }

    @SuppressWarnings("unchecked")
    private static String textualOutput(Object value) throws IOException {
        if (value == null) return null;
        if (value instanceof String text) return text;
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("markdown", "text", "content", "output")) {
                Object candidate = map.get(key);
                if (candidate != null) {
                    String text = textualOutput(candidate);
                    if (text != null && !text.isBlank()) return text;
                }
            }
            Object pages = map.get("pages");
            if (pages != null) return pagesText(MAPPER.valueToTree(Map.of("pages", pages)));
        }
        return null;
    }

    private static Map<String, String> stringOptions(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value == null || "pipelineDefinition".equals(key)) return;
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
        try { return Integer.parseInt(options.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static double decimal(Map<String, String> options, String key, double fallback) {
        try { return Double.parseDouble(options.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(Map<String, String> options, String key, boolean fallback) {
        String value = options.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private record WorkerOutput(JsonNode completion, String failure) {
    }

    private static String tail(Path logFile) {
        try {
            String value = Files.readString(logFile, StandardCharsets.UTF_8).strip();
            return value.length() > 4_000 ? value.substring(value.length() - 4_000) : value;
        } catch (Exception ignored) {
            return null;
        }
    }
}
