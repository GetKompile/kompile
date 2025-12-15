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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for serving application configuration to the frontend.
 * Provides configurable UI settings like application title.
 */
@RestController
@RequestMapping("/api/config")
public class AppConfigController {

    @Value("${kompile.app.title:Kompile RAG Console}")
    private String appTitle;

    @Value("${spring.application.name:kompile-rag-app}")
    private String applicationName;

    /**
     * Get application configuration for the frontend.
     * Returns UI configuration settings including the application title.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAppConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("appTitle", appTitle);
        config.put("applicationName", applicationName);
        return ResponseEntity.ok(config);
    }

    /**
     * Get just the application title.
     */
    @GetMapping("/title")
    public ResponseEntity<Map<String, String>> getAppTitle() {
        return ResponseEntity.ok(Map.of("title", appTitle));
    }
}
