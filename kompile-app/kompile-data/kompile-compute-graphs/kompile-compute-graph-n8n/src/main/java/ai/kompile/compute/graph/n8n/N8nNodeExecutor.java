package ai.kompile.compute.graph.n8n;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.n8n.model.N8nExecutionResult;
import ai.kompile.compute.graph.n8n.model.N8nWorkflow;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes n8n workflows on compute graph nodes.
 * n8n is a JavaScript/TypeScript-based workflow automation tool.
 * <p>
 * The node's {@code script} field contains the n8n workflow definition (JSON).
 * Execution follows the real n8n CLI pipeline:
 * <ol>
 *   <li>Write workflow JSON to a temp file, injecting inputs as pinData on the trigger node</li>
 *   <li>Import: {@code n8n import:workflow --input=workflow.json}</li>
 *   <li>Execute: {@code n8n execute --id=<id> --rawOutput}</li>
 *   <li>Parse the IRun JSON output to extract per-node results</li>
 * </ol>
 * <p>
 * Parameters:
 * <ul>
 *   <li>{@code n8nExecutable} — path to the n8n binary (default: "n8n")</li>
 *   <li>{@code npxExecutable} — path to npx for fallback execution (default: "npx")</li>
 *   <li>{@code workingDirectory} — working directory for the subprocess</li>
 *   <li>{@code timeoutSeconds} — max execution time (default: from ExecutionLimits)</li>
 *   <li>{@code environmentVars} — additional environment variables as a map</li>
 *   <li>{@code workflowId} — if set, skip import and execute an existing workflow by ID</li>
 * </ul>
 */
@Slf4j
public class N8nNodeExecutor implements NodeExecutor {

    private final ObjectMapper objectMapper;
    private final String defaultExecutable;
    private final String defaultNpxExecutable;

    public N8nNodeExecutor() {
        this("n8n", "npx");
    }

