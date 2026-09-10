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

import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.agent.KClawExecutionScopeResolver;
import ai.kompile.kclaw.agent.ToolkitRegistry;
import ai.kompile.kclaw.gateway.ChannelController;
import ai.kompile.kclaw.gateway.integration.ChannelProviderCatalog;
import ai.kompile.kclaw.gateway.whatsapp.WhatsAppWebhookInbox;
import ai.kompile.react.service.ReActAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KClawAutoConfigurationTest {

    @Test
    void channelManager_startsEmptyUntilPersistentConnectionsAreRestored() {
        ChannelManager manager = new KClawAutoConfiguration().channelManager();

        assertTrue(manager.getStatus().isEmpty());
    }

    @Test
    void emptyChannelManager_returnsEmptyStatusInsteadOfMissingEndpoints() throws Exception {
        ChannelManager manager = new ChannelManager();
        @SuppressWarnings("unchecked")
        ObjectProvider<WhatsAppWebhookInbox> inbox = mock(ObjectProvider.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ChannelController(manager, inbox))
                .build();

        mvc.perform(get("/api/kclaw/channels"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void providerCatalog_exposesAllBuiltInChannelSchemas() {
        ChannelProviderCatalog catalog = new ChannelProviderCatalog();
        assertEquals(
                Set.of("telegram", "discord", "slack", "whatsapp", "email"),
                catalog.providers().stream()
                        .map(provider -> provider.id())
                        .collect(Collectors.toSet()));
    }

    @Test
    void agentServiceKeepsLegacyWiringWhenNoExecutionScopeResolverExists() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KClawExecutionScopeResolver> resolverProvider = mock(ObjectProvider.class);
        when(resolverProvider.getIfAvailable()).thenReturn(null);

        KClawAgentService service = new KClawAutoConfiguration().kClawAgentService(
                mock(SessionService.class),
                mock(AgentRegistry.class),
                mock(ToolkitRegistry.class),
                mock(ReActAgentService.class),
                resolverProvider);

        assertNotNull(service);
    }
}
