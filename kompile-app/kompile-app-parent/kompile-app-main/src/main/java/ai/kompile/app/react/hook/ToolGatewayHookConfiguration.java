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

package ai.kompile.app.react.hook;

import ai.kompile.toolgateway.service.ToolGatewayConfigService;
import ai.kompile.toolgateway.service.ToolGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ToolGatewayHook} as a Spring bean so it's
 * automatically picked up by the ReAct agent's hook collection.
 */
@Configuration(proxyBeanMethods = false)
public class ToolGatewayHookConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayHookConfiguration.class);

    @Bean
    @ConditionalOnBean({ToolGatewayService.class, ToolGatewayConfigService.class})
    public ToolGatewayHook toolGatewayHook(ToolGatewayService gatewayService,
                                            ToolGatewayConfigService configService) {
        log.info("Configuring ToolGatewayHook (priority=10)");
        return new ToolGatewayHook(gatewayService, configService);
    }
}
