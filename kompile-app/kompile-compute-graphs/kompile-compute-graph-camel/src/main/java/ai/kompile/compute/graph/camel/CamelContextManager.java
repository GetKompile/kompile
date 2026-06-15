package ai.kompile.compute.graph.camel;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.Resource;
import org.apache.camel.spi.RoutesLoader;
import org.apache.camel.support.ResourceHelper;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages CamelContext lifecycle with support for persistent, long-lived routes
 * alongside per-execution isolated contexts.
 * <p>
 * <b>Architecture for native-image compatibility:</b>
 * <ul>
 *   <li>All route loading uses stream-backed {@link Resource} objects via Camel's
 *       {@link RoutesLoader} — no classpath scanning, no {@code classpath:} URIs.</li>
 *   <li>Only XML DSL ({@code camel-xml-io-dsl}) and YAML DSL ({@code camel-yaml-dsl})
 *       are used as route definition formats. These parsers are compiled into the native
 *       image and work at runtime without reflection-based class loading.</li>
 *   <li>Groovy/JavaScript/Mvel expression languages are NOT supported in native mode.
 *       Use Camel Simple language or DataSonnet for expressions.</li>
 *   <li>Route definitions are stored as files under {@code ~/.kompile/camel-routes/}
 *       and loaded at startup. New routes can be deployed at runtime via the REST API
 *       or MCP tools.</li>
 * </ul>
 * <p>
 * The shared context hosts all persistent routes. Isolated contexts are created for
 * one-shot compute-graph node executions to prevent route-ID collisions.
 */
@Slf4j
public class CamelContextManager implements Closeable {

    private final DefaultCamelContext sharedContext;
    private final Map<String, CamelContext> isolatedContexts = new ConcurrentHashMap<>();
    private final Map<String, String> deployedRouteIds = new ConcurrentHashMap<>();
    private final CamelRouteParser routeParser;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public CamelContextManager() {
        this(new CamelRouteParser());
    }

    @SuppressWarnings("removal")
    public CamelContextManager(CamelRouteParser routeParser) {
        this.routeParser = routeParser;
        this.sharedContext = new DefaultCamelContext();
        this.sharedContext.setName("kompile-camel-shared");
        try {
            this.sharedContext.start();
            log.info("Shared CamelContext started");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start shared CamelContext", e);
        }
    }

    /**
     * Get the shared CamelContext that hosts all persistent routes.
     */
    public CamelContext getSharedContext() {
        ensureOpen();
        return sharedContext;
    }

    // ---- Persistent Route Lifecycle ----

    /**
     * Deploy a route to the shared context. The route stays active until explicitly
     * removed or the application shuts down.
     *
     * @param registryId the route's registry ID (from CamelRouteRegistry)
     * @param script     the route definition (XML DSL, YAML DSL, or Simple expression)
     * @return the Camel route ID assigned to the deployed route
     */
    public String deployRoute(String registryId, String script) throws Exception {
        ensureOpen();
        String routeId = routeParser.parseAndAddRoute(sharedContext, script, registryId);
        deployedRouteIds.put(registryId, routeId);
        log.info("Deployed route '{}' (camelRouteId={})", registryId, routeId);
        return routeId;
    }

    /**
     * Undeploy (stop + remove) a persistent route from the shared context.
     */
    public boolean undeployRoute(String registryId) {
        ensureOpen();
        String routeId = deployedRouteIds.remove(registryId);
        if (routeId == null) {
            log.debug("Route '{}' not found in deployed routes", registryId);
            return false;
        }
        routeParser.removeRoute(sharedContext, routeId);
        log.info("Undeployed route '{}' (camelRouteId={})", registryId, routeId);
        return true;
    }

    /**
     * Stop a deployed route without removing it. It can be started again later.
     */
    public boolean stopRoute(String registryId) throws Exception {
        ensureOpen();
        String routeId = deployedRouteIds.get(registryId);
        if (routeId == null) return false;
        sharedContext.getRouteController().stopRoute(routeId);
        log.info("Stopped route '{}' (camelRouteId={})", registryId, routeId);
        return true;
    }

    /**
     * Start a previously stopped route.
     */
    public boolean startRoute(String registryId) throws Exception {
        ensureOpen();
        String routeId = deployedRouteIds.get(registryId);
        if (routeId == null) return false;
        sharedContext.getRouteController().startRoute(routeId);
        log.info("Started route '{}' (camelRouteId={})", registryId, routeId);
        return true;
    }

