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
package ai.kompile.app.services.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CliAgentLLMChatTest {

    @Mock
    private AgentRegistryService agentRegistryService;

    @Mock
    private AgentSubprocessExecutor subprocessExecutor;

    @Mock
    private ClaudeStreamParser streamParser;

    private CliAgentLLMChat chat;

    @BeforeEach
    void setUp() {
        when(agentRegistryService.hasAvailableAgents()).thenReturn(false);
        chat = new CliAgentLLMChat(agentRegistryService, subprocessExecutor, streamParser);
    }

    @Test
    void isNotAvailableWhenNoAgents() {
        assertFalse(chat.isAvailable());
    }

    @Test
    void constructsWithoutError() {
        assertNotNull(chat);
    }
}
