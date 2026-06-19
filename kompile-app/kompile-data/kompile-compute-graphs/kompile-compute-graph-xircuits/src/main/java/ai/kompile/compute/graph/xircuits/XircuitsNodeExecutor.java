package ai.kompile.compute.graph.xircuits;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.xircuits.model.XircuitsNode;
import ai.kompile.compute.graph.xircuits.model.XircuitsWorkflow;
import com.fasterxml.jackson.core.type.TypeReference;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Executes Xircuits workflows on compute graph nodes.
 * Xircuits is a Python-based visual workflow engine built on JupyterLab.
 * <p>
 * The node's {@code script} field contains the Xircuits workflow definition (JSON).
 * Execution follows the real Xircuits CLI pipeline:
 * <ol>
 *   <li>Write .xircuits JSON to a temp file</li>
 *   <li>Compile: {@code xircuits compile workflow.xircuits workflow.py}</li>
 *   <li>Run: {@code python3 workflow.py --arg1 val1 --arg2 val2}</li>
 *   <li>Capture stdout/stderr for outputs</li>
 * </ol>
 * <p>
 * Inputs from upstream nodes are matched to Xircuits Argument nodes by name.
 * An Argument node named "Argument (string): query" maps to CLI flag {@code --query}.
 * <p>
 * Parameters:
 * <ul>
 *   <li>{@code xircuitsExecutable} — path to the xircuits binary (default: "xircuits")</li>
 *   <li>{@code pythonExecutable} — path to python binary (default: "python3")</li>
 *   <li>{@code workingDirectory} — working directory for the subprocess</li>
 *   <li>{@code timeoutSeconds} — max execution time (default: from ExecutionLimits)</li>
 *   <li>{@code environmentVars} — additional environment variables as a map</li>
 *   <li>{@code precompiled} — path to an already-compiled .py file (skip compile step)</li>
 * </ul>
 */
@Slf4j
public class XircuitsNodeExecutor implements NodeExecutor {

    private final ObjectMapper objectMapper;
    private final String defaultExecutable;
    private final String defaultPythonExecutable;

    public XircuitsNodeExecutor() {
        this("xircuits", "python3");
    }

