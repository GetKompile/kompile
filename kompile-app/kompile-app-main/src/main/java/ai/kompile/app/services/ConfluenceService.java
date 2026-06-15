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

package ai.kompile.app.services;

import ai.kompile.app.web.dto.confluence.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for interacting with Confluence REST API.
 * Supports both Confluence Cloud and Server/Data Center.
 */
@Service
public class ConfluenceService {

    private static final Logger logger = LoggerFactory.getLogger(ConfluenceService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final DocumentIngestService documentIngestService;

    // Connection state (session-based, in-memory)
    private volatile ConfluenceConnectionConfig currentConfig;
    private volatile ConfluenceConnectionStatus currentStatus;

    public ConfluenceService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            DocumentIngestService documentIngestService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.documentIngestService = documentIngestService;
        this.currentStatus = ConfluenceConnectionStatus.builder()
                .connected(false)
                .build();
    }

    /**
     * Get the current connection status.
     */
    public ConfluenceConnectionStatus getConnectionStatus() {
        return currentStatus;
    }

    /**
     * Connect to a Confluence instance.
     */
    public ConfluenceConnectionStatus connect(ConfluenceConnectionConfig config) {
        logger.info("Attempting to connect to Confluence at: {}", config.getBaseUrl());

        try {
            // Normalize the base URL
            String baseUrl = normalizeBaseUrl(config.getBaseUrl());
            config.setBaseUrl(baseUrl);

            // Test the connection by fetching the current user
            HttpHeaders headers = createAuthHeaders(config);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Try to get current user info
            String userUrl = baseUrl + "/rest/api/user/current";
            ResponseEntity<String> response = restTemplate.exchange(
                    userUrl,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode userNode = objectMapper.readTree(response.getBody());

                String displayName = userNode.has("displayName")
                        ? userNode.get("displayName").asText()
                        : userNode.has("publicName")
                        ? userNode.get("publicName").asText()
                        : config.getEmail();

                // Determine deployment type
                String deploymentType = detectDeploymentType(baseUrl, headers);

                this.currentConfig = config;
                this.currentStatus = ConfluenceConnectionStatus.builder()
                        .connected(true)
                        .baseUrl(baseUrl)
                        .username(config.getEmail())
                        .displayName(displayName)
                        .deploymentType(deploymentType)
                        .build();

                logger.info("Successfully connected to Confluence as: {}", displayName);
                return currentStatus;
            }
        } catch (HttpClientErrorException e) {
            logger.error("Failed to connect to Confluence: {} - {}", e.getStatusCode(), e.getMessage());
            this.currentStatus = ConfluenceConnectionStatus.builder()
                    .connected(false)
                    .errorMessage("Authentication failed: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            logger.error("Failed to connect to Confluence", e);
            this.currentStatus = ConfluenceConnectionStatus.builder()
                    .connected(false)
                    .errorMessage("Connection failed: " + e.getMessage())
                    .build();
        }

        return currentStatus;
    }

    /**
     * Disconnect from Confluence.
     */
    public void disconnect() {
        this.currentConfig = null;
        this.currentStatus = ConfluenceConnectionStatus.builder()
                .connected(false)
                .build();
        logger.info("Disconnected from Confluence");
    }

    /**
     * List all accessible spaces.
     */
    public ConfluenceSpaceListResponse listSpaces(Integer start, Integer limit) {
        ensureConnected();

        String url = UriComponentsBuilder.fromHttpUrl(currentConfig.getBaseUrl())
                .path("/rest/api/space")
                .queryParamIfPresent("start", Optional.ofNullable(start))
                .queryParamIfPresent("limit", Optional.ofNullable(limit != null ? limit : 25))
                .queryParam("expand", "description.plain,icon")
                .toUriString();

        try {
            HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(currentConfig));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                List<ConfluenceSpace> spaces = new ArrayList<>();

                JsonNode results = root.get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode spaceNode : results) {
                        spaces.add(parseSpace(spaceNode));
                    }
                }

