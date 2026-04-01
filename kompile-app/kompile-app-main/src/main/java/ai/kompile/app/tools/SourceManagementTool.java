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

import ai.kompile.app.web.controllers.SourceViewerController;
import ai.kompile.app.web.controllers.SourceProviderController;
import ai.kompile.app.web.controllers.DocumentDebuggerController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for source document management and debugging.
 * Exposes source viewing, source providers, and document debugging functionality.
 */
@Component
public class SourceManagementTool {

    private static final Logger logger = LoggerFactory.getLogger(SourceManagementTool.class);

    private final SourceViewerController sourceViewerController;
    private final SourceProviderController sourceProviderController;
    private final DocumentDebuggerController documentDebuggerController;

    @Autowired
    public SourceManagementTool(
            @Autowired(required = false) SourceViewerController sourceViewerController,
            @Autowired(required = false) SourceProviderController sourceProviderController,
            @Autowired(required = false) DocumentDebuggerController documentDebuggerController) {
        this.sourceViewerController = sourceViewerController;
        this.sourceProviderController = sourceProviderController;
        this.documentDebuggerController = documentDebuggerController;
    }

    public record ListSourcesInput(Integer limit, Integer offset) {}
    public record GetSourceInfoInput(String fileName) {}
    public record GetSourceTextContentInput(String fileName, Integer maxLines) {}
    public record GetSupportedSourceTypesInput() {}
    public record GetSourceProvidersInput(Boolean includeUnavailable) {}
    public record GetSourceProvidersByCategoryInput() {}
    public record GetSourceProviderInput(String providerId) {}
    public record GetSourceProviderCategoriesInput() {}

    // === Source Viewer ===

    @Tool(name = "list_document_sources",
            description = "Lists available stored document sources with pagination.")
    public Map<String, Object> listSources(ListSourcesInput input) {
        try {
            if (sourceViewerController == null) return Map.of("status", "error", "error", "Source viewer not available");
            int limit = input.limit() != null ? input.limit() : 50;
            int offset = input.offset() != null ? input.offset() : 0;
            ResponseEntity<?> response = sourceViewerController.listSources(limit, offset);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing sources: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_source_info",
            description = "Gets metadata about a specific source file by name.")
    public Map<String, Object> getSourceInfo(GetSourceInfoInput input) {
        try {
            if (sourceViewerController == null) return Map.of("status", "error", "error", "Source viewer not available");
            if (input.fileName() == null) return Map.of("status", "error", "error", "File name is required");
            ResponseEntity<?> response = sourceViewerController.getSourceInfo(input.fileName());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting source info: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_source_text_content",
            description = "Gets text content of a source file for inline viewing. Optionally limit the number of lines returned.")
    public Map<String, Object> getSourceTextContent(GetSourceTextContentInput input) {
        try {
            if (sourceViewerController == null) return Map.of("status", "error", "error", "Source viewer not available");
            if (input.fileName() == null) return Map.of("status", "error", "error", "File name is required");
            int maxLines = input.maxLines() != null ? input.maxLines() : 500;
            ResponseEntity<?> response = sourceViewerController.getTextContent(input.fileName(), maxLines, null);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting source text content: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_supported_source_types",
            description = "Gets supported file extensions and view modes for source documents.")
    public Map<String, Object> getSupportedSourceTypes(GetSupportedSourceTypesInput input) {
        try {
            if (sourceViewerController == null) return Map.of("status", "error", "error", "Source viewer not available");
            ResponseEntity<?> response = sourceViewerController.getSupportedTypes();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting supported source types: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Source Providers ===

    @Tool(name = "get_source_providers",
            description = "Gets all available source providers. Set includeUnavailable=true to include unavailable providers.")
    public Map<String, Object> getSourceProviders(GetSourceProvidersInput input) {
        try {
            if (sourceProviderController == null) return Map.of("status", "error", "error", "Source provider registry not available");
            boolean includeUnavailable = input.includeUnavailable() != null && input.includeUnavailable();
            ResponseEntity<?> response = sourceProviderController.getSourceProviders(includeUnavailable);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting source providers: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_source_providers_by_category",
            description = "Gets source providers organized by category (file upload, cloud storage, web, etc.).")
    public Map<String, Object> getSourceProvidersByCategory(GetSourceProvidersByCategoryInput input) {
        try {
            if (sourceProviderController == null) return Map.of("status", "error", "error", "Source provider registry not available");
            ResponseEntity<?> response = sourceProviderController.getProvidersByCategory();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting source providers by category: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_source_provider",
            description = "Gets detailed information about a specific source provider by ID.")
    public Map<String, Object> getSourceProvider(GetSourceProviderInput input) {
        try {
            if (sourceProviderController == null) return Map.of("status", "error", "error", "Source provider registry not available");
            if (input.providerId() == null) return Map.of("status", "error", "error", "Provider ID is required");
            ResponseEntity<?> response = sourceProviderController.getProvider(input.providerId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting source provider: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_source_provider_categories",
            description = "Gets category metadata including display names, icons, and ordering.")
    public Map<String, Object> getSourceProviderCategories(GetSourceProviderCategoriesInput input) {
        try {
            if (sourceProviderController == null) return Map.of("status", "error", "error", "Source provider registry not available");
            ResponseEntity<?> response = sourceProviderController.getCategories();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting source provider categories: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
