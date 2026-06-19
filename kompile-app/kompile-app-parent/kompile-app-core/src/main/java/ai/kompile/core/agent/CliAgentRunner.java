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

package ai.kompile.core.agent;

/**
 * Runs a named CLI agent non-interactively and returns its completion text.
 *
 * <p>Bridge interface implemented in {@code kompile-app-main} so lower modules (such as
 * the crawl pipeline) can route a <em>per-agent</em> CLI completion through the shared
 * persistent subprocess infrastructure — which builds the proper interactive command,
 * strips ANSI/TUI escape sequences, and parses the agent's JSON stream — instead of
 * spawning a raw one-shot subprocess that captures the agent's banner/help output.</p>
 */
public interface CliAgentRunner {

    /**
     * Run the given CLI agent with the prompt and return its parsed completion text.
     *
     * @param agentName      registry name of the CLI agent (e.g. {@code "claude-cli"}, {@code "opencode-cli"})
     * @param prompt         the fully-assembled prompt
     * @param timeoutSeconds timeout in seconds (0 = no timeout)
     * @return the completion text, or {@code null} if the agent is unavailable or the run failed
     */
    String run(String agentName, String prompt, int timeoutSeconds);
}
