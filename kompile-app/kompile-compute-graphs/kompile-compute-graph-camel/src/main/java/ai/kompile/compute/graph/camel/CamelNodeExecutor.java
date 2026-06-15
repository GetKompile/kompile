package ai.kompile.compute.graph.camel;

import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Executes Apache Camel routes as compute graph nodes.
 * <p>
 * The node's {@code script} field contains a Camel route definition (XML DSL, YAML DSL,
 * or a Camel Simple expression). Inputs from upstream nodes are set as exchange headers,
 * and the first input value (or the map of all inputs) becomes the exchange body.
 * <p>
 * After route execution, the resulting exchange body and any output-bound headers
 * are collected as the node's outputs.
 * <p>
 * Supports Enterprise Integration Patterns:
 * <ul>
 *   <li>Content-Based Router — route messages based on input data</li>
 *   <li>Message Transformer — transform data formats between nodes</li>
 *   <li>Splitter/Aggregator — split and recombine message payloads</li>
 *   <li>Recipient List — dynamic routing to multiple endpoints</li>
 *   <li>Wire Tap — audit/log without disrupting flow</li>
 * </ul>
 */
@Slf4j
public class CamelNodeExecutor implements NodeExecutor {

    private final CamelContextManager contextManager;
    private final CamelRouteParser routeParser;
    private final long timeoutMs;

    public CamelNodeExecutor(CamelContextManager contextManager, CamelRouteParser routeParser, long timeoutMs) {
        this.contextManager = contextManager;
        this.routeParser = routeParser;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Instant startedAt = Instant.now();

        try {
            // Use an isolated context per execution to avoid route name collisions
            CamelContext camelContext = contextManager.getOrCreateIsolatedContext(context.getExecutionId());
            String routeId = routeParser.parseAndAddRoute(camelContext, node.getScript(), node.getId());

            try (ProducerTemplate producer = camelContext.createProducerTemplate()) {
                // Determine the endpoint to send to
                String endpoint = resolveEndpoint(node, routeId);

                // Build the exchange body from inputs
                Object body = buildBody(inputs, node);

                // Send and receive
                Exchange result = producer.request(endpoint, exchange -> {
                    exchange.getIn().setBody(body);
                    // Set all inputs as headers
                    for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                        exchange.getIn().setHeader(entry.getKey(), entry.getValue());
                    }
                    // Set node parameters as headers with a prefix
                    if (node.getParameters() != null) {
                        for (Map.Entry<String, Object> entry : node.getParameters().entrySet()) {
                            exchange.getIn().setHeader("param_" + entry.getKey(), entry.getValue());
                        }
                    }
                    // Set execution context info
                    exchange.getIn().setHeader("kompile_executionId", context.getExecutionId());
                    exchange.getIn().setHeader("kompile_nodeId", node.getId());
                    exchange.getIn().setHeader("kompile_nodeName", node.getName());
                });

                // Check for exchange exceptions
                if (result.getException() != null) {
                    throw result.getException();
                }

                // Collect outputs
                Map<String, Object> outputs = collectOutputs(result, node);

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

            } finally {
                // Clean up the route after execution
                routeParser.removeRoute(camelContext, routeId);
            }

        } catch (Exception e) {
            log.error("Camel route execution failed on node '{}'", node.getName(), e);
            return ExecutionResult.failure(node.getId(), context.getExecutionId(),
                    e.getMessage(), Arrays.toString(e.getStackTrace()));
        }
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.CAMEL_ROUTE);
    }

    @Override
    public String validate(ComputeNode node) {
        if (node.getScript() == null || node.getScript().isBlank()) {
            return "Camel route definition is empty";
        }
        try {
            routeParser.detectFormat(node.getScript());
            return null;
        } catch (Exception e) {
            return "Invalid Camel route definition: " + e.getMessage();
        }
    }

    /**
     * Resolve the Camel endpoint URI for sending messages to the route.
     * If the node parameters specify a custom endpoint, use that.
     * Otherwise use the direct: component with the route ID.
     */
    private String resolveEndpoint(ComputeNode node, String routeId) {
        if (node.getParameters() != null && node.getParameters().containsKey("endpoint")) {
            return String.valueOf(node.getParameters().get("endpoint"));
        }
        return "direct:" + routeId;
    }

    /**
     * Build the exchange body from node inputs.
     * If there's a single input, use its value directly.
     * If there's an input named "body", use that.
     * Otherwise, send all inputs as a Map.
     */
    private Object buildBody(Map<String, Object> inputs, ComputeNode node) {
        if (inputs.containsKey("body")) {
            return inputs.get("body");
        }
        if (inputs.size() == 1) {
            return inputs.values().iterator().next();
        }
        return new HashMap<>(inputs);
    }

    /**
     * Collect outputs from the completed exchange.
     * The exchange body becomes the "result" output.
     * Output-bound headers (matching node outputBindings) become named outputs.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> collectOutputs(Exchange exchange, ComputeNode node) {
        Map<String, Object> outputs = new HashMap<>();

        // Primary result is the exchange body
        Object body = exchange.getMessage().getBody();
        outputs.put("result", body);

        // If the body is a Map, also spread its entries as individual outputs
        if (body instanceof Map) {
            Map<String, Object> bodyMap = (Map<String, Object>) body;
            outputs.putAll(bodyMap);
        }

        // Collect headers that match output bindings
        if (node.getOutputBindings() != null && !node.getOutputBindings().isEmpty()) {
            for (String outputName : node.getOutputBindings().keySet()) {
                Object headerValue = exchange.getMessage().getHeader(outputName);
                if (headerValue != null) {
                    outputs.put(outputName, headerValue);
                }
            }
        }

        // Collect any kompile_output_* headers as outputs
        for (Map.Entry<String, Object> header : exchange.getMessage().getHeaders().entrySet()) {
            if (header.getKey().startsWith("kompile_output_")) {
                String outputName = header.getKey().substring("kompile_output_".length());
                outputs.put(outputName, header.getValue());
            }
        }

        return outputs;
    }
}
