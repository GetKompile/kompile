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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.ConfluenceService;
import ai.kompile.app.web.dto.confluence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for Confluence integration.
 * Provides endpoints for connecting to Confluence, browsing spaces/pages, and ingesting content.
 */
@RestController
@RequestMapping("/api/confluence")
public class ConfluenceController {

    private static final Logger logger = LoggerFactory.getLogger(ConfluenceController.class);

    private final ConfluenceService confluenceService;

    public ConfluenceController(ConfluenceService confluenceService) {
        this.confluenceService = confluenceService;
    }

    /**
     * Get the current Confluence connection status.
     */
    @GetMapping("/status")
    public ResponseEntity<ConfluenceConnectionStatus> getStatus() {
        return ResponseEntity.ok(confluenceService.getConnectionStatus());
    }

    /**
     * Connect to a Confluence instance.
     */
    @PostMapping("/connect")
    public ResponseEntity<ConfluenceConnectionStatus> connect(@RequestBody ConfluenceConnectionConfig config) {
        logger.info("Received request to connect to Confluence: {}", config.getBaseUrl());
        ConfluenceConnectionStatus status = confluenceService.connect(config);
        return status.isConnected()
                ? ResponseEntity.ok(status)
                : ResponseEntity.badRequest().body(status);
    }

    /**
     * Disconnect from Confluence.
     */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Boolean>> disconnect() {
        confluenceService.disconnect();
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * List all accessible spaces.
     */
    @GetMapping("/spaces")
    public ResponseEntity<ConfluenceSpaceListResponse> listSpaces(
            @RequestParam(required = false) Integer start,
            @RequestParam(required = false) Integer limit) {
        try {
            return ResponseEntity.ok(confluenceService.listSpaces(start, limit));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Failed to list spaces", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get a specific space by key.
     */
    @GetMapping("/spaces/{spaceKey}")
    public ResponseEntity<ConfluenceSpace> getSpace(@PathVariable String spaceKey) {
        try {
            ConfluenceSpace space = confluenceService.getSpace(spaceKey);
            return space != null
                    ? ResponseEntity.ok(space)
                    : ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Failed to get space: {}", spaceKey, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * List pages in a space.
     */
    @GetMapping("/spaces/{spaceKey}/pages")
    public ResponseEntity<ConfluencePageListResponse> listPages(
            @PathVariable String spaceKey,
            @RequestParam(required = false) Integer start,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String parentId) {
        try {
            return ResponseEntity.ok(confluenceService.listPages(spaceKey, start, limit, parentId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Failed to list pages in space: {}", spaceKey, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Search pages with CQL or filters.
     */
    @GetMapping("/search")
    public ResponseEntity<ConfluencePageListResponse> searchPages(
            @RequestParam(required = false) String cql,
            @RequestParam(required = false) String spaceKey,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer start,
            @RequestParam(required = false) Integer limit) {
        try {
            return ResponseEntity.ok(confluenceService.searchPages(cql, spaceKey, title, type, start, limit));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Failed to search pages", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get a page by ID with full content.
     */
    @GetMapping("/pages/{pageId}")
    public ResponseEntity<ConfluencePage> getPage(@PathVariable String pageId) {
        try {
            ConfluencePage page = confluenceService.getPage(pageId);
            return page != null
                    ? ResponseEntity.ok(page)
                    : ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Failed to get page: {}", pageId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get children of a page.
     */
    @GetMapping("/pages/{pageId}/children")
    public ResponseEntity<ConfluencePageListResponse> getPageChildren(
            @PathVariable String pageId,
            @RequestParam(required = false) Integer start,
            @RequestParam(required = false) Integer limit) {
        try {
            // Use parent ID in listPages
            ConfluencePage page = confluenceService.getPage(pageId);
            if (page == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(confluenceService.listPages(page.getSpaceKey(), start, limit, pageId));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            logger.error("Failed to get page children: {}", pageId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Ingest selected pages.
     */
    @PostMapping("/ingest")
    public ResponseEntity<ConfluenceIngestResponse> ingestPages(@RequestBody ConfluenceIngestRequest request) {
        try {
            logger.info("Received request to ingest {} page IDs and {} space keys",
                    request.getPageIds() != null ? request.getPageIds().size() : 0,
                    request.getSpaceKeys() != null ? request.getSpaceKeys().size() : 0);

            ConfluenceIngestResponse response = confluenceService.ingestPages(request);
            return response.getSuccess()
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.badRequest().body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401)
                    .body(ConfluenceIngestResponse.builder()
                            .success(false)
                            .errorMessage("Not connected to Confluence")
                            .build());
        } catch (Exception e) {
            logger.error("Failed to ingest pages", e);
            return ResponseEntity.internalServerError()
                    .body(ConfluenceIngestResponse.builder()
                            .success(false)
                            .errorMessage("Internal server error: " + e.getMessage())
                            .build());
        }
    }

    /**
     * Ingest all pages from a space.
     */
    @PostMapping("/spaces/{spaceKey}/ingest")
    public ResponseEntity<ConfluenceIngestResponse> ingestSpace(
            @PathVariable String spaceKey,
            @RequestBody(required = false) ConfluenceIngestRequest options) {
        try {
            logger.info("Received request to ingest space: {}", spaceKey);

            ConfluenceIngestRequest request = options != null ? options : ConfluenceIngestRequest.builder().build();
            request.setSpaceKeys(java.util.List.of(spaceKey));

            ConfluenceIngestResponse response = confluenceService.ingestPages(request);
            return response.getSuccess()
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.badRequest().body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401)
                    .body(ConfluenceIngestResponse.builder()
                            .success(false)
                            .errorMessage("Not connected to Confluence")
                            .build());
        } catch (Exception e) {
            logger.error("Failed to ingest space: {}", spaceKey, e);
            return ResponseEntity.internalServerError()
                    .body(ConfluenceIngestResponse.builder()
                            .success(false)
                            .errorMessage("Internal server error: " + e.getMessage())
                            .build());
        }
    }
}
