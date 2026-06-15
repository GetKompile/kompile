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

package ai.kompile.cli.main.chat.tools.custom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A custom tool definition loaded from a JSON file in {@code ~/.kompile/tools/}
 * or {@code .kompile/tools/}.
 *
 * <h3>Example: bash-backed tool</h3>
 * <pre>{@code
 * {
 *   "name": "jira_lookup",
 *   "description": "Look up a JIRA ticket by ID",
 *   "parameters": {
 *     "type": "object",
 *     "properties": { "ticket": { "type": "string", "description": "JIRA ticket ID" } },
 *     "required": ["ticket"]
 *   },
 *   "execute": {
 *     "type": "bash",
 *     "command": "jira view {{ticket}} --json"
 *   }
 * }
 * }</pre>
 *
 * <h3>Example: HTTP-backed tool</h3>
 * <pre>{@code
 * {
 *   "name": "deploy_status",
 *   "description": "Check deployment status for an environment",
 *   "parameters": {
 *     "type": "object",
 *     "properties": { "env": { "type": "string" } },
 *     "required": ["env"]
 *   },
 *   "execute": {
 *     "type": "http",
 *     "url": "https://deploy.internal/api/status/{{env}}",
 *     "method": "GET"
 *   }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomToolDefinition {

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("parameters")
    private JsonNode parameters;

    @JsonProperty("execute")
    private ExecuteConfig execute;

    @JsonProperty("timeout_seconds")
    private int timeoutSeconds = 30;

    public String getName() { return name; }
    public String getDescription() { return description; }
    public JsonNode getParameters() { return parameters; }
    public ExecuteConfig getExecute() { return execute; }
    public int getTimeoutSeconds() { return timeoutSeconds; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setParameters(JsonNode parameters) { this.parameters = parameters; }
    public void setExecute(ExecuteConfig execute) { this.execute = execute; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    /**
     * Validate that the definition has all required fields.
     * @return error message, or null if valid
     */
    public String validate() {
        if (name == null || name.isBlank()) return "missing 'name'";
        if (!name.matches("[a-z][a-z0-9_]*")) return "name must be lowercase alphanumeric with underscores, starting with a letter";
        if (description == null || description.isBlank()) return "missing 'description'";
        if (execute == null) return "missing 'execute' block";
        return execute.validate();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExecuteConfig {

        @JsonProperty("type")
        private String type;

        // bash backend
        @JsonProperty("command")
        private String command;

        @JsonProperty("working_dir")
        private String workingDir;

        // http backend
        @JsonProperty("url")
        private String url;

        @JsonProperty("method")
        private String method;

        @JsonProperty("headers")
        private JsonNode headers;

        @JsonProperty("body")
        private String body;

        public String getType() { return type; }
        public String getCommand() { return command; }
        public String getWorkingDir() { return workingDir; }
        public String getUrl() { return url; }
        public String getMethod() { return method; }
        public JsonNode getHeaders() { return headers; }
        public String getBody() { return body; }

        public void setType(String type) { this.type = type; }
        public void setCommand(String command) { this.command = command; }
        public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
        public void setUrl(String url) { this.url = url; }
        public void setMethod(String method) { this.method = method; }
        public void setHeaders(JsonNode headers) { this.headers = headers; }
        public void setBody(String body) { this.body = body; }

        public String validate() {
            if (type == null || type.isBlank()) return "execute.type is required ('bash' or 'http')";
            return switch (type) {
                case "bash" -> command == null || command.isBlank() ? "execute.command is required for bash type" : null;
                case "http" -> url == null || url.isBlank() ? "execute.url is required for http type" : null;
                default -> "unknown execute.type '" + type + "': must be 'bash' or 'http'";
            };
        }
    }
}
