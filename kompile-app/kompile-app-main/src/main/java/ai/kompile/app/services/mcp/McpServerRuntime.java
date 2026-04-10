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

import ai.kompile.core.mcp.server.*;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runtime manager for a single MCP server instance.
 * Implements MCP protocol over SSE transport using Jetty.
 */
public class McpServerRuntime {

    private static final Logger logger = LoggerFactory.getLogger(McpServerRuntime.class);

    private final McpServerConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private Server jettyServer;
    private final Map<String, AsyncContext> sseClients = new ConcurrentHashMap<>();

    public McpServerRuntime(McpServerConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Starts the MCP server with the configured tools, resources, and prompts.
     */
    public void start() throws Exception {
        logger.info("Starting MCP server: {} on port {}", config.getName(), config.getPort());

        // Create Jetty server for HTTP transport
        jettyServer = new Server(config.getPort());

        // Create servlet context
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath(config.getBasePath());
        jettyServer.setHandler(context);

        // Add SSE endpoint for MCP communication
        context.addServlet(new ServletHolder(new McpSseServlet()), "/sse");

        // Add message endpoint for receiving client messages
        context.addServlet(new ServletHolder(new McpMessageServlet()), "/message");

        // Start Jetty server
        jettyServer.start();

        logger.info("MCP server started successfully: {} at http://localhost:{}{}",
                config.getName(), config.getPort(), config.getBasePath());
    }

    /**
     * Stops the MCP server.
     */
    public void stop() throws Exception {
        logger.info("Stopping MCP server: {}", config.getName());

        // Close all SSE connections
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

        logger.info("MCP server stopped: {}", config.getName());
    }

    /**
     * Checks if the server is running.
     */
    public boolean isRunning() {
        return jettyServer != null && jettyServer.isRunning();
    }

    /**
     * SSE Servlet for MCP server-sent events
     */
    private class McpSseServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/event-stream");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Cache-Control", "no-cache");
            resp.setHeader("Connection", "keep-alive");
            resp.setHeader("Access-Control-Allow-Origin", "*");

            String clientId = UUID.randomUUID().toString();
            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(0); // No timeout

            // MEMORY LEAK FIX: Add listener to cleanup when client disconnects
            final String finalClientId = clientId;
            asyncContext.addListener(new jakarta.servlet.AsyncListener() {
                @Override
                public void onComplete(jakarta.servlet.AsyncEvent event) {
                    sseClients.remove(finalClientId);
                    logger.debug("SSE client completed: {}", finalClientId);
                }

                @Override
                public void onTimeout(jakarta.servlet.AsyncEvent event) {
                    sseClients.remove(finalClientId);
                    logger.debug("SSE client timed out: {}", finalClientId);
                }

                @Override
                public void onError(jakarta.servlet.AsyncEvent event) {
                    sseClients.remove(finalClientId);
                    logger.debug("SSE client error: {}", finalClientId);
                }

                @Override
                public void onStartAsync(jakarta.servlet.AsyncEvent event) {
                    // Re-register listener if async is restarted
                }
            });

            sseClients.put(clientId, asyncContext);

            PrintWriter writer = resp.getWriter();

            // Send endpoint information
            writer.write("event: endpoint\n");
            writer.write("data: " + config.getBasePath() + "/message?sessionId=" + clientId + "\n\n");
            writer.flush();

            logger.debug("SSE client connected: {}", clientId);
        }
    }

    /**
     * Message Servlet for handling MCP JSON-RPC messages
     */
    private class McpMessageServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.setHeader("Access-Control-Allow-Origin", "*");

            String sessionId = req.getParameter("sessionId");

            // Read request body
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = req.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            String requestBody = sb.toString();
            logger.debug("Received MCP message: {}", requestBody);

            try {
                ObjectNode request = (ObjectNode) objectMapper.readTree(requestBody);
                String method = request.has("method") ? request.get("method").asText() : null;
                Object id = request.has("id") ? request.get("id") : null;

                ObjectNode response = handleMcpRequest(method, request.get("params"), id);

                PrintWriter writer = resp.getWriter();
                writer.write(objectMapper.writeValueAsString(response));
                writer.flush();

                // Also send via SSE if client is connected
                if (sessionId != null && sseClients.containsKey(sessionId)) {
                    sendSseMessage(sessionId, response);
                }

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

    private ObjectNode handleMcpRequest(String method, com.fasterxml.jackson.databind.JsonNode params, Object id) {
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
            ObjectNode result = objectMapper.createObjectNode();

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
                case "resources/list":
                    result = handleListResources();
                    break;
                case "resources/read":
                    result = handleReadResource(params);
                    break;
                case "prompts/list":
                    result = handleListPrompts();
                    break;
                case "prompts/get":
                    result = handleGetPrompt(params);
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
        serverInfo.put("name", config.getName());
        serverInfo.put("version", config.getVersion());

        ObjectNode capabilities = result.putObject("capabilities");

        if (config.getTools() != null && !config.getTools().isEmpty()) {
            ObjectNode tools = capabilities.putObject("tools");
            tools.put("listChanged", true);
        }

        if (config.getResources() != null && !config.getResources().isEmpty()) {
            ObjectNode resources = capabilities.putObject("resources");
            resources.put("subscribe", config.getResources().stream()
                    .anyMatch(McpResourceConfig::isSupportsSubscription));
            resources.put("listChanged", true);
        }

        if (config.getPrompts() != null && !config.getPrompts().isEmpty()) {
            ObjectNode prompts = capabilities.putObject("prompts");
            prompts.put("listChanged", true);
        }

        if (config.isLoggingEnabled()) {
            capabilities.putObject("logging");
        }

        return result;
    }

    private ObjectNode handleListTools() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        if (config.getTools() != null) {
            for (McpToolConfig toolConfig : config.getTools()) {
                if (!toolConfig.isEnabled()) continue;

                ObjectNode tool = tools.addObject();
                tool.put("name", toolConfig.getName());
                tool.put("description", toolConfig.getDescription());

                if (toolConfig.getInputSchema() != null) {
                    tool.set("inputSchema", toolConfig.getInputSchema());
                } else {
                    tool.set("inputSchema", buildSchemaFromParameters(toolConfig.getParameters()));
                }
            }
        }

        return result;
    }

    private ObjectNode handleCallTool(com.fasterxml.jackson.databind.JsonNode params) throws Exception {
        String toolName = params.has("name") ? params.get("name").asText() : null;
        com.fasterxml.jackson.databind.JsonNode arguments = params.get("arguments");

        McpToolConfig toolConfig = config.getTools().stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolName));

        @SuppressWarnings("unchecked")
        Map<String, Object> argsMap = arguments != null ?
                objectMapper.convertValue(arguments, Map.class) : new HashMap<>();

        Object toolResult = executeTool(toolConfig, argsMap);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", objectMapper.writeValueAsString(toolResult));
        result.put("isError", false);

        return result;
    }

    private ObjectNode handleListResources() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode resources = result.putArray("resources");

        if (config.getResources() != null) {
            for (McpResourceConfig resourceConfig : config.getResources()) {
                if (!resourceConfig.isEnabled()) continue;

                ObjectNode resource = resources.addObject();
                resource.put("uri", resourceConfig.getUri());
                resource.put("name", resourceConfig.getName());
                if (resourceConfig.getDescription() != null) {
                    resource.put("description", resourceConfig.getDescription());
                }
                resource.put("mimeType", resourceConfig.getMimeType());
            }
        }

        return result;
    }

    private ObjectNode handleReadResource(com.fasterxml.jackson.databind.JsonNode params) throws Exception {
        String uri = params.has("uri") ? params.get("uri").asText() : null;

        McpResourceConfig resourceConfig = config.getResources().stream()
                .filter(r -> r.getUri().equals(uri))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Resource not found: " + uri));

        String content = readResourceContent(resourceConfig);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode contents = result.putArray("contents");
        ObjectNode textContent = contents.addObject();
        textContent.put("uri", resourceConfig.getUri());
        textContent.put("mimeType", resourceConfig.getMimeType());
        textContent.put("text", content);

        return result;
    }

    private ObjectNode handleListPrompts() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode prompts = result.putArray("prompts");

        if (config.getPrompts() != null) {
            for (McpPromptConfig promptConfig : config.getPrompts()) {
                if (!promptConfig.isEnabled()) continue;

                ObjectNode prompt = prompts.addObject();
                prompt.put("name", promptConfig.getName());
                if (promptConfig.getDescription() != null) {
                    prompt.put("description", promptConfig.getDescription());
                }

                if (promptConfig.getArguments() != null && !promptConfig.getArguments().isEmpty()) {
                    ArrayNode arguments = prompt.putArray("arguments");
                    for (McpPromptConfig.PromptArgument arg : promptConfig.getArguments()) {
                        ObjectNode argNode = arguments.addObject();
                        argNode.put("name", arg.getName());
                        if (arg.getDescription() != null) {
                            argNode.put("description", arg.getDescription());
                        }
                        argNode.put("required", arg.isRequired());
                    }
                }
            }
        }

        return result;
    }

    private ObjectNode handleGetPrompt(com.fasterxml.jackson.databind.JsonNode params) {
        String promptName = params.has("name") ? params.get("name").asText() : null;
        com.fasterxml.jackson.databind.JsonNode argumentsNode = params.get("arguments");

        McpPromptConfig promptConfig = config.getPrompts().stream()
                .filter(p -> p.getName().equals(promptName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptName));

        @SuppressWarnings("unchecked")
        Map<String, String> arguments = argumentsNode != null ?
                objectMapper.convertValue(argumentsNode, Map.class) : new HashMap<>();

        ObjectNode result = objectMapper.createObjectNode();
        if (promptConfig.getDescription() != null) {
            result.put("description", promptConfig.getDescription());
        }

        ArrayNode messages = result.putArray("messages");
        for (McpPromptConfig.PromptMessage msg : promptConfig.getMessages()) {
            String content = msg.getContent();
            // Replace placeholders
            for (Map.Entry<String, String> arg : arguments.entrySet()) {
                content = content.replace("{{" + arg.getKey() + "}}", arg.getValue());
            }

            ObjectNode message = messages.addObject();
            message.put("role", msg.getRole().name().toLowerCase());
            ObjectNode contentNode = message.putObject("content");
            contentNode.put("type", "text");
            contentNode.put("text", content);
        }

        return result;
    }

    private Object executeTool(McpToolConfig toolConfig, Map<String, Object> arguments) throws Exception {
        switch (toolConfig.getImplementationType()) {
            case HTTP_ENDPOINT:
                return executeHttpTool(toolConfig, arguments);
            case SCRIPT:
                return executeScriptTool(toolConfig, arguments);
            case JAVA_CLASS:
                return executeJavaClassTool(toolConfig, arguments);
            case BUILT_IN:
                return executeBuiltInTool(toolConfig, arguments);
            default:
                throw new UnsupportedOperationException("Unknown tool type: " + toolConfig.getImplementationType());
        }
    }

    private Object executeScriptTool(McpToolConfig toolConfig, Map<String, Object> arguments) throws Exception {
        McpToolConfig.ScriptConfig scriptConfig = toolConfig.getScriptConfig();
        if (scriptConfig == null) {
            throw new IllegalStateException("Script configuration is required for SCRIPT tools");
        }

        String language = scriptConfig.getLanguage() != null ? scriptConfig.getLanguage() : "python";
        String interpreter;
        switch (language.toLowerCase()) {
            case "python":
            case "python3":
                interpreter = "python3";
                break;
            case "javascript":
            case "node":
                interpreter = "node";
                break;
            case "bash":
            case "sh":
                interpreter = "bash";
                break;
            default:
                throw new UnsupportedOperationException("Unsupported script language: " + language);
        }

        // Determine script content: inline script or file path
        List<String> command = new ArrayList<>();
        command.add(interpreter);

        java.nio.file.Path tempScript = null;
        try {
            if (scriptConfig.getScriptPath() != null && !scriptConfig.getScriptPath().isEmpty()) {
                command.add(scriptConfig.getScriptPath());
            } else if (scriptConfig.getScript() != null && !scriptConfig.getScript().isEmpty()) {
                // Write inline script to temp file
                String ext = language.contains("python") ? ".py" : language.contains("javascript") ? ".js" : ".sh";
                tempScript = java.nio.file.Files.createTempFile("mcp-script-", ext);
                java.nio.file.Files.writeString(tempScript, scriptConfig.getScript());
                command.add(tempScript.toString());
            } else {
                throw new IllegalStateException("Script tool requires either 'script' or 'scriptPath'");
            }

            // Pass arguments as JSON via stdin
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (scriptConfig.getEnvironment() != null) {
                pb.environment().putAll(scriptConfig.getEnvironment());
            }

            Process process = pb.start();

            // Write arguments as JSON to stdin
            String argsJson = objectMapper.writeValueAsString(arguments);
            process.getOutputStream().write(argsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().close();

            // Read output
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(scriptConfig.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new java.util.concurrent.TimeoutException(
                        "Script execution timed out after " + scriptConfig.getTimeoutMs() + "ms");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return Map.of("error", "Script exited with code " + exitCode, "output", output);
            }

            // Try to parse as JSON, fall back to plain text
            try {
                return objectMapper.readTree(output);
            } catch (Exception e) {
                return Map.of("result", output);
            }
        } finally {
            if (tempScript != null) {
                java.nio.file.Files.deleteIfExists(tempScript);
            }
        }
    }

    private Object executeJavaClassTool(McpToolConfig toolConfig, Map<String, Object> arguments) throws Exception {
        McpToolConfig.JavaClassConfig javaConfig = toolConfig.getJavaClassConfig();
        if (javaConfig == null) {
            throw new IllegalStateException("Java class configuration is required for JAVA_CLASS tools");
        }

        String className = javaConfig.getClassName();
        String methodName = javaConfig.getMethodName();
        if (className == null || methodName == null) {
            throw new IllegalStateException("Both className and methodName are required for JAVA_CLASS tools");
        }

        Class<?> clazz = Class.forName(className);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        // Find the method - look for one accepting Map<String, Object>
        java.lang.reflect.Method method = null;
        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName)) {
                Class<?>[] paramTypes = m.getParameterTypes();
                if (paramTypes.length == 1 && paramTypes[0].isAssignableFrom(Map.class)) {
                    method = m;
                    break;
                } else if (paramTypes.length == 0) {
                    method = m;
                    // Keep looking for a method that accepts arguments
                }
            }
        }

        if (method == null) {
            throw new NoSuchMethodException("No suitable method '" + methodName + "' found in " + className);
        }

        Object result;
        if (method.getParameterCount() == 0) {
            result = method.invoke(instance);
        } else {
            result = method.invoke(instance, arguments);
        }

        if (result == null) {
            return Map.of("status", "success");
        }

        // If already a Map or JsonNode, return as-is
        if (result instanceof Map || result instanceof com.fasterxml.jackson.databind.JsonNode) {
            return result;
        }

        return Map.of("result", result.toString());
    }

    private Object executeBuiltInTool(McpToolConfig toolConfig, Map<String, Object> arguments) throws Exception {
        // Built-in tools are handled by forwarding to the local HTTP API
        // The tool name maps to a REST endpoint on this server
        String toolName = toolConfig.getName();
        String localUrl = "http://localhost:" + config.getPort() + "/api/tools/" + toolName + "/execute";

        try {
            String requestBody = objectMapper.writeValueAsString(arguments);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(localUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                // Fall back to returning tool info
                return Map.of(
                        "status", "error",
                        "tool", toolName,
                        "message", "Built-in tool returned HTTP " + response.statusCode(),
                        "response", response.body()
                );
            }

            return objectMapper.readTree(response.body());
        } catch (java.net.ConnectException e) {
            logger.warn("Built-in tool {} not available via local API, returning tool info", toolName);
            return Map.of(
                    "status", "unavailable",
                    "tool", toolName,
                    "message", "Built-in tool endpoint not reachable"
            );
        }
    }

    private Object executeHttpTool(McpToolConfig toolConfig, Map<String, Object> arguments) throws Exception {
        McpToolConfig.HttpEndpointConfig httpConfig = toolConfig.getHttpConfig();
        if (httpConfig == null) {
            throw new IllegalStateException("HTTP configuration is required for HTTP_ENDPOINT tools");
        }

        String requestBody = objectMapper.writeValueAsString(arguments);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(httpConfig.getUrl()))
                .timeout(Duration.ofMillis(httpConfig.getTimeoutMs()))
                .header("Content-Type", httpConfig.getContentType());

        if (httpConfig.getHeaders() != null) {
            httpConfig.getHeaders().forEach(requestBuilder::header);
        }

        HttpRequest request;
        switch (httpConfig.getMethod().toUpperCase()) {
            case "POST":
                request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
                break;
            case "PUT":
                request = requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(requestBody)).build();
                break;
            case "GET":
                request = requestBuilder.GET().build();
                break;
            case "DELETE":
                request = requestBuilder.DELETE().build();
                break;
            case "PATCH":
                request = requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(requestBody)).build();
                break;
            default:
                throw new UnsupportedOperationException("HTTP method not supported: " + httpConfig.getMethod() +
                        ". Supported: GET, POST, PUT, DELETE, PATCH");
        }

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP error " + response.statusCode() + ": " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    private String readResourceContent(McpResourceConfig resourceConfig) throws Exception {
        switch (resourceConfig.getResourceType()) {
            case STATIC:
                return resourceConfig.getStaticContent() != null ? resourceConfig.getStaticContent() : "";
            case FILE:
                if (resourceConfig.getFileConfig() != null && resourceConfig.getFileConfig().getBasePath() != null) {
                    return java.nio.file.Files.readString(
                            java.nio.file.Paths.get(resourceConfig.getFileConfig().getBasePath()));
                }
                return "File path not configured";
            case HTTP:
                if (resourceConfig.getHttpConfig() != null && resourceConfig.getHttpConfig().getUrl() != null) {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(resourceConfig.getHttpConfig().getUrl()))
                            .timeout(Duration.ofMillis(resourceConfig.getHttpConfig().getTimeoutMs()))
                            .GET()
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    return response.body();
                }
                return "HTTP URL not configured";
            case DATABASE:
                return readDatabaseResource(resourceConfig);
            default:
                return "Resource type not implemented: " + resourceConfig.getResourceType();
        }
    }

    private String readDatabaseResource(McpResourceConfig resourceConfig) {
        McpResourceConfig.DatabaseResourceConfig dbConfig = resourceConfig.getDatabaseConfig();
        if (dbConfig == null || dbConfig.getQuery() == null || dbConfig.getQuery().isEmpty()) {
            return "Database resource not configured: missing query";
        }

        // Build JDBC URL from dataSourceName - supports Spring datasource naming convention
        String dataSourceName = dbConfig.getDataSourceName();
        if (dataSourceName == null || dataSourceName.isEmpty()) {
            return "Database resource not configured: missing dataSourceName";
        }

        try {
            // Look up datasource via JNDI or construct from common Spring properties
            // For security, only allow SELECT queries
            String query = dbConfig.getQuery().trim();
            if (!query.toLowerCase().startsWith("select")) {
                return "Only SELECT queries are allowed for database resources";
            }

            // Use the application's datasource - get it from Spring context
            javax.sql.DataSource dataSource = resolveDataSource(dataSourceName);
            if (dataSource == null) {
                return "DataSource not found: " + dataSourceName;
            }

            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.PreparedStatement stmt = conn.prepareStatement(query)) {

                // Bind parameters if any
                if (dbConfig.getParameters() != null) {
                    int idx = 1;
                    for (Map.Entry<String, Object> param : dbConfig.getParameters().entrySet()) {
                        stmt.setObject(idx++, param.getValue());
                    }
                }

                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    String format = dbConfig.getOutputFormat() != null ? dbConfig.getOutputFormat() : "json";
                    switch (format.toLowerCase()) {
                        case "csv":
                            return resultSetToCsv(rs);
                        case "text":
                            return resultSetToText(rs);
                        case "json":
                        default:
                            return resultSetToJson(rs);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error reading database resource: {}", e.getMessage(), e);
            return "Database query error: " + e.getMessage();
        }
    }

    private javax.sql.DataSource resolveDataSource(String dataSourceName) {
        // Try to look up via Spring application context if available
        try {
            // Use JNDI lookup for named datasources
            javax.naming.InitialContext ctx = new javax.naming.InitialContext();
            return (javax.sql.DataSource) ctx.lookup("java:comp/env/" + dataSourceName);
        } catch (Exception e) {
            // If JNDI fails, try to create a simple connection from the datasource name
            // which may be a JDBC URL
            try {
                if (dataSourceName.startsWith("jdbc:")) {
                    return new javax.sql.DataSource() {
                        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                            return java.sql.DriverManager.getConnection(dataSourceName);
                        }
                        @Override public java.sql.Connection getConnection(String u, String p) throws java.sql.SQLException {
                            return java.sql.DriverManager.getConnection(dataSourceName, u, p);
                        }
                        @Override public java.io.PrintWriter getLogWriter() { return null; }
                        @Override public void setLogWriter(java.io.PrintWriter out) {}
                        @Override public void setLoginTimeout(int seconds) {}
                        @Override public int getLoginTimeout() { return 0; }
                        @Override public java.util.logging.Logger getParentLogger() { return null; }
                        @Override public <T> T unwrap(Class<T> iface) { return null; }
                        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
                    };
                }
            } catch (Exception ex) {
                logger.warn("Failed to resolve DataSource '{}': {}", dataSourceName, ex.getMessage());
            }
            return null;
        }
    }

    private String resultSetToJson(java.sql.ResultSet rs) throws Exception {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        ArrayNode rows = objectMapper.createArrayNode();

        while (rs.next()) {
            ObjectNode row = objectMapper.createObjectNode();
            for (int i = 1; i <= colCount; i++) {
                String colName = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                if (value == null) {
                    row.putNull(colName);
                } else if (value instanceof Number) {
                    if (value instanceof Integer || value instanceof Long) {
                        row.put(colName, ((Number) value).longValue());
                    } else {
                        row.put(colName, ((Number) value).doubleValue());
                    }
                } else if (value instanceof Boolean) {
                    row.put(colName, (Boolean) value);
                } else {
                    row.put(colName, value.toString());
                }
            }
            rows.add(row);
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows);
    }

    private String resultSetToCsv(java.sql.ResultSet rs) throws Exception {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        StringBuilder sb = new StringBuilder();

        // Header
        for (int i = 1; i <= colCount; i++) {
            if (i > 1) sb.append(",");
            sb.append(escapeCsv(meta.getColumnLabel(i)));
        }
        sb.append("\n");

        // Rows
        while (rs.next()) {
            for (int i = 1; i <= colCount; i++) {
                if (i > 1) sb.append(",");
                Object val = rs.getObject(i);
                sb.append(val == null ? "" : escapeCsv(val.toString()));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String resultSetToText(java.sql.ResultSet rs) throws Exception {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        StringBuilder sb = new StringBuilder();

        // Header
        for (int i = 1; i <= colCount; i++) {
            if (i > 1) sb.append("\t");
            sb.append(meta.getColumnLabel(i));
        }
        sb.append("\n");
        sb.append("-".repeat(colCount * 15)).append("\n");

        // Rows
        while (rs.next()) {
            for (int i = 1; i <= colCount; i++) {
                if (i > 1) sb.append("\t");
                Object val = rs.getObject(i);
                sb.append(val == null ? "NULL" : val.toString());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private ObjectNode buildSchemaFromParameters(List<McpToolConfig.ParameterConfig> parameters) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = objectMapper.createArrayNode();

        if (parameters != null) {
            for (McpToolConfig.ParameterConfig param : parameters) {
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

        if (required.size() > 0) {
            schema.set("required", required);
        }

        return schema;
    }

    private void sendSseMessage(String sessionId, ObjectNode message) {
        AsyncContext asyncContext = sseClients.get(sessionId);
        if (asyncContext != null) {
            try {
                PrintWriter writer = asyncContext.getResponse().getWriter();
                writer.write("event: message\n");
                writer.write("data: " + objectMapper.writeValueAsString(message) + "\n\n");
                writer.flush();
            } catch (Exception e) {
                logger.warn("Failed to send SSE message to client: {}", sessionId, e);
                sseClients.remove(sessionId);
            }
        }
    }
}