                return ConfluenceSpaceListResponse.builder()
                        .spaces(spaces)
                        .start(root.has("start") ? root.get("start").asInt() : 0)
                        .limit(root.has("limit") ? root.get("limit").asInt() : 25)
                        .size(root.has("size") ? root.get("size").asInt() : spaces.size())
                        .totalSize(root.has("totalSize") ? root.get("totalSize").asInt() : null)
                        .build();
            }
        } catch (Exception e) {
            logger.error("Failed to list spaces", e);
            throw new RuntimeException("Failed to list spaces: " + e.getMessage(), e);
        }

        return ConfluenceSpaceListResponse.builder()
                .spaces(Collections.emptyList())
                .start(0)
                .limit(25)
                .size(0)
                .build();
    }

    /**
     * Get a specific space by key.
     */
    public ConfluenceSpace getSpace(String spaceKey) {
        ensureConnected();

        String url = UriComponentsBuilder.fromHttpUrl(currentConfig.getBaseUrl())
                .path("/rest/api/space/" + spaceKey)
                .queryParam("expand", "description.plain,icon")
                .toUriString();

        try {
            HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(currentConfig));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode spaceNode = objectMapper.readTree(response.getBody());
                return parseSpace(spaceNode);
            }
        } catch (Exception e) {
            logger.error("Failed to get space: {}", spaceKey, e);
            throw new RuntimeException("Failed to get space: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * List pages in a space.
     */
    public ConfluencePageListResponse listPages(String spaceKey, Integer start, Integer limit, String parentId) {
        ensureConnected();

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(currentConfig.getBaseUrl())
                .path("/rest/api/content")
                .queryParam("type", "page")
                .queryParam("spaceKey", spaceKey)
                .queryParamIfPresent("start", Optional.ofNullable(start))
                .queryParamIfPresent("limit", Optional.ofNullable(limit != null ? limit : 25))
                .queryParam("expand", "ancestors,version,children.page");

        String url = builder.toUriString();

        try {
            HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(currentConfig));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                List<ConfluencePage> pages = new ArrayList<>();

                JsonNode results = root.get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode pageNode : results) {
                        pages.add(parsePage(pageNode, spaceKey));
                    }
                }

                return ConfluencePageListResponse.builder()
                        .pages(pages)
                        .start(root.has("start") ? root.get("start").asInt() : 0)
                        .limit(root.has("limit") ? root.get("limit").asInt() : 25)
                        .size(root.has("size") ? root.get("size").asInt() : pages.size())
                        .totalSize(root.has("totalSize") ? root.get("totalSize").asInt() : null)
                        .build();
            }
        } catch (Exception e) {
            logger.error("Failed to list pages in space: {}", sanitizeForLog(spaceKey), e);
            throw new RuntimeException("Failed to list pages: " + e.getMessage(), e);
        }

        return ConfluencePageListResponse.builder()
                .pages(Collections.emptyList())
                .start(0)
                .limit(25)
                .size(0)
                .build();
    }

    /**
     * Get a page by ID with full content.
     */
    public ConfluencePage getPage(String pageId) {
        ensureConnected();

        String url = UriComponentsBuilder.fromHttpUrl(currentConfig.getBaseUrl())
                .path("/rest/api/content/" + pageId)
                .queryParam("expand", "body.storage,ancestors,version,space,children.page")
                .toUriString();

        try {
            HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(currentConfig));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode pageNode = objectMapper.readTree(response.getBody());
                ConfluencePage page = parsePage(pageNode, null);

                // Extract body content
                if (pageNode.has("body") && pageNode.get("body").has("storage")) {
                    page.setBodyContent(pageNode.get("body").get("storage").get("value").asText());
                }

                return page;
            }
        } catch (Exception e) {
            logger.error("Failed to get page: {}", pageId, e);
            throw new RuntimeException("Failed to get page: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * Search pages with CQL.
     */
    public ConfluencePageListResponse searchPages(String cql, String spaceKey, String title,
                                                   String type, Integer start, Integer limit) {
        ensureConnected();

        // Build CQL query if not provided
        String searchCql = cql;
        if (searchCql == null || searchCql.isEmpty()) {
            List<String> conditions = new ArrayList<>();
            conditions.add("type=page");
            if (spaceKey != null && !spaceKey.isEmpty()) {
                conditions.add("space=\"" + spaceKey.replace("\"", "\\\"") + "\"");
            }
            if (title != null && !title.isEmpty()) {
                conditions.add("title~\"" + title.replace("\"", "\\\"") + "\"");
            }
            searchCql = String.join(" AND ", conditions);
        }

        String url = UriComponentsBuilder.fromHttpUrl(currentConfig.getBaseUrl())
                .path("/rest/api/content/search")
                .queryParam("cql", searchCql)
                .queryParamIfPresent("start", Optional.ofNullable(start))
                .queryParamIfPresent("limit", Optional.ofNullable(limit != null ? limit : 25))
                .queryParam("expand", "ancestors,version,space")
                .toUriString();

        try {
            HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(currentConfig));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                List<ConfluencePage> pages = new ArrayList<>();

                JsonNode results = root.get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode pageNode : results) {
                        pages.add(parsePage(pageNode, null));
                    }
                }

                return ConfluencePageListResponse.builder()
                        .pages(pages)
                        .start(root.has("start") ? root.get("start").asInt() : 0)
                        .limit(root.has("limit") ? root.get("limit").asInt() : 25)
                        .size(root.has("size") ? root.get("size").asInt() : pages.size())
                        .totalSize(root.has("totalSize") ? root.get("totalSize").asInt() : null)
                        .build();
            }
        } catch (Exception e) {
            logger.error("Failed to search pages with CQL: {}", sanitizeForLog(searchCql), e);
            throw new RuntimeException("Failed to search pages: " + e.getMessage(), e);
        }

        return ConfluencePageListResponse.builder()
                .pages(Collections.emptyList())
                .start(0)
                .limit(25)
                .size(0)
                .build();
    }

    /**
     * Ingest pages from Confluence.
     */
    public ConfluenceIngestResponse ingestPages(ConfluenceIngestRequest request) {
        ensureConnected();

        List<String> taskIds = new ArrayList<>();
        int pagesQueued = 0;

        try {
            // Collect all pages to ingest
            List<ConfluencePage> pagesToIngest = new ArrayList<>();

            // If specific page IDs are provided
            if (request.getPageIds() != null && !request.getPageIds().isEmpty()) {
                for (String pageId : request.getPageIds()) {
                    ConfluencePage page = getPage(pageId);
                    if (page != null) {
                        pagesToIngest.add(page);

                        // Optionally get children
                        if (Boolean.TRUE.equals(request.getIncludeChildren())) {
                            collectChildPages(page.getId(), pagesToIngest, request.getMaxDepth(), 0);
                        }
                    }
                }
            }

            // If space keys are provided, get all pages from those spaces
            if (request.getSpaceKeys() != null && !request.getSpaceKeys().isEmpty()) {
                for (String spaceKey : request.getSpaceKeys()) {
                    ConfluencePageListResponse pagesResponse = listPages(spaceKey, 0, 100, null);
                    pagesToIngest.addAll(pagesResponse.getPages());

                    // Handle pagination
                    while (pagesResponse.getSize() > 0 &&
                            (pagesResponse.getTotalSize() == null ||
                                    pagesResponse.getStart() + pagesResponse.getSize() < pagesResponse.getTotalSize())) {
                        pagesResponse = listPages(spaceKey, pagesResponse.getStart() + pagesResponse.getSize(), 100, null);
                        pagesToIngest.addAll(pagesResponse.getPages());
                    }
                }
            }

            logger.info("Collected {} pages for ingestion", pagesToIngest.size());

            // Convert pages to documents and ingest
            for (ConfluencePage page : pagesToIngest) {
                try {
                    // Fetch full page content if not already loaded
                    if (page.getBodyContent() == null) {
                        ConfluencePage fullPage = getPage(page.getId());
                        if (fullPage != null && fullPage.getBodyContent() != null) {
                            page.setBodyContent(fullPage.getBodyContent());
                        }
                    }

                    if (page.getBodyContent() != null && !page.getBodyContent().isEmpty()) {
                        // Create document source descriptor
                        DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                                .type(DocumentSourceDescriptor.SourceType.CONFLUENCE)
                                .pathOrUrl(currentConfig.getBaseUrl() + "/pages/" + page.getId())
                                .originalFileName(page.getTitle() + ".html")
                                .sourceId("confluence-" + page.getId())
                                .metadata(Map.of(
                                        "confluencePageId", page.getId(),
                                        "confluenceSpaceKey", page.getSpaceKey() != null ? page.getSpaceKey() : "",
                                        "confluenceTitle", page.getTitle(),
                                        "confluenceVersion", page.getVersion() != null ? page.getVersion().toString() : "1"
                                ))
                                .build();

                        // Create Spring AI Document from page content
                        String content = stripHtml(page.getBodyContent());
                        Document document = new Document(content, Map.of(
                                "source", "confluence",
                                "pageId", page.getId(),
                                "title", page.getTitle(),
                                "spaceKey", page.getSpaceKey() != null ? page.getSpaceKey() : "",
                                "webUrl", page.getWebUrl() != null ? page.getWebUrl() : ""
                        ));

                        // Submit for ingestion - write content to temp file
                        if (documentIngestService != null) {
                            // Generate a unique task ID
                            String taskId = "confluence-" + page.getId() + "-" + System.currentTimeMillis();

                            // Write content to a temp file (auto-deleted on JVM shutdown)
                            Path tempFile = Files.createTempFile("confluence-" + page.getId() + "-", ".html");
                            tempFile.toFile().deleteOnExit();
                            Files.writeString(tempFile, page.getBodyContent());

                            // Process using existing async method
                            DocumentIngestService.ProcessingMode mode =
                                request.getProcessingMode() != null ?
                                    DocumentIngestService.ProcessingMode.valueOf(request.getProcessingMode()) :
                                    DocumentIngestService.ProcessingMode.AUTO;

                            documentIngestService.processDocumentAsync(
                                    taskId,
                                    tempFile,
                                    null, // loader name (auto-detect)
                                    request.getChunkerName(),
                                    mode
                            );
                            taskIds.add(taskId);
                            pagesQueued++;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Failed to ingest page: {} - {}", page.getId(), e.getMessage());
                }
            }

            return ConfluenceIngestResponse.builder()
                    .taskIds(taskIds)
                    .pagesQueued(pagesQueued)
                    .message("Successfully queued " + pagesQueued + " pages for ingestion")
                    .success(true)
                    .build();

        } catch (Exception e) {
            logger.error("Failed to ingest pages from Confluence", e);
            return ConfluenceIngestResponse.builder()
                    .taskIds(Collections.emptyList())
                    .pagesQueued(0)
                    .message("Failed to ingest pages")
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    // Helper methods

    private ConfluenceConnectionConfig ensureConnected() {
        ConfluenceConnectionConfig cfg = currentConfig;
        if (cfg == null || !currentStatus.isConnected()) {
            throw new IllegalStateException("Not connected to Confluence. Please connect first.");
        }
        return cfg;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return null;
        baseUrl = baseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // If it's an Atlassian cloud URL without /wiki, add it
        if (baseUrl.contains(".atlassian.net") && !baseUrl.endsWith("/wiki")) {
            baseUrl = baseUrl + "/wiki";
        }
        return baseUrl;
    }

    private HttpHeaders createAuthHeaders(ConfluenceConnectionConfig config) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        // Use Basic Auth with email:apiToken
        String auth = config.getEmail() + ":" + config.getApiToken();
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);

        return headers;
    }

    private String detectDeploymentType(String baseUrl, HttpHeaders headers) {
        // Confluence Cloud URLs contain .atlassian.net
        if (baseUrl.contains(".atlassian.net")) {
            return "cloud";
        }
        // For Server/Data Center, try to get server info
        try {
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/rest/api/settings/systemInfo",
                    HttpMethod.GET,
                    entity,
                    String.class
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                return "server";
            }
        } catch (Exception e) {
            logger.debug("Could not determine Confluence server type from systemInfo endpoint: {}", e.getMessage());
        }
        return "unknown";
    }

    private ConfluenceSpace parseSpace(JsonNode node) {
        ConfluenceSpace.ConfluenceSpaceBuilder builder = ConfluenceSpace.builder()
                .id(getTextValue(node, "id"))
                .key(getTextValue(node, "key"))
                .name(getTextValue(node, "name"))
                .type(getTextValue(node, "type"))
                .status(getTextValue(node, "status"));

        if (node.has("description") && node.get("description").has("plain")) {
            builder.description(getTextValue(node.get("description").get("plain"), "value"));
        }

        if (node.has("icon") && node.get("icon").has("path")) {
            builder.iconUrl(currentConfig.getBaseUrl() + getTextValue(node.get("icon"), "path"));
        }

        if (node.has("homepage")) {
            builder.homepageId(getTextValue(node.get("homepage"), "id"));
        }

        return builder.build();
    }

    private ConfluencePage parsePage(JsonNode node, String spaceKey) {
        ConfluencePage.ConfluencePageBuilder builder = ConfluencePage.builder()
                .id(getTextValue(node, "id"))
                .title(getTextValue(node, "title"))
                .type(getTextValue(node, "type"))
                .status(getTextValue(node, "status"));

        // Space info
        if (node.has("space")) {
            builder.spaceKey(getTextValue(node.get("space"), "key"));
            builder.spaceName(getTextValue(node.get("space"), "name"));
        } else if (spaceKey != null) {
            builder.spaceKey(spaceKey);
        }

        // Version info
        if (node.has("version")) {
            builder.version(node.get("version").has("number")
                    ? node.get("version").get("number").asInt()
                    : null);
            if (node.get("version").has("when")) {
                builder.modifiedDate(getTextValue(node.get("version"), "when"));
            }
            if (node.get("version").has("by")) {
                builder.lastModifiedBy(getTextValue(node.get("version").get("by"), "displayName"));
            }
        }

        // Ancestors (breadcrumb)
        if (node.has("ancestors") && node.get("ancestors").isArray()) {
            List<Map<String, String>> ancestors = new ArrayList<>();
            for (JsonNode ancestor : node.get("ancestors")) {
                ancestors.add(Map.of(
                        "id", getTextValue(ancestor, "id"),
                        "title", getTextValue(ancestor, "title")
                ));
            }
            builder.ancestors(ancestors);
        }

        // Children info
        if (node.has("children") && node.get("children").has("page")) {
            JsonNode childPage = node.get("children").get("page");
            int childCount = childPage.has("size") ? childPage.get("size").asInt() : 0;
            builder.hasChildren(childCount > 0);
            builder.children(Map.of("page", Map.of("size", childCount)));
        }

        // Web URL
        if (node.has("_links") && node.get("_links").has("webui")) {
            builder.webUrl(currentConfig.getBaseUrl() + getTextValue(node.get("_links"), "webui"));
        }

        return builder.build();
    }

    private String getTextValue(JsonNode node, String field) {
        return node != null && node.has(field) && !node.get(field).isNull()
                ? node.get(field).asText()
                : null;
    }

    private void collectChildPages(String parentId, List<ConfluencePage> pages, Integer maxDepth, int currentDepth) {
        if (maxDepth != null && maxDepth > 0 && currentDepth >= maxDepth) {
            return;
        }

        try {
            String url = UriComponentsBuilder.fromHttpUrl(currentConfig.getBaseUrl())
                    .path("/rest/api/content/" + parentId + "/child/page")
                    .queryParam("expand", "ancestors,version,children.page")
                    .toUriString();

            HttpEntity<String> entity = new HttpEntity<>(createAuthHeaders(currentConfig));
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode results = root.get("results");
                if (results != null && results.isArray()) {
                    for (JsonNode pageNode : results) {
                        ConfluencePage page = parsePage(pageNode, null);
                        pages.add(page);

                        // Recursively collect children
                        if (Boolean.TRUE.equals(page.getHasChildren())) {
                            collectChildPages(page.getId(), pages, maxDepth, currentDepth + 1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to collect child pages for parent: {}", parentId, e);
        }
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        // Simple HTML stripping - for production, consider using Jsoup
        return html
                .replaceAll("<script[^>]*>[^<]*</script>", "")
                .replaceAll("<style[^>]*>[^<]*</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .trim();
    }

    private static String sanitizeForLog(String value) {
        if (value == null) return null;
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
