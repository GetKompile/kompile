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
import lombok.extern.slf4j.Slf4j;
import org.nd4j.python4j.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Executes Python scripts via Python4J (embedded CPython via JavaCPP).
 * This provides full CPython execution including native libraries (numpy, pandas, scipy, etc.)
 * as opposed to GraalPy which has limited library compatibility.
 *
 * <p>Each execution runs in an isolated Python context (via {@link PythonContextManager})
 * to prevent variable leakage between nodes. The GIL is properly managed for thread safety.
 */
@Slf4j
public class Python4jNodeExecutor implements NodeExecutor {

    private volatile boolean initialized = false;

    public Python4jNodeExecutor() {
    }

    private synchronized void ensureInitialized() {
        if (!initialized) {
            try {
                PythonExecutioner.init();
                initialized = true;
                log.info("Python4J executor initialized successfully");
            } catch (Exception e) {
                log.error("Failed to initialize Python4J: {}", e.getMessage(), e);
                throw new RuntimeException("Python4J initialization failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        ensureInitialized();
        Instant startedAt = Instant.now();
        ByteArrayOutputStream consoleBuffer = new ByteArrayOutputStream();

        String contextId = "ctx_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "_");

        try (PythonGIL gil = PythonGIL.lock()) {
            // Use an isolated context to prevent variable leakage
            PythonContextManager.addContext(contextId);
            PythonContextManager.setContext(contextId);

            try {
                // Set up input variables
                List<PythonVariable> inputVars = new ArrayList<>();
                if (inputs != null) {
                    for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                        PythonVariable<?> var = toPythonVariable(entry.getKey(), entry.getValue());
                        if (var != null) {
                            inputVars.add(var);
                        }
                    }
                }

                // Also bind parameters
                if (node.getParameters() != null) {
                    for (Map.Entry<String, Object> entry : node.getParameters().entrySet()) {
                        PythonVariable<?> var = toPythonVariable("$" + entry.getKey(), entry.getValue());
                        if (var != null) {
                            inputVars.add(var);
                        }
                    }
                }

                // Bind special variables
                inputVars.add(new PythonVariable<>("_nodeId", PythonTypes.STR, node.getId()));
                inputVars.add(new PythonVariable<>("_executionId", PythonTypes.STR, context.getExecutionId()));

                // Provide _inputs as a dict for convenience
                if (inputs != null) {
                    inputVars.add(new PythonVariable<>("_inputs", PythonTypes.DICT, new HashMap<>(inputs)));
                }

                // Capture stdout by wrapping the script
                String wrappedScript = buildWrappedScript(node.getScript());

                // Execute
                PythonExecutioner.setVariables(inputVars);
                PythonExecutioner.exec(wrappedScript);

                // Capture console output
                try {
                    PythonVariable<String> consoleVar = PythonExecutioner.getVariable("__console_output__", PythonTypes.STR);
                    if (consoleVar.getValue() != null) {
                        consoleBuffer.write(consoleVar.getValue().getBytes());
                    }
                } catch (Exception ignored) {
                    // console capture is best-effort
                }

                // Extract outputs
                Map<String, Object> outputs = extractOutputs(node);

                Instant completedAt = Instant.now();
                return ExecutionResult.builder()
                        .nodeId(node.getId())
                        .executionId(context.getExecutionId())
                        .status(ExecutionStatus.COMPLETED)
                        .outputs(outputs)
                        .consoleOutput(consoleBuffer.toString())
                        .startedAt(startedAt)
                        .completedAt(completedAt)
                        .duration(Duration.between(startedAt, completedAt))
                        .build();

            } finally {
                // Clean up the isolated context
                try {
                    PythonContextManager.setMainContext();
                    PythonContextManager.deleteContext(contextId);
                } catch (Exception e) {
                    log.debug("Failed to clean up Python context {}: {}", contextId, e.getMessage());
                }
            }

        } catch (PythonException e) {
            Instant completedAt = Instant.now();
            log.warn("Python4J execution failed on node '{}': {}", node.getName(), e.getMessage());
            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(context.getExecutionId())
                    .status(ExecutionStatus.FAILED)
                    .error(e.getMessage())
                    .consoleOutput(consoleBuffer.toString())
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error executing node '{}' via Python4J", node.getName(), e);
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
        // Python4J doesn't have a parse-only mode, so we do a basic syntax check
        ensureInitialized();
        try (PythonGIL gil = PythonGIL.lock()) {
            PythonExecutioner.exec("import ast; ast.parse(" + pythonStringLiteral(node.getScript()) + ")");
            return null;
        } catch (Exception e) {
            return "Python syntax error: " + e.getMessage();
        }
    }

    /**
     * Wraps user script to capture stdout.
     */
    private String buildWrappedScript(String userScript) {
        return "import sys as __sys__\n"
                + "import io as __io__\n"
                + "__old_stdout__ = __sys__.stdout\n"
                + "__sys__.stdout = __io__.StringIO()\n"
                + "try:\n"
                + indent(userScript)
                + "\nfinally:\n"
                + "    __console_output__ = __sys__.stdout.getvalue()\n"
                + "    __sys__.stdout = __old_stdout__\n";
    }

    private String indent(String code) {
        StringBuilder sb = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            sb.append("    ").append(line).append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private PythonVariable<?> toPythonVariable(String name, Object value) {
        if (value == null) return null;
        if (value instanceof String) return new PythonVariable<>(name, PythonTypes.STR, (String) value);
        if (value instanceof Integer) return new PythonVariable<>(name, PythonTypes.INT, ((Integer) value).longValue());
        if (value instanceof Long) return new PythonVariable<>(name, PythonTypes.INT, (Long) value);
        if (value instanceof Float) return new PythonVariable<>(name, PythonTypes.FLOAT, ((Float) value).doubleValue());
        if (value instanceof Double) return new PythonVariable<>(name, PythonTypes.FLOAT, (Double) value);
        if (value instanceof Boolean) return new PythonVariable<>(name, PythonTypes.BOOL, (Boolean) value);
        if (value instanceof List) return new PythonVariable<>(name, PythonTypes.LIST, (List<?>) value);
        if (value instanceof Map) return new PythonVariable<>(name, PythonTypes.DICT, (Map<?, ?>) value);
        // For other types, convert to string
        return new PythonVariable<>(name, PythonTypes.STR, value.toString());
    }

    private Map<String, Object> extractOutputs(ComputeNode node) {
        Map<String, Object> outputs = new HashMap<>();

        // If the node specifies output bindings, extract those specific variables
        if (node.getOutputBindings() != null && !node.getOutputBindings().isEmpty()) {
            for (Map.Entry<String, String> binding : node.getOutputBindings().entrySet()) {
                String varName = binding.getKey();
                String outputName = binding.getValue();
                try {
                    PythonVariable<?> var = getVariableAuto(varName);
                    if (var != null && var.getValue() != null) {
                        outputs.put(outputName, var.getValue());
                    }
                } catch (Exception e) {
                    log.debug("Could not extract output binding '{}': {}", varName, e.getMessage());
                }
            }
        }

        // Check for _output dict set by the script
        try {
            PythonVariable<Map> outputVar = PythonExecutioner.getVariable("_output", PythonTypes.DICT);
            if (outputVar.getValue() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> outputMap = outputVar.getValue();
                outputs.putAll(outputMap);
            }
        } catch (Exception ignored) {
            // _output not set — that's fine
        }

        // Check for _result variable
        try {
            PythonVariable<?> resultVar = getVariableAuto("_result");
            if (resultVar != null && resultVar.getValue() != null) {
                outputs.put("_result", resultVar.getValue());
            }
        } catch (Exception ignored) {
            // _result not set — that's fine
        }

        return outputs;
    }

    /**
     * Attempts to get a variable with automatic type detection.
     * Tries common types in order.
     */
    private PythonVariable<?> getVariableAuto(String name) {
        // Try each type in order of likelihood
        PythonType<?>[] types = {PythonTypes.STR, PythonTypes.INT, PythonTypes.FLOAT,
                PythonTypes.BOOL, PythonTypes.LIST, PythonTypes.DICT};
        for (PythonType<?> type : types) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                PythonVariable<?> var = PythonExecutioner.getVariable(name, (PythonType) type);
                if (var.getValue() != null) return var;
            } catch (Exception ignored) {
                // wrong type or doesn't exist — try next
            }
        }
        return null;
    }

    /**
     * Creates a Python string literal with proper escaping for use in ast.parse().
     */
    private String pythonStringLiteral(String s) {
        return "'''" + s.replace("\\", "\\\\").replace("'''", "\\'\\'\\'") + "'''";
    }
}
