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

package ai.kompile.app.mcp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the MCP (Model Context Protocol) server.
 *
 * Configuration prefix: mcp.server
 *
 * Example configuration:
 * <pre>
 * mcp.server.enabled=true
 * mcp.server.name=kompile-mcp-server
 * mcp.server.version=1.0.0
 * mcp.server.transport=sse
 * mcp.server.sse.endpoint=/mcp/sse
 * mcp.server.sse.message-endpoint=/mcp/message
 * mcp.server.sse.timeout=300000
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "mcp.server")
@Getter
@Setter
public class McpServerProperties {

    /**
     * Whether the MCP server is enabled.
     */
    private boolean enabled = true;

    /**
     * The name of the MCP server (used in server info).
     */
    private String name = "kompile-mcp-server";

    /**
     * The version of the MCP server.
     */
    private String version = "1.0.0";

    /**
     * The transport type to use: 'sse' or 'stdio'.
     */
    private String transport = "sse";

    /**
     * SSE-specific configuration.
     */
    private Sse sse = new Sse();

    /**
     * Action logging configuration.
     */
    private ActionLog actionLog = new ActionLog();

    /**
     * SSE transport configuration.
     */
    @Getter
    @Setter
    public static class Sse {
        /**
         * The endpoint path for SSE connections.
         */
        private String endpoint = "/mcp/sse";

        /**
         * The endpoint path for receiving messages.
         */
        private String messageEndpoint = "/mcp/message";

        /**
         * SSE connection timeout in milliseconds.
         */
        private long timeout = 300000L;
    }

    /**
     * Action logging configuration.
     */
    @Getter
    @Setter
    public static class ActionLog {
        /**
         * Whether action logging is enabled.
         */
        private boolean enabled = true;

        /**
         * Maximum number of actions to keep in the log.
         */
        private int maxEntries = 1000;

        /**
         * Hours to retain actions before cleanup.
         */
        private int retentionHours = 24;
    }
}
