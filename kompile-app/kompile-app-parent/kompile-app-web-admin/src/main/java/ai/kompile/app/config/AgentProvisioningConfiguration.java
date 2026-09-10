/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.config;

import ai.kompile.agent.graph.AgentInstanceStore;
import ai.kompile.agent.graph.AgentPrivateGraphContextAssembler;
import ai.kompile.agent.graph.LocalOwnerIdentity;
import ai.kompile.agent.graph.LocalOwnerIdentityStore;
import ai.kompile.app.services.AdminKClawExecutionScopeResolver;
import ai.kompile.app.services.AdminProvisionedAgentRuntimeService;
import ai.kompile.app.services.AgentPrivateGraphToolFactory;
import ai.kompile.app.services.AgentProvisioningService;
import ai.kompile.app.services.AgentProvisioningService.ProvisionedAgent;
import ai.kompile.app.services.agent.ProvisionedAgentRuntime;
import ai.kompile.app.web.controllers.AgentProvisioningController.CreateAgentRequest;
import ai.kompile.app.web.controllers.AgentProvisioningController.ErrorResponse;
import ai.kompile.app.web.controllers.ProvisionedAgentRuntimeController;
import ai.kompile.kclaw.agent.KClawExecutionScopeResolver;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Admin-persona-only persistence wiring for durable private-graph agents.
 *
 * <p>This configuration intentionally lives in {@code kompile-app-web-admin}; it is not an
 * auto-configuration and must never be moved to a shared, chat, crawl, or agent library module.</p>
 */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
        CreateAgentRequest.class,
        ProvisionedAgent.class,
        ErrorResponse.class,
        ProvisionedAgentRuntime.PrepareRequest.class,
        ProvisionedAgentRuntime.RuntimeContext.class,
        ProvisionedAgentRuntime.CanonicalEvent.class,
        ProvisionedAgentRuntime.EventDraft.class,
        ProvisionedAgentRuntime.AppendEventsRequest.class,
        ProvisionedAgentRuntime.ToolDescriptor.class,
        ProvisionedAgentRuntime.ToolExecutionRequest.class,
        ProvisionedAgentRuntime.ToolExecutionResult.class,
        ProvisionedAgentRuntimeController.ErrorResponse.class
})
public class AgentProvisioningConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LocalOwnerIdentityStore localOwnerIdentityStore(
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDirectory)
            throws IOException {
        return new LocalOwnerIdentityStore(Path.of(dataDirectory).resolve("control"));
    }

    @Bean
    @ConditionalOnMissingBean
    public LocalOwnerIdentity localOwnerIdentity(LocalOwnerIdentityStore identityStore)
            throws IOException {
        return identityStore.loadOrCreate();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentInstanceStore agentInstanceStore(
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDirectory)
            throws IOException {
        return new AgentInstanceStore(Path.of(dataDirectory).resolve("agents"));
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentProvisioningService agentProvisioningService(
            AgentInstanceStore store,
            LocalOwnerIdentity localOwnerIdentity) {
        return new AgentProvisioningService(store, localOwnerIdentity);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentPrivateGraphContextAssembler agentPrivateGraphContextAssembler() {
        return new AgentPrivateGraphContextAssembler();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentPrivateGraphToolFactory agentPrivateGraphToolFactory(
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new AgentPrivateGraphToolFactory(
                objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean(ProvisionedAgentRuntime.class)
    public ProvisionedAgentRuntime provisionedAgentRuntime(
            AgentInstanceStore store,
            LocalOwnerIdentity localOwnerIdentity,
            AgentPrivateGraphContextAssembler contextAssembler,
            AgentPrivateGraphToolFactory toolFactory) {
        return new AdminProvisionedAgentRuntimeService(
                store, localOwnerIdentity, contextAssembler, toolFactory);
    }

    @Bean
    @ConditionalOnMissingBean(KClawExecutionScopeResolver.class)
    public KClawExecutionScopeResolver kClawExecutionScopeResolver(
            AgentInstanceStore store,
            LocalOwnerIdentity localOwnerIdentity,
            AgentPrivateGraphContextAssembler contextAssembler,
            AgentPrivateGraphToolFactory toolFactory,
            @Value("${kompile.kclaw.provisioned-agent-template:jarvis}") String runtimeTemplateName) {
        // AgentInstance currently persists identity/display metadata, not runtime persona/model policy.
        // Until that policy becomes durable, the server explicitly selects this named KClaw template.
        return new AdminKClawExecutionScopeResolver(
                store, localOwnerIdentity, contextAssembler, toolFactory, runtimeTemplateName);
    }
}
