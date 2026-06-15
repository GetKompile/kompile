package ai.kompile.tool.camel;

import ai.kompile.compute.graph.camel.CamelContextManager;
import ai.kompile.compute.graph.camel.CamelNodeExecutor;
import ai.kompile.compute.graph.camel.CamelRouteParser;
import ai.kompile.compute.graph.camel.CamelRouteRegistry;
import ai.kompile.compute.graph.camel.CamelRouteRegistry.RouteDefinitionRecord;
import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for managing and executing Apache Camel routes.
 * Exposes persistent route CRUD, lifecycle management (deploy/undeploy/start/stop/reload),
 * and one-shot route execution to LLMs.
 * <p>
 * Routes are persisted to {@code ~/.kompile/camel-routes/} and survive restarts.
 * Enabled routes are automatically deployed to the shared CamelContext on startup.
 * Supports XML DSL, YAML DSL, and Camel Simple expressions — all native-image safe.
 */
@Component
@ConditionalOnBean(CamelNodeExecutor.class)
public class CamelRouteTool {

    private static final Logger log = LoggerFactory.getLogger(CamelRouteTool.class);

    private final CamelRouteRegistry routeRegistry;
    private final CamelNodeExecutor camelExecutor;
    private final CamelRouteParser routeParser;
    private final CamelContextManager contextManager;

    public CamelRouteTool(CamelRouteRegistry routeRegistry,
                          CamelNodeExecutor camelExecutor,
                          CamelRouteParser routeParser,
                          CamelContextManager contextManager) {
        this.routeRegistry = routeRegistry;
        this.camelExecutor = camelExecutor;
        this.routeParser = routeParser;
        this.contextManager = contextManager;
    }

    // ---- Input Records ----

    public record ListRoutesInput() {}

    public record GetRouteInput(String routeId) {}

    public record SaveRouteInput(
            String id,
            String name,
            String description,
            String script,
            boolean enabled
    ) {}

    public record DeleteRouteInput(String routeId) {}

    public record DeployRouteInput(String routeId) {}

    public record UndeployRouteInput(String routeId) {}

    public record StartRouteInput(String routeId) {}

    public record StopRouteInput(String routeId) {}

    public record ReloadRouteInput(String routeId) {}

    public record RouteStatusInput() {}

    public record ExecuteRouteInput(
            String routeId,
            String inlineScript,
            Map<String, Object> inputs
    ) {}

    public record ValidateRouteInput(String script) {}

    // ---- CRUD Tools ----

    @Tool(name = "camel_list_routes",
          description = "List all saved Apache Camel route definitions. Returns route IDs, names, formats, enabled/deployed status.")
    public Map<String, Object> listRoutes(ListRoutesInput input) {
        List<RouteDefinitionRecord> routes = routeRegistry.list();
        List<Map<String, Object>> routeSummaries = routes.stream()
                .map(r -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", r.getId());
                    summary.put("name", r.getName());
                    summary.put("description", r.getDescription());
                    summary.put("format", r.getFormat());
                    summary.put("enabled", r.isEnabled());
                    summary.put("deployed", contextManager.isRouteDeployed(r.getId()));
                    summary.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
                    return summary;
                })
                .collect(Collectors.toList());

