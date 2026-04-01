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

package ai.kompile.app.web.controllers;

import ai.kompile.core.guardrails.GuardrailService;
import ai.kompile.core.guardrails.InputGuardrail;
import ai.kompile.core.guardrails.OutputGuardrail;
import ai.kompile.guardrails.GuardrailsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST controller for managing guardrails configuration.
 */
@RestController
@RequestMapping("/api/guardrails")
@CrossOrigin(origins = "*")
public class GuardrailsConfigController {

    private static final Logger logger = LoggerFactory.getLogger(GuardrailsConfigController.class);

    private final GuardrailsProperties properties;
    private final GuardrailService guardrailService;

    @Autowired
    public GuardrailsConfigController(
            @Autowired(required = false) GuardrailsProperties properties,
            @Autowired(required = false) GuardrailService guardrailService
    ) {
        this.properties = properties;
        this.guardrailService = guardrailService;
    }

    /**
     * Get current guardrails configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        if (properties == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Guardrails module not loaded"
            ));
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("available", true);
        config.put("enabled", properties.isEnabled());
        config.put("maxRetries", properties.getMaxRetries());

        // Input guardrails config
        Map<String, Object> inputConfig = new LinkedHashMap<>();

        // Prompt injection
        Map<String, Object> promptInjection = new LinkedHashMap<>();
        promptInjection.put("enabled", properties.getInput().getPromptInjection().isEnabled());
        promptInjection.put("threshold", properties.getInput().getPromptInjection().getThreshold());
        inputConfig.put("promptInjection", promptInjection);

        // Toxicity
        Map<String, Object> toxicity = new LinkedHashMap<>();
        toxicity.put("enabled", properties.getInput().getToxicity().isEnabled());
        toxicity.put("threshold", properties.getInput().getToxicity().getThreshold());
        toxicity.put("categories", properties.getInput().getToxicity().getCategories());
        inputConfig.put("toxicity", toxicity);

        // PII
        Map<String, Object> pii = new LinkedHashMap<>();
        pii.put("enabled", properties.getInput().getPii().isEnabled());
        pii.put("detectEmail", properties.getInput().getPii().isDetectEmail());
        pii.put("detectPhone", properties.getInput().getPii().isDetectPhone());
        pii.put("detectSsn", properties.getInput().getPii().isDetectSsn());
        pii.put("detectCreditCard", properties.getInput().getPii().isDetectCreditCard());
        pii.put("blockOnDetection", properties.getInput().getPii().isBlockOnDetection());
        inputConfig.put("pii", pii);

        // Topic
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("enabled", properties.getInput().getTopic().isEnabled());
        topic.put("allowedTopics", properties.getInput().getTopic().getAllowedTopics());
        topic.put("blockedTopics", properties.getInput().getTopic().getBlockedTopics());
        inputConfig.put("topic", topic);

        config.put("input", inputConfig);

        // Output guardrails config
        Map<String, Object> outputConfig = new LinkedHashMap<>();

        // Hallucination
        Map<String, Object> hallucination = new LinkedHashMap<>();
        hallucination.put("enabled", properties.getOutput().getHallucination().isEnabled());
        hallucination.put("threshold", properties.getOutput().getHallucination().getThreshold());
        hallucination.put("supportsRetry", properties.getOutput().getHallucination().isSupportsRetry());
        outputConfig.put("hallucination", hallucination);

        // Format
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("enabled", properties.getOutput().getFormat().isEnabled());
        format.put("expectedFormat", properties.getOutput().getFormat().getExpectedFormat());
        format.put("maxLength", properties.getOutput().getFormat().getMaxLength());
        format.put("minLength", properties.getOutput().getFormat().getMinLength());
        outputConfig.put("format", format);

        // Relevancy
        Map<String, Object> relevancy = new LinkedHashMap<>();
        relevancy.put("enabled", properties.getOutput().getRelevancy().isEnabled());
        relevancy.put("threshold", properties.getOutput().getRelevancy().getThreshold());
        relevancy.put("supportsRetry", properties.getOutput().getRelevancy().isSupportsRetry());
        outputConfig.put("relevancy", relevancy);

        config.put("output", outputConfig);

        return ResponseEntity.ok(config);
    }

    /**
     * Update guardrails configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> request) {
        if (properties == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Guardrails module not loaded"
            ));
        }

        logger.info("Updating guardrails configuration: {}", request);

        // Update top-level settings
        if (request.containsKey("enabled")) {
            properties.setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("maxRetries")) {
            properties.setMaxRetries((Integer) request.get("maxRetries"));
        }

        // Update input configuration
        @SuppressWarnings("unchecked")
        Map<String, Object> inputConfig = (Map<String, Object>) request.get("input");
        if (inputConfig != null) {
            updateInputConfig(inputConfig);
        }

        // Update output configuration
        @SuppressWarnings("unchecked")
        Map<String, Object> outputConfig = (Map<String, Object>) request.get("output");
        if (outputConfig != null) {
            updateOutputConfig(outputConfig);
        }

        return getConfig();
    }

    @SuppressWarnings("unchecked")
    private void updateInputConfig(Map<String, Object> inputConfig) {
        // Prompt injection
        Map<String, Object> promptInjection = (Map<String, Object>) inputConfig.get("promptInjection");
        if (promptInjection != null) {
            if (promptInjection.containsKey("enabled")) {
                properties.getInput().getPromptInjection().setEnabled((Boolean) promptInjection.get("enabled"));
            }
            if (promptInjection.containsKey("threshold")) {
                properties.getInput().getPromptInjection().setThreshold(((Number) promptInjection.get("threshold")).doubleValue());
            }
        }

        // Toxicity
        Map<String, Object> toxicity = (Map<String, Object>) inputConfig.get("toxicity");
        if (toxicity != null) {
            if (toxicity.containsKey("enabled")) {
                properties.getInput().getToxicity().setEnabled((Boolean) toxicity.get("enabled"));
            }
            if (toxicity.containsKey("threshold")) {
                properties.getInput().getToxicity().setThreshold(((Number) toxicity.get("threshold")).doubleValue());
            }
            if (toxicity.containsKey("categories")) {
                properties.getInput().getToxicity().setCategories(new java.util.HashSet<>((List<String>) toxicity.get("categories")));
            }
        }

        // PII
        Map<String, Object> pii = (Map<String, Object>) inputConfig.get("pii");
        if (pii != null) {
            if (pii.containsKey("enabled")) {
                properties.getInput().getPii().setEnabled((Boolean) pii.get("enabled"));
            }
            if (pii.containsKey("detectEmail")) {
                properties.getInput().getPii().setDetectEmail((Boolean) pii.get("detectEmail"));
            }
            if (pii.containsKey("detectPhone")) {
                properties.getInput().getPii().setDetectPhone((Boolean) pii.get("detectPhone"));
            }
            if (pii.containsKey("detectSsn")) {
                properties.getInput().getPii().setDetectSsn((Boolean) pii.get("detectSsn"));
            }
            if (pii.containsKey("detectCreditCard")) {
                properties.getInput().getPii().setDetectCreditCard((Boolean) pii.get("detectCreditCard"));
            }
            if (pii.containsKey("blockOnDetection")) {
                properties.getInput().getPii().setBlockOnDetection((Boolean) pii.get("blockOnDetection"));
            }
        }

        // Topic
        Map<String, Object> topic = (Map<String, Object>) inputConfig.get("topic");
        if (topic != null) {
            if (topic.containsKey("enabled")) {
                properties.getInput().getTopic().setEnabled((Boolean) topic.get("enabled"));
            }
            if (topic.containsKey("allowedTopics")) {
                properties.getInput().getTopic().setAllowedTopics(new java.util.HashSet<>((List<String>) topic.get("allowedTopics")));
            }
            if (topic.containsKey("blockedTopics")) {
                properties.getInput().getTopic().setBlockedTopics(new java.util.HashSet<>((List<String>) topic.get("blockedTopics")));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateOutputConfig(Map<String, Object> outputConfig) {
        // Hallucination
        Map<String, Object> hallucination = (Map<String, Object>) outputConfig.get("hallucination");
        if (hallucination != null) {
            if (hallucination.containsKey("enabled")) {
                properties.getOutput().getHallucination().setEnabled((Boolean) hallucination.get("enabled"));
            }
            if (hallucination.containsKey("threshold")) {
                properties.getOutput().getHallucination().setThreshold(((Number) hallucination.get("threshold")).doubleValue());
            }
            if (hallucination.containsKey("supportsRetry")) {
                properties.getOutput().getHallucination().setSupportsRetry((Boolean) hallucination.get("supportsRetry"));
            }
        }

        // Format
        Map<String, Object> format = (Map<String, Object>) outputConfig.get("format");
        if (format != null) {
            if (format.containsKey("enabled")) {
                properties.getOutput().getFormat().setEnabled((Boolean) format.get("enabled"));
            }
            if (format.containsKey("expectedFormat")) {
                properties.getOutput().getFormat().setExpectedFormat((String) format.get("expectedFormat"));
            }
            if (format.containsKey("maxLength")) {
                properties.getOutput().getFormat().setMaxLength((Integer) format.get("maxLength"));
            }
            if (format.containsKey("minLength")) {
                properties.getOutput().getFormat().setMinLength((Integer) format.get("minLength"));
            }
        }

        // Relevancy
        Map<String, Object> relevancy = (Map<String, Object>) outputConfig.get("relevancy");
        if (relevancy != null) {
            if (relevancy.containsKey("enabled")) {
                properties.getOutput().getRelevancy().setEnabled((Boolean) relevancy.get("enabled"));
            }
            if (relevancy.containsKey("threshold")) {
                properties.getOutput().getRelevancy().setThreshold(((Number) relevancy.get("threshold")).doubleValue());
            }
            if (relevancy.containsKey("supportsRetry")) {
                properties.getOutput().getRelevancy().setSupportsRetry((Boolean) relevancy.get("supportsRetry"));
            }
        }
    }

    /**
     * Get list of available guardrails.
     */
    @GetMapping("/available")
    public ResponseEntity<Map<String, Object>> getAvailableGuardrails() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (guardrailService == null) {
            result.put("available", false);
            result.put("message", "Guardrail service not loaded");
            return ResponseEntity.ok(result);
        }

