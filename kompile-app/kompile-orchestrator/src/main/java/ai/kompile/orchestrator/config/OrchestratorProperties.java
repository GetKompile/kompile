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
package ai.kompile.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the orchestrator.
 */
@Data
@ConfigurationProperties(prefix = "kompile.orchestrator")
public class OrchestratorProperties {

    /**
     * Whether the orchestrator is enabled.
     */
    private boolean enabled = true;

    /**
     * Interval for creating state snapshots.
     */
    private Duration snapshotInterval = Duration.ofSeconds(30);

    /**
     * Whether to attempt recovery on startup.
     */
    private boolean recoveryOnStartup = true;

    /**
     * Task configuration.
     */
    private TaskProperties task = new TaskProperties();

    /**
     * Workflow configuration.
     */
    private WorkflowProperties workflow = new WorkflowProperties();

    /**
     * LLM configuration.
     */
    private LlmProperties llm = new LlmProperties();

    /**
     * WebSocket configuration.
     */
    private WebSocketProperties websocket = new WebSocketProperties();

    @Data
    public static class TaskProperties {
        /**
         * Default timeout for tasks.
         */
        private Duration defaultTimeout = Duration.ofMinutes(5);

        /**
         * Maximum concurrent tasks.
         */
        private int maxConcurrent = 10;

        /**
         * Maximum output buffer size in bytes.
         */
        private long outputBufferSize = 10 * 1024 * 1024; // 10 MB

        /**
         * Whether to stream output by default.
         */
        private boolean streamOutput = true;
    }

    @Data
    public static class WorkflowProperties {
        /**
         * Maximum steps per workflow.
         */
        private int maxSteps = 50;

        /**
         * Whether to auto-advance by default.
         */
        private boolean autoAdvance = false;

        /**
         * Default LLM provider for workflows.
         */
        private String defaultLlmProvider;
    }

    @Data
    public static class LlmProperties {
        /**
         * Default LLM provider.
         */
        private String defaultProvider = "spring-ai";

        /**
         * Default timeout for LLM sessions.
         */
        private Duration defaultTimeout = Duration.ofMinutes(10);

        /**
         * Claude Code provider configuration.
         */
        private ClaudeCodeProperties claudeCode = new ClaudeCodeProperties();

        /**
         * Spring AI provider configuration.
         */
        private SpringAiProperties springAi = new SpringAiProperties();
    }

    @Data
    public static class ClaudeCodeProperties {
        /**
         * Whether Claude Code provider is enabled.
         */
        private boolean enabled = true;

        /**
         * Command to invoke Claude Code.
         */
        private String command = "claude";

        /**
         * Whether to skip permissions prompt.
         */
        private boolean skipPermissions = true;

        /**
         * Working directory for Claude Code.
         */
        private String workingDirectory;
    }

    @Data
    public static class SpringAiProperties {
        /**
         * Whether Spring AI provider is enabled.
         */
        private boolean enabled = true;
    }

    @Data
    public static class WebSocketProperties {
        /**
         * Whether WebSocket broadcasting is enabled.
         */
        private boolean enabled = true;

        /**
         * Heartbeat interval.
         */
        private Duration heartbeatInterval = Duration.ofSeconds(30);
    }
}
