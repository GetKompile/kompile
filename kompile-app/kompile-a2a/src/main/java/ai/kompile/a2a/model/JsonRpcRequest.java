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
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * JSON-RPC 2.0 request envelope for A2A protocol methods.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcRequest {

    @Builder.Default
    private String jsonrpc = "2.0";

    private String method;
    private Object id;
    private Map<String, Object> params;

    /**
     * A2A JSON-RPC method names.
     */
    public static final String METHOD_MESSAGE_SEND = "message/send";
    public static final String METHOD_MESSAGE_STREAM = "message/stream";
    public static final String METHOD_TASKS_GET = "tasks/get";
    public static final String METHOD_TASKS_CANCEL = "tasks/cancel";
}
