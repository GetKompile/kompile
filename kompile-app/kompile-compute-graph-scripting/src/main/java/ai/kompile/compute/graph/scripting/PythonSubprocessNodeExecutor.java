/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.compute.graph.scripting;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Fallback Python executor that runs scripts via a system {@code python3} subprocess.
 * Used when Python4J native libraries are not available (unsupported platform, missing
 * native binaries, etc.).
 *
 * <p>Each execution spawns a short-lived {@code python3 -c} process. Inputs are passed
 * as a JSON dict on stdin, and outputs are collected from a JSON dict written to stdout.
 * The user script is wrapped in a harness that deserialises inputs into local variables,
 * executes the script, then serialises {@code _output} (dict) or individual variables
 * back as JSON.
 *
 * <p>This executor is intentionally simple — it does not pool processes or maintain a
 * persistent interpreter. It is a correctness-first fallback, not a performance path.
 */
@Slf4j
public class PythonSubprocessNodeExecutor implements NodeExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final String pythonExecutable;

    public PythonSubprocessNodeExecutor() {
        this(findPythonExecutable());
    }

    public PythonSubprocessNodeExecutor(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();

        try {
            String wrappedScript = buildWrapper(node.getScript(), inputs, node.getOutputBindings());
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-c", wrappedScript);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(false);

            Process proc = pb.start();

            // Read stdout and stderr in parallel to avoid blocking
            String stdout;
            String stderr;
            try (InputStream stdoutStream = proc.getInputStream();
                 InputStream stderrStream = proc.getErrorStream()) {
                stdout = new String(stdoutStream.readAllBytes(), StandardCharsets.UTF_8);
                stderr = new String(stderrStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            boolean finished = proc.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                        "Python subprocess timed out after " + DEFAULT_TIMEOUT_SECONDS + "s", null);
            }

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                String error = stderr.isBlank() ? "Python process exited with code " + exitCode : stderr.trim();
                log.warn("Python subprocess failed on node '{}': {}", node.getName(), error);
                return ExecutionResult.builder()
                        .nodeId(node.getId())
                        .executionId(context.getExecutionId())
                        .status(ExecutionStatus.FAILED)
                        .error(error)
                        .consoleOutput(stderr)
                        .startedAt(startedAt)
                        .completedAt(Instant.now())
                        .duration(Duration.between(startedAt, Instant.now()))
                        .build();
            }

            // Parse JSON output from stdout
            Map<String, Object> outputs = parseOutputs(stdout);

            Instant completedAt = Instant.now();
            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(context.getExecutionId())
                    .status(ExecutionStatus.COMPLETED)
                    .outputs(outputs)
                    .consoleOutput(stderr) // stderr has any print() output from the wrapper
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();

        } catch (Exception e) {
            log.error("Unexpected error executing node '{}' via Python subprocess", node.getName(), e);
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    e.getMessage(), Arrays.toString(e.getStackTrace()));
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.PYTHON);
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return "Script is empty";
        }
        return null;
    }

    /**
     * Builds a self-contained Python script that:
     * 1. Deserialises input variables from an inline JSON literal
     * 2. Executes the user script
     * 3. Collects outputs (_output dict, output bindings, or _result)
     * 4. Prints the outputs as a JSON dict to stdout
     */
    private String buildWrapper(String userScript, Map<String, Object> inputs,
                                Map<String, String> outputBindings) throws Exception {
        String inputsJson = MAPPER.writeValueAsString(inputs != null ? inputs : Map.of());
        // Escape for embedding inside a Python triple-quoted string
        String escapedInputs = inputsJson.replace("\\", "\\\\").replace("'''", "\\'\\'\\'");

        StringBuilder sb = new StringBuilder();
        sb.append("import json, sys\n");
        sb.append("_inputs = json.loads('''").append(escapedInputs).append("''')\n");
        // Inject each input as a local variable
        sb.append("for _k, _v in _inputs.items(): globals()[_k] = _v\n");
        sb.append("_output = None\n");
        sb.append("_result = None\n");
        // Execute user script
        sb.append(userScript).append("\n");
        // Collect outputs
        sb.append("_out = {}\n");
        sb.append("if isinstance(_output, dict): _out.update(_output)\n");
        sb.append("if _result is not None: _out['_result'] = _result\n");
        if (outputBindings != null && !outputBindings.isEmpty()) {
            for (Map.Entry<String, String> binding : outputBindings.entrySet()) {
                String varName = binding.getKey();
                String outputName = binding.getValue();
                sb.append("if '").append(varName).append("' in dir(): _out['")
                        .append(outputName).append("'] = ").append(varName).append("\n");
            }
        }
        sb.append("print(json.dumps(_out))\n");
        return sb.toString();
    }

    private Map<String, Object> parseOutputs(String stdout) {
        String trimmed = stdout.trim();
        if (trimmed.isEmpty()) {
            return Map.of();
        }
        // Take only the last line (user print() statements go to stderr in the wrapper,
        // but just in case, we parse the last line as JSON)
        String[] lines = trimmed.split("\n");
        String lastLine = lines[lines.length - 1].trim();
        try {
            return MAPPER.readValue(lastLine, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse Python subprocess output as JSON: {}", lastLine);
            return Map.of("_result", trimmed);
        }
    }

    /**
     * Detects an available Python 3 executable on the system PATH.
     */
    private static String findPythonExecutable() {
        for (String candidate : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
                        && output.contains("Python 3")) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // not found, try next
            }
        }
        return "python3"; // default, will fail at execution time with a clear error
    }

    /**
     * Returns true if a usable Python 3 interpreter is available on this system.
     */
    public static boolean isPythonAvailable() {
        String exe = findPythonExecutable();
        try {
            Process p = new ProcessBuilder(exe, "-c", "print('ok')")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && out.trim().equals("ok");
        } catch (Exception e) {
            return false;
        }
    }
}
