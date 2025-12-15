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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public Sse getSse() {
        return sse;
    }

    public void setSse(Sse sse) {
        this.sse = sse;
    }

    public ActionLog getActionLog() {
        return actionLog;
    }

    public void setActionLog(ActionLog actionLog) {
        this.actionLog = actionLog;
    }

    /**
     * SSE transport configuration.
     */
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

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getMessageEndpoint() {
            return messageEndpoint;
        }

        public void setMessageEndpoint(String messageEndpoint) {
            this.messageEndpoint = messageEndpoint;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * Action logging configuration.
     */
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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }
    }
}
