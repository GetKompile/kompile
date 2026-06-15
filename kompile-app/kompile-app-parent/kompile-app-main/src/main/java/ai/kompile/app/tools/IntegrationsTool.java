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

import ai.kompile.app.web.controllers.ConfluenceController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for external service integrations.
 * Exposes Confluence integration functionality.
 */
@Component
public class IntegrationsTool {

    private static final Logger logger = LoggerFactory.getLogger(IntegrationsTool.class);

    private final ConfluenceController confluenceController;

    @Autowired
    public IntegrationsTool(@Autowired(required = false) ConfluenceController confluenceController) {
        this.confluenceController = confluenceController;
    }

    public record GetConfluenceStatusInput() {}
    public record DisconnectConfluenceInput() {}
    public record ListConfluenceSpacesInput(Integer start, Integer limit) {}
    public record GetConfluenceSpaceInput(String spaceKey) {}
    public record ListConfluencePagesInput(String spaceKey, Integer start, Integer limit, String parentId) {}
    public record GetConfluencePageInput(String pageId) {}
    public record GetConfluencePageChildrenInput(String pageId, Integer start, Integer limit) {}
    public record SearchConfluencePagesInput(String cql, String spaceKey, String title, Integer start, Integer limit) {}
    public record IngestConfluenceSpaceInput(String spaceKey) {}

    @Tool(name = "get_confluence_status",
            description = "Gets the current Confluence connection status.")
    public Map<String, Object> getConfluenceStatus(GetConfluenceStatusInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            ResponseEntity<?> response = confluenceController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting Confluence status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "disconnect_confluence",
            description = "Disconnects from the Confluence instance.")
    public Map<String, Object> disconnectConfluence(DisconnectConfluenceInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            ResponseEntity<?> response = confluenceController.disconnect();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error disconnecting Confluence: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_confluence_spaces",
            description = "Lists accessible Confluence spaces with pagination.")
    public Map<String, Object> listConfluenceSpaces(ListConfluenceSpacesInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            int start = input.start() != null ? input.start() : 0;
            int limit = input.limit() != null ? input.limit() : 25;
            ResponseEntity<?> response = confluenceController.listSpaces(start, limit);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing Confluence spaces: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_confluence_space",
            description = "Gets a specific Confluence space by its key.")
    public Map<String, Object> getConfluenceSpace(GetConfluenceSpaceInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            if (input.spaceKey() == null) return Map.of("status", "error", "error", "Space key is required");
            ResponseEntity<?> response = confluenceController.getSpace(input.spaceKey());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting Confluence space: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_confluence_pages",
            description = "Lists pages in a Confluence space with pagination. Optionally filter by parent page ID.")
    public Map<String, Object> listConfluencePages(ListConfluencePagesInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            if (input.spaceKey() == null) return Map.of("status", "error", "error", "Space key is required");
            int start = input.start() != null ? input.start() : 0;
            int limit = input.limit() != null ? input.limit() : 25;
            ResponseEntity<?> response = confluenceController.listPages(input.spaceKey(), start, limit, input.parentId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing Confluence pages: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_confluence_page",
            description = "Gets a Confluence page with full content by its page ID.")
    public Map<String, Object> getConfluencePage(GetConfluencePageInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            if (input.pageId() == null) return Map.of("status", "error", "error", "Page ID is required");
            ResponseEntity<?> response = confluenceController.getPage(input.pageId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting Confluence page: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_confluence_page_children",
            description = "Gets child pages of a Confluence page.")
    public Map<String, Object> getConfluencePageChildren(GetConfluencePageChildrenInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            if (input.pageId() == null) return Map.of("status", "error", "error", "Page ID is required");
            int start = input.start() != null ? input.start() : 0;
            int limit = input.limit() != null ? input.limit() : 25;
            ResponseEntity<?> response = confluenceController.getPageChildren(input.pageId(), start, limit);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting Confluence page children: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "search_confluence_pages",
            description = "Searches Confluence pages using CQL or filters (spaceKey, title).")
    public Map<String, Object> searchConfluencePages(SearchConfluencePagesInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            int start = input.start() != null ? input.start() : 0;
            int limit = input.limit() != null ? input.limit() : 25;
            ResponseEntity<?> response = confluenceController.searchPages(input.cql(), input.spaceKey(), input.title(), null, start, limit);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error searching Confluence pages: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "ingest_confluence_space",
            description = "Ingests all pages from a Confluence space into the RAG system.")
    public Map<String, Object> ingestConfluenceSpace(IngestConfluenceSpaceInput input) {
        try {
            if (confluenceController == null) return Map.of("status", "error", "error", "Confluence integration not available");
            if (input.spaceKey() == null) return Map.of("status", "error", "error", "Space key is required");
            ResponseEntity<?> response = confluenceController.ingestSpace(input.spaceKey(), null);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error ingesting Confluence space: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
