package ai.kompile.compute.graph.camel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for managing Apache Camel routes.
 * Provides CRUD for route definitions (persisted to ~/.kompile/camel-routes/)
 * and lifecycle management (deploy/undeploy/start/stop/reload) for live routes
 * on the shared CamelContext.
 */
@Slf4j
@RestController
@RequestMapping("/api/camel")
@ConditionalOnClass(name = "org.apache.camel.CamelContext")
public class CamelRouteController {

    private final CamelRouteRegistry routeRegistry;
    private final CamelContextManager contextManager;
    private final CamelRouteParser routeParser;

    public CamelRouteController(CamelRouteRegistry routeRegistry,
                                 CamelContextManager contextManager,
                                 CamelRouteParser routeParser) {
        this.routeRegistry = routeRegistry;
        this.contextManager = contextManager;
        this.routeParser = routeParser;
    }

    // ---- Route Definition CRUD ----

    @GetMapping("/routes")
    public ResponseEntity<Map<String, Object>> listRoutes() {
        List<Map<String, Object>> routes = routeRegistry.list().stream()
                .map(this::toSummaryMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("routes", routes, "count", routes.size()));
    }

    @GetMapping("/routes/{id}")
    public ResponseEntity<Map<String, Object>> getRoute(@PathVariable String id) {
        return routeRegistry.get(id)
                .map(r -> ResponseEntity.ok(toDetailMap(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/routes")
    public ResponseEntity<Map<String, Object>> createRoute(@RequestBody RouteCreateRequest request) {
        try {
            CamelRouteParser.RouteFormat format = routeParser.detectFormat(request.script);

            CamelRouteRegistry.RouteDefinitionRecord record = CamelRouteRegistry.RouteDefinitionRecord.builder()
                    .name(request.name)
                    .description(request.description)
                    .script(request.script)
                    .format(format.name())
                    .enabled(request.enabled != null ? request.enabled : false)
                    .metadata(request.metadata)
                    .build();

            CamelRouteRegistry.RouteDefinitionRecord saved = routeRegistry.save(record);

            // Auto-deploy if enabled
            if (saved.isEnabled()) {
                try {
                    contextManager.deployRoute(saved.getId(), saved.getScript());
                } catch (Exception e) {
                    log.warn("Route saved but failed to deploy: {}", e.getMessage());
                }
            }

            Map<String, Object> result = toDetailMap(saved);
            result.put("deployed", saved.isEnabled() && contextManager.isRouteDeployed(saved.getId()));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/routes/{id}")
    public ResponseEntity<Map<String, Object>> updateRoute(@PathVariable String id,
                                                            @RequestBody RouteCreateRequest request) {
        Optional<CamelRouteRegistry.RouteDefinitionRecord> existing = routeRegistry.get(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            CamelRouteParser.RouteFormat format = routeParser.detectFormat(request.script);

            CamelRouteRegistry.RouteDefinitionRecord record = CamelRouteRegistry.RouteDefinitionRecord.builder()
                    .id(id)
                    .name(request.name)
                    .description(request.description)
                    .script(request.script)
                    .format(format.name())
                    .enabled(request.enabled != null ? request.enabled : existing.get().isEnabled())
                    .metadata(request.metadata)
                    .createdAt(existing.get().getCreatedAt())
                    .build();

            CamelRouteRegistry.RouteDefinitionRecord saved = routeRegistry.save(record);

            // Reload if currently deployed
            if (contextManager.isRouteDeployed(id)) {
                contextManager.reloadRoute(id, saved.getScript());
            } else if (saved.isEnabled()) {
                contextManager.deployRoute(id, saved.getScript());
            }

            Map<String, Object> result = toDetailMap(saved);
            result.put("deployed", contextManager.isRouteDeployed(id));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/routes/{id}")
    public ResponseEntity<Map<String, Object>> deleteRoute(@PathVariable String id) {
        contextManager.undeployRoute(id);
        boolean deleted = routeRegistry.delete(id);
        return ResponseEntity.ok(Map.of("deleted", deleted, "routeId", id));
    }

    // ---- Route Lifecycle ----

    @PostMapping("/routes/{id}/deploy")
    public ResponseEntity<Map<String, Object>> deployRoute(@PathVariable String id) {
        return routeRegistry.get(id)
                .map(record -> {
                    try {
                        String camelRouteId = contextManager.deployRoute(id, record.getScript());
                        return ResponseEntity.ok(Map.of(
                                "deployed", true,
                                "routeId", id,
                                "camelRouteId", (Object) camelRouteId));
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", (Object) ("Deploy failed: " + e.getMessage())));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/routes/{id}/undeploy")
    public ResponseEntity<Map<String, Object>> undeployRoute(@PathVariable String id) {
        boolean removed = contextManager.undeployRoute(id);
        return ResponseEntity.ok(Map.of("undeployed", removed, "routeId", id));
    }

    @PostMapping("/routes/{id}/start")
    public ResponseEntity<Map<String, Object>> startRoute(@PathVariable String id) {
        try {
            boolean started = contextManager.startRoute(id);
            return ResponseEntity.ok(Map.of("started", started, "routeId", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/routes/{id}/stop")
    public ResponseEntity<Map<String, Object>> stopRoute(@PathVariable String id) {
        try {
            boolean stopped = contextManager.stopRoute(id);
            return ResponseEntity.ok(Map.of("stopped", stopped, "routeId", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/routes/{id}/reload")
    public ResponseEntity<Map<String, Object>> reloadRoute(@PathVariable String id) {
        return routeRegistry.get(id)
                .map(record -> {
                    try {
                        String camelRouteId = contextManager.reloadRoute(id, record.getScript());
                        return ResponseEntity.ok(Map.of(
                                "reloaded", true,
                                "routeId", id,
                                "camelRouteId", (Object) camelRouteId));
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", (Object) ("Reload failed: " + e.getMessage())));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/routes/{id}/status")
    public ResponseEntity<Map<String, Object>> getRouteStatus(@PathVariable String id) {
        List<CamelContextManager.RouteStatus> statuses = contextManager.getDeployedRouteStatuses();
        Optional<CamelContextManager.RouteStatus> found = statuses.stream()
                .filter(s -> s.registryId.equals(id))
                .findFirst();

        if (found.isPresent()) {
            return ResponseEntity.ok(found.get().toMap());
        }

        return routeRegistry.get(id)
                .map(record -> ResponseEntity.ok(Map.of(
                        "registryId", id,
                        "status", (Object) "NOT_DEPLOYED",
                        "enabled", record.isEnabled())))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getOverallStatus() {
        List<CamelContextManager.RouteStatus> statuses = contextManager.getDeployedRouteStatuses();
        List<Map<String, Object>> statusMaps = statuses.stream()
                .map(CamelContextManager.RouteStatus::toMap)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deployedRoutes", statusMaps);
        result.put("deployedCount", statusMaps.size());
        result.put("registeredCount", routeRegistry.list().size());
        result.put("camelVersion", org.apache.camel.CamelContext.class.getPackage().getImplementationVersion());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateScript(@RequestBody Map<String, String> body) {
        String script = body.get("script");
        if (script == null || script.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "script is required"));
        }
        try {
            CamelRouteParser.RouteFormat format = routeParser.detectFormat(script);
            return ResponseEntity.ok(Map.of("valid", true, "format", format.name()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("valid", false, "error", e.getMessage()));
        }
    }

    // ---- Request/Response DTOs ----

    public static class RouteCreateRequest {
        public String name;
        public String description;
        public String script;
        public Boolean enabled;
        public Map<String, String> metadata;
    }

    private Map<String, Object> toSummaryMap(CamelRouteRegistry.RouteDefinitionRecord r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("name", r.getName());
        map.put("description", r.getDescription());
        map.put("format", r.getFormat());
        map.put("enabled", r.isEnabled());
        map.put("deployed", contextManager.isRouteDeployed(r.getId()));
        map.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> toDetailMap(CamelRouteRegistry.RouteDefinitionRecord r) {
        Map<String, Object> map = toSummaryMap(r);
        map.put("script", r.getScript());
        map.put("metadata", r.getMetadata());
        map.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return map;
    }
}
