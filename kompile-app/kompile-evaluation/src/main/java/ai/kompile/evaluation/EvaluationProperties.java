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

package ai.kompile.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for RAG evaluation.
 */
@Data
@ConfigurationProperties(prefix = "kompile.evaluation")
public class EvaluationProperties {

    /**
     * Whether evaluation is enabled.
     */
    private boolean enabled = false;

    /**
     * Whether to run evaluations asynchronously.
     */
    private boolean async = true;

    /**
     * Default threshold for pass/fail determination.
     */
    private double defaultThreshold = 0.5;

    /**
     * Relevancy evaluation configuration.
     */
    private EvaluatorConfig relevancy = new EvaluatorConfig();

    /**
     * Faithfulness evaluation configuration.
     */
    private EvaluatorConfig faithfulness = new EvaluatorConfig();

    /**
     * Answer correctness evaluation configuration.
     */
    private AnswerCorrectnessConfig answerCorrectness = new AnswerCorrectnessConfig();

    /**
     * Context relevancy evaluation configuration.
     */
    private EvaluatorConfig contextRelevancy = new EvaluatorConfig();

    /**
     * Hallucination detection configuration.
     */
    private EvaluatorConfig hallucination = new EvaluatorConfig();

    /**
     * Entity presence evaluation configuration.
     */
    private EvaluatorConfig entityPresence = new EvaluatorConfig();

    /**
     * Entity type accuracy evaluation configuration.
     */
    private EvaluatorConfig entityTypeAccuracy = new EvaluatorConfig();

    /**
     * Graph completeness evaluation configuration.
     */
    private EvaluatorConfig graphCompleteness = new EvaluatorConfig();

    /**
     * Relationship presence evaluation configuration.
     */
    private EvaluatorConfig relationshipPresence = new EvaluatorConfig();

    @Data
    public static class EvaluatorConfig {
        private boolean enabled = false;
        private double threshold = 0.5;
    }

    @Data
    public static class AnswerCorrectnessConfig extends EvaluatorConfig {
        /**
         * Weight for semantic similarity component (0.0 to 1.0).
         */
        private double semanticWeight = 0.5;

        /**
         * Weight for factual correctness component (0.0 to 1.0).
         */
        private double factualWeight = 0.5;
    }
}