    /**
     * Reload a route: undeploy the old version and deploy the new script.
     */
    public String reloadRoute(String registryId, String newScript) throws Exception {
        undeployRoute(registryId);
        return deployRoute(registryId, newScript);
    }

    /**
     * Deploy all enabled routes from the registry. Called on startup.
     */
    public void deployAllFromRegistry(CamelRouteRegistry registry) {
        List<CamelRouteRegistry.RouteDefinitionRecord> enabled = registry.listEnabled();
        int deployed = 0;
        for (CamelRouteRegistry.RouteDefinitionRecord record : enabled) {
            try {
                deployRoute(record.getId(), record.getScript());
                deployed++;
            } catch (Exception e) {
                log.warn("Failed to deploy route '{}' ({}): {}", record.getName(), record.getId(), e.getMessage());
            }
        }
        log.info("Deployed {}/{} enabled routes from registry", deployed, enabled.size());
    }

    /**
     * Get the status of all deployed routes.
     */
    public List<RouteStatus> getDeployedRouteStatuses() {
        List<RouteStatus> statuses = new ArrayList<>();
        for (Map.Entry<String, String> entry : deployedRouteIds.entrySet()) {
            String registryId = entry.getKey();
            String camelRouteId = entry.getValue();
            Route route = sharedContext.getRoute(camelRouteId);

            RouteStatus status = new RouteStatus();
            status.registryId = registryId;
            status.camelRouteId = camelRouteId;

            if (route != null) {
                ServiceStatus svcStatus = sharedContext.getRouteController().getRouteStatus(camelRouteId);
                status.status = svcStatus != null ? svcStatus.name() : "UNKNOWN";
                status.endpoint = route.getEndpoint().getEndpointUri();
                status.uptimeMillis = route.getUptimeMillis();
            } else {
                status.status = "NOT_FOUND";
            }

            statuses.add(status);
        }
        return statuses;
    }

    /**
     * Check if a route is currently deployed and running.
     */
    public boolean isRouteDeployed(String registryId) {
        String routeId = deployedRouteIds.get(registryId);
        if (routeId == null) return false;
        return sharedContext.getRoute(routeId) != null;
    }

    // ---- Isolated Contexts for Compute Graph Execution ----

    /**
     * Get or create an isolated CamelContext for a specific graph execution.
     * Isolated contexts prevent route name collisions between concurrent executions.
     */
    @SuppressWarnings("removal")
    public CamelContext getOrCreateIsolatedContext(String executionId) {
        ensureOpen();
        return isolatedContexts.computeIfAbsent(executionId, id -> {
            DefaultCamelContext ctx = new DefaultCamelContext();
            ctx.setName("kompile-camel-" + id);
            try {
                ctx.start();
                log.debug("Created isolated CamelContext for execution {}", id);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to start isolated CamelContext for " + id, e);
            }
            return ctx;
        });
    }

    /**
     * Destroy an isolated context after graph execution completes.
     */
    public void destroyIsolatedContext(String executionId) {
        CamelContext ctx = isolatedContexts.remove(executionId);
        if (ctx != null) {
            try {
                ctx.stop();
                log.debug("Destroyed isolated CamelContext for execution {}", executionId);
            } catch (Exception e) {
                log.warn("Error stopping isolated CamelContext for {}", executionId, e);
            }
        }
    }

    // ---- Lifecycle ----

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (Map.Entry<String, CamelContext> entry : isolatedContexts.entrySet()) {
                try {
                    entry.getValue().stop();
                } catch (Exception e) {
                    log.warn("Error stopping isolated context {}", entry.getKey(), e);
                }
            }
            isolatedContexts.clear();
            deployedRouteIds.clear();

            try {
                sharedContext.stop();
                log.info("Shared CamelContext stopped ({} routes)", sharedContext.getRoutesSize());
            } catch (Exception e) {
                log.warn("Error stopping shared CamelContext", e);
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("CamelContextManager is closed");
        }
    }

    /**
     * Status of a deployed route.
     */
    public static class RouteStatus {
        public String registryId;
        public String camelRouteId;
        public String status;
        public String endpoint;
        public long uptimeMillis;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("registryId", registryId);
            map.put("camelRouteId", camelRouteId);
            map.put("status", status);
            map.put("endpoint", endpoint);
            map.put("uptimeMillis", uptimeMillis);
            return map;
        }
    }
}
