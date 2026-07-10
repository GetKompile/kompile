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

package ai.kompile.staging.web;

import ai.kompile.staging.training.AbliterationService;
import ai.kompile.staging.web.dto.AbliterationRequest;
import ai.kompile.staging.web.dto.AbliterationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API controller for abliteration/model-edit workflows.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/abliteration")
@CrossOrigin(origins = "*")
public class AbliterationController {

    private static final Logger log = LoggerFactory.getLogger(AbliterationController.class);

    private final AbliterationService abliterationService;

    public AbliterationController(AbliterationService abliterationService) {
        this.abliterationService = abliterationService;
    }

    /**
     * Apply abliteration to a model using explicit directions or precomputed activations.
     */
    @PostMapping("/apply")
    public ResponseEntity<AbliterationResponse> apply(@RequestBody AbliterationRequest request) {
        if (request == null || request.getModelId() == null || request.getModelId().isBlank()) {
            return ResponseEntity.badRequest().body(AbliterationResponse.builder()
                    .success(false)
                    .error("modelId is required")
                    .build());
        }
        try {
            log.info("Applying abliteration to model: {}", request.getModelId());
            AbliterationResponse response = abliterationService.applyAbliteration(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to apply abliteration to model: {}", request.getModelId(), e);
            return ResponseEntity.internalServerError().body(AbliterationResponse.builder()
                    .success(false)
                    .error(e.getMessage())
                    .build());
        }
    }
}
