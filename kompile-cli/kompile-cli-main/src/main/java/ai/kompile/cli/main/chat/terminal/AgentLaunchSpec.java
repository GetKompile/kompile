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

package ai.kompile.cli.main.chat.terminal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable description of how to launch an agent subprocess. Consumed by
 * {@link AgentProcess#start(AgentLaunchSpec)}.
 *
 * <p>The env base-key inherit list is shared across all spawn sites (design finding F5b:
 * {@code EmulatedPassthroughCommand}, {@code SubprocessAgentRunner} ×2). {@link AgentProcess}
 * implementations inherit those keys, apply terminal defaults when {@code dims} is present, then
 * layer {@code extraEnv} on top — so per-host specifics (enforcer env, Gemini trust flag,
 * system-prompt env) are expressed only as {@code extraEnv} entries.</p>
 *
 * @param command            the raw agent command (binary + args), before PTY wrapping
 * @param workingDir         process working directory
 * @param dims               PTY dimensions, or {@code null} to inherit the kernel-assigned size
 * @param extraEnv           environment overrides applied after the inherited base keys
 * @param redirectErrorStream whether to fold stderr into stdout (the CLI default)
 */
public record AgentLaunchSpec(
        List<String> command,
        String workingDir,
        PtyDims dims,
        Map<String, String> extraEnv,
        boolean redirectErrorStream) {

    /** Environment keys every agent subprocess inherits from the parent (shared across spawn sites). */
    public static final List<String> DEFAULT_INHERIT_ENV = List.of(
            "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
            "JAVA_HOME", "MAVEN_HOME", "TERM", "COLORTERM",
            "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY");

    public AgentLaunchSpec {
        extraEnv = extraEnv == null ? Map.of() : Map.copyOf(extraEnv);
    }

    public static Builder builder(List<String> command, String workingDir) {
        return new Builder(command, workingDir);
    }

    /** Fluent builder for the common case (dims + a handful of extra env entries). */
    public static final class Builder {
        private final List<String> command;
        private final String workingDir;
        private PtyDims dims;
        private final Map<String, String> extraEnv = new LinkedHashMap<>();
        private boolean redirectErrorStream = true;

        private Builder(List<String> command, String workingDir) {
            this.command = command;
            this.workingDir = workingDir;
        }

        public Builder dims(PtyDims dims) {
            this.dims = dims;
            return this;
        }

        public Builder env(String key, String value) {
            if (key != null && value != null) extraEnv.put(key, value);
            return this;
        }

        public Builder env(Map<String, String> values) {
            if (values != null) extraEnv.putAll(values);
            return this;
        }

        public Builder redirectErrorStream(boolean v) {
            this.redirectErrorStream = v;
            return this;
        }

        public AgentLaunchSpec build() {
            return new AgentLaunchSpec(command, workingDir, dims, extraEnv, redirectErrorStream);
        }
    }
}
