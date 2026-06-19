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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
    @Builder.Default
    private int timeoutSeconds = 30;

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
}
