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

package ai.kompile.app.services.agent;

import ai.kompile.core.agent.CliAgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * App-side {@link CliAgentRunner} backed by {@link AgentSubprocessExecutor}.
 *
 * <p>Routes a per-agent CLI completion through the shared subprocess infrastructure that
 * builds the proper interactive command, strips ANSI/TUI escape sequences, and parses the
 * agent's JSON stream into clean text — the same machinery the {@code cliAgentLLMChat}
 * pool and passthrough sessions use. This lets lower modules (the crawl pipeline) obtain a
 * named-agent completion without a raw one-shot {@code <agent> -p <prompt>} spawn.</p>
 */
@Service
public class CliAgentRunnerImpl implements CliAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(CliAgentRunnerImpl.class);

    private final AgentSubprocessExecutor executor;

    public CliAgentRunnerImpl(AgentSubprocessExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String run(String agentName, String prompt, int timeoutSeconds) {
        AgentSubprocessExecutor.SubprocessResult result =
                executor.executeSync(agentName, prompt, true, false, null, timeoutSeconds);
        if (result == null) {
            return null;
        }
        if (result.error() != null && (result.content() == null || result.content().isBlank())) {
            log.warn("CLI agent '{}' run failed: {}", agentName, result.error());
            return null;
        }
        return result.content();
    }
}
