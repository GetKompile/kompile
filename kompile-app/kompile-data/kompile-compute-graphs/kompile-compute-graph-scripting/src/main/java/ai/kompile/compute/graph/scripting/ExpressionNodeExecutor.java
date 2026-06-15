package ai.kompile.compute.graph.scripting;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight executor for simple expression evaluation using Spring Expression Language (SpEL).
 * Ideal for simple calculations, data transformations, and conditional logic
 * without the overhead of a full scripting engine.
 *
 * Script format: semicolon-separated expressions.
 * The last expression's value becomes _result.
 * Assign to variables to create outputs: #score = #input1 * 0.7 + #input2 * 0.3
 */
@Slf4j
public class ExpressionNodeExecutor implements NodeExecutor {

    private final ExpressionParser parser = new SpelExpressionParser();

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();

        try {
            StandardEvaluationContext evalContext = new StandardEvaluationContext();

            // Bind inputs as SpEL variables
            if (inputs != null) {
                for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                    evalContext.setVariable(entry.getKey(), entry.getValue());
                }
            }

            // Bind parameters
            if (node.getParameters() != null) {
                for (Map.Entry<String, Object> entry : node.getParameters().entrySet()) {
                    evalContext.setVariable("param_" + entry.getKey(), entry.getValue());
                }
            }

            // Bind global state
            evalContext.setVariable("global", context.getGlobalState());

            // Parse and evaluate expressions (semicolon-separated)
            String[] expressions = node.getScript().split(";");
            Object lastResult = null;
            Map<String, Object> outputs = new HashMap<>();

            for (String expr : expressions) {
                String trimmed = expr.trim();
                if (trimmed.isEmpty()) continue;

                // Check for assignment pattern: varName = expression
                int eqIndex = trimmed.indexOf('=');
                if (eqIndex > 0 && trimmed.charAt(eqIndex - 1) != '!' && trimmed.charAt(eqIndex - 1) != '<'
                        && trimmed.charAt(eqIndex - 1) != '>' && (eqIndex + 1 < trimmed.length() && trimmed.charAt(eqIndex + 1) != '=')) {
                    String varName = trimmed.substring(0, eqIndex).trim();
                    String valueExpr = trimmed.substring(eqIndex + 1).trim();

                    // Remove # prefix if present (SpEL variable syntax)
                    if (varName.startsWith("#")) {
                        varName = varName.substring(1);
                    }

                    lastResult = parser.parseExpression(valueExpr).getValue(evalContext);
                    evalContext.setVariable(varName, lastResult);
                    outputs.put(varName, lastResult);
                } else {
                    lastResult = parser.parseExpression(trimmed).getValue(evalContext);
                }
            }

            outputs.put("_result", lastResult);

            Instant completedAt = Instant.now();
            return ExecutionResult.builder()
                    .nodeId(node.getId())
                    .executionId(context.getExecutionId())
                    .status(ExecutionStatus.COMPLETED)
                    .outputs(outputs)
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .duration(Duration.between(startedAt, completedAt))
                    .build();

        } catch (Exception e) {
            log.warn("Expression evaluation failed on node '{}': {}", node.getName(), e.getMessage());
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    e.getMessage(), null);
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.EXPRESSION);
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return "Expression is empty";
        }
        try {
            String[] expressions = node.getScript().split(";");
            for (String expr : expressions) {
                String trimmed = expr.trim();
                if (trimmed.isEmpty()) continue;
                // Just parse to check syntax — don't evaluate
                if (trimmed.contains("=") && !trimmed.contains("==")) {
                    String valueExpr = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                    parser.parseExpression(valueExpr);
                } else {
                    parser.parseExpression(trimmed);
                }
            }
            return null;
        } catch (Exception e) {
            return "Expression parse error: " + e.getMessage();
        }
    }
}
