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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.tools.ToolContext;

/**
 * Interface for running subagent tasks. The implementation will use the
 * configured LLM (via the kompile-app server agent endpoint) to run
 * a subagent with its own tool loop.
 */
public interface SubagentRunner {

    /**
     * Run a subagent with the given configuration and prompt.
     * The subagent executes autonomously and returns its final text result.
     *
     * @param agent  subagent configuration (tools, permissions, system prompt)
     * @param prompt the task description/prompt for the subagent
     * @param parentContext the parent tool context
     * @return the subagent's final text response
     * @throws Exception if the subagent fails
     */
    String runSubagent(AgentConfig agent, String prompt, ToolContext parentContext) throws Exception;
}
