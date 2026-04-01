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

import ai.kompile.app.web.controllers.KarchArchiveController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ArchiveTool {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveTool.class);

    private final KarchArchiveController archiveController;

    @Autowired
    public ArchiveTool(@Autowired(required = false) KarchArchiveController archiveController) {
        this.archiveController = archiveController;
        logger.info("ArchiveTool initialized");
    }

    public record ListArchivesInput() {}
    public record GetArchiveStatusInput() {}
    public record ListArchiveModelsInput(String type) {}

    @Tool(name = "list_archives",
            description = "Lists all available .karch archives that can be loaded.")
    public Map<String, Object> listArchives(ListArchivesInput input) {
        try {
            if (archiveController == null) return Map.of("status", "error", "error", "KarchArchiveController not available");
            var response = archiveController.listArchives();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("archives", response.getBody());
            return result;
        } catch (Exception e) {
            logger.error("Error listing archives: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_archive_status",
            description = "Gets the status of the currently loaded archive including loaded models and paths.")
    public Map<String, Object> getArchiveStatus(GetArchiveStatusInput input) {
        try {
            if (archiveController == null) return Map.of("status", "error", "error", "KarchArchiveController not available");
            var response = archiveController.getStatus();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("archiveStatus", response.getBody());
            return result;
        } catch (Exception e) {
            logger.error("Error getting archive status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_archive_models",
            description = "Lists models available in the currently loaded archive. Optionally filter by type (e.g. 'dense_encoder', 'cross_encoder').")
    public Map<String, Object> listArchiveModels(ListArchiveModelsInput input) {
        try {
            if (archiveController == null) return Map.of("status", "error", "error", "KarchArchiveController not available");
            ResponseEntity<?> response;
            if (input.type() != null) {
                response = archiveController.getModelsByType(input.type());
            } else {
                response = archiveController.getModels();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("models", response.getBody());
            return result;
        } catch (Exception e) {
            logger.error("Error listing archive models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
