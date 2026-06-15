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

package ai.kompile.toolgateway.config;

import ai.kompile.app.config.KompileServerConstants;
import ai.kompile.toolgateway.model.ToolGatewayConfig;
import ai.kompile.toolgateway.service.ToolGatewayConfigService;
import ai.kompile.toolgateway.service.ToolGatewayRulesProvider;
import ai.kompile.toolgateway.service.ToolGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the tool gateway.
 * <p>
 * Beans are always created (no conditional). The gateway checks
 * {@link ToolGatewayConfigService#isEnabled()} at runtime to decide
 * whether to evaluate tool calls or pass them through.
 * </p>
 * <p>
 * Model resolution uses the existing kompile serving infrastructure:
 * <ol>
 *   <li>{@link ToolGatewayConfig.ModelSource#STAGING} — creates an
 *       {@link OpenAiChatModel} pointing at the kompile-model-staging
 *       server (resolved from {@code kompile.staging.url})</li>
 *   <li>{@link ToolGatewayConfig.ModelSource#GLOBAL} — uses the
 *       application's global {@link ChatModel} bean</li>
 * </ol>
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class ToolGatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayConfiguration.class);

    @Bean
    public ToolGatewayRulesProvider toolGatewayRulesProvider(ToolGatewayConfigService configService) {
        return new ToolGatewayRulesProvider(configService);
    }

    @Bean
    public ToolGatewayService toolGatewayService(
            ToolGatewayConfigService configService,
            ToolGatewayRulesProvider rulesProvider,
            @Autowired(required = false) ChatModel globalChatModel) {

        ChatModel chatModel = resolveModel(configService, globalChatModel, KompileServerConstants.DEFAULT_STAGING_URL);
        return new ToolGatewayService(configService, rulesProvider, chatModel);
    }

    /**
     * Resolve which ChatModel the gateway should use based on config.
     * <p>
     * Uses the existing kompile infrastructure — no new model endpoints created.
     * <ul>
     *   <li>STAGING: points at kompile-model-staging's OpenAI-compatible
     *       {@code /v1/chat/completions} endpoint (same server managed by
     *       {@code StagingServerLifecycleService})</li>
     *   <li>GLOBAL: uses whatever ChatModel bean Spring AI autoconfigured</li>
     * </ul>
     */
    private ChatModel resolveModel(ToolGatewayConfigService configService,
                                   ChatModel globalChatModel,
                                   String stagingUrl) {
        ToolGatewayConfig config = configService.getConfig();
        ToolGatewayConfig.ModelSource source = config.getModelSource();

        if (source == ToolGatewayConfig.ModelSource.STAGING) {
            log.info("Tool gateway using kompile-model-staging at: {}", stagingUrl);

            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(stagingUrl)
                    .apiKey("kompile-gateway")
                    .build();

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model("default")
                    .temperature(0.0)
                    .build();

            return OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .build();
        }

        // GLOBAL — use the application's existing ChatModel bean
        if (globalChatModel != null) {
            log.info("Tool gateway using global ChatModel: {}", globalChatModel.getClass().getSimpleName());
            return globalChatModel;
        }

        log.warn("Tool gateway has no ChatModel available. "
                + "Either start kompile-model-staging or configure a global LLM provider.");
        return null;
    }
}
