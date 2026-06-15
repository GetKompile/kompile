/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.app.config.AppIndexConfig;
import ai.kompile.app.services.AppIndexConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight controller at {@code /api/config} serving the application identity
 * fields that the Angular frontend's {@code ConfigService} fetches on startup.
 *
 * <p>The canonical source for {@code appTitle} is the persisted
 * {@code app-index-config.json} (via {@link AppIndexConfigService}). If no
 * title has been persisted, falls back to the Spring property
 * {@code kompile.app.title} (set by {@code RagPomGenerator} during
 * {@code init-project}).</p>
 */
@RestController
@RequestMapping("/api/config")
@CrossOrigin(origins = "*")
public class FrontendConfigController {

    private final AppIndexConfigService configService;

    private String fallbackAppTitle = "Kompile RAG Console";

    @Value("${spring.application.name:kompile-rag-app}")
    private String applicationName;

    @Autowired
    public FrontendConfigController(
            @Autowired(required = false) AppIndexConfigService configService) {
        this.configService = configService;
    }

    /**
     * Returns the minimal config the frontend needs on startup:
     * {@code appTitle} and {@code applicationName}.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        String appTitle = fallbackAppTitle;
        if (configService != null) {
            AppIndexConfig config = configService.getActualConfiguration();
            if (config != null && config.getAppTitle() != null && !config.getAppTitle().isBlank()) {
                appTitle = config.getAppTitle();
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("appTitle", appTitle);
        response.put("applicationName", applicationName);
        return ResponseEntity.ok(response);
    }
}
