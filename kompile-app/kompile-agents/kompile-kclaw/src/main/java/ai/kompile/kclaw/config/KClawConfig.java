/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.config;

import lombok.Data;

@Data
public class KClawConfig {

    private String workspace;
    
    private GatewayConfig gateway;
    
    private String defaultAgentId;

    @Data
    public static class GatewayConfig {
        private int port;
        private boolean websocketEnabled;
        private boolean restEnabled;
        private String websocketPath;
    }

    public static KClawConfig defaults() {
        String home = System.getProperty("user.home");
        KClawConfig config = new KClawConfig();
        config.setWorkspace(home + "/.kompile/kclaw");
        config.setDefaultAgentId("jarvis");
        
        GatewayConfig gateway = new GatewayConfig();
        gateway.setPort(18789);
        gateway.setWebsocketEnabled(true);
        gateway.setRestEnabled(true);
        gateway.setWebsocketPath("/ws/kclaw");
        config.setGateway(gateway);
        
        return config;
    }
}
