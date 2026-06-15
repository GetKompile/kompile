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
package ai.kompile.openclaw.gateway.channel;

import ai.kompile.openclaw.agent.OpenClawAgentService;

/**
 * OpenClaw-specific base channel adapter.
 * Extends the shared {@link ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter}
 * and wires in the OpenClaw agent service as the executor.
 *
 * @deprecated New channel adapters should extend
 *   {@link ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter} directly
 *   and accept an {@link ai.kompile.gateway.core.service.AgentExecutor}.
 */
public abstract class BaseChannelAdapter
        extends ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter {

    /**
     * Convenience constructor that adapts {@link OpenClawAgentService} (which implements
     * {@link ai.kompile.gateway.core.service.AgentExecutor}) into the shared base class.
     */
    protected BaseChannelAdapter(OpenClawAgentService agentService) {
        super(agentService);
    }
}
