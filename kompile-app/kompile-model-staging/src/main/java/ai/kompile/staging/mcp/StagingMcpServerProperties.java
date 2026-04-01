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

package ai.kompile.staging.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component("stagingMcpServerProperties")
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@ConfigurationProperties(prefix = "mcp.server")
public class StagingMcpServerProperties {

    private boolean enabled = true;
    private String name = "kompile-model-staging-mcp";
    private String version = "1.0.0";
    private String transport = "sse";
    private Sse sse = new Sse();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public Sse getSse() { return sse; }
    public void setSse(Sse sse) { this.sse = sse; }

    public static class Sse {
        private String endpoint = "/mcp/sse";
        private String messageEndpoint = "/mcp/message";
        private long timeout = 300000L;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getMessageEndpoint() { return messageEndpoint; }
        public void setMessageEndpoint(String messageEndpoint) { this.messageEndpoint = messageEndpoint; }
        public long getTimeout() { return timeout; }
        public void setTimeout(long timeout) { this.timeout = timeout; }
    }
}
