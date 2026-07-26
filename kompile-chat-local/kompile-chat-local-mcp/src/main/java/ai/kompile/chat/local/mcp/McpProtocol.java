package ai.kompile.chat.local.mcp;

import ai.kompile.graph.reasoning.unified.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;

final class McpProtocol {

    static final String JSON_RPC_VERSION = "2.0";
    static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    static final String SERVER_NAME = "kompile-chat-local-graph";
    static final String SERVER_VERSION = "0.1.0-SNAPSHOT";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    static final int NOT_INITIALIZED = -32002;

    private McpProtocol() {
    }

    static String result(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("result", result);
        return MiniJson.write(response);
    }

    static String error(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("error", error);
        return MiniJson.write(response);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?>)) {
            throw new InvalidParamsException(label + " must be a JSON object");
        }
        return (Map<String, Object>) value;
    }

    static final class InvalidParamsException extends IllegalArgumentException {
        InvalidParamsException(String message) {
            super(message);
        }
    }
}