        result.put("available", true);

        // Input guardrails
        List<Map<String, Object>> inputGuardrails = guardrailService.getInputGuardrails().stream()
                .map(this::guardrailToMap)
                .collect(Collectors.toList());
        result.put("inputGuardrails", inputGuardrails);

        // Output guardrails
        List<Map<String, Object>> outputGuardrails = guardrailService.getOutputGuardrails().stream()
                .map(this::guardrailToMap)
                .collect(Collectors.toList());
        result.put("outputGuardrails", outputGuardrails);

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> guardrailToMap(InputGuardrail guardrail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", guardrail.getName());
        map.put("categories", guardrail.getCategories());
        map.put("priority", guardrail.getPriority());
        map.put("requiresLlm", guardrail.requiresLlm());
        return map;
    }

    private Map<String, Object> guardrailToMap(OutputGuardrail guardrail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", guardrail.getName());
        map.put("categories", guardrail.getCategories());
        map.put("priority", guardrail.getPriority());
        map.put("requiresLlm", guardrail.requiresLlm());
        map.put("supportsRetry", guardrail.supportsRetry());
        return map;
    }

    /**
     * Toggle guardrails on/off.
     */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleGuardrails(@RequestBody Map<String, Boolean> request) {
        if (properties == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Guardrails module not loaded"
            ));
        }

        Boolean enabled = request.get("enabled");
        if (enabled != null) {
            properties.setEnabled(enabled);
            logger.info("Guardrails {}", enabled ? "enabled" : "disabled");
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "enabled", properties.isEnabled()
        ));
    }
}
