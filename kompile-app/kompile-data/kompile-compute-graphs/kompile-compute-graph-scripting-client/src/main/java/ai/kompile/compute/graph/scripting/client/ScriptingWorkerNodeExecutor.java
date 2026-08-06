package ai.kompile.compute.graph.scripting.client;

import ai.kompile.cli.common.util.JavaRuntimeLocator;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionLimits;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Native-image-safe JavaScript/Python executor backed by the separately packaged JVM worker.
 *
 * <p>The worker is intentionally a process boundary. GraalJS, Truffle and Python4J therefore stay
 * out of the app-main native-image classpath while process-engine SCRIPT steps still use the normal
 * {@link NodeExecutor} discovery and execution contract.</p>
 */
public class ScriptingWorkerNodeExecutor implements NodeExecutor {

    public static final String RESULT_PREFIX = "KOMPILE_SCRIPT_RESULT:";
    public static final String WORKER_JAR_NAME = "kompile-scripting-worker.jar";
    public static final String WORKER_JAR_PROPERTY = "kompile.scripting.worker.jar";
    public static final String WORKER_JAR_ENV = "KOMPILE_SCRIPTING_WORKER_JAR";

    private static final Logger log = LoggerFactory.getLogger(ScriptingWorkerNodeExecutor.class);
    private static final ObjectMapper mapper = JsonUtils.standardMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration STARTUP_ALLOWANCE = Duration.ofSeconds(30);

    private final String javaExecutable;
    private final Path configuredWorkerJar;
    private final List<String> fixedCommand;
    private final Duration defaultTimeout;

    /** Creates a distribution-aware executor. Worker resolution is lazy so application boot stays cheap. */
    public ScriptingWorkerNodeExecutor() {
        this(JavaRuntimeLocator.javaExecutable(), null, null, DEFAULT_TIMEOUT);
    }

    /** Visible for focused process-boundary tests. */
    ScriptingWorkerNodeExecutor(List<String> fixedCommand, Duration defaultTimeout) {
        this(null, null, List.copyOf(fixedCommand), defaultTimeout);
    }

    /** Explicit worker location for embedders that do not use the standard Kompile distribution layout. */
    public ScriptingWorkerNodeExecutor(String javaExecutable, Path workerJar, Duration defaultTimeout) {
        this(javaExecutable, workerJar, null, defaultTimeout);
    }

    private ScriptingWorkerNodeExecutor(String javaExecutable, Path configuredWorkerJar,
                                        List<String> fixedCommand, Duration defaultTimeout) {
        this.javaExecutable = javaExecutable;
        this.configuredWorkerJar = configuredWorkerJar;
        this.fixedCommand = fixedCommand;
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : DEFAULT_TIMEOUT;
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        try {
            JsonNode response = invoke("execute", node, inputs, context, timeoutFor(node));
            return resultFromResponse(response, node.getId(), context.getExecutionId());
        } catch (Exception e) {
            log.error("Scripting worker execution failed for node '{}'", node.getName(), e);
            return ExecutionResult.failure(node.getId(), context.getExecutionId(), e.getMessage(), stackTrace(e));
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.JAVASCRIPT, NodeExecutionType.PYTHON);
    }

    @Override
    public String validate(ComputeNode node) {
        if (node == null || node.getScript() == null || node.getScript().isBlank()) {
            return "Script is empty";
        }
        try {
            JsonNode response = invoke("validate", node, Map.of(), null, defaultTimeout);
            return nullableText(response, "error");
        } catch (Exception e) {
            return "Scripting worker validation failed: " + e.getMessage();
        }
    }

    private JsonNode invoke(String operation, ComputeNode node, Map<String, Object> inputs,
                            ExecutionContext context, Duration timeout) throws Exception {
        List<String> command = buildCommand();
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                () -> readProcessOutput(process.getInputStream()));
        try (var stdin = process.getOutputStream()) {
            mapper.writeValue(stdin, request(operation, node, inputs, context));
        }

