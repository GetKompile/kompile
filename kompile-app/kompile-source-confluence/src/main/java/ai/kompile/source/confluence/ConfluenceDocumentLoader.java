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

package ai.kompile.source.confluence;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Document loader for Confluence pages.
 *
 * <p>Supports two ingestion modes:</p>
 * <ul>
 *     <li>Confluence Cloud REST API when {@code pathOrUrl} is an HTTP URL.</li>
 *     <li>Local Confluence exports when {@code pathOrUrl} points at files or directories.</li>
 * </ul>
 */
public class ConfluenceDocumentLoader implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfluenceDocumentLoader.class);
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>|<h1[^>]*>(.*?)</h1>");
    private static final int DEFAULT_MAX_DOCUMENTS = 100;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "Confluence Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor != null
                && sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.CONFLUENCE;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        return load(sourceDescriptor, null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor descriptor,
                               Consumer<LoaderProgress> progressCallback) throws Exception {
        if (!supports(descriptor)) {
            throw new IllegalArgumentException("ConfluenceDocumentLoader only supports CONFLUENCE source type.");
        }

        String source = Objects.toString(descriptor.getPathOrUrl(), "").trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Confluence source requires pathOrUrl.");
        }

        Path localPath = resolveLocalPath(source);
        if (localPath != null && Files.exists(localPath)) {
            return loadLocalExport(localPath, descriptor, progressCallback);
        }

        if (source.startsWith("http://") || source.startsWith("https://")) {
            return loadFromApi(source, descriptor, progressCallback);
        }

        throw new IllegalArgumentException("Confluence source must be a Confluence base URL or a local export path: " + source);
    }

    private List<Document> loadLocalExport(Path path,
                                           DocumentSourceDescriptor descriptor,
                                           Consumer<LoaderProgress> progressCallback) throws IOException {
        report(progressCallback, 5, path.toString(), "Scanning Confluence export");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                stream.filter(Files::isRegularFile)
                        .filter(this::isSupportedExportFile)
                        .forEach(files::add);
            }
        } else if (isSupportedExportFile(path)) {
            files.add(path);
        }

        int maxDocuments = getInt(descriptor.getMetadata(), "maxDocuments", DEFAULT_MAX_DOCUMENTS);
        if (maxDocuments > 0 && files.size() > maxDocuments) {
            files = files.subList(0, maxDocuments);
        }

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            Path file = files.get(i);
            String raw = Files.readString(file);
            String title = extractTitle(raw).orElse(stripExtension(file.getFileName().toString()));
            String text = toPlainText(raw);
            Document document = new Document(text);
            Map<String, Object> md = document.getMetadata();
            addCommonMetadata(md, "confluence-export:" + file.toAbsolutePath(), title);
            md.put(GraphConstants.META_SOURCE_PATH, file.toAbsolutePath().toString());
            md.put(GraphConstants.META_FILE_NAME, file.getFileName().toString());
            md.put("confluence.pageId", stripExtension(file.getFileName().toString()));
            md.put("confluence.title", title);
            md.put("confluence.exportPath", file.toAbsolutePath().toString());
            documents.add(document);
            report(progressCallback, progress(i + 1, files.size()), file.toString(),
                    "Loaded Confluence export page " + (i + 1) + "/" + files.size());
        }
        return documents;
    }

    private List<Document> loadFromApi(String baseUrl,
                                       DocumentSourceDescriptor descriptor,
                                       Consumer<LoaderProgress> progressCallback) throws Exception {
        Map<String, Object> metadata = descriptor.getMetadata() != null ? descriptor.getMetadata() : Map.of();
        String spaceKey = stringValue(metadata, "spaceKey")
                .or(() -> stringValue(metadata, "space"))
                .orElseThrow(() -> new IllegalArgumentException("Confluence API loading requires metadata.spaceKey."));
        String authHeader = resolveAuthHeader(metadata);
        int maxDocuments = getInt(metadata, "maxDocuments", DEFAULT_MAX_DOCUMENTS);

        report(progressCallback, 5, spaceKey, "Discovering Confluence pages");
        List<Document> documents = new ArrayList<>();
        String nextUrl = apiUrl(baseUrl, spaceKey, Math.min(100, Math.max(1, maxDocuments)));

        while (nextUrl != null && documents.size() < maxDocuments) {
            JsonNode response = getJson(nextUrl, authHeader);
            JsonNode results = response.path("results");
            if (results.isArray()) {
                for (JsonNode page : results) {
                    if (documents.size() >= maxDocuments || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    documents.add(toDocument(page, baseUrl));
                    report(progressCallback, progress(documents.size(), maxDocuments),
                            page.path("title").asText("page"),
                            "Loaded Confluence page " + documents.size() + "/" + maxDocuments);
                }
            }
            nextUrl = nextPageUrl(baseUrl, response).orElse(null);
        }
        return documents;
    }

    private Document toDocument(JsonNode page, String baseUrl) {
        String pageId = page.path("id").asText();
        String title = page.path("title").asText(pageId);
        String html = page.path("body").path("storage").path("value").asText("");
        String text = toPlainText(html);
        Document document = new Document(text.isBlank() ? title : text);
        Map<String, Object> md = document.getMetadata();
        addCommonMetadata(md, "confluence:" + pageId, title);
        md.put("confluence.pageId", pageId);
        md.put("confluence.title", title);
        md.put("confluence.type", page.path("type").asText("page"));
        md.put("confluence.status", page.path("status").asText(null));
        md.put("confluence.webUrl", webUrl(baseUrl, page));
        JsonNode space = page.path("space");
        if (!space.isMissingNode()) {
            md.put("confluence.spaceKey", space.path("key").asText(null));
            md.put("confluence.spaceName", space.path("name").asText(null));
        }
        JsonNode version = page.path("version");
        if (!version.isMissingNode()) {
            md.put("confluence.version", version.path("number").asText(null));
            md.put("confluence.modifiedDate", version.path("when").asText(null));
            md.put("confluence.lastModifiedBy", version.path("by").path("displayName").asText(null));
        }
        return document;
    }

    private void addCommonMetadata(Map<String, Object> metadata, String source, String title) {
        metadata.put(GraphConstants.META_SOURCE, source);
        metadata.put(GraphConstants.META_SOURCE_PATH, source);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "confluence");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "confluence_page");
        metadata.put(GraphConstants.META_LOADER, getName());
        metadata.put(GraphConstants.META_FILE_NAME, title);
    }

    private JsonNode getJson(String url, String authHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Confluence API request failed with HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private String resolveAuthHeader(Map<String, Object> metadata) {
        Optional<String> bearer = stringValue(metadata, "accessToken")
                .or(() -> stringValue(metadata, "oauthToken"))
                .or(() -> stringValue(metadata, "token"));
        if (bearer.isPresent()) {
            return "Bearer " + bearer.get();
        }

        String email = stringValue(metadata, "email")
                .or(() -> stringValue(metadata, "username"))
                .orElseThrow(() -> new IllegalArgumentException("Confluence API token auth requires metadata.email or metadata.username."));
        String apiToken = stringValue(metadata, "apiToken")
                .or(() -> stringValue(metadata, "password"))
                .orElseThrow(() -> new IllegalArgumentException("Confluence API token auth requires metadata.apiToken."));
        String encoded = Base64.getEncoder().encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String apiUrl(String baseUrl, String spaceKey, int limit) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/rest/api/content?spaceKey=" + encode(spaceKey)
                + "&type=page&expand=body.storage,version,space,ancestors,metadata.labels"
                + "&limit=" + limit;
    }

    private Optional<String> nextPageUrl(String baseUrl, JsonNode response) {
        String next = response.path("_links").path("next").asText(null);
        if (next == null || next.isBlank()) {
            return Optional.empty();
        }
        if (next.startsWith("http://") || next.startsWith("https://")) {
            return Optional.of(next);
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!next.startsWith("/")) {
            next = "/" + next;
        }
        return Optional.of(normalized + next);
    }

    private String webUrl(String baseUrl, JsonNode page) {
        String webui = page.path("_links").path("webui").asText(null);
        if (webui == null || webui.isBlank()) {
            return baseUrl + "/pages/" + page.path("id").asText();
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return webui.startsWith("http") ? webui : normalized + (webui.startsWith("/") ? webui : "/" + webui);
    }

    private Path resolveLocalPath(String source) {
        try {
            if (source.startsWith("file://")) {
                return Path.of(URI.create(source));
            }
            if (!source.startsWith("http://") && !source.startsWith("https://")) {
                return Path.of(source);
            }
        } catch (Exception e) {
            logger.debug("Could not resolve Confluence source as local path: {}", source, e);
        }
        return null;
    }

    private boolean isSupportedExportFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".md") || name.endsWith(".txt");
    }

    private Optional<String> extractTitle(String raw) {
        Matcher matcher = TITLE_PATTERN.matcher(raw);
        if (matcher.find()) {
            String title = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            title = toPlainText(title).trim();
            if (!title.isBlank()) {
                return Optional.of(title);
            }
        }
        return Optional.empty();
    }

    private String toPlainText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private Optional<String> stringValue(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key) || metadata.get(key) == null) {
            return Optional.empty();
        }
        String value = metadata.get(key).toString().trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private int getInt(Map<String, Object> metadata, String key, int defaultValue) {
        if (metadata == null || metadata.get(key) == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int progress(int current, int total) {
        if (total <= 0) {
            return 100;
        }
        return Math.min(100, Math.max(0, (int) Math.round((current * 100.0) / total)));
    }

    private void report(Consumer<LoaderProgress> progressCallback, int progress, String currentStep, String message) {
        if (progressCallback != null) {
            progressCallback.accept(new LoaderProgress("confluence", progress, currentStep, message, Map.of()));
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