    public N8nNodeExecutor(String defaultExecutable, String defaultNpxExecutable) {
        this.objectMapper = JsonUtils.standardMapper();
        this.defaultExecutable = defaultExecutable;
        this.defaultNpxExecutable = defaultNpxExecutable;
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();
        Path workflowFile = null;

        try {
            Map<String, Object> params = node.getParameters() != null ? node.getParameters() : Map.of();
            String executable = (String) params.getOrDefault("n8nExecutable", defaultExecutable);
            String npxExec = (String) params.getOrDefault("npxExecutable", defaultNpxExecutable);
            String workDir = (String) params.get("workingDirectory");
            long timeoutSeconds = resolveTimeout(node, params);

            // Parse the workflow to understand its structure
            N8nWorkflow workflow = objectMapper.readValue(node.getScript(), N8nWorkflow.class);

            // Inject upstream inputs as pinData on the trigger/start node
            String enrichedJson = injectInputsAsPinData(workflow, inputs, node, context);

            // Check if we should use an existing workflow ID or import fresh
            String workflowId = (String) params.get("workflowId");
            StringBuilder allConsoleOutput = new StringBuilder();

            if (workflowId == null) {
                // Step 1: Write enriched workflow to temp file
                workflowFile = Files.createTempFile("kompile-n8n-", ".json");
                Files.writeString(workflowFile, enrichedJson, StandardCharsets.UTF_8);

                // Step 2: Import workflow into n8n database
                List<String> importCmd = buildN8nCommand(executable, npxExec);
                importCmd.add("import:workflow");
                importCmd.add("--input=" + workflowFile.toAbsolutePath());

                SubprocessResult importResult = runSubprocess(importCmd, workDir, params,
                        timeoutSeconds, node.getId(), context.getExecutionId());
                allConsoleOutput.append("=== Import ===\n").append(importResult.consoleOutput).append("\n");

                if (importResult.exitCode != 0) {
                    Instant completedAt = Instant.now();
                    return ExecutionResult.builder()
                            .nodeId(node.getId())
                            .executionId(context.getExecutionId())
                            .status(ExecutionStatus.FAILED)
                            .error("n8n import:workflow failed (exit " + importResult.exitCode + ")")
                            .stackTrace(importResult.stderr)
                            .consoleOutput(allConsoleOutput.toString())
                            .startedAt(startedAt)
                            .completedAt(completedAt)
                            .duration(Duration.between(startedAt, completedAt))
                            .build();
                }

                // Extract workflow ID from the workflow JSON (n8n uses the id field if present)
                // If no ID was in the JSON, we need to parse it from import output
                workflowId = workflow.getId();
                if (workflowId == null) {
                    workflowId = parseWorkflowIdFromOutput(importResult.stdout);
                }

                if (workflowId == null) {
                    return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                            "Could not determine workflow ID after import. "
                                    + "Set 'id' in the workflow JSON or use the 'workflowId' parameter.",
                            importResult.stdout);
                }
            }

            // Step 3: Execute the workflow
            List<String> executeCmd = buildN8nCommand(executable, npxExec);
            executeCmd.add("execute");
            executeCmd.add("--id=" + workflowId);
            executeCmd.add("--rawOutput");

            @SuppressWarnings("unchecked")
            List<String> extraArgs = (List<String>) params.get("extraArgs");
            if (extraArgs != null) {
                executeCmd.addAll(extraArgs);
            }

            SubprocessResult execResult = runSubprocess(executeCmd, workDir, params,
                    timeoutSeconds, node.getId(), context.getExecutionId());
            allConsoleOutput.append("=== Execute ===\n").append(execResult.consoleOutput);

            if (execResult.timedOut) {
                Instant completedAt = Instant.now();
                return ExecutionResult.builder()
                        .nodeId(node.getId())
                        .executionId(context.getExecutionId())
                        .status(ExecutionStatus.TIMED_OUT)
                        .error("n8n workflow timed out after " + timeoutSeconds + "s")
                        .consoleOutput(allConsoleOutput.toString())
                        .startedAt(startedAt)
                        .completedAt(completedAt)
                        .duration(Duration.between(startedAt, completedAt))
                        .build();
            }

            // Parse the IRun JSON output
            Map<String, Object> outputs = parseN8nOutput(execResult.stdout, execResult.exitCode);
            outputs.put("_exitCode", execResult.exitCode);
            outputs.put("_workflowId", workflowId);

            ExecutionStatus status = execResult.exitCode == 0
                    ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;

            Instant completedAt = Instant.now();
            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(context.getExecutionId())
                    .status(status)
                    .outputs(outputs)
                    .error(status == ExecutionStatus.FAILED
                            ? "n8n workflow exited with code " + execResult.exitCode : null)
                    .stackTrace(status == ExecutionStatus.FAILED ? execResult.stderr : null)
                    .consoleOutput(allConsoleOutput.toString())
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    "n8n execution interrupted", null);
        } catch (Exception e) {
            log.error("Unexpected error executing n8n workflow on node '{}'", node.getName(), e);
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    e.getMessage(), Arrays.toString(e.getStackTrace()));
        } finally {
            cleanupTempFile(workflowFile);
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.N8N);
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return "n8n workflow definition is empty";
        }
        try {
            N8nWorkflow workflow = objectMapper.readValue(node.getScript(), N8nWorkflow.class);
            if (workflow.getNodes() == null || workflow.getNodes().isEmpty()) {
                return "n8n workflow has no nodes";
            }
            if (workflow.getConnections() == null) {
                return "n8n workflow has no connections";
            }
            return null;
        } catch (Exception e) {
            return "Invalid n8n workflow JSON: " + e.getMessage();
        }
    }

    /**
     * Inject upstream inputs as pinData on the trigger/start node.
     * pinData is n8n's mechanism for providing test data to nodes,
     * which is used during CLI execution to provide initial input.
     */
    private String injectInputsAsPinData(N8nWorkflow workflow,
                                          Map<String, Object> inputs,
                                          ComputeNode node,
                                          ExecutionContext context) {
        try {
            if (inputs == null || inputs.isEmpty()) {
                return objectMapper.writeValueAsString(workflow);
            }

            // Find the start/trigger node
            var startNode = workflow.findStartNode();
            if (startNode == null) {
                return objectMapper.writeValueAsString(workflow);
            }

            // Build pinData entry: the trigger node gets the input data as its output
            Map<String, Object> pinDataEntry = new HashMap<>(inputs);
            if (node.getParameters() != null) {
                pinDataEntry.put("_params", node.getParameters());
            }
            pinDataEntry.put("_nodeId", node.getId());
            pinDataEntry.put("_executionId", context.getExecutionId());

            // pinData format: { "NodeName": [ { "json": { ...data... } } ] }
            Map<String, Object> pinData = new HashMap<>(workflow.getPinData());
            pinData.put(startNode.getName(), List.of(Map.of("json", pinDataEntry)));
            workflow.setPinData(pinData);

            return objectMapper.writeValueAsString(workflow);
        } catch (Exception e) {
            log.debug("Could not inject inputs into n8n workflow: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(workflow);
            } catch (Exception ex) {
                return node.getScript();
            }
        }
    }

    /**
     * Parse the IRun JSON output from n8n execute --rawOutput.
     * Extracts per-node runData and the last node's output as _result.
     */
    private Map<String, Object> parseN8nOutput(String stdout, int exitCode) {
        Map<String, Object> outputs = new HashMap<>();
        if (stdout == null || stdout.isBlank()) return outputs;

        // Strip the "Execution was successful:" header if present (when --rawOutput not supported)
        String jsonPart = stdout.strip();
        int jsonStart = jsonPart.indexOf('{');
        if (jsonStart > 0) {
            jsonPart = jsonPart.substring(jsonStart);
        }

        try {
            N8nExecutionResult iRun = objectMapper.readValue(jsonPart, N8nExecutionResult.class);

            outputs.put("_status", iRun.getStatus());
            outputs.put("_mode", iRun.getMode());
            outputs.put("_startedAt", iRun.getStartedAt());
            outputs.put("_stoppedAt", iRun.getStoppedAt());

            Map<String, Object> runData = iRun.getRunData();
            if (!runData.isEmpty()) {
                outputs.put("_runData", runData);
            }

            String lastNode = iRun.getLastNodeExecuted();
            if (lastNode != null) {
                outputs.put("_lastNodeExecuted", lastNode);
                // Extract the last node's output data as the primary _result
                Object lastNodeData = runData.get(lastNode);
                if (lastNodeData != null) {
                    outputs.put("_result", lastNodeData);
                }
            }

            Object error = iRun.getError();
            if (error != null) {
                outputs.put("_error", error);
            }

        } catch (Exception e) {
            log.debug("Could not parse n8n IRun output as JSON: {}", e.getMessage());
            outputs.put("_result", stdout.strip());
        }

        return outputs;
    }

    /**
     * Try to extract workflow ID from n8n import output.
     */
    private String parseWorkflowIdFromOutput(String output) {
        if (output == null) return null;
        // n8n may output the ID in various formats
        Pattern idPattern = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = idPattern.matcher(output);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Try numeric ID pattern
        Pattern numPattern = Pattern.compile("(?:id|ID)\\s*[=:]\\s*(\\d+)");
        Matcher numMatcher = numPattern.matcher(output);
        if (numMatcher.find()) {
            return numMatcher.group(1);
        }
        return null;
    }

    private List<String> buildN8nCommand(String executable, String npxExec) {
        List<String> command = new ArrayList<>();
        if ("n8n".equals(executable) || executable.endsWith("/n8n")) {
            command.add(executable);
        } else {
            command.add(npxExec);
            command.add("n8n");
        }
        return command;
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

    // ---- Subprocess helper ----

    private record SubprocessResult(int exitCode, String stdout, String stderr,
                                     String consoleOutput, boolean timedOut) {}

    private SubprocessResult runSubprocess(List<String> command, String workDir,
                                            Map<String, Object> params,
                                            long timeoutSeconds, String nodeId,
                                            String executionId) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        if (workDir != null) {
            pb.directory(Path.of(workDir).toFile());
        }

        Map<String, String> env = pb.environment();
        env.put("KOMPILE_NODE_ID", nodeId);
        env.put("KOMPILE_EXECUTION_ID", executionId);
        env.put("N8N_DIAGNOSTICS_ENABLED", "false");
        env.put("N8N_PERSONALIZATION_ENABLED", "false");
        @SuppressWarnings("unchecked")
        Map<String, String> extraEnv = (Map<String, String>) params.get("environmentVars");
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }

        log.info("Running: {}", String.join(" ", command));
        Process process = pb.start();

        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        Thread outT = streamReader(process.getInputStream(), stdoutBuf);
        Thread errT = streamReader(process.getErrorStream(), stderrBuf);

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        outT.join(5000);
        errT.join(5000);

        String stdout = stdoutBuf.toString(StandardCharsets.UTF_8);
        String stderr = stderrBuf.toString(StandardCharsets.UTF_8);

        if (!finished) {
            process.destroyForcibly();
            return new SubprocessResult(-1, stdout, stderr,
                    stdout + "\n--- stderr ---\n" + stderr, true);
        }

        String console = stdout + (stderr.isEmpty() ? "" : "\n--- stderr ---\n" + stderr);
        return new SubprocessResult(process.exitValue(), stdout, stderr, console, false);
    }

    private Thread streamReader(InputStream is, ByteArrayOutputStream buffer) {
        Thread t = new Thread(() -> {
            try { is.transferTo(buffer); } catch (IOException e) { /* stream closed */ }
        }, "n8n-stream-reader");
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
