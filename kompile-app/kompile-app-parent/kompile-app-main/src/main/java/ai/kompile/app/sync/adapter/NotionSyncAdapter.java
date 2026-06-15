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

package ai.kompile.app.sync.adapter;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.sync.config.NoteSyncConfigService;
import ai.kompile.app.sync.convert.NotionBlockConverter;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Sync adapter for Notion. Uses RestTemplate to call the Notion API directly
 * (matches existing ConfluenceService pattern, avoids notion-sdk-jvm dependency).
 *
 * Enabled at runtime via NoteSyncConfigService (JSON config), NOT @ConditionalOnProperty.
 */
@Service
public class NotionSyncAdapter implements SyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(NotionSyncAdapter.class);
    private static final String NOTION_API_BASE = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";

    // Rate limit: 3 req/sec sustained, 10 burst
    private final Semaphore rateLimiter = new Semaphore(10);

    @Autowired
    private NoteSyncConfigService configService;

    @Autowired(required = false)
    private OAuthConnectionService oauthConnectionService;

    @Autowired
    private NotionBlockConverter blockConverter;

    @Autowired
    private ObjectMapper objectMapper;

    private final RestTemplate restTemplate;
    {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        restTemplate = new RestTemplate(factory);
    }

    @Override
    public String adapterId() {
        return "notion";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ExternalNoteSnapshot> fetchChangedSince(NoteSyncConnection conn, Instant since) {
        checkEnabled();
        List<ExternalNoteSnapshot> results = new ArrayList<>();

        // Search for pages under the parent scope that were edited after `since`
        Map<String, Object> searchBody = new LinkedHashMap<>();
        searchBody.put("filter", Map.of("property", "object", "value", "page"));
        searchBody.put("sort", Map.of("direction", "descending", "timestamp", "last_edited_time"));
        searchBody.put("page_size", 100);

        String cursor = null;
        boolean hasMore = true;

        while (hasMore) {
            if (cursor != null) {
                searchBody.put("start_cursor", cursor);
            }

            Map<String, Object> response = notionPost("/search", searchBody);
            List<Map<String, Object>> pages = (List<Map<String, Object>>) response.get("results");

            if (pages != null) {
                for (Map<String, Object> page : pages) {
                    String pageId = (String) page.get("id");
                    String parentId = extractParentId(page);

                    // Filter to pages under the configured externalScope
                    if (!conn.getExternalScope().equals(parentId)) {
                        continue;
                    }

                    Instant lastEdited = parseNotionTime((String) page.get("last_edited_time"));
                    if (lastEdited != null && lastEdited.isAfter(since)) {
                        // Fetch full block content
                        List<Map<String, Object>> blocks = fetchBlockChildren(pageId);
                        String markdown = blockConverter.blocksToMarkdown(blocks);
                        String title = extractPageTitle(page);

                        results.add(new ExternalNoteSnapshot(
                                pageId, title, markdown, null, lastEdited));
                    }
                }
            }

            hasMore = Boolean.TRUE.equals(response.get("has_more"));
            cursor = (String) response.get("next_cursor");
        }

        log.info("Notion fetch: found {} changed pages since {}", results.size(), since);
        return results;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<ExternalNoteSnapshot> fetchById(NoteSyncConnection conn, String externalId) {
        checkEnabled();
        try {
            Map<String, Object> page = notionGet("/pages/" + externalId);
            if (page == null || page.containsKey("error")) {
                return Optional.empty();
            }

            List<Map<String, Object>> blocks = fetchBlockChildren(externalId);
            String markdown = blockConverter.blocksToMarkdown(blocks);
            String title = extractPageTitle(page);
            Instant lastEdited = parseNotionTime((String) page.get("last_edited_time"));

            return Optional.of(new ExternalNoteSnapshot(externalId, title, markdown, null, lastEdited));
        } catch (Exception e) {
            log.warn("Failed to fetch Notion page {}: {}", externalId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String createExternal(NoteSyncConnection conn, Note note, String markdownContent) {
        checkEnabled();

        // Create page under the parent scope
        Map<String, Object> pageBody = new LinkedHashMap<>();
        pageBody.put("parent", Map.of("page_id", conn.getExternalScope()));
        pageBody.put("properties", Map.of(
                "title", Map.of("title", List.of(Map.of(
                        "type", "text",
                        "text", Map.of("content", note.getTitle() != null ? note.getTitle() : "Untitled")
                )))
        ));

        // Convert markdown to Notion blocks
        List<Map<String, Object>> blocks = blockConverter.markdownToBlocks(markdownContent);
        if (!blocks.isEmpty()) {
            pageBody.put("children", blocks);
        }

        Map<String, Object> response = notionPost("/pages", pageBody);
        String pageId = (String) response.get("id");
        log.info("Created Notion page {} for note '{}'", pageId, note.getTitle());
        return pageId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updateExternal(NoteSyncConnection conn, String externalId, Note note, String markdownContent) {
        checkEnabled();

        // Update page title
        Map<String, Object> titleUpdate = Map.of(
                "properties", Map.of(
                        "title", Map.of("title", List.of(Map.of(
                                "type", "text",
                                "text", Map.of("content", note.getTitle() != null ? note.getTitle() : "Untitled")
                        )))
                )
        );
        notionPatch("/pages/" + externalId, titleUpdate);

        // Delete existing blocks
        List<Map<String, Object>> existingBlocks = fetchBlockChildren(externalId);
        for (Map<String, Object> block : existingBlocks) {
            String blockId = (String) block.get("id");
            if (blockId != null) {
                notionDelete("/blocks/" + blockId);
            }
        }

        // Append new blocks
        List<Map<String, Object>> newBlocks = blockConverter.markdownToBlocks(markdownContent);
        if (!newBlocks.isEmpty()) {
            notionPatch("/blocks/" + externalId + "/children",
                    Map.of("children", newBlocks));
        }

        log.info("Updated Notion page {} for note '{}'", externalId, note.getTitle());
    }

    @Override
    public void deleteExternal(NoteSyncConnection conn, String externalId) {
        checkEnabled();
        // Archive the page (Notion soft-delete)
        notionPatch("/pages/" + externalId, Map.of("archived", true));
        log.info("Archived Notion page {}", externalId);
    }

    // ── Notion API Helpers ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchBlockChildren(String blockId) {
        List<Map<String, Object>> allBlocks = new ArrayList<>();
        String cursor = null;
        boolean hasMore = true;

        while (hasMore) {
            String url = "/blocks/" + blockId + "/children?page_size=100";
            if (cursor != null) {
                url += "&start_cursor=" + cursor;
            }

            Map<String, Object> response = notionGet(url);
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results != null) {
                allBlocks.addAll(results);
            }

            hasMore = Boolean.TRUE.equals(response.get("has_more"));
            cursor = (String) response.get("next_cursor");
        }

        return allBlocks;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> notionGet(String path) {
        acquireRateLimit();
        HttpEntity<Void> entity = new HttpEntity<>(notionHeaders());
        ResponseEntity<Map> resp = restTemplate.exchange(
                NOTION_API_BASE + path, HttpMethod.GET, entity, Map.class);
        Map<String, Object> body = resp.getBody();
        return body != null ? body : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> notionPost(String path, Object body) {
        acquireRateLimit();
        HttpEntity<Object> entity = new HttpEntity<>(body, notionHeaders());
        ResponseEntity<Map> resp = restTemplate.exchange(
                NOTION_API_BASE + path, HttpMethod.POST, entity, Map.class);
        Map<String, Object> responseBody = resp.getBody();
        return responseBody != null ? responseBody : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> notionPatch(String path, Object body) {
        acquireRateLimit();
        HttpEntity<Object> entity = new HttpEntity<>(body, notionHeaders());
        ResponseEntity<Map> resp = restTemplate.exchange(
                NOTION_API_BASE + path, HttpMethod.PATCH, entity, Map.class);
        return resp.getBody();
    }

    private void notionDelete(String path) {
        acquireRateLimit();
        HttpEntity<Void> entity = new HttpEntity<>(notionHeaders());
        restTemplate.exchange(NOTION_API_BASE + path, HttpMethod.DELETE, entity, Void.class);
    }

    private HttpHeaders notionHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getAccessToken());
        headers.set("Notion-Version", NOTION_VERSION);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String getAccessToken() {
        if (oauthConnectionService == null) {
            throw new IllegalStateException("OAuthConnectionService not available - cannot connect to Notion");
        }
        String token = oauthConnectionService.getValidAccessToken("notion");
        if (token == null) {
            throw new IllegalStateException("Notion OAuth not connected. Configure via Connections Manager.");
        }
        return token;
    }

    private void acquireRateLimit() {
        try {
            rateLimiter.acquire();
            // Refill happens via scheduled rate; simple delay for now
            Thread.sleep(100); // ~10 req/sec max
            rateLimiter.release();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during rate limiting", e);
        }
    }

    private void checkEnabled() {
        if (!configService.isNotionEnabled()) {
            throw new IllegalStateException("Notion sync is not enabled. Enable it in Sync settings.");
        }
    }

    @SuppressWarnings("unchecked")
    private String extractParentId(Map<String, Object> page) {
        Map<String, Object> parent = (Map<String, Object>) page.get("parent");
        if (parent == null) return null;
        String type = (String) parent.get("type");
        if ("page_id".equals(type)) return (String) parent.get("page_id");
        if ("database_id".equals(type)) return (String) parent.get("database_id");
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractPageTitle(Map<String, Object> page) {
        Map<String, Object> properties = (Map<String, Object>) page.get("properties");
        if (properties == null) return "Untitled";

        // Try "title" property first (common for pages)
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Map<String, Object> prop = (Map<String, Object>) entry.getValue();
            if ("title".equals(prop.get("type"))) {
                List<Map<String, Object>> titleParts = (List<Map<String, Object>>) prop.get("title");
                if (titleParts != null && !titleParts.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map<String, Object> part : titleParts) {
                        String plainText = (String) part.get("plain_text");
                        if (plainText != null) sb.append(plainText);
                    }
                    return sb.toString();
                }
            }
        }
        return "Untitled";
    }

    private Instant parseNotionTime(String isoString) {
        if (isoString == null) return null;
        try {
            return DateTimeFormatter.ISO_DATE_TIME.parse(isoString, Instant::from);
        } catch (Exception e) {
            log.warn("Failed to parse Notion timestamp: {}", isoString);
            return null;
        }
    }
}
