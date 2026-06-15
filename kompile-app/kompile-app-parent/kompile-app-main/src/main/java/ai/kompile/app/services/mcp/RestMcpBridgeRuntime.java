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

package ai.kompile.app.services.mcp;

import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime for a REST-MCP bridge.
 * Handles bidirectional translation between REST APIs and MCP protocol.
 */
public class RestMcpBridgeRuntime {

    private static final Logger logger = LoggerFactory.getLogger(RestMcpBridgeRuntime.class);

    private final RestMcpBridgeConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private Server jettyServer;
    private final Map<String, AsyncContext> sseClients = new ConcurrentHashMap<>();

    // Cache for compiled path patterns
    private final Map<String, Pattern> pathPatterns = new ConcurrentHashMap<>();

    public RestMcpBridgeRuntime(RestMcpBridgeConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Starts the bridge runtime.
     */
    public void start() throws Exception {
        if (config.getDirection() == BridgeDirection.REST_TO_MCP) {
            startRestToMcpBridge();
        } else {
            startMcpToRestBridge();
        }
    }

    /**
     * Stops the bridge runtime.
     */
    public void stop() throws Exception {
        sseClients.values().forEach(ctx -> {
            try {
                ctx.complete();
            } catch (Exception e) {
                logger.warn("Error closing SSE connection", e);
            }
        });
        sseClients.clear();

        if (jettyServer != null) {
            jettyServer.stop();
            jettyServer = null;
        }

        logger.info("Bridge stopped: {}", config.getName());
    }

    /**
     * Starts a REST-to-MCP bridge that exposes REST endpoints as MCP tools.
     */
    private void startRestToMcpBridge() throws Exception {
        int port = config.getMcpServerRef().getPort();
        String basePath = config.getMcpServerRef().getBasePath();

        logger.info("Starting REST-to-MCP bridge: {} on port {}", config.getName(), port);

        jettyServer = new Server(port);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath(basePath);
        jettyServer.setHandler(context);

        // MCP SSE endpoint
        context.addServlet(new ServletHolder(new BridgeSseServlet()), "/sse");
        context.addServlet(new ServletHolder(new BridgeMessageServlet()), "/message");

        jettyServer.start();

        logger.info("REST-to-MCP bridge started at http://localhost:{}{}", port, basePath);
    }

    /**
     * Starts an MCP-to-REST bridge that exposes MCP tools as REST endpoints.
     */
    private void startMcpToRestBridge() throws Exception {
        int port = config.getMcpServerRef().getPort();
        String basePath = config.getMcpServerRef().getBasePath();

        logger.info("Starting MCP-to-REST bridge: {} on port {}", config.getName(), port);

        jettyServer = new Server(port);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath(basePath);
        jettyServer.setHandler(context);

        // REST proxy endpoint
        context.addServlet(new ServletHolder(new RestProxyServlet()), "/*");

        jettyServer.start();

        logger.info("MCP-to-REST bridge started at http://localhost:{}{}", port, basePath);
    }

    /**
     * SSE Servlet for MCP connections
     */
    private class BridgeSseServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/event-stream");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.setHeader("Connection", "keep-alive");
            resp.setHeader("Access-Control-Allow-Origin", "*");

            String clientId = UUID.randomUUID().toString();
            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(0);
            sseClients.put(clientId, asyncContext);

            PrintWriter writer = resp.getWriter();
            writer.write("event: endpoint\n");
            writer.write("data: " + config.getMcpServerRef().getBasePath() + "/message?sessionId=" + clientId + "\n\n");
            writer.flush();

            logger.debug("Bridge SSE client connected: {}", clientId);
        }
    }

    /**
     * Message Servlet for MCP JSON-RPC
     */
    private class BridgeMessageServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Access-Control-Allow-Origin", "*");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = req.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            String requestBody = sb.toString();
            logger.debug("Bridge received MCP message: {}", requestBody);

            try {
                ObjectNode request = (ObjectNode) objectMapper.readTree(requestBody);
                String method = request.has("method") ? request.get("method").asText() : null;
                Object id = request.has("id") ? request.get("id") : null;

                ObjectNode response = handleMcpRequest(method, request.get("params"), id);

                PrintWriter writer = resp.getWriter();
                writer.write(objectMapper.writeValueAsString(response));
                writer.flush();

            } catch (Exception e) {
                logger.error("Error processing MCP message", e);
                ObjectNode errorResponse = objectMapper.createObjectNode();
                errorResponse.put("jsonrpc", "2.0");
                errorResponse.putNull("id");
                ObjectNode error = errorResponse.putObject("error");
                error.put("code", -32603);
                error.put("message", "Internal error: " + e.getMessage());

                PrintWriter writer = resp.getWriter();
                writer.write(objectMapper.writeValueAsString(errorResponse));
            }
        }

        @Override
        protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        }
    }

    /**
     * REST Proxy Servlet for MCP-to-REST bridges
     */
    private class RestProxyServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String path = req.getPathInfo();
            String method = req.getMethod();

            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Access-Control-Allow-Origin", "*");

            if ("OPTIONS".equals(method)) {
                resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
                resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
                return;
            }

            // Find matching MCP tool
            EndpointMapping mapping = findMappingForPath(method, path);
            if (mapping == null) {
                resp.setStatus(404);
                ObjectNode error = objectMapper.createObjectNode();
                error.put("error", "No mapping found for " + method + " " + path);
                resp.getWriter().write(objectMapper.writeValueAsString(error));
                return;
            }

            try {
                // Read request body
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = req.getReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                // Call MCP tool
                Object result = callMcpTool(mapping, method, path, sb.toString(), req);

                resp.getWriter().write(objectMapper.writeValueAsString(result));

            } catch (Exception e) {
                logger.error("Error proxying request to MCP", e);
                resp.setStatus(500);
                ObjectNode error = objectMapper.createObjectNode();
                error.put("error", e.getMessage());
                resp.getWriter().write(objectMapper.writeValueAsString(error));
            }
        }
    }

    private ObjectNode handleMcpRequest(String method, JsonNode params, Object id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            if (id instanceof Number) {
                response.put("id", ((Number) id).intValue());
            } else {
                response.put("id", id.toString());
            }
        }

        try {
            ObjectNode result;

            switch (method != null ? method : "") {
                case "initialize":
                    result = handleInitialize();
                    break;
                case "tools/list":
                    result = handleListTools();
                    break;
                case "tools/call":
                    result = handleCallTool(params);
                    break;
                case "ping":
                    result = objectMapper.createObjectNode();
                    break;
                default:
                    ObjectNode error = response.putObject("error");
                    error.put("code", -32601);
                    error.put("message", "Method not found: " + method);
                    return response;
            }

            response.set("result", result);

        } catch (Exception e) {
            logger.error("Error handling MCP request: {}", method, e);
            ObjectNode error = response.putObject("error");
            error.put("code", -32603);
            error.put("message", e.getMessage());
        }

        return response;
    }

    private ObjectNode handleInitialize() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", config.getName() + " (Bridge)");
        serverInfo.put("version", "1.0.0");

        ObjectNode capabilities = result.putObject("capabilities");
        ObjectNode tools = capabilities.putObject("tools");
        tools.put("listChanged", true);

        return result;
    }

    private ObjectNode handleListTools() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        for (EndpointMapping mapping : config.getMappings()) {
            if (!mapping.isEnabled()) continue;

            ObjectNode tool = tools.addObject();
            tool.put("name", mapping.getMcpTool().getName());
            tool.put("description", mapping.getMcpTool().getDescription());

            // Build input schema from REST endpoint parameters
            ObjectNode inputSchema = buildInputSchemaForMapping(mapping);
            tool.set("inputSchema", inputSchema);
        }

        return result;
    }

    private ObjectNode handleCallTool(JsonNode params) throws Exception {
        String toolName = params.has("name") ? params.get("name").asText() : null;
        JsonNode arguments = params.get("arguments");

        EndpointMapping mapping = config.getMappings().stream()
                .filter(m -> m.getMcpTool().getName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolName));

        // Call REST endpoint
        Object restResult = callRestEndpoint(mapping, arguments);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", objectMapper.writeValueAsString(restResult));
        result.put("isError", false);

        return result;
    }

    private Object callRestEndpoint(EndpointMapping mapping, JsonNode arguments) throws Exception {
        RestEndpoint endpoint = mapping.getRestEndpoint();
        RestApiConfig restConfig = config.getRestApiConfig();

        // Build URL with path parameters
        String path = endpoint.getPath();
        if (arguments != null) {
            // Replace path parameters
            Pattern pattern = Pattern.compile("\\{([^}]+)}");
            Matcher matcher = pattern.matcher(path);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String paramName = matcher.group(1);
                if (arguments.has(paramName)) {
                    matcher.appendReplacement(sb, arguments.get(paramName).asText());
                }
            }
            matcher.appendTail(sb);
            path = sb.toString();
        }

        String url = restConfig.getBaseUrl() + path;

        // Add query parameters
        if (endpoint.getQueryParams() != null && !endpoint.getQueryParams().isEmpty() && arguments != null) {
            StringBuilder queryString = new StringBuilder();
            for (ParameterDef param : endpoint.getQueryParams()) {
                if (arguments.has(param.getName())) {
                    if (queryString.length() > 0) queryString.append("&");
                    queryString.append(param.getName())
                            .append("=")
                            .append(java.net.URLEncoder.encode(arguments.get(param.getName()).asText(), "UTF-8"));
                }
            }
            if (queryString.length() > 0) {
                url += "?" + queryString;
            }
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(restConfig.getTimeoutMs()))
                .header("Content-Type", endpoint.getContentType())
                .header("Accept", endpoint.getAcceptType());

        // Add authentication
        addAuthHeaders(requestBuilder, config.getAuthConfig());

        // Add default headers
        if (restConfig.getDefaultHeaders() != null) {
            restConfig.getDefaultHeaders().forEach(requestBuilder::header);
        }

        // Build request body (excluding path and query params)
        ObjectNode bodyArgs = objectMapper.createObjectNode();
        if (arguments != null) {
            Set<String> excludeParams = new HashSet<>();
            if (endpoint.getPathParams() != null) {
                endpoint.getPathParams().forEach(p -> excludeParams.add(p.getName()));
            }
            if (endpoint.getQueryParams() != null) {
                endpoint.getQueryParams().forEach(p -> excludeParams.add(p.getName()));
            }

            arguments.fields().forEachRemaining(entry -> {
                if (!excludeParams.contains(entry.getKey())) {
                    bodyArgs.set(entry.getKey(), entry.getValue());
                }
            });
        }

        String body = bodyArgs.size() > 0 ? objectMapper.writeValueAsString(bodyArgs) : "";

        HttpRequest request;
        switch (endpoint.getMethod().toUpperCase()) {
            case "GET":
                request = requestBuilder.GET().build();
                break;
            case "POST":
                request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
                break;
            case "PUT":
                request = requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                break;
            case "DELETE":
                request = requestBuilder.DELETE().build();
                break;
            case "PATCH":
                request = requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
                break;
            default:
                throw new UnsupportedOperationException("HTTP method not supported: " + endpoint.getMethod());
        }

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("REST API error " + response.statusCode() + ": " + response.body());
        }

        // Apply response transformation if configured
        Object result = objectMapper.readTree(response.body());
        if (mapping.getResponseTransform() != null) {
            result = applyTransform(result, mapping.getResponseTransform());
        }

        return result;
    }

    private EndpointMapping findMappingForPath(String method, String path) {
        for (EndpointMapping mapping : config.getMappings()) {
            if (!mapping.isEnabled()) continue;

            RestEndpoint endpoint = mapping.getRestEndpoint();
            if (!endpoint.getMethod().equalsIgnoreCase(method)) continue;

            // Convert path template to regex
            String patternKey = endpoint.getMethod() + ":" + endpoint.getPath();
            Pattern pattern = pathPatterns.computeIfAbsent(patternKey, k -> {
                String regex = endpoint.getPath()
                        .replaceAll("\\{[^}]+}", "[^/]+")
                        .replace("/", "\\/");
                return Pattern.compile("^" + regex + "$");
            });

            if (pattern.matcher(path).matches()) {
                return mapping;
            }
        }
        return null;
    }

    private Object callMcpTool(EndpointMapping mapping, String method, String path,
                               String body, HttpServletRequest req) throws Exception {
        // For MCP-to-REST, we need to call the MCP server
        // This is a simplified implementation - full implementation would connect to MCP server

        // Extract path parameters
        Map<String, String> pathParams = extractPathParams(mapping.getRestEndpoint().getPath(), path);

        // Build MCP tool call arguments
        ObjectNode arguments = objectMapper.createObjectNode();

        // Add path params
        pathParams.forEach(arguments::put);

        // Add query params
        req.getParameterMap().forEach((key, values) -> {
            if (values.length > 0) {
                arguments.put(key, values[0]);
            }
        });

        // Add body params
        if (body != null && !body.isEmpty()) {
            try {
                JsonNode bodyJson = objectMapper.readTree(body);
                if (bodyJson.isObject()) {
                    bodyJson.fields().forEachRemaining(entry ->
                            arguments.set(entry.getKey(), entry.getValue()));
                }
            } catch (Exception e) {
                // Not JSON, ignore
            }
        }

        // For now, return a placeholder - full implementation would call MCP server
        ObjectNode result = objectMapper.createObjectNode();
        result.put("tool", mapping.getMcpTool().getName());
        result.set("arguments", arguments);
        result.put("status", "MCP-to-REST proxy not fully implemented");

        return result;
    }

    private Map<String, String> extractPathParams(String template, String path) {
        Map<String, String> params = new HashMap<>();

        String[] templateParts = template.split("/");
        String[] pathParts = path.split("/");

        for (int i = 0; i < templateParts.length && i < pathParts.length; i++) {
            if (templateParts[i].startsWith("{") && templateParts[i].endsWith("}")) {
                String paramName = templateParts[i].substring(1, templateParts[i].length() - 1);
                params.put(paramName, pathParts[i]);
            }
        }

        return params;
    }

    private ObjectNode buildInputSchemaForMapping(EndpointMapping mapping) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = objectMapper.createArrayNode();

        RestEndpoint endpoint = mapping.getRestEndpoint();

        // Add path parameters
        if (endpoint.getPathParams() != null) {
            for (ParameterDef param : endpoint.getPathParams()) {
                ObjectNode prop = properties.putObject(param.getName());
                prop.put("type", param.getType() != null ? param.getType() : "string");
                if (param.getDescription() != null) {
                    prop.put("description", param.getDescription());
                }
                if (param.isRequired()) {
                    required.add(param.getName());
                }
            }
        }

        // Add query parameters
        if (endpoint.getQueryParams() != null) {
            for (ParameterDef param : endpoint.getQueryParams()) {
                ObjectNode prop = properties.putObject(param.getName());
                prop.put("type", param.getType() != null ? param.getType() : "string");
                if (param.getDescription() != null) {
                    prop.put("description", param.getDescription());
                }
                if (param.isRequired()) {
                    required.add(param.getName());
                }
            }
        }

        // Add request body schema if present
        if (endpoint.getRequestBodySchema() != null) {
            try {
                JsonNode bodySchema = objectMapper.valueToTree(endpoint.getRequestBodySchema());
                if (bodySchema.has("properties")) {
                    bodySchema.get("properties").fields().forEachRemaining(entry ->
                            properties.set(entry.getKey(), entry.getValue()));
                }
                if (bodySchema.has("required") && bodySchema.get("required").isArray()) {
                    for (JsonNode req : bodySchema.get("required")) {
                        required.add(req.asText());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to merge request body schema", e);
            }
        }

        if (required.size() > 0) {
            schema.set("required", required);
        }

        return schema;
    }

    private Object applyTransform(Object data, TransformConfig transform) {
        if (transform == null || transform.getType() == TransformType.PASSTHROUGH) {
            return data;
        }

        // Simplified transform implementation
        switch (transform.getType()) {
            case JSON_PATH:
                // Would need a JSON Path library
                return data;
            case FIELD_MAPPING:
                if (transform.getFieldMappings() != null && data instanceof JsonNode) {
                    ObjectNode result = objectMapper.createObjectNode();
                    JsonNode source = (JsonNode) data;
                    transform.getFieldMappings().forEach((target, sourceField) -> {
                        if (source.has(sourceField)) {
                            result.set(target, source.get(sourceField));
                        }
                    });
                    return result;
                }
                return data;
            default:
                return data;
        }
    }

    private void addAuthHeaders(HttpRequest.Builder builder, AuthConfig authConfig) {
        if (authConfig == null || authConfig.getType() == AuthType.NONE) {
            return;
        }

        switch (authConfig.getType()) {
            case API_KEY:
                builder.header(authConfig.getApiKeyHeader(), authConfig.getApiKey());
                break;
            case BEARER:
                builder.header("Authorization", "Bearer " + authConfig.getBearerToken());
                break;
            case BASIC:
                String credentials = authConfig.getUsername() + ":" + authConfig.getPassword();
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
                builder.header("Authorization", "Basic " + encoded);
                break;
            case OAUTH2:
                logger.warn("OAuth2 authentication not fully implemented");
                break;
        }
    }
}
