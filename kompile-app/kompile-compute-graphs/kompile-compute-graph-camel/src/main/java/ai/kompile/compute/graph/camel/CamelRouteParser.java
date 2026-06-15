package ai.kompile.compute.graph.camel;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.spi.Resource;
import org.apache.camel.spi.RoutesLoader;
import org.apache.camel.support.ResourceHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Parses Camel route definitions from compute node scripts.
 * Supports XML DSL and YAML DSL route definitions.
 */
@Slf4j
public class CamelRouteParser {

    /**
     * Route format detected from the script content.
     */
    public enum RouteFormat {
        XML_DSL,
        YAML_DSL,
        SIMPLE_EXPRESSION
    }

    /**
     * Detect the route format from the script content.
     */
    public RouteFormat detectFormat(String script) {
        String trimmed = script.trim();
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<routes") || trimmed.startsWith("<route")) {
            return RouteFormat.XML_DSL;
        }
        if (trimmed.startsWith("- route:") || trimmed.startsWith("- from:") || trimmed.startsWith("route:")) {
            return RouteFormat.YAML_DSL;
        }
        return RouteFormat.SIMPLE_EXPRESSION;
    }

    /**
     * Parse a route definition and add it to the CamelContext.
     * Returns the route ID for later reference.
     */
    public String parseAndAddRoute(CamelContext context, String script, String nodeId) throws Exception {
        RouteFormat format = detectFormat(script);
        String routeId = "kompile-node-" + sanitize(nodeId);

        switch (format) {
            case XML_DSL -> addXmlRoute(context, script, routeId);
            case YAML_DSL -> addYamlRoute(context, script, routeId);
            case SIMPLE_EXPRESSION -> addSimpleRoute(context, script, routeId);
        }

        log.debug("Added {} route '{}' for node {}", format, routeId, nodeId);
        return routeId;
    }

    private void addXmlRoute(CamelContext context, String script, String routeId) throws Exception {
        String xml = script.trim();
        // Wrap in <routes> if it's a bare <route> element
        if (xml.startsWith("<route") && !xml.startsWith("<routes")) {
            xml = "<routes xmlns=\"http://camel.apache.org/schema/spring\">" + xml + "</routes>";
        }
        // Inject route ID if not present
        if (!xml.contains("id=\"")) {
            xml = xml.replaceFirst("<route", "<route id=\"" + routeId + "\"");
        }

        RoutesLoader loader = context.getCamelContextExtension().getContextPlugin(RoutesLoader.class);
        Resource resource = ResourceHelper.fromBytes("route.xml", xml.getBytes(StandardCharsets.UTF_8));
        loader.loadRoutes(resource);
    }

    private void addYamlRoute(CamelContext context, String script, String routeId) throws Exception {
        RoutesLoader loader = context.getCamelContextExtension().getContextPlugin(RoutesLoader.class);
        Resource resource = ResourceHelper.fromBytes("route.yaml", script.getBytes(StandardCharsets.UTF_8));
        loader.loadRoutes(resource);
    }

    /**
     * For simple expressions, create a direct route that evaluates the expression
     * and sets the result as the body.
     */
    private void addSimpleRoute(CamelContext context, String script, String routeId) throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:" + routeId)
                        .routeId(routeId)
                        .transform().simple(script.trim());
            }
        });
    }

    /**
     * Remove a previously added route from the context.
     */
    public void removeRoute(CamelContext context, String routeId) {
        try {
            context.getRouteController().stopRoute(routeId);
            context.removeRoute(routeId);
            log.debug("Removed route '{}'", routeId);
        } catch (Exception e) {
            log.warn("Failed to remove route '{}': {}", routeId, e.getMessage());
        }
    }

    private String sanitize(String input) {
        if (input == null) return "unnamed";
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
