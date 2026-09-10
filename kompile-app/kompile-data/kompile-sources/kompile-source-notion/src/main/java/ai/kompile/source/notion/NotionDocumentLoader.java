/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.source.notion;

import ai.kompile.core.crawl.graph.SourceCredentialRedactor;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.oauth.service.OAuthConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Loads Notion pages/databases into ordinary crawl documents. */
public class NotionDocumentLoader implements DocumentLoader {
    private static final String API_BASE = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";

    private final OAuthConnectionService oauthService;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String apiBase;
    private final long minRequestIntervalMillis;
    private long lastRequestNanos;

    public NotionDocumentLoader(OAuthConnectionService oauthService, ObjectMapper mapper) {
        this(oauthService, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20)).build(), API_BASE, 334L);
    }

    NotionDocumentLoader(OAuthConnectionService oauthService, ObjectMapper mapper,
                         HttpClient httpClient, String apiBase) {
        this(oauthService, mapper, httpClient, apiBase, 0L);
    }

    NotionDocumentLoader(OAuthConnectionService oauthService, ObjectMapper mapper,
                         HttpClient httpClient, String apiBase, long minRequestIntervalMillis) {
        this.oauthService = oauthService;
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.minRequestIntervalMillis = Math.max(0L, minRequestIntervalMillis);
    }

    @Override public String getName() { return "Notion Loader"; }

    @Override
    public boolean supports(DocumentSourceDescriptor descriptor) {
        return descriptor != null && descriptor.getType() == DocumentSourceDescriptor.SourceType.NOTION;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor descriptor) throws Exception {
        return load(descriptor, null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor descriptor,
                               Consumer<LoaderProgress> progressCallback) throws Exception {
        if (!supports(descriptor)) throw new IllegalArgumentException("Notion loader requires NOTION source type");
        Map<String, Object> metadata = descriptor.getMetadata() == null ? Map.of() : descriptor.getMetadata();
        int maxApiRequests = boundedInt(metadata.get("maxApiRequests"), 5_000, 1, 25_000);
        Optional<String> explicitToken = text(metadata, "accessToken").or(() -> text(metadata, "apiToken"));
        ApiAccess access = explicitToken.map(token -> ApiAccess.fixed(token, maxApiRequests))
                .orElseGet(() -> ApiAccess.managed(oauthService, maxApiRequests));
        if (!access.available()) {
            throw new IllegalArgumentException("Notion requires metadata.accessToken/apiToken or connected Notion OAuth");
        }
        int maxPages = boundedInt(metadata.get("maxPages"), 250, 1, 10_000);
        int maxBlockDepth = boundedInt(metadata.get("maxBlockDepth"), 16, 0, 64);
        int maxBlocks = boundedInt(metadata.get("maxBlocks"), 10_000, 1, 100_000);
        boolean includeSubpages = booleanValue(metadata.get("includeSubpages"), true);
        LinkedHashSet<String> pageIds = new LinkedHashSet<>(values(metadata.get("pageIds")));
        LinkedHashSet<String> databaseIds = new LinkedHashSet<>(values(metadata.get("databaseIds")));
        String resourceType = text(metadata, "resourceType")
                .or(() -> text(metadata, "scopeType")).orElse("");
        if (pageIds.isEmpty() && databaseIds.isEmpty()) {
            if ("database".equalsIgnoreCase(resourceType)) {
                databaseIds.addAll(identifiers(descriptor.getPathOrUrl()));
            } else {
                pageIds.addAll(identifiers(descriptor.getPathOrUrl()));
            }
        } else if ("page".equalsIgnoreCase(resourceType)) {
            pageIds.addAll(identifiers(descriptor.getPathOrUrl()));
        }
        for (String databaseId : databaseIds) {
            pageIds.addAll(queryDatabase(databaseId, access, maxPages - pageIds.size()));
            if (pageIds.size() >= maxPages) break;
        }
        if (pageIds.isEmpty()) throw new IllegalArgumentException("Notion page/database IDs are required");

        List<Document> documents = new ArrayList<>();
        Set<String> queued = new LinkedHashSet<>(pageIds);
        List<String> queue = new ArrayList<>(pageIds);
        for (int i = 0; i < queue.size() && documents.size() < maxPages; i++) {
            String pageId = cleanId(queue.get(i));
            JsonNode page = get("/pages/" + encode(pageId), access);
            List<JsonNode> blocks = blockChildren(pageId, access, maxBlockDepth, maxBlocks);
            String title = pageTitle(page);
            Map<String, String> notionProperties = pageProperties(page);
            String body = joinSections(propertiesToMarkdown(notionProperties), blocksToMarkdown(blocks));
            Map<String, Object> docMetadata = new LinkedHashMap<>();
            docMetadata.put(GraphConstants.META_SOURCE, "notion:" + pageId);
            docMetadata.put(GraphConstants.META_SOURCE_PATH, "notion:" + pageId);
            docMetadata.put(GraphConstants.META_SOURCE_TYPE, "notion");
            docMetadata.put(GraphConstants.META_DOCUMENT_TYPE, "notion_page");
            docMetadata.put(GraphConstants.META_LOADER, getName());
            docMetadata.put(GraphConstants.META_FILE_NAME, title);
            docMetadata.put("notion.pageId", pageId);
            putText(docMetadata, "notion.url", page.path("url"));
            if (!notionProperties.isEmpty()) docMetadata.put("notion.properties", notionProperties);
            putText(docMetadata, "notion.createdTime", page.path("created_time"));
            putText(docMetadata, "notion.lastEditedTime", page.path("last_edited_time"));
            if (descriptor.getCollectionName() != null) docMetadata.put("collection_name", descriptor.getCollectionName());
            documents.add(new Document("# " + title + (body.isBlank() ? "" : "\n\n" + body), docMetadata));
            if (includeSubpages) {
                for (JsonNode block : blocks) {
                    if ("child_page".equals(block.path("type").asText())) {
                        String child = block.path("id").asText();
                        if (!child.isBlank() && queued.add(child)) queue.add(child);
                    }
                }
            }
            report(progressCallback, Math.min(95, 5 + 90 * documents.size() / maxPages),
                    "Loaded " + documents.size() + " Notion page(s)");
        }
        report(progressCallback, 100, "Loaded " + documents.size() + " Notion page(s)");
        return List.copyOf(documents);
    }

    private List<String> queryDatabase(String databaseId, ApiAccess access, int remaining) throws Exception {
        if (remaining <= 0) return List.of();
        List<String> pages = new ArrayList<>();
        Set<String> cursors = new LinkedHashSet<>();
        String cursor = null;
        do {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("page_size", Math.min(100, remaining - pages.size()));
            if (cursor != null) body.put("start_cursor", cursor);
            JsonNode response = post("/databases/" + encode(cleanId(databaseId)) + "/query", access, body);
            for (JsonNode page : response.path("results")) {
                String id = page.path("id").asText();
                if (!id.isBlank()) pages.add(id);
                if (pages.size() >= remaining) break;
            }
            String nextCursor = response.path("has_more").asBoolean(false)
                    ? response.path("next_cursor").asText(null) : null;
            cursor = nextCursor != null && cursors.add(nextCursor) ? nextCursor : null;
        } while (cursor != null && pages.size() < remaining);
        return pages;
    }

    private List<JsonNode> blockChildren(String blockId, ApiAccess access,
                                         int maxDepth, int maxBlocks) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        appendBlockChildren(blockId, access, 0, maxDepth, maxBlocks,
                new LinkedHashSet<>(), result);
        return result;
    }

    private void appendBlockChildren(String blockId, ApiAccess access, int depth, int maxDepth,
                                     int maxBlocks,
                                     Set<String> visited, List<JsonNode> result) throws Exception {
        if (depth > maxDepth || result.size() >= maxBlocks || !visited.add(cleanId(blockId))) return;
        Set<String> cursors = new LinkedHashSet<>();
        String cursor = null;
        do {
            String path = "/blocks/" + encode(cleanId(blockId)) + "/children?page_size=100"
                    + (cursor == null ? "" : "&start_cursor=" + encode(cursor));
            JsonNode response = get(path, access);
            for (JsonNode block : response.path("results")) {
                if (result.size() >= maxBlocks) break;
                result.add(block);
                if (depth < maxDepth && block.path("has_children").asBoolean(false)
                        && !"child_page".equals(block.path("type").asText())) {
                    String childId = block.path("id").asText();
                    if (!childId.isBlank()) {
                        appendBlockChildren(childId, access, depth + 1, maxDepth,
                                maxBlocks, visited, result);
                    }
                }
            }
            String nextCursor = response.path("has_more").asBoolean(false)
                    ? response.path("next_cursor").asText(null) : null;
            cursor = nextCursor != null && cursors.add(nextCursor) ? nextCursor : null;
        } while (cursor != null && result.size() < maxBlocks);
    }

    static String blocksToMarkdown(List<JsonNode> blocks) {
        StringBuilder out = new StringBuilder();
        for (JsonNode block : blocks) {
            String type = block.path("type").asText();
            JsonNode value = block.path(type);
            String text = richText(value.path("rich_text"));
            String line = switch (type) {
                case "heading_1" -> "# " + text;
                case "heading_2" -> "## " + text;
                case "heading_3" -> "### " + text;
                case "bulleted_list_item" -> "- " + text;
                case "numbered_list_item" -> "1. " + text;
                case "to_do" -> (value.path("checked").asBoolean(false) ? "- [x] " : "- [ ] ") + text;
                case "quote" -> "> " + text;
                case "code" -> "```" + value.path("language").asText("") + "\n" + text + "\n```";
                case "child_page" -> "## " + value.path("title").asText("Child page");
                case "bookmark", "link_preview" -> value.path("url").asText("");
                case "divider" -> "---";
                default -> text;
            };
            if (!line.isBlank()) {
                if (out.length() > 0) out.append("\n\n");
                out.append(line.trim());
            }
        }
        return out.toString();
    }

    private static String richText(JsonNode values) {
        StringBuilder out = new StringBuilder();
        if (values.isArray()) for (JsonNode value : values) out.append(value.path("plain_text").asText(""));
        return out.toString();
    }

    private static String pageTitle(JsonNode page) {
        JsonNode properties = page.path("properties");
        for (JsonNode property : properties) {
            if ("title".equals(property.path("type").asText())) {
                String title = richText(property.path("title"));
                if (!title.isBlank()) return title;
            }
        }
        return "Notion page";
    }

    private static Map<String, String> pageProperties(JsonNode page) {
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = page.path("properties").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String value = propertyValue(field.getValue());
            if (value != null && !value.isBlank()) result.put(field.getKey(), abbreviate(value, 4_000));
        }
        return result;
    }

    private static String propertyValue(JsonNode property) {
        String type = property.path("type").asText();
        JsonNode value = property.path(type);
        return switch (type) {
            case "title", "rich_text" -> richText(value);
            case "number", "checkbox", "url", "email", "phone_number",
                    "created_time", "last_edited_time" -> value.isNull() ? "" : value.asText("");
            case "select", "status" -> value.path("name").asText("");
            case "multi_select" -> names(value, "name");
            case "people" -> people(value);
            case "relation" -> names(value, "id");
            case "date" -> {
                String start = value.path("start").asText("");
                String end = value.path("end").asText("");
                yield end.isBlank() ? start : start + " – " + end;
            }
            case "files" -> fileNames(value);
            case "formula", "rollup" -> scalarValue(value);
            default -> "";
        };
    }

    private static String names(JsonNode values, String key) {
        List<String> names = new ArrayList<>();
        if (values.isArray()) for (JsonNode value : values) {
            String name = value.path(key).asText("");
            if (!name.isBlank()) names.add(name);
        }
        return String.join(", ", names);
    }

    private static String people(JsonNode values) {
        List<String> people = new ArrayList<>();
        if (values.isArray()) for (JsonNode value : values) {
            String person = value.path("name").asText("");
            if (person.isBlank()) person = value.path("person").path("email").asText("");
            if (person.isBlank()) person = value.path("id").asText("");
            if (!person.isBlank()) people.add(person);
        }
        return String.join(", ", people);
    }

    private static String fileNames(JsonNode values) {
        List<String> files = new ArrayList<>();
        if (values.isArray()) for (JsonNode value : values) {
            String name = value.path("name").asText("");
            String url = value.path(value.path("type").asText()).path("url").asText("");
            if (!name.isBlank()) files.add(url.isBlank() ? name : name + " (" + url + ")");
        }
        return String.join(", ", files);
    }

    private static String scalarValue(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isValueNode()) return value.asText("");
        String nestedType = value.path("type").asText("");
        JsonNode nested = nestedType.isBlank() ? value : value.path(nestedType);
        return nested.isValueNode() ? nested.asText("") : nested.toString();
    }

    private static String propertiesToMarkdown(Map<String, String> properties) {
        if (properties.isEmpty()) return "";
        StringBuilder out = new StringBuilder("## Properties");
        properties.forEach((name, value) -> out.append("\n- **")
                .append(name.replace("**", ""))
                .append(":** ").append(value));
        return out.toString();
    }

    private static String joinSections(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + "\n\n" + second;
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    private JsonNode get(String path, ApiAccess access) throws Exception {
        return request(access, authorization -> HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .header("Notion-Version", NOTION_VERSION).GET().build());
    }

    private JsonNode post(String path, ApiAccess access, Object body) throws Exception {
        String json = mapper.writeValueAsString(body);
        return request(access, authorization -> HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .header("Notion-Version", NOTION_VERSION)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private JsonNode request(ApiAccess access, Function<String, HttpRequest> requestFactory) throws Exception {
        HttpResponse<String> response = sendWithRetry(access, requestFactory);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Notion API request failed with HTTP " + response.statusCode()
                    + ": " + SourceCredentialRedactor.redact(response.body()));
        }
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> sendWithRetry(
            ApiAccess access, Function<String, HttpRequest> requestFactory) throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            access.acquireRequest();
            rateLimit();
            HttpResponse<String> response;
            try {
                response = httpClient.send(
                        requestFactory.apply("Bearer " + access.token()),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException transportFailure) {
                if (attempt == 4) throw transportFailure;
                sleepBeforeRetry(null, attempt);
                continue;
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response;
            if ((response.statusCode() != 429 && response.statusCode() < 500) || attempt == 4) {
                return response;
            }
            sleepBeforeRetry(response, attempt);
        }
        throw new IOException("Notion API request failed without a response");
    }

    private synchronized void rateLimit() throws InterruptedException {
        if (minRequestIntervalMillis <= 0L || lastRequestNanos == 0L) {
            lastRequestNanos = System.nanoTime();
            return;
        }
        long requiredNanos = minRequestIntervalMillis * 1_000_000L;
        long remainingNanos = requiredNanos - (System.nanoTime() - lastRequestNanos);
        if (remainingNanos > 0L) {
            long millis = remainingNanos / 1_000_000L;
            int nanos = (int) (remainingNanos % 1_000_000L);
            Thread.sleep(millis, nanos);
        }
        lastRequestNanos = System.nanoTime();
    }

    private static void sleepBeforeRetry(HttpResponse<?> response, int attempt) throws InterruptedException {
        long delayMillis = Math.min(30_000L, 500L << Math.max(0, attempt - 1));
        if (response != null) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            if (retryAfter != null) {
                try {
                    delayMillis = Math.min(30_000L,
                            Math.max(0L, Long.parseLong(retryAfter.trim()) * 1_000L));
                } catch (NumberFormatException ignored) {
                    // Notion normally emits seconds; retain bounded exponential backoff otherwise.
                }
            }
        }
        if (delayMillis > 0L) Thread.sleep(delayMillis);
    }

    private static List<String> identifiers(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) if (!item.isBlank()) result.add(cleanId(item));
        return result;
    }

    private static List<String> values(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            iterable.forEach(item -> { if (item != null && !item.toString().isBlank()) result.add(cleanId(item.toString())); });
            return result;
        }
        return value == null ? List.of() : identifiers(value.toString());
    }

    private static String cleanId(String value) {
        String id = value.trim().replaceAll("^.*notion\\.so/", "").replaceAll("[?#].*$", "");
        String compact = id.replace("-", "");
        if (compact.length() >= 32) compact = compact.substring(compact.length() - 32);
        if (!compact.matches("[A-Fa-f0-9]{32}")) throw new IllegalArgumentException("Invalid Notion page/database ID");
        return compact;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Optional<String> text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null || value.toString().isBlank() ? Optional.empty() : Optional.of(value.toString().trim());
    }
    private static int boundedInt(Object value, int fallback, int min, int max) {
        int resolved = value instanceof Number number ? number.intValue() : value == null ? fallback : Integer.parseInt(value.toString());
        return Math.max(min, Math.min(max, resolved));
    }
    private static boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }
    private static void putText(Map<String, Object> target, String key, JsonNode value) {
        String text = value.asText(""); if (!text.isBlank()) target.put(key, text);
    }
    private static void report(Consumer<LoaderProgress> callback, int percent, String message) {
        if (callback != null) callback.accept(new LoaderProgress("notion", percent, null, message, Map.of()));
    }

    private static final class ApiAccess {
        private final OAuthConnectionService oauthService;
        private final String fixedToken;
        private int remainingRequests;

        private ApiAccess(OAuthConnectionService oauthService, String fixedToken, int maxRequests) {
            this.oauthService = oauthService;
            this.fixedToken = fixedToken;
            this.remainingRequests = maxRequests;
        }

        static ApiAccess fixed(String token, int maxRequests) {
            return new ApiAccess(null, token, maxRequests);
        }

        static ApiAccess managed(OAuthConnectionService oauthService, int maxRequests) {
            return new ApiAccess(oauthService, null, maxRequests);
        }

        boolean managed() {
            return oauthService != null;
        }

        boolean available() {
            try {
                String value = token();
                return value != null && !value.isBlank();
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        String token() {
            if (!managed()) return fixedToken;
            String token = oauthService.getValidAccessToken("notion");
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Notion OAuth connection is no longer usable");
            }
            return token;
        }

        synchronized void acquireRequest() {
            if (remainingRequests <= 0) {
                throw new IllegalStateException("Notion API request budget exhausted");
            }
            remainingRequests--;
        }

    }
}
