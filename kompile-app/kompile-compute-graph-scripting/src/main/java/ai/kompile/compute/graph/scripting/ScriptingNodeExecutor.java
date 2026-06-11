package ai.kompile.compute.graph.scripting;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyHashMap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Executes JavaScript and Python scripts on compute graph nodes using GraalVM polyglot.
 * Each node execution gets its own isolated polyglot context with configurable resource limits.
 */
@Slf4j
public class ScriptingNodeExecutor implements NodeExecutor {

    private final Engine sharedEngine;

    public ScriptingNodeExecutor() {
        this.sharedEngine = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    public ScriptingNodeExecutor(Engine engine) {
        this.sharedEngine = engine;
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();
        String languageId = getLanguageId(node.getExecutionType());
        ByteArrayOutputStream consoleBuffer = new ByteArrayOutputStream();

        try (Context polyglotContext = buildContext(node, languageId, consoleBuffer)) {
            // Bind inputs into the scripting context
            Value bindings = polyglotContext.getBindings(languageId);
            bindInputs(bindings, inputs, languageId);
            bindParameters(bindings, node.getParameters(), languageId);

            // Bind global state
            bindings.putMember("_global", context.getGlobalState());
            bindings.putMember("_nodeId", node.getId());
            bindings.putMember("_executionId", context.getExecutionId());

            // Execute the script
            Value result = polyglotContext.eval(languageId, node.getScript());

            // Extract outputs
            Map<String, Object> outputs = extractOutputs(bindings, result, node, languageId);

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

        } catch (org.graalvm.polyglot.PolyglotException e) {
            Instant completedAt = Instant.now();
            String error = e.isGuestException() ? e.getMessage() : e.toString();
            log.warn("Script execution failed on node '{}': {}", node.getName(), error);

            ExecutionStatus status = e.isCancelled() ? ExecutionStatus.TIMED_OUT : ExecutionStatus.FAILED;
            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(context.getExecutionId())
                    .status(status)
                    .error(error)
                    .stackTrace(e.isGuestException() ? getGuestStackTrace(e) : null)
                    .consoleOutput(consoleBuffer.toString())
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error executing node '{}'", node.getName(), e);
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    e.getMessage(), Arrays.toString(e.getStackTrace()));
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.JAVASCRIPT);
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return "Script is empty";
        }
        String langId = getLanguageId(node.getExecutionType());
        try (Context ctx = Context.newBuilder(langId)
                .engine(sharedEngine)
                .build()) {
            ctx.parse(langId, node.getScript());
            return null;
        } catch (Exception e) {
            return "Script parse error: " + e.getMessage();
        }
    }

    private Context buildContext(ComputeNode node, String languageId, ByteArrayOutputStream consoleBuffer) {
        ExecutionLimits limits = node.getLimits() != null ? node.getLimits() : ExecutionLimits.defaults();
        PrintStream consolePrint = new PrintStream(consoleBuffer);

        // Try building with sandbox limits first; fall back to no sandbox if unsupported
        try {
            Context.Builder builder = Context.newBuilder(languageId)
                    .engine(sharedEngine)
                    .out(consolePrint)
                    .err(consolePrint)
                    .allowExperimentalOptions(true);

            if (limits.getMaxCpuTime() != null) {
                builder.option("sandbox.MaxCPUTime", limits.getMaxCpuTime().toMillis() + "ms");
            }
            if (limits.getMaxHeapMemoryBytes() > 0) {
                builder.option("sandbox.MaxHeapMemory", formatBytes(limits.getMaxHeapMemoryBytes()));
            }
            if (limits.getMaxStackFrames() > 0) {
                builder.option("sandbox.MaxStackFrames", String.valueOf(limits.getMaxStackFrames()));
            }

            applySecuritySettings(builder, limits);
            return builder.build();
        } catch (Exception e) {
            log.debug("Sandbox options not supported on this runtime, falling back to unsandboxed: {}", e.getMessage());
        }

        // Fallback: no sandbox limits
        Context.Builder builder = Context.newBuilder(languageId)
                .engine(sharedEngine)
                .out(consolePrint)
                .err(consolePrint);
        applySecuritySettings(builder, limits);
        return builder.build();
    }

    private void applySecuritySettings(Context.Builder builder, ExecutionLimits limits) {
        if (limits.isAllowIO()) {
            builder.allowIO(true);
        }
        if (limits.isAllowHostAccess()) {
            builder.allowHostAccess(HostAccess.ALL);
            builder.allowHostClassLookup(className -> true);
        } else {
            builder.allowHostAccess(HostAccess.EXPLICIT);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024 && bytes % (1024L * 1024 * 1024) == 0) {
            return (bytes / (1024L * 1024 * 1024)) + "GB";
        } else if (bytes >= 1024L * 1024 && bytes % (1024L * 1024) == 0) {
            return (bytes / (1024L * 1024)) + "MB";
        } else if (bytes >= 1024 && bytes % 1024 == 0) {
            return (bytes / 1024) + "KB";
        }
        return bytes + "B";
    }

    private void bindInputs(Value bindings, Map<String, Object> inputs, String languageId) {
        if (inputs == null) return;
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            bindings.putMember(entry.getKey(), toPolyglotValue(entry.getValue()));
        }
        // Also provide inputs as a single object for convenience
        bindings.putMember("_inputs", inputs);
    }

    /**
     * Converts Java collections to GraalVM polyglot-friendly types so that
     * JavaScript can access them with native array/object semantics (.length,
     * indexing, for-of, etc.) rather than Java host-object semantics (.size()).
     */
    @SuppressWarnings("unchecked")
    private Object toPolyglotValue(Object value) {
        if (value instanceof List<?> list) {
            Object[] converted = new Object[list.size()];
            for (int i = 0; i < list.size(); i++) {
                converted[i] = toPolyglotValue(list.get(i));
            }
            return ProxyArray.fromArray(converted);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> converted = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                converted.put(entry.getKey(), toPolyglotValue(entry.getValue()));
            }
            return ProxyHashMap.from(converted);
        }
        return value;
    }

    private void bindParameters(Value bindings, Map<String, Object> parameters, String languageId) {
        if (parameters == null) return;
        bindings.putMember("_params", parameters);
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            // Parameters are prefixed with $ to distinguish from inputs
            bindings.putMember("$" + entry.getKey(), entry.getValue());
        }
    }

    private Map<String, Object> extractOutputs(Value bindings, Value result, ComputeNode node, String languageId) {
        Map<String, Object> outputs = new HashMap<>();

        // If the node specifies output bindings, extract those specific variables
        if (node.getOutputBindings() != null && !node.getOutputBindings().isEmpty()) {
            for (Map.Entry<String, String> binding : node.getOutputBindings().entrySet()) {
                String varName = binding.getKey();
                String outputName = binding.getValue();
                Value val = bindings.getMember(varName);
                if (val != null && !val.isNull()) {
                    outputs.put(outputName, convertValue(val));
                }
            }
        }

        // Always include the script's return value as "_result"
        if (result != null && !result.isNull()) {
            Object converted = convertValue(result);
            outputs.put("_result", converted);
            // If result is a map-like object, spread its entries into outputs
            if (result.hasMembers()) {
                for (String key : result.getMemberKeys()) {
                    Value member = result.getMember(key);
                    if (member != null && !member.isNull()) {
                        outputs.putIfAbsent(key, convertValue(member));
                    }
                }
            }
        }

        // Check for _output variable set by the script
        Value outputVar = bindings.getMember("_output");
        if (outputVar != null && !outputVar.isNull() && outputVar.hasMembers()) {
            for (String key : outputVar.getMemberKeys()) {
                Value member = outputVar.getMember(key);
                if (member != null && !member.isNull()) {
                    outputs.put(key, convertValue(member));
                }
            }
        }

        return outputs;
    }

    private Object convertValue(Value value) {
        if (value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) {
            if (value.fitsInInt()) return value.asInt();
            if (value.fitsInLong()) return value.asLong();
            return value.asDouble();
        }
        if (value.isString()) return value.asString();
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(convertValue(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers()) {
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertValue(value.getMember(key)));
            }
            return map;
        }
        // Fallback: try to get as string
        return value.toString();
    }

    private String getLanguageId(NodeExecutionType type) {
        return switch (type) {
            case JAVASCRIPT -> "js";
            case PYTHON -> "python";
            default -> throw new IllegalArgumentException("Unsupported scripting type: " + type);
        };
    }

    private String getGuestStackTrace(org.graalvm.polyglot.PolyglotException e) {
        StringBuilder sb = new StringBuilder();
        for (org.graalvm.polyglot.PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame()) {
                sb.append("  at ").append(frame).append("\n");
            }
        }
        return sb.toString();
    }
}
