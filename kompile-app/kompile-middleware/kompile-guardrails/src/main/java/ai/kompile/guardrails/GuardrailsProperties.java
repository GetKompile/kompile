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

package ai.kompile.guardrails;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;

/**
 * Configuration properties for guardrails.
 */
@Data
@ConfigurationProperties(prefix = "kompile.guardrails")
public class GuardrailsProperties {

    /**
     * Whether guardrails are enabled.
     */
    private boolean enabled = false;

    /**
     * Maximum number of retries for output guardrails.
     */
    private int maxRetries = 2;

    /**
     * Input guardrail configuration.
     */
    private InputConfig input = new InputConfig();

    /**
     * Output guardrail configuration.
     */
    private OutputConfig output = new OutputConfig();

    @Data
    public static class InputConfig {
        /**
         * Prompt injection detection configuration.
         */
        private PromptInjectionConfig promptInjection = new PromptInjectionConfig();

        /**
         * Toxicity detection configuration.
         */
        private ToxicityConfig toxicity = new ToxicityConfig();

        /**
         * PII detection configuration.
         */
        private PiiConfig pii = new PiiConfig();

        /**
         * Topic guardrail configuration.
         */
        private TopicConfig topic = new TopicConfig();
    }

    @Data
    public static class OutputConfig {
        /**
         * Hallucination detection configuration.
         */
        private HallucinationConfig hallucination = new HallucinationConfig();

        /**
         * Format validation configuration.
         */
        private FormatConfig format = new FormatConfig();

        /**
         * Relevancy check configuration.
         */
        private RelevancyConfig relevancy = new RelevancyConfig();
    }

    @Data
    public static class PromptInjectionConfig {
        private boolean enabled = false;
        private double threshold = 0.7;
    }

    @Data
    public static class ToxicityConfig {
        private boolean enabled = false;
        private double threshold = 0.7;
        private Set<String> categories = new HashSet<>();
    }

    @Data
    public static class PiiConfig {
        private boolean enabled = false;
        private boolean detectEmail = true;
        private boolean detectPhone = true;
        private boolean detectSsn = true;
        private boolean detectCreditCard = true;
        private boolean blockOnDetection = true;
    }

    @Data
    public static class TopicConfig {
        private boolean enabled = false;
        private Set<String> allowedTopics = new HashSet<>();
        private Set<String> blockedTopics = new HashSet<>();
    }

    @Data
    public static class HallucinationConfig {
        private boolean enabled = false;
        private double threshold = 0.7;
        private boolean supportsRetry = true;
    }

    @Data
    public static class FormatConfig {
        private boolean enabled = false;
        private String expectedFormat;
        private int maxLength = 0;
        private int minLength = 0;
    }

    @Data
    public static class RelevancyConfig {
        private boolean enabled = false;
        private double threshold = 0.5;
        private boolean supportsRetry = true;
    }
}
