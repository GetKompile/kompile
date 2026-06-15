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

package ai.kompile.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON-RPC 2.0 response envelope for A2A protocol methods.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {

    @Builder.Default
    private String jsonrpc = "2.0";

    private Object id;
    private Object result;
    private JsonRpcError error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonRpcError {
        private int code;
        private String message;
        private Object data;
    }

    public static JsonRpcResponse success(Object id, Object result) {
        return JsonRpcResponse.builder().id(id).result(result).build();
    }

    public static JsonRpcResponse error(Object id, int code, String message) {
        return JsonRpcResponse.builder()
                .id(id)
                .error(JsonRpcError.builder().code(code).message(message).build())
                .build();
    }

    // Standard JSON-RPC error codes
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    // A2A-specific error codes
    public static final int TASK_NOT_FOUND = -32001;
    public static final int TASK_NOT_CANCELABLE = -32002;
    public static final int AGENT_NOT_FOUND = -32003;
}
