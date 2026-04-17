/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

/**
 * Thrown when a subagent process exits due to rate limiting or token quota exhaustion.
 */
public class RateLimitException extends Exception {
    private final String agentName;
    private final String output;

    public RateLimitException(String agentName, String output) {
        super("Agent '" + agentName + "' was rate limited");
        this.agentName = agentName;
        this.output = output;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getOutput() {
        return output;
    }
}