        return Map.of("routes", routeSummaries, "count", routeSummaries.size());
    }

    @Tool(name = "camel_get_route",
          description = "Get the full definition of a saved Camel route by ID, including the route script and deployment status.")
    public Map<String, Object> getRoute(GetRouteInput input) {
        return routeRegistry.get(input.routeId())
                .map(r -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", r.getId());
                    result.put("name", r.getName());
                    result.put("description", r.getDescription());
                    result.put("script", r.getScript());
                    result.put("format", r.getFormat());
                    result.put("enabled", r.isEnabled());
                    result.put("deployed", contextManager.isRouteDeployed(r.getId()));
                    result.put("metadata", r.getMetadata());
                    result.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
                    result.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
                    return result;
                })
                .orElse(Map.of("error", "Route not found: " + input.routeId()));
    }

    @Tool(name = "camel_save_route",
          description = "Save or update a Camel route definition. The script can be XML DSL, YAML DSL, or a Camel Simple expression. " +
                  "Set enabled=true to auto-deploy the route to the live CamelContext. Returns the saved route with its ID.")
    public Map<String, Object> saveRoute(SaveRouteInput input) {
        try {
            CamelRouteParser.RouteFormat format = routeParser.detectFormat(input.script());

            RouteDefinitionRecord record = RouteDefinitionRecord.builder()
                    .id(input.id())
                    .name(input.name())
                    .description(input.description())
                    .script(input.script())
                    .format(format.name())
                    .enabled(input.enabled())
                    .build();

            RouteDefinitionRecord saved = routeRegistry.save(record);

            // Auto-deploy if enabled
            boolean deployed = false;
            if (saved.isEnabled()) {
                try {
                    if (contextManager.isRouteDeployed(saved.getId())) {
                        contextManager.reloadRoute(saved.getId(), saved.getScript());
                    } else {
                        contextManager.deployRoute(saved.getId(), saved.getScript());
                    }
                    deployed = true;
                } catch (Exception e) {
                    log.warn("Route saved but deploy failed: {}", e.getMessage());
                }
            } else if (contextManager.isRouteDeployed(saved.getId())) {
                contextManager.undeployRoute(saved.getId());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", saved.getId());
            result.put("name", saved.getName());
            result.put("format", saved.getFormat());
            result.put("enabled", saved.isEnabled());
            result.put("deployed", deployed);
            result.put("status", "saved");
            return result;

        } catch (Exception e) {
            log.error("Failed to save route", e);
            return Map.of("error", "Failed to save route: " + e.getMessage());
        }
    }

    @Tool(name = "camel_delete_route",
          description = "Delete a saved Camel route definition by ID. Also undeploys it if currently running.")
    public Map<String, Object> deleteRoute(DeleteRouteInput input) {
        contextManager.undeployRoute(input.routeId());
        boolean deleted = routeRegistry.delete(input.routeId());
        return Map.of("deleted", deleted, "routeId", input.routeId());
    }

    // ---- Lifecycle Tools ----

    @Tool(name = "camel_deploy_route",
          description = "Deploy a saved route to the live CamelContext. The route starts processing messages immediately.")
    public Map<String, Object> deployRoute(DeployRouteInput input) {
        return routeRegistry.get(input.routeId())
                .map(record -> {
                    try {
                        String camelRouteId = contextManager.deployRoute(record.getId(), record.getScript());
                        return Map.of("deployed", (Object) true, "routeId", record.getId(), "camelRouteId", camelRouteId);
                    } catch (Exception e) {
                        return Map.of("error", (Object) ("Deploy failed: " + e.getMessage()));
                    }
                })
                .orElse(Map.of("error", "Route not found: " + input.routeId()));
    }

    @Tool(name = "camel_undeploy_route",
          description = "Undeploy (stop and remove) a route from the live CamelContext. The route definition is kept in the registry.")
    public Map<String, Object> undeployRoute(UndeployRouteInput input) {
        boolean removed = contextManager.undeployRoute(input.routeId());
        return Map.of("undeployed", removed, "routeId", input.routeId());
    }

    @Tool(name = "camel_start_route",
          description = "Start a previously stopped route. The route must already be deployed.")
    public Map<String, Object> startRoute(StartRouteInput input) {
        try {
            boolean started = contextManager.startRoute(input.routeId());
            return Map.of("started", started, "routeId", input.routeId());
        } catch (Exception e) {
            return Map.of("error", "Start failed: " + e.getMessage());
        }
    }

    @Tool(name = "camel_stop_route",
          description = "Stop a running route without removing it. It can be restarted later.")
    public Map<String, Object> stopRoute(StopRouteInput input) {
        try {
            boolean stopped = contextManager.stopRoute(input.routeId());
            return Map.of("stopped", stopped, "routeId", input.routeId());
        } catch (Exception e) {
            return Map.of("error", "Stop failed: " + e.getMessage());
        }
    }

    @Tool(name = "camel_reload_route",
          description = "Reload a deployed route from its latest saved definition. Undeploys the old version and deploys the new one.")
    public Map<String, Object> reloadRoute(ReloadRouteInput input) {
        return routeRegistry.get(input.routeId())
                .map(record -> {
                    try {
                        String camelRouteId = contextManager.reloadRoute(record.getId(), record.getScript());
                        return Map.of("reloaded", (Object) true, "routeId", record.getId(), "camelRouteId", camelRouteId);
                    } catch (Exception e) {
                        return Map.of("error", (Object) ("Reload failed: " + e.getMessage()));
                    }
                })
                .orElse(Map.of("error", "Route not found: " + input.routeId()));
    }

    @Tool(name = "camel_route_status",
          description = "Get the status of all deployed Camel routes — shows running/stopped state, endpoint URIs, and uptime.")
    public Map<String, Object> routeStatus(RouteStatusInput input) {
        List<CamelContextManager.RouteStatus> statuses = contextManager.getDeployedRouteStatuses();
        List<Map<String, Object>> statusMaps = statuses.stream()
                .map(CamelContextManager.RouteStatus::toMap)
                .collect(Collectors.toList());
        return Map.of(
                "deployedRoutes", statusMaps,
                "deployedCount", statusMaps.size(),
                "registeredCount", routeRegistry.list().size());
    }

    // ---- Execution Tools ----

    @Tool(name = "camel_execute_route",
          description = "Execute a Camel route with the given inputs. Either provide a saved routeId OR an inlineScript. " +
                  "For saved routes, the route is executed on the shared context. For inline scripts, a temporary context is used. " +
                  "Inputs are passed as exchange headers and body. Returns the route execution result.")
    public Map<String, Object> executeRoute(ExecuteRouteInput input) {
        try {
            String script;
            String routeName;

            if (input.routeId() != null && !input.routeId().isBlank()) {
                RouteDefinitionRecord route = routeRegistry.get(input.routeId()).orElse(null);
                if (route == null) {
                    return Map.of("error", "Route not found: " + input.routeId());
                }
                script = route.getScript();
                routeName = route.getName();
            } else if (input.inlineScript() != null && !input.inlineScript().isBlank()) {
                script = input.inlineScript();
                routeName = "inline-execution";
            } else {
                return Map.of("error", "Either routeId or inlineScript must be provided");
            }

            ComputeNode node = ComputeNode.builder()
                    .id("tool-exec-" + UUID.randomUUID())
                    .name(routeName)
                    .executionType(NodeExecutionType.CAMEL_ROUTE)
                    .script(script)
                    .build();

            ComputeGraph graph = ComputeGraph.builder()
                    .id("tool-graph-" + UUID.randomUUID())
                    .name("Tool Execution")
                    .nodes(List.of(node))
                    .edges(List.of())
                    .build();

            Map<String, Object> inputs = input.inputs() != null ? input.inputs() : Map.of();
            ExecutionContext context = new ExecutionContext(
                    UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());

            ExecutionResult result = camelExecutor.execute(node, inputs, context);
            contextManager.destroyIsolatedContext(context.getExecutionId());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.getStatus().name());
            response.put("outputs", result.getOutputs());
            if (result.getError() != null) {
                response.put("error", result.getError());
            }
            if (result.getDuration() != null) {
                response.put("durationMs", result.getDuration().toMillis());
            }
            return response;

        } catch (Exception e) {
            log.error("Route execution failed", e);
            return Map.of("error", "Route execution failed: " + e.getMessage());
        }
    }

    @Tool(name = "camel_validate_route",
          description = "Validate a Camel route script without executing it. Detects the format (XML/YAML/Simple) and checks for syntax issues. " +
                  "Note: In GraalVM native image mode, only XML DSL, YAML DSL, and Simple expressions are supported. " +
                  "Groovy/JavaScript/Mvel expressions will NOT work in native mode.")
    public Map<String, Object> validateRoute(ValidateRouteInput input) {
        try {
            CamelRouteParser.RouteFormat format = routeParser.detectFormat(input.script());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("format", format.name());
            result.put("valid", true);
            result.put("scriptLength", input.script().length());
            result.put("nativeImageSafe", format != CamelRouteParser.RouteFormat.SIMPLE_EXPRESSION
                    || !input.script().contains("groovy(") && !input.script().contains("js("));

            switch (format) {
                case XML_DSL -> result.put("hint", "XML DSL route detected. Ensure <from> and <to> elements are present.");
                case YAML_DSL -> result.put("hint", "YAML DSL route detected. Ensure 'from' and steps are properly indented.");
                case SIMPLE_EXPRESSION -> result.put("hint", "Simple expression detected. Use ${header.x} or ${body} for dynamic values.");
            }

            return result;

        } catch (Exception e) {
            return Map.of("valid", false, "error", e.getMessage());
        }
    }
}
