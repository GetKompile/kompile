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

package ai.kompile.core.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for an MCP tool.
 * Tools expose executable functionality that LLMs can invoke.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolConfig {

    /**
     * Unique name for the tool (used for invocation)
     */
    private String name;

    /**
     * Human-readable description of what the tool does
     */
    private String description;

    /**
     * JSON Schema defining the tool's input parameters
     */
    private JsonNode inputSchema;

    /**
     * Type of implementation for this tool
     */
    @Builder.Default
    private ToolImplementationType implementationType = ToolImplementationType.HTTP_ENDPOINT;

    /**
     * Configuration for HTTP endpoint implementation
     */
    private HttpEndpointConfig httpConfig;

    /**
     * Configuration for script-based implementation
     */
    private ScriptConfig scriptConfig;

    /**
     * Configuration for Java class-based implementation
     */
    private JavaClassConfig javaClassConfig;

    /**
     * List of parameter definitions
     */
    @Builder.Default
    private List<ParameterConfig> parameters = new ArrayList<>();

    /**
     * Whether this tool is enabled
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Tool implementation types
     */
    public enum ToolImplementationType {
        /**
         * Tool forwards to an HTTP endpoint
         */
        HTTP_ENDPOINT,
        /**
         * Tool executes a script (Python, JavaScript, etc.)
         */
        SCRIPT,
        /**
         * Tool invokes a Java class method
         */
        JAVA_CLASS,
        /**
         * Tool uses a pre-built integration (RAG, filesystem, etc.)
         */
        BUILT_IN
    }

    /**
     * HTTP endpoint configuration for tool execution
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HttpEndpointConfig {
        private String url;
        @Builder.Default
        private String method = "POST";
        @Builder.Default
        private String contentType = "application/json";
        private java.util.Map<String, String> headers;
        @Builder.Default
        private int timeoutMs = 30000;
    }

    /**
     * Script execution configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScriptConfig {
        private String language; // python, javascript, etc.
        private String script; // inline script content
        private String scriptPath; // path to script file
        @Builder.Default
        private int timeoutMs = 60000;
        private java.util.Map<String, String> environment;
    }

    /**
     * Java class invocation configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JavaClassConfig {
        private String className;
        private String methodName;
        private String beanName; // if using Spring bean
    }

    /**
     * Parameter definition for tools
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterConfig {
        private String name;
        private String type; // string, number, boolean, object, array
        private String description;
        @Builder.Default
        private boolean required = false;
        private Object defaultValue;
        private List<Object> enumValues; // for enum types
    }
}
