package ai.kompile.chat.local.mcp;

import ai.kompile.chat.local.GraphToolBackend;
import ai.kompile.graph.reasoning.unified.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Transport-independent MCP server for a single local graph session.
 *
 * <p>The server deliberately depends only on the backend-neutral graph contract and
 * the repository's reflection-free JSON codec. One instance owns one backend and is
 * therefore suitable for a single trusted local MCP client.</p>
 */
public final class GraphMcpServer implements AutoCloseable {

    private final GraphToolBackend backend;
    private final List<Map<String, Object>> tools;
    private final Set<String> toolNames;
    private boolean initializeSeen;
    private boolean closed;

    public GraphMcpServer(GraphToolBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        Catalog catalog = translateCatalog(backend.catalogJson());
        this.tools = catalog.tools();
        this.toolNames = catalog.names();
    }

    /**
     * Handle one newline-framed JSON-RPC message.
     *
     * @return a response for requests, or an empty optional for notifications
     */
    public synchronized Optional<String> handle(String message) {
        Objects.requireNonNull(message, "message");

        Map<String, Object> request;
        try {
            request = MiniJson.parseObject(message);
        } catch (RuntimeException ex) {
            return Optional.of(McpProtocol.error(null, McpProtocol.PARSE_ERROR, "Parse error"));
        }

        boolean hasId = request.containsKey("id");
        Object id = request.get("id");
        if (!McpProtocol.JSON_RPC_VERSION.equals(request.get("jsonrpc"))
                || !(request.get("method") instanceof String method)
                || method.isBlank()) {
            return hasId
                    ? Optional.of(McpProtocol.error(id, McpProtocol.INVALID_REQUEST, "Invalid Request"))
                    : Optional.empty();
        }

        try {
            Object result = dispatch(method, request.get("params"));
            return hasId ? Optional.of(McpProtocol.result(id, result)) : Optional.empty();
        } catch (McpProtocol.InvalidParamsException ex) {
            return hasId
                    ? Optional.of(McpProtocol.error(id, McpProtocol.INVALID_PARAMS, ex.getMessage()))
                    : Optional.empty();
        } catch (MethodNotFoundException ex) {
            return hasId
                    ? Optional.of(McpProtocol.error(id, McpProtocol.METHOD_NOT_FOUND, ex.getMessage()))
                    : Optional.empty();
        } catch (NotInitializedException ex) {
            return hasId
                    ? Optional.of(McpProtocol.error(id, McpProtocol.NOT_INITIALIZED, ex.getMessage()))
                    : Optional.empty();
        } catch (RuntimeException ex) {
            return hasId
                    ? Optional.of(McpProtocol.error(id, McpProtocol.INTERNAL_ERROR, "Internal error"))
                    : Optional.empty();
        }
    }

    public List<Map<String, Object>> tools() {
        return tools;
    }

    private Object dispatch(String method, Object paramsValue) {
        if (closed) {
            throw new IllegalStateException("MCP server is closed");
        }

        return switch (method) {
            case "initialize" -> initialize(paramsValue);
            case "notifications/initialized" -> {
                initializeSeen = true;
                yield Map.of();
            }
            case "ping" -> Map.of();
            case "tools/list" -> listTools();
            case "tools/call" -> callTool(paramsValue);
            default -> throw new MethodNotFoundException("Method not found: " + method);
        };
    }

    private Map<String, Object> initialize(Object paramsValue) {
        if (paramsValue != null) {
            McpProtocol.object(paramsValue, "params");
        }
        initializeSeen = true;

        Map<String, Object> toolsCapability = new LinkedHashMap<>();
        toolsCapability.put("listChanged", false);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", toolsCapability);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", McpProtocol.SERVER_NAME);
        serverInfo.put("version", McpProtocol.SERVER_VERSION);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", McpProtocol.MCP_PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Map<String, Object> listTools() {
        requireInitialized();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return result;
    }

    private Map<String, Object> callTool(Object paramsValue) {
        requireInitialized();
        Map<String, Object> params = McpProtocol.object(paramsValue, "params");

        Object nameValue = params.get("name");
        if (!(nameValue instanceof String name) || name.isBlank()) {
            throw new McpProtocol.InvalidParamsException("params.name must be a non-empty string");
        }
        if (!toolNames.contains(name)) {
            throw new McpProtocol.InvalidParamsException("Unknown tool: " + name);
        }

        Object argumentsValue = params.get("arguments");
        Map<String, Object> arguments = argumentsValue == null
                ? Map.of()
                : McpProtocol.object(argumentsValue, "params.arguments");

        String backendResult;
        boolean isError;
        try {
            backendResult = Objects.requireNonNull(
                    backend.execute(name, MiniJson.write(arguments)),
                    "backend result");
            isError = isBackendError(backendResult);
        } catch (RuntimeException ex) {
            backendResult = MiniJson.write(Map.of(
                    "status", "ERROR",
                    "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            isError = true;
        }

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", backendResult);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(text));
        result.put("isError", isError);
        return result;
    }

    private void requireInitialized() {
        if (!initializeSeen) {
            throw new NotInitializedException("Server not initialized");
        }
    }

    private static boolean isBackendError(String resultJson) {
        try {
            Object parsed = MiniJson.parse(resultJson);
            if (!(parsed instanceof Map<?, ?> result)) {
                return true;
            }
            if (Boolean.TRUE.equals(result.get("isError"))) {
                return true;
            }
            Object statusValue = result.get("status");
            if (!(statusValue instanceof String status)) {
                return false;
            }
            return switch (status.toUpperCase(Locale.ROOT)) {
                case "ERROR", "INVALID", "FAILED", "FAILURE" -> true;
                default -> false;
            };
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private static Catalog translateCatalog(String catalogJson) {
        Object parsed;
        try {
            parsed = MiniJson.parse(Objects.requireNonNull(catalogJson, "catalogJson"));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Graph tool catalog is not valid JSON", ex);
        }
        if (!(parsed instanceof List<?> entries)) {
            throw new IllegalArgumentException("Graph tool catalog must be a JSON array");
        }

        List<Map<String, Object>> tools = new ArrayList<>(entries.size());
        Set<String> names = new LinkedHashSet<>();
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("Graph tool catalog entries must be JSON objects");
            }

            Object nameValue = entry.get("name");
            if (!(nameValue instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("Graph tool catalog entry has no name");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate graph tool in catalog: " + name);
            }

            Object descriptionValue = entry.get("description");
            String description = descriptionValue instanceof String text ? text : "";
            Object schemaValue = entry.get("parameters");
            if (!(schemaValue instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Graph tool " + name + " has no object parameter schema");
            }

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("description", description);
            tool.put("inputSchema", schemaValue);
            tools.add(tool);
        }
        return new Catalog(List.copyOf(tools), Set.copyOf(names));
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            backend.close();
        }
    }

    private record Catalog(List<Map<String, Object>> tools, Set<String> names) {
    }

    private static final class MethodNotFoundException extends IllegalArgumentException {
        MethodNotFoundException(String message) {
            super(message);
        }
    }

    private static final class NotInitializedException extends IllegalStateException {
        NotInitializedException(String message) {
            super(message);
        }
    }
}
