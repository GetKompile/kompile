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

import ai.kompile.app.facts.service.FactSheetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class FactSheetTool {

    private static final Logger logger = LoggerFactory.getLogger(FactSheetTool.class);

    private final FactSheetService factSheetService;

    @Autowired
    public FactSheetTool(@Autowired(required = false) FactSheetService factSheetService) {
        this.factSheetService = factSheetService;
        logger.info("FactSheetTool initialized");
    }

    public record ListFactSheetsInput() {}
    public record GetActiveFactSheetInput() {}
    public record GetFactSheetInput(Long id) {}
    public record CreateFactSheetInput(String name, String description, String color, String icon) {}
    public record ActivateFactSheetInput(Long id) {}
    public record DeleteFactSheetInput(Long id) {}
    public record UpdateFactSheetInput(Long id, String name, String description, String color, String icon) {}
    public record DeriveFactSheetInput(Long sourceSheetId, String newName, String description) {}

    @Tool(name = "list_fact_sheets",
            description = "Lists all fact sheets in the system. Returns id, name, description, color, icon, and active status for each.")
    public Map<String, Object> listFactSheets(ListFactSheetsInput input) {
        try {
            if (factSheetService == null) return Map.of("status", "error", "error", "FactSheetService not available");
            var sheets = factSheetService.getAllSheets();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", sheets.size());
            result.put("sheets", sheets.stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("name", s.getName());
                m.put("description", s.getDescription());
                m.put("color", s.getColor());
                m.put("icon", s.getIcon());
                m.put("active", s.getIsActive());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing fact sheets: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_active_fact_sheet",
            description = "Gets the currently active fact sheet with all its details including index paths and embedding configuration.")
    public Map<String, Object> getActiveFactSheet(GetActiveFactSheetInput input) {
        try {
            if (factSheetService == null) return Map.of("status", "error", "error", "FactSheetService not available");
            var sheet = factSheetService.getActiveSheet();
            if (sheet == null) return Map.of("status", "error", "error", "No active fact sheet found");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", sheet.getId());
            result.put("name", sheet.getName());
            result.put("description", sheet.getDescription());
            result.put("color", sheet.getColor());
            result.put("icon", sheet.getIcon());
            result.put("active", sheet.getIsActive());
            return result;
        } catch (Exception e) {
            logger.error("Error getting active fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_fact_sheet",
            description = "Gets a specific fact sheet by its ID with full details.")
    public Map<String, Object> getFactSheet(GetFactSheetInput input) {
        try {
            if (factSheetService == null) return Map.of("status", "error", "error", "FactSheetService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            var sheet = factSheetService.getSheetById(input.id());
            if (sheet.isEmpty()) return Map.of("status", "error", "error", "Fact sheet not found: " + input.id());
            var s = sheet.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", s.getId());
            result.put("name", s.getName());
            result.put("description", s.getDescription());
            result.put("color", s.getColor());
            result.put("icon", s.getIcon());
            result.put("active", s.getIsActive());
            return result;
        } catch (Exception e) {
            logger.error("Error getting fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "create_fact_sheet",
            description = "Creates a new fact sheet with a name, description, color, and icon. The new sheet is not active by default.")
    public Map<String, Object> createFactSheet(CreateFactSheetInput input) {
        try {
            if (factSheetService == null) return Map.of("status", "error", "error", "FactSheetService not available");
            if (input.name() == null || input.name().isBlank()) return Map.of("status", "error", "error", "name is required");
            var sheet = factSheetService.createSheet(input.name(), input.description(), input.color(), input.icon());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", sheet.getId());
            result.put("name", sheet.getName());
            result.put("message", "Fact sheet created successfully");
            return result;
        } catch (Exception e) {
            logger.error("Error creating fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "activate_fact_sheet",
            description = "Activates a fact sheet by its ID, making it the current active sheet. The previously active sheet is deactivated.")
    public Map<String, Object> activateFactSheet(ActivateFactSheetInput input) {
        try {
            if (factSheetService == null) return Map.of("status", "error", "error", "FactSheetService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            var activated = factSheetService.activateSheet(input.id());
            return Map.of("status", "success", "message", "Fact sheet activated", "id", activated.getId());
        } catch (Exception e) {
            logger.error("Error activating fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_fact_sheet",
            description = "Deletes a fact sheet by its ID. Cannot delete the active fact sheet.")
    public Map<String, Object> deleteFactSheet(DeleteFactSheetInput input) {
        try {
            if (factSheetService == null) return Map.of("status", "error", "error", "FactSheetService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            factSheetService.deleteSheet(input.id());
            return Map.of("status", "success", "message", "Fact sheet deleted", "id", input.id());
        } catch (Exception e) {
            logger.error("Error deleting fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "update_fact_sheet",
            description = "Updates a fact sheet's name, description, color, or icon by its ID.")
    public Map<String, Object> updateFactSheet(UpdateFactSheetInput input) {
        try {
            if (factSheetService == null) return Map.of("status", "error", "error", "FactSheetService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            var sheet = factSheetService.updateSheet(input.id(), input.name(), input.description(), input.color(), input.icon());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", sheet.getId());
            result.put("name", sheet.getName());
            result.put("message", "Fact sheet updated");
            return result;
        } catch (Exception e) {
            logger.error("Error updating fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "derive_fact_sheet",
            description = "Creates a new fact sheet derived from an existing one. Copies configuration from the source sheet.")
    public Map<String, Object> deriveFactSheet(DeriveFactSheetInput input) {
        try {
            if (factSheetService == null) return Map.of("status", "error", "error", "FactSheetService not available");
            if (input.sourceSheetId() == null) return Map.of("status", "error", "error", "sourceSheetId is required");
            if (input.newName() == null || input.newName().isBlank()) return Map.of("status", "error", "error", "newName is required");
            var sheet = factSheetService.deriveSheet(input.sourceSheetId(), input.newName(), input.description());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", sheet.getId());
            result.put("name", sheet.getName());
            result.put("message", "Fact sheet derived successfully");
            return result;
        } catch (Exception e) {
            logger.error("Error deriving fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
