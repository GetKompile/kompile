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

package ai.kompile.notebook.tools;

import ai.kompile.notebook.domain.Notebook;
import ai.kompile.notebook.domain.NotebookSource;
import ai.kompile.notebook.service.NotebookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring AI @Tool bean for notebook operations.
 *
 * Registered with McpToolRegistry automatically because it is a @Component with @Tool methods.
 * Mirrors the REST API at /api/notebook so both the web UI and MCP agents can manage notebooks.
 */
@Component
public class NotebookTool {

    private static final Logger logger = LoggerFactory.getLogger(NotebookTool.class);

    private final NotebookService notebookService;

    @Autowired
    public NotebookTool(@Autowired(required = false) NotebookService notebookService) {
        this.notebookService = notebookService;
        logger.info("NotebookTool initialized");
    }

    public record ListNotebooksInput() {}
    public record GetNotebookInput(Long id) {}
    public record CreateNotebookInput(String name, String description, String icon, String color) {}
    public record UpdateNotebookInput(Long id, String name, String description, String icon, String color) {}
    public record ArchiveNotebookInput(Long id) {}
    public record DeleteNotebookInput(Long id) {}
    public record AddSourceInput(Long notebookId, Long factId, String displayName) {}
    public record RemoveSourceInput(Long notebookId, Long factId) {}
    public record ListSourcesInput(Long notebookId) {}

    @Tool(name = "list_notebooks",
          description = "Lists all non-archived research notebooks. Returns id, name, description, icon, color for each.")
    public Map<String, Object> listNotebooks(ListNotebooksInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            List<Notebook> notebooks = notebookService.listNotebooks();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", notebooks.size());
            result.put("notebooks", notebooks.stream().map(nb -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", nb.getId());
                m.put("name", nb.getName());
                m.put("description", nb.getDescription());
                m.put("icon", nb.getIcon());
                m.put("color", nb.getColor());
                m.put("archived", nb.getArchived());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing notebooks: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "get_notebook",
          description = "Gets a specific notebook by its ID with full details including source and note counts.")
    public Map<String, Object> getNotebook(GetNotebookInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            if (input.id() == null) return errorMap("id is required");
            return notebookService.getNotebook(input.id())
                    .map(nb -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "success");
                        result.put("id", nb.getId());
                        result.put("name", nb.getName());
                        result.put("description", nb.getDescription());
                        result.put("icon", nb.getIcon());
                        result.put("color", nb.getColor());
                        result.put("archived", nb.getArchived());
                        return result;
                    })
                    .orElse(errorMap("Notebook not found: " + input.id()));
        } catch (Exception e) {
            logger.error("Error getting notebook {}: {}", input.id(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "create_notebook",
          description = "Creates a new research notebook with a name, optional description, icon, and color.")
    public Map<String, Object> createNotebook(CreateNotebookInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            if (input.name() == null || input.name().isBlank()) return errorMap("name is required");
            Notebook nb = notebookService.createNotebook(input.name(), input.description(), input.icon(), input.color());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", nb.getId());
            result.put("name", nb.getName());
            result.put("message", "Notebook created successfully");
            return result;
        } catch (Exception e) {
            logger.error("Error creating notebook: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "update_notebook",
          description = "Updates a notebook's name, description, icon, or color by its ID.")
    public Map<String, Object> updateNotebook(UpdateNotebookInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            if (input.id() == null) return errorMap("id is required");
            Notebook nb = notebookService.updateNotebook(input.id(), input.name(), input.description(), input.icon(), input.color());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", nb.getId());
            result.put("name", nb.getName());
            result.put("message", "Notebook updated");
            return result;
        } catch (Exception e) {
            logger.error("Error updating notebook {}: {}", input.id(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "archive_notebook",
          description = "Archives a notebook by its ID. Archived notebooks are hidden from default views.")
    public Map<String, Object> archiveNotebook(ArchiveNotebookInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            if (input.id() == null) return errorMap("id is required");
            notebookService.archiveNotebook(input.id());
            return Map.of("status", "success", "message", "Notebook archived", "id", input.id());
        } catch (Exception e) {
            logger.error("Error archiving notebook {}: {}", input.id(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "delete_notebook",
          description = "Permanently deletes a notebook and all its notes. Sources (Facts) are not deleted.")
    public Map<String, Object> deleteNotebook(DeleteNotebookInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            if (input.id() == null) return errorMap("id is required");
            notebookService.deleteNotebook(input.id());
            return Map.of("status", "success", "message", "Notebook deleted", "id", input.id());
        } catch (Exception e) {
            logger.error("Error deleting notebook {}: {}", input.id(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "list_notebook_sources",
          description = "Lists all sources (documents) added to a notebook. Returns factId and displayName for each.")
    public Map<String, Object> listSources(ListSourcesInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            if (input.notebookId() == null) return errorMap("notebookId is required");
            List<NotebookSource> sources = notebookService.getSourcesForNotebook(input.notebookId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", sources.size());
            result.put("sources", sources.stream().map(ns -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ns.getId());
                m.put("factId", ns.getFactId());
                m.put("displayName", ns.getDisplayName());
                m.put("addedAt", ns.getAddedAt());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing sources for notebook {}: {}", input.notebookId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "add_source_to_notebook",
          description = "Adds an existing source document (by factId) to a notebook. A source can belong to multiple notebooks.")
    public Map<String, Object> addSource(AddSourceInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            if (input.notebookId() == null) return errorMap("notebookId is required");
            if (input.factId() == null) return errorMap("factId is required");
            NotebookSource ns = notebookService.addSource(input.notebookId(), input.factId(), input.displayName());
            return Map.of("status", "success", "id", ns.getId(), "factId", ns.getFactId(),
                    "message", "Source added to notebook");
        } catch (Exception e) {
            logger.error("Error adding source {} to notebook {}: {}", input.factId(), input.notebookId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "remove_source_from_notebook",
          description = "Removes a source document (by factId) from a notebook. Does not delete the underlying fact.")
    public Map<String, Object> removeSource(RemoveSourceInput input) {
        try {
            if (notebookService == null) return errorMap("NotebookService not available");
            if (input.notebookId() == null) return errorMap("notebookId is required");
            if (input.factId() == null) return errorMap("factId is required");
            notebookService.removeSource(input.notebookId(), input.factId());
            return Map.of("status", "success", "message", "Source removed from notebook");
        } catch (Exception e) {
            logger.error("Error removing source {} from notebook {}: {}", input.factId(), input.notebookId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    private Map<String, Object> errorMap(String message) {
        return Map.of("status", "error", "error", message);
    }
}
