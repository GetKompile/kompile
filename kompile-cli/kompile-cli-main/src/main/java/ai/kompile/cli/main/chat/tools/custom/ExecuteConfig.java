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
 * Execution configuration for a {@link CustomToolDefinition}.
 * Supports bash and HTTP backends.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecuteConfig {

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

    public String validate() {
        if (type == null || type.isBlank()) return "execute.type is required ('bash' or 'http')";
        return switch (type) {
            case "bash" -> command == null || command.isBlank() ? "execute.command is required for bash type" : null;
            case "http" -> url == null || url.isBlank() ? "execute.url is required for http type" : null;
            default -> "unknown execute.type '" + type + "': must be 'bash' or 'http'";
        };
    }
}