        long timeoutMillis = Math.max(1L, timeout.toMillis());
        boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }

        String combinedOutput;
        try {
            combinedOutput = outputFuture.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeoutException) {
            process.destroyForcibly();
            throw new IOException("Scripting worker output did not close after process termination", timeoutException);
        }

        if (!finished) {
            throw new IOException("Scripting worker timed out after " + timeout.toSeconds() + " seconds");
        }

        String payload = findProtocolPayload(combinedOutput);
        if (payload == null) {
            throw new IOException("Scripting worker exited with code " + process.exitValue()
                    + " without a protocol response. Output: " + abbreviate(combinedOutput));
        }
        byte[] decoded = Base64.getDecoder().decode(payload);
        return mapper.readTree(decoded);
    }

    private List<String> buildCommand() throws IOException {
        if (fixedCommand != null) {
            return fixedCommand;
        }

        Path workerJar = configuredWorkerJar != null
                ? requireWorkerJar(configuredWorkerJar, "configured worker path")
                : locateWorkerJar();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable != null && !javaExecutable.isBlank()
                ? javaExecutable : JavaRuntimeLocator.javaExecutable());
        command.add("-Dfile.encoding=UTF-8");
        String pythonMode = System.getProperty("kompile.scripting.python.mode");
        if (pythonMode != null && !pythonMode.isBlank()) {
            command.add("-Dkompile.scripting.python.mode=" + pythonMode);
        }
        command.add("-jar");
        command.add(workerJar.toString());
        return command;
    }

    private static ObjectNode request(String operation, ComputeNode node, Map<String, Object> inputs,
                                      ExecutionContext context) {
        ObjectNode root = mapper.createObjectNode();
        root.put("operation", operation);
        root.put("executionId", context != null ? context.getExecutionId() : "validation");
        root.set("inputs", mapper.valueToTree(inputs != null ? inputs : Map.of()));
        root.set("globalState", mapper.valueToTree(context != null ? context.getGlobalState() : Map.of()));

        ObjectNode nodeJson = root.putObject("node");
        putNullable(nodeJson, "id", node.getId());
        putNullable(nodeJson, "name", node.getName());
        putNullable(nodeJson, "description", node.getDescription());
        nodeJson.put("executionType", node.getExecutionType().name());
        putNullable(nodeJson, "script", node.getScript());
        nodeJson.set("parameters", mapper.valueToTree(node.getParameters() != null ? node.getParameters() : Map.of()));
        nodeJson.set("inputBindings", mapper.valueToTree(node.getInputBindings() != null ? node.getInputBindings() : Map.of()));
        nodeJson.set("outputBindings", mapper.valueToTree(node.getOutputBindings() != null ? node.getOutputBindings() : Map.of()));
        nodeJson.set("metadata", mapper.valueToTree(node.getMetadata() != null ? node.getMetadata() : Map.of()));

        ExecutionLimits limits = node.getLimits() != null ? node.getLimits() : ExecutionLimits.defaults();
        ObjectNode limitsJson = nodeJson.putObject("limits");
        if (limits.getMaxCpuTime() == null) {
            limitsJson.putNull("maxCpuTimeMillis");
        } else {
            limitsJson.put("maxCpuTimeMillis", limits.getMaxCpuTime().toMillis());
        }
        limitsJson.put("maxHeapMemoryBytes", limits.getMaxHeapMemoryBytes());
        limitsJson.put("maxStackFrames", limits.getMaxStackFrames());
        limitsJson.put("allowIO", limits.isAllowIO());
        limitsJson.put("allowNetwork", limits.isAllowNetwork());
        limitsJson.put("allowHostAccess", limits.isAllowHostAccess());
        return root;
    }

    @SuppressWarnings("unchecked")
    private static ExecutionResult resultFromResponse(JsonNode response, String fallbackNodeId,
                                                       String fallbackExecutionId) {
        JsonNode outputsNode = response.get("outputs");
        Map<String, Object> outputs = outputsNode != null && !outputsNode.isNull()
                ? (Map<String, Object>) mapper.convertValue(outputsNode, Map.class) : Map.of();
        ExecutionResult.ExecutionResultBuilder builder = ExecutionResult.builder()
                .nodeId(textOr(response, "nodeId", fallbackNodeId))
                .executionId(textOr(response, "executionId", fallbackExecutionId))
                .status(ExecutionStatus.valueOf(textOr(response, "status", ExecutionStatus.FAILED.name())))
                .outputs(outputs)
                .error(nullableText(response, "error"))
                .stackTrace(nullableText(response, "stackTrace"))
                .consoleOutput(nullableText(response, "consoleOutput"));
        if (response.hasNonNull("durationMillis")) {
            builder.duration(Duration.ofMillis(response.get("durationMillis").asLong()));
        }
        if (response.hasNonNull("startedAtEpochMillis")) {
            builder.startedAt(Instant.ofEpochMilli(response.get("startedAtEpochMillis").asLong()));
        }
        if (response.hasNonNull("completedAtEpochMillis")) {
            builder.completedAt(Instant.ofEpochMilli(response.get("completedAtEpochMillis").asLong()));
        }
        return builder.build();
    }

    private Duration timeoutFor(ComputeNode node) {
        if (node != null && node.getLimits() != null && node.getLimits().getMaxCpuTime() != null) {
            Duration requested = node.getLimits().getMaxCpuTime().plus(STARTUP_ALLOWANCE);
            return requested.compareTo(defaultTimeout) > 0 ? requested : defaultTimeout;
        }
        return defaultTimeout;
    }

    static Path locateWorkerJar() throws IOException {
        String property = System.getProperty(WORKER_JAR_PROPERTY);
        if (property != null && !property.isBlank()) {
            return requireWorkerJar(Path.of(property), "-D" + WORKER_JAR_PROPERTY);
        }
        String environment = System.getenv(WORKER_JAR_ENV);
        if (environment != null && !environment.isBlank()) {
            return requireWorkerJar(Path.of(environment), "$" + WORKER_JAR_ENV);
        }

        List<Path> candidates = new ArrayList<>();
        addDistributionCandidate(candidates, System.getenv("KOMPILE_DIST_HOME"));
        addDistributionCandidate(candidates, System.getenv("KOMPILE_INSTALL_DIR"));
        addDistributionCandidate(candidates,
                Path.of(System.getProperty("user.home"), ".kompile").toString());

        Path nativeExecutable = NativeImageInfo.getExecutablePathAsPath();
        if (nativeExecutable != null) {
            Path bin = nativeExecutable.toAbsolutePath().normalize().getParent();
            if (bin != null && bin.getParent() != null) {
                candidates.add(bin.getParent().resolve("lib").resolve(WORKER_JAR_NAME));
            }
        }

        // JVM fallback launched with `java -jar <dist>/lib/kompile-server.jar`: the Boot jar is
        // the classpath entry even though this client class itself lives in BOOT-INF/lib.
        String classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            String separator = System.getProperty("path.separator");
            for (String entry : classPath.split(java.util.regex.Pattern.quote(separator))) {
                if (!entry.isBlank()) {
                    Path classPathEntry = Path.of(entry).toAbsolutePath().normalize();
                    Path parent = classPathEntry.getParent();
                    if (parent != null && Files.isRegularFile(classPathEntry)) {
                        candidates.add(parent.resolve(WORKER_JAR_NAME));
                    }
                }
            }
        }

        try {
            URI location = ScriptingWorkerNodeExecutor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path codeSource = Path.of(location).toAbsolutePath().normalize();
            Path parent = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            if (parent != null) {
                candidates.add(parent.resolve(WORKER_JAR_NAME));
            }
        } catch (Exception ignored) {
            // The explicit distribution and native-executable candidates remain authoritative.
        }

        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        candidates.add(workingDirectory.resolve("lib").resolve(WORKER_JAR_NAME));
        Path developmentTarget = workingDirectory.resolve(
                "kompile-app/kompile-data/kompile-compute-graphs/kompile-compute-graph-scripting/target");
        Path developmentJar = firstExecJar(developmentTarget);
        if (developmentJar != null) {
            candidates.add(developmentJar);
        }

        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IOException("Unable to locate " + WORKER_JAR_NAME
                + ". Install a complete Kompile distribution or set -D" + WORKER_JAR_PROPERTY
                + " / $" + WORKER_JAR_ENV + ". Checked: " + candidates);
    }

    private static Path firstExecJar(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*-exec.jar")) {
            for (Path jar : jars) {
                if (Files.isRegularFile(jar)) {
                    return jar;
                }
            }
        } catch (IOException ignored) {
            // Caller will report every checked location in the final resolution error.
        }
        return null;
    }

    private static void addDistributionCandidate(List<Path> candidates, String root) {
        if (root != null && !root.isBlank()) {
            candidates.add(Path.of(root).toAbsolutePath().normalize().resolve("lib").resolve(WORKER_JAR_NAME));
        }
    }

    private static Path requireWorkerJar(Path path, String source) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Scripting worker JAR from " + source + " does not exist: " + normalized);
        }
        return normalized;
    }

    private static String readProcessOutput(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read scripting worker output", e);
        }
    }

    private static String findProtocolPayload(String output) {
        String payload = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith(RESULT_PREFIX)) {
                payload = line.substring(RESULT_PREFIX.length()).trim();
            }
        }
        return payload;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = nullableText(node, field);
        return value != null ? value : fallback;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        return normalized.length() <= 1000 ? normalized : normalized.substring(normalized.length() - 1000);
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
