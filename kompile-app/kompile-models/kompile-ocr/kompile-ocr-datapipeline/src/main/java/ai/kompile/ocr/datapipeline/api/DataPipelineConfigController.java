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

package ai.kompile.ocr.datapipeline.api;

import ai.kompile.ocr.datapipeline.config.DataPipelineConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing OCR data pipeline configurations.
 * Provides UI-accessible endpoints for CRUD operations on pipeline configs.
 */
@RestController
@RequestMapping("/api/ocr/pipeline-config")
public class DataPipelineConfigController {

    private final PipelineConfigStore configStore;

    public DataPipelineConfigController(PipelineConfigStore configStore) {
        this.configStore = configStore;
    }

    /**
     * Lists all configurations.
     */
    @GetMapping
    public List<DataPipelineConfig> listAll() {
        return configStore.list();
    }

    /**
     * Lists only preset configurations.
     */
    @GetMapping("/presets")
    public List<DataPipelineConfig> listPresets() {
        return configStore.listPresets();
    }

    /**
     * Lists only user-created configurations.
     */
    @GetMapping("/user")
    public List<DataPipelineConfig> listUserConfigs() {
        return configStore.listUserConfigs();
    }

    /**
     * Gets a configuration by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DataPipelineConfig> get(@PathVariable String id) {
        DataPipelineConfig config = configStore.get(id);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    /**
     * Creates a new configuration.
     */
    @PostMapping
    public ResponseEntity<DataPipelineConfig> create(@RequestBody DataPipelineConfig config) {
        try {
            DataPipelineConfig saved = configStore.save(config);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Updates an existing configuration.
     */
    @PutMapping("/{id}")
    public ResponseEntity<DataPipelineConfig> update(
            @PathVariable String id,
            @RequestBody DataPipelineConfig config
    ) {
        if (!configStore.exists(id)) {
            return ResponseEntity.notFound().build();
        }

        try {
            // Preserve the ID
            DataPipelineConfig updated = DataPipelineConfig.builder()
                    .id(id)
                    .name(config.getName())
                    .pipelineType(config.getPipelineType())
                    .preprocess(config.getPreprocess())
                    .outputParse(config.getOutputParse())
                    .entityIndex(config.getEntityIndex())
                    .custom(config.getCustom())
                    .build();

            DataPipelineConfig saved = configStore.save(updated);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(null);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Deletes a configuration.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        try {
            configStore.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Duplicates a configuration.
     */
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<DataPipelineConfig> duplicate(
            @PathVariable String id,
            @RequestParam(required = false) String name
    ) {
        try {
            DataPipelineConfig duplicated = configStore.duplicate(id, name);
            return ResponseEntity.status(HttpStatus.CREATED).body(duplicated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Gets a map of preset IDs to names for quick selection.
     */
    @GetMapping("/presets/summary")
    public Map<String, String> getPresetSummary() {
        return configStore.listPresets().stream()
                .collect(java.util.stream.Collectors.toMap(
                        DataPipelineConfig::getId,
                        DataPipelineConfig::getName
                ));
    }

    /**
     * Validates a configuration without saving.
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(@RequestBody DataPipelineConfig config) {
        ValidationResult result = validateConfig(config);
        return ResponseEntity.ok(result);
    }

    /**
     * Reloads all configurations from disk.
     */
    @PostMapping("/reload")
    public ResponseEntity<Void> reload() {
        configStore.reload();
        return ResponseEntity.ok().build();
    }

    /**
     * Validates a configuration.
     */
    private ValidationResult validateConfig(DataPipelineConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            return ValidationResult.failure("Name is required");
        }

        if (config.getPreprocess() == null) {
            return ValidationResult.failure("Preprocess configuration is required");
        }

        if (config.getOutputParse() == null) {
            return ValidationResult.failure("Output parse configuration is required");
        }

        if (config.getEntityIndex() == null) {
            return ValidationResult.failure("Entity index configuration is required");
        }

        if (config.getPreprocess().getDpi() < 72 || config.getPreprocess().getDpi() > 600) {
            return ValidationResult.failure("DPI must be between 72 and 600");
        }

        if (config.getPreprocess().getMaxImageHeight() < 100) {
            return ValidationResult.failure("Max image height must be at least 100");
        }

        if (config.getPreprocess().getMaxImageWidth() < 100) {
            return ValidationResult.failure("Max image width must be at least 100");
        }

        return ValidationResult.success();
    }

    /**
     * Validation result.
     */
    public record ValidationResult(boolean isValid, String message) {
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult failure(String message) {
            return new ValidationResult(false, message);
        }
    }
}
