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

package ai.kompile.app.tools;

import ai.kompile.app.services.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class BackupTool {

    private static final Logger logger = LoggerFactory.getLogger(BackupTool.class);

    private final BackupService backupService;

    @Autowired
    public BackupTool(@Autowired(required = false) BackupService backupService) {
        this.backupService = backupService;
        logger.info("BackupTool initialized");
    }

    public record TriggerBackupInput() {}

    @Tool(name = "trigger_backup",
            description = "Triggers an immediate backup of all application data including indexes, fact sheets, and configuration.")
    public Map<String, Object> triggerBackup(TriggerBackupInput input) {
        try {
            if (backupService == null) return Map.of("status", "error", "error", "BackupService not available (is kompile.backup.enabled=true?)");
            var result = backupService.triggerBackup();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("result", result);
            return response;
        } catch (Exception e) {
            logger.error("Error triggering backup: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
