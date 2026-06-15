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

import ai.kompile.app.sync.config.NoteSyncConfig;
import ai.kompile.app.sync.config.NoteSyncConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for managing bilateral sync configuration.
 * Configuration is persisted to ~/.kompile/config/note-sync-config.json
 */
@RestController
@RequestMapping("/api/sync/config")
public class NoteSyncConfigController {

    @Autowired
    private NoteSyncConfigService configService;

    @GetMapping
    public ResponseEntity<NoteSyncConfig> getConfig() {
        return ResponseEntity.ok(configService.getConfiguration());
    }

    @PutMapping
    public ResponseEntity<NoteSyncConfig> updateConfig(@RequestBody NoteSyncConfig update) {
        return ResponseEntity.ok(configService.updateConfiguration(update));
    }

    @PostMapping("/reset")
    public ResponseEntity<NoteSyncConfig> resetConfig() {
        return ResponseEntity.ok(configService.resetConfiguration());
    }
}