    public XircuitsNodeExecutor(String defaultExecutable, String defaultPythonExecutable) {
        this.objectMapper = JsonUtils.standardMapper();
        this.defaultExecutable = defaultExecutable;
        this.defaultPythonExecutable = defaultPythonExecutable;
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();
        Path workflowFile = null;
        Path compiledFile = null;
        boolean compiledIsTemp = false;

        try {
            Map<String, Object> params = node.getParameters() != null ? node.getParameters() : Map.of();
            String executable = (String) params.getOrDefault("xircuitsExecutable", defaultExecutable);
            String pythonExec = (String) params.getOrDefault("pythonExecutable", defaultPythonExecutable);
            String workDir = (String) params.get("workingDirectory");
            long timeoutSeconds = resolveTimeout(node, params);

            // Parse the workflow to discover Argument nodes for input mapping
            XircuitsWorkflow workflow = objectMapper.readValue(node.getScript(), XircuitsWorkflow.class);
            List<XircuitsNode> argumentNodes = extractArgumentNodes(workflow);

            // Check for precompiled .py path
            String precompiledPath = (String) params.get("precompiled");
            if (precompiledPath != null) {
                compiledFile = Path.of(precompiledPath);
            } else {
                // Step 1: Write .xircuits file
                workflowFile = Files.createTempFile("kompile-xircuits-", ".xircuits");
                Files.writeString(workflowFile, node.getScript(), StandardCharsets.UTF_8);

                // Step 2: Compile .xircuits → .py
                compiledFile = Path.of(workflowFile.toString().replace(".xircuits", ".py"));
                compiledIsTemp = true;

                ExecutionResult compileResult = runCompile(executable, pythonExec,
                        workflowFile, compiledFile, workDir, params, timeoutSeconds,
                        node.getId(), context.getExecutionId(), startedAt);
                if (compileResult != null) {
                    return compileResult; // Compilation failed
                }
            }

            // Step 3: Run the compiled Python script with CLI arguments
            List<String> runCommand = new ArrayList<>();
            runCommand.add(pythonExec);
            runCommand.add(compiledFile.toAbsolutePath().toString());

            // Map upstream inputs to CLI arguments via Argument node names
            Map<String, String> argMapping = buildArgumentMapping(argumentNodes, inputs);
            for (Map.Entry<String, String> arg : argMapping.entrySet()) {
                runCommand.add("--" + arg.getKey());
                runCommand.add(arg.getValue());
            }

            // Additional CLI args from parameters
            @SuppressWarnings("unchecked")
            List<String> extraArgs = (List<String>) params.get("extraArgs");
            if (extraArgs != null) {
                runCommand.addAll(extraArgs);
            }

            ProcessBuilder pb = new ProcessBuilder(runCommand);
            pb.redirectErrorStream(false);
            if (workDir != null) {
                pb.directory(Path.of(workDir).toFile());
            }

            // Environment
            Map<String, String> env = pb.environment();
            env.put("KOMPILE_NODE_ID", node.getId());
            env.put("KOMPILE_EXECUTION_ID", context.getExecutionId());
            @SuppressWarnings("unchecked")
            Map<String, String> extraEnv = (Map<String, String>) params.get("environmentVars");
            if (extraEnv != null) {
                env.putAll(extraEnv);
            }

            log.info("Running Xircuits workflow on node '{}': {}", node.getName(), String.join(" ", runCommand));

            Process process = pb.start();
            ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
            Thread stdoutThread = streamReader(process.getInputStream(), stdoutBuffer);
            Thread stderrThread = streamReader(process.getErrorStream(), stderrBuffer);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            stdoutThread.join(5000);
            stderrThread.join(5000);

            String stdout = stdoutBuffer.toString(StandardCharsets.UTF_8);
            String stderr = stderrBuffer.toString(StandardCharsets.UTF_8);
            String consoleOutput = stdout + (stderr.isEmpty() ? "" : "\n--- stderr ---\n" + stderr);

            if (!finished) {
                process.destroyForcibly();
                Instant completedAt = Instant.now();
                return ExecutionResult.builder()
                        .nodeId(node.getId())
                        .executionId(context.getExecutionId())
                        .status(ExecutionStatus.TIMED_OUT)
                        .error("Xircuits workflow timed out after " + timeoutSeconds + "s")
                        .consoleOutput(consoleOutput)
                        .startedAt(startedAt)
                        .completedAt(completedAt)
                        .duration(Duration.between(startedAt, completedAt))
                        .build();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                Instant completedAt = Instant.now();
                return ExecutionResult.builder()
                        .nodeId(node.getId())
                        .executionId(context.getExecutionId())
                        .status(ExecutionStatus.FAILED)
                        .error("Xircuits workflow exited with code " + exitCode)
                        .stackTrace(stderr)
                        .consoleOutput(consoleOutput)
                        .startedAt(startedAt)
                        .completedAt(completedAt)
                        .duration(Duration.between(startedAt, completedAt))
                        .build();
            }

            // Parse outputs from stdout
            // Xircuits compiled scripts print output values with "output:\n" followed by pprint
            Map<String, Object> outputs = parseXircuitsStdout(stdout);
            outputs.put("_exitCode", exitCode);
            outputs.put("_consoleOutput", consoleOutput);

            Instant completedAt = Instant.now();
            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(context.getExecutionId())
                    .status(ExecutionStatus.COMPLETED)
                    .outputs(outputs)
                    .consoleOutput(consoleOutput)
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    "Xircuits execution interrupted", null);
        } catch (Exception e) {
            log.error("Unexpected error executing Xircuits workflow on node '{}'", node.getName(), e);
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    e.getMessage(), Arrays.toString(e.getStackTrace()));
        } finally {
            cleanupTempFile(workflowFile);
            if (compiledIsTemp) {
                cleanupTempFile(compiledFile);
            }
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.XIRCUITS);
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return "Xircuits workflow definition is empty";
        }
        try {
            XircuitsWorkflow workflow = objectMapper.readValue(node.getScript(), XircuitsWorkflow.class);
            if (workflow.getLayers() == null || workflow.getLayers().size() < 2) {
                return "Invalid Xircuits workflow: must have exactly 2 layers (links and nodes)";
            }
            return null;
        } catch (Exception e) {
            return "Invalid Xircuits workflow JSON: " + e.getMessage();
        }
    }

    /**
     * Run the Xircuits compile step: .xircuits → .py
     * Returns null on success, or an ExecutionResult on failure.
     */
    private ExecutionResult runCompile(String executable, String pythonExec,
                                       Path workflowFile, Path compiledFile,
                                       String workDir, Map<String, Object> params,
                                       long timeoutSeconds, String nodeId,
                                       String executionId, Instant startedAt) throws Exception {
        List<String> compileCmd = new ArrayList<>();
        if ("xircuits".equals(executable) || executable.endsWith("/xircuits")) {
            compileCmd.add(executable);
        } else {
            compileCmd.add(pythonExec);
            compileCmd.add("-m");
            compileCmd.add("xircuits");
        }
        compileCmd.add("compile");
        compileCmd.add(workflowFile.toAbsolutePath().toString());
        compileCmd.add(compiledFile.toAbsolutePath().toString());

        ProcessBuilder compilePb = new ProcessBuilder(compileCmd);
        compilePb.redirectErrorStream(false);
        if (workDir != null) {
            compilePb.directory(Path.of(workDir).toFile());
        }
        @SuppressWarnings("unchecked")
        Map<String, String> extraEnv = (Map<String, String>) params.get("environmentVars");
        if (extraEnv != null) {
            compilePb.environment().putAll(extraEnv);
        }

        log.info("Compiling Xircuits workflow: {}", String.join(" ", compileCmd));

        Process compileProcess = compilePb.start();
        ByteArrayOutputStream compileOut = new ByteArrayOutputStream();
        ByteArrayOutputStream compileErr = new ByteArrayOutputStream();
        Thread outT = streamReader(compileProcess.getInputStream(), compileOut);
        Thread errT = streamReader(compileProcess.getErrorStream(), compileErr);

        boolean compileFinished = compileProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        outT.join(5000);
        errT.join(5000);

        if (!compileFinished) {
            compileProcess.destroyForcibly();
            return ExecutionResult.builder()
                    .nodeId(nodeId).executionId(executionId)
                    .status(ExecutionStatus.TIMED_OUT)
                    .error("Xircuits compile timed out")
                    .consoleOutput(compileErr.toString(StandardCharsets.UTF_8))
                    .startedAt(startedAt).completedAt(Instant.now())
                    .duration(Duration.between(startedAt, Instant.now()))
                    .build();
        }

        if (compileProcess.exitValue() != 0) {
            String errMsg = compileErr.toString(StandardCharsets.UTF_8);
            return ExecutionResult.builder()
                    .nodeId(nodeId).executionId(executionId)
                    .status(ExecutionStatus.FAILED)
                    .error("Xircuits compile failed (exit " + compileProcess.exitValue() + ")")
                    .stackTrace(errMsg)
                    .consoleOutput(compileOut.toString(StandardCharsets.UTF_8) + "\n" + errMsg)
                    .startedAt(startedAt).completedAt(Instant.now())
                    .duration(Duration.between(startedAt, Instant.now()))
                    .build();
        }

        return null; // success
    }

    /**
     * Extract Argument nodes from the workflow.
     * These define the CLI arguments the compiled script accepts.
     */
    @SuppressWarnings("unchecked")
    private List<XircuitsNode> extractArgumentNodes(XircuitsWorkflow workflow) {
        List<XircuitsNode> argNodes = new ArrayList<>();
        if (workflow.getNodesLayer() == null) return argNodes;

        Map<String, Object> models = workflow.getNodesLayer().getModels();
        if (models == null) return argNodes;

        for (Object modelObj : models.values()) {
            try {
                XircuitsNode xNode = objectMapper.convertValue(modelObj, XircuitsNode.class);
                if (xNode.isArgument()) {
                    argNodes.add(xNode);
                }
            } catch (Exception e) {
                log.debug("Could not parse Xircuits node model: {}", e.getMessage());
            }
        }
        return argNodes;
    }

    /**
     * Build CLI argument mapping from upstream inputs to Xircuits Argument nodes.
     * Matches input keys to argument names (case-insensitive).
     */
    private Map<String, String> buildArgumentMapping(List<XircuitsNode> argumentNodes,
                                                      Map<String, Object> inputs) {
        Map<String, String> args = new LinkedHashMap<>();
        if (inputs == null || argumentNodes.isEmpty()) return args;

        // Build a lookup: argument name (lowercase) → actual argument name
        Map<String, String> argNameLookup = new HashMap<>();
        for (XircuitsNode argNode : argumentNodes) {
            String argName = argNode.getArgumentName();
            if (argName != null) {
                argNameLookup.put(argName.toLowerCase(), argName);
            }
        }

        // Match inputs to argument names
        for (Map.Entry<String, Object> input : inputs.entrySet()) {
            String key = input.getKey();
            // Skip internal keys
            if (key.startsWith("_")) continue;

            String matchedArg = argNameLookup.get(key.toLowerCase());
            if (matchedArg != null) {
                args.put(matchedArg, String.valueOf(input.getValue()));
            } else {
                // Pass through as-is — the compiled script uses ArgumentParser with parse_known_args
                args.put(key, String.valueOf(input.getValue()));
            }
        }

        return args;
    }

    /**
     * Parse Xircuits compiled script stdout.
     * The compiled script prints output values with "output:" prefix followed by pprint output.
     * Also looks for the "Finished Executing" marker.
     */
    private Map<String, Object> parseXircuitsStdout(String stdout) {
        Map<String, Object> outputs = new HashMap<>();
        if (stdout == null || stdout.isBlank()) return outputs;

        String[] lines = stdout.split("\n");
        StringBuilder outputSection = new StringBuilder();
        boolean inOutputSection = false;

        for (String line : lines) {
            if (line.strip().equals("output:")) {
                inOutputSection = true;
                continue;
            }
            if (line.strip().equals("Finished Executing")) {
                inOutputSection = false;
                continue;
            }
            if (inOutputSection) {
                outputSection.append(line).append("\n");
            }
        }

        // Try parsing the output section as JSON
        String outputStr = outputSection.toString().strip();
        if (!outputStr.isEmpty()) {
            try {
                if (outputStr.startsWith("{") || outputStr.startsWith("[")) {
                    // pprint output uses single quotes — try replacing for JSON parse
                    String jsonAttempt = outputStr.replace("'", "\"");
                    Map<String, Object> parsed = objectMapper.readValue(jsonAttempt,
                            new TypeReference<Map<String, Object>>() {});
                    outputs.putAll(parsed);
                } else {
                    outputs.put("_result", outputStr);
                }
            } catch (Exception e) {
                // pprint output isn't valid JSON — store as raw string
                outputs.put("_result", outputStr);
            }
        }

        // Always include full stdout
        outputs.put("_stdout", stdout.strip());

        return outputs;
    }

    private long resolveTimeout(ComputeNode node, Map<String, Object> params) {
        Object paramTimeout = params.get("timeoutSeconds");
        if (paramTimeout instanceof Number) {
            return ((Number) paramTimeout).longValue();
        }
        ExecutionLimits limits = node.getLimits() != null ? node.getLimits() : ExecutionLimits.defaults();
        if (limits.getMaxCpuTime() != null) {
            return limits.getMaxCpuTime().toSeconds();
        }
        return 300;
    }

    private Thread streamReader(InputStream is, ByteArrayOutputStream buffer) {
        Thread t = new Thread(() -> {
            try { is.transferTo(buffer); } catch (IOException e) { /* stream closed */ }
        }, "xircuits-stream-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void cleanupTempFile(Path file) {
        if (file != null) {
            try { Files.deleteIfExists(file); } catch (IOException e) {
                log.debug("Failed to clean up temp file: {}", file, e);
            }
        }
    }
}
