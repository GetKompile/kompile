/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

// New File: upload/kompile-pipelines-framework-api/src/main/java/ai/kompile/pipelines/framework/api/data/PipelineDataConstants.java
package ai.kompile.pipelines.framework.api.data;

/**
 * Defines conventional constant keys for storing tool-related information
 * within {@link Data} objects in the Kompile Pipeline Framework.
 */
public final class PipelineDataConstants {

    private PipelineDataConstants() {
        // Prevent instantiation
    }

    /**
     * Key in a {@link Data} object whose value is a {@code List<PipelineToolCallRequest>}.
     * Used when a pipeline step outputs a request to call one or more tools.
     * The list should be retrievable via {@code Data.getList(TOOL_CALL_REQUESTS_KEY, ValueType.DATA)}
     * and then each element cast to {@code PipelineToolCallRequest} (assuming underlying JDataFactory handles POJOs).
     * Alternatively, if custom serialization is used, a more specific {@code ValueType} or direct POJO getter might be involved.
     */
    public static final String TOOL_CALL_REQUESTS_KEY = "kompile_tool_call_requests";

    /**
     * Key in a {@link Data} object whose value is a {@code List<PipelineToolCallResponse>}.
     * Used when feeding the results of tool executions back into a pipeline.
     * The list should be retrievable similarly to requests.
     */
    public static final String TOOL_CALL_RESPONSES_KEY = "kompile_tool_call_responses";

    /**
     * Key in a {@link Data} object whose value is a {@code List<PipelineToolDefinition>}.
     * Used to provide a pipeline with information about available tools it can request.
     * The list should be retrievable similarly to requests.
     */
    public static final String AVAILABLE_TOOLS_KEY = "kompile_available_tools";

    /**
     * Optional key in a {@link Data} object output by a pipeline step. If true,
     * it indicates that the primary purpose of this output is to signal tool call requests
     * found under {@link #TOOL_CALL_REQUESTS_KEY}, rather than being a final direct response.
     * The value associated with this key should be a Boolean.
     */
    public static final String IS_TOOL_CALL_REQUEST_FLAG_KEY = "kompile_is_tool_call_request";

}