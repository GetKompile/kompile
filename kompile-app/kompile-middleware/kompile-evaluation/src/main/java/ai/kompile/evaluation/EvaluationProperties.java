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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Managed-JSON config service for RAG evaluation settings.
 * Configuration is loaded from and persisted to
 * {@code <dataDir>/config/evaluation-config.json} rather than
 * application.properties.
 */
@Data
@Component
@JsonIgnoreProperties({"configFilePath", "objectMapper"})
public class EvaluationProperties {

    private static final Logger log = LoggerFactory.getLogger(EvaluationProperties.class);
    private static final String CONFIG_FILENAME = "evaluation-config.json";

    private Path configFilePath;
    private ObjectMapper objectMapper;

    @Autowired
    public EvaluationProperties(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.objectMapper = new ObjectMapper();
        log.info("EvaluationProperties initialized, config path: {}", configFilePath);
    }

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

    /**
     * Loads configuration from the JSON file if it exists.
     * Field defaults are preserved when the file is absent or unreadable.
     */
    @PostConstruct
    public void loadConfig() {
        if (!Files.exists(configFilePath)) {
            log.info("No evaluation config found at {} - using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            objectMapper.readerForUpdating(this).readValue(json);
            log.info("Loaded evaluation config from {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to load evaluation config from {}: {} - using defaults",
                    configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Persists current field values to the JSON config file.
     * Parent directories are created automatically.
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
            log.info("Persisted evaluation config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist evaluation config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

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
