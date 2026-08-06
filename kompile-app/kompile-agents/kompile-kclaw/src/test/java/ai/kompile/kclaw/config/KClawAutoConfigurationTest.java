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
import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.gateway.ChannelController;
import ai.kompile.kclaw.gateway.OAuthController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KClawAutoConfigurationTest {

    @Test
    @SuppressWarnings("unchecked")
    void channelManager_keepsRestContractAvailableWithoutReactAgent() {
        ObjectProvider<KClawAgentService> agents = mock(ObjectProvider.class);
        when(agents.getIfAvailable()).thenReturn(null);

        ChannelManager manager = new KClawAutoConfiguration().channelManager(
                agents, mock(ApplicationEventPublisher.class));

        assertTrue(manager.getStatus().isEmpty());
    }

    @Test
    void emptyChannelManager_returnsEmptyStatusInsteadOfMissingEndpoints() throws Exception {
        ChannelManager manager = new ChannelManager();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ChannelController(manager),
                new OAuthController(manager, new ObjectMapper()))
                .build();

        mvc.perform(get("/api/kclaw/channels"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get("/api/kclaw/oauth/slack/status"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/kclaw/oauth/discord/status"))
                .andExpect(status().isOk());
    }

    @Test
    @SuppressWarnings("unchecked")
    void channelManager_registersAllAdaptersWhenReactAgentIsAvailable() {
        ObjectProvider<KClawAgentService> agents = mock(ObjectProvider.class);
        when(agents.getIfAvailable()).thenReturn(mock(KClawAgentService.class));

        ChannelManager manager = new KClawAutoConfiguration().channelManager(
                agents, mock(ApplicationEventPublisher.class));

        assertEquals(
                Set.of("telegram", "discord", "slack", "whatsapp", "email"),
                manager.getStatus().stream()
                        .map(ChannelManager.ChannelStatus::channelName)
                        .collect(Collectors.toSet()));
    }
}
