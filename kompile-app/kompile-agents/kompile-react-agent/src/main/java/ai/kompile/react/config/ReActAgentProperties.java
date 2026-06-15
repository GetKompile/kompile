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
package ai.kompile.react.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the ReAct agent.
 */
@Data
@ConfigurationProperties(prefix = "kompile.react")
public class ReActAgentProperties {

    /**
     * Whether the ReAct agent is enabled.
     */
    private boolean enabled = true;

    /**
     * Default maximum number of reasoning steps.
     */
    private int maxSteps = 10;

    /**
     * Execution mode for tools: SEQUENTIAL or PARALLEL.
     */
    private ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;

    /**
     * Whether to use the GraphRag-enhanced reasoner.
     */
    private boolean graphRagEnabled = false;

    /**
     * Default search type for GraphRag: LOCAL or GLOBAL.
     */
    private String graphRagSearchType = "LOCAL";

    /**
     * Maximum results from GraphRag queries.
     */
    private int graphRagMaxResults = 10;

    /**
     * Whether to enable the filter chain integration.
     */
    private boolean filterChainEnabled = true;

    /**
     * Whether to summarize tool results in observations.
     */
    private boolean summarizeResults = true;

    /**
     * Maximum length of tool results before truncation.
     */
    private int maxResultLength = 2000;

    /**
     * System prompt for the default reasoner.
     */
    private String systemPrompt;

    /**
     * System prompt for summarization on max steps exceeded.
     */
    private String summarizePrompt;

    // ==================== Evaluation Configuration ====================

    /**
     * Whether to use the eval-based reasoner.
     */
    private boolean evalBasedEnabled = false;

    /**
     * Whether to enable eval tracking.
     */
    private boolean evalTrackingEnabled = true;

    /**
     * Whether to enable the evaluation hook.
     */
    private boolean evalHookEnabled = true;

    /**
     * Whether the reasoner should self-evaluate before final answers.
     */
    private boolean selfEvaluate = true;

    /**
     * Whether to evaluate reasoning steps (more expensive).
     */
    private boolean evaluateReasoning = false;

    /**
     * Quality threshold for passing evaluations (0.0 to 1.0).
     */
    private double qualityThreshold = 0.7;

    /**
     * Number of days to retain evaluation results.
     */
    private int evalRetentionDays = 30;

    /**
     * Execution mode enum.
     */
    public enum ExecutionMode {
        SEQUENTIAL,
        PARALLEL
    }
}
