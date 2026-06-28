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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Managed configuration for guardrails.
 * Loaded from and persisted to {@code <dataDir>/config/guardrails-config.json}.
 * Defaults are preserved when the file is absent.
 */
@Component
public class GuardrailsProperties {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsProperties.class);
    private static final String CONFIG_FILENAME = "guardrails-config.json";

    @JsonIgnore
    private final Path configFilePath;

    @JsonIgnore
    private final ObjectMapper objectMapper;

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

    public GuardrailsProperties(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = JsonUtils.standardMapper();
        String effectiveDataDir = (dataDir == null || dataDir.isBlank())
                ? System.getProperty("user.home") + "/.kompile"
                : dataDir;
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        log.info("GuardrailsProperties initialized, config path: {}", configFilePath);
    }

    /**
     * Loads configuration from {@code guardrails-config.json} on startup.
     * When the file is absent the existing field defaults are kept; errors are
     * logged but never propagated.
     */
    @PostConstruct
    public void load() {
        if (!Files.exists(configFilePath)) {
            log.info("No guardrails config found at {} - using defaults", configFilePath);
            return;
        }
        try {
            objectMapper.readerForUpdating(this).readValue(configFilePath.toFile());
            log.info("Loaded guardrails config from {}", configFilePath);
        } catch (IOException e) {
            log.warn("Could not load guardrails config from {}: {} - using defaults",
                    configFilePath, e.getMessage());
        }
    }

    /**
     * Persists the current configuration as pretty-printed JSON, creating parent
     * directories as needed.
     */
    public void persist() {
        try {
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
            Files.writeString(configFilePath, json);
            log.info("Persisted guardrails config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist guardrails config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ── Getters / setters ────────────────────────────────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public InputConfig getInput() {
        return input;
    }

    public void setInput(InputConfig input) {
        this.input = input;
    }

    public OutputConfig getOutput() {
        return output;
    }

    public void setOutput(OutputConfig output) {
        this.output = output;
    }

    // ── Inner configuration types (unchanged public API) ─────────────────────────

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
