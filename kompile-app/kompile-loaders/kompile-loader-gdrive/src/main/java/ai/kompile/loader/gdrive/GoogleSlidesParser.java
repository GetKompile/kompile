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

package ai.kompile.loader.gdrive;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import ai.kompile.core.graphrag.model.Graph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Parses Google Slides presentations via the Slides API v1 to produce
 * per-slide documents with speaker notes, table extraction, and image metadata.
 *
 * <p>Uses raw HTTP against {@code slides.googleapis.com} — no Google client
 * library dependency — consistent with the project's existing approach in
 * {@link GoogleDriveLoaderImpl} and {@link GoogleSheetsParser}.
 *
 * <p>For each slide, produces a Document with:
 * <ul>
 *   <li>{@code content_type = "slide"} for graph extractor routing</li>
 *   <li>{@code pptx.slideTitle} — slide title text (if present)</li>
 *   <li>{@code pptx.speakerNotes} — speaker notes text</li>
 *   <li>{@code pptx.slideHyperlinks} — hyperlinks found in slide elements</li>
 *   <li>Table metadata with cell-level graph (if slide contains tables)</li>
 *   <li>Google Drive metadata (gdrive_file_id, gdrive_slide_id, etc.)</li>
 * </ul>
 */
public class GoogleSlidesParser {

    private static final Logger logger = LoggerFactory.getLogger(GoogleSlidesParser.class);

    private static final String SLIDES_API_BASE = "https://slides.googleapis.com/v1/presentations";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleSlidesParser(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Load a Google Slides presentation as a list of slide documents, one per slide.
     *
     * @param presentationId the Google Drive file ID
     * @param accessToken    valid OAuth2 access token with presentations.readonly scope
     * @param driveMetadata  metadata from the Drive API (name, webViewLink, etc.)
     * @return list of Documents, one per slide
     */
    public List<Document> loadPresentation(String presentationId, String accessToken,
                                            Map<String, Object> driveMetadata) throws Exception {
        JsonNode presentation = fetchPresentation(presentationId, accessToken);
        if (presentation == null) {
            return List.of();
        }

        String title = presentation.path("title").asText(presentationId);
        JsonNode slides = presentation.path("slides");
        if (!slides.isArray() || slides.isEmpty()) {
            return List.of();
        }

        // Extract page dimensions for metadata
        JsonNode pageSize = presentation.path("pageSize");
        String pageSizeStr = null;
        if (pageSize.has("width") && pageSize.has("height")) {
            pageSizeStr = pageSize.path("width").path("magnitude").asText("0") + "x"
                    + pageSize.path("height").path("magnitude").asText("0")
                    + " " + pageSize.path("width").path("unit").asText("EMU");
        }

        List<Document> documents = new ArrayList<>();
        int slideIndex = 0;

        for (JsonNode slide : slides) {
            Document slideDoc = buildSlideDocument(
                    slide, slideIndex, title, presentationId, pageSizeStr,
                    slides.size(), driveMetadata);
            if (slideDoc != null) {
                documents.add(slideDoc);
            }
            slideIndex++;
        }

        logger.info("Parsed Google Slides presentation '{}': {} slides → {} documents",
                title, slides.size(), documents.size());
        return documents;
    }

    private Document buildSlideDocument(JsonNode slide, int slideIndex, String presentationTitle,
                                         String presentationId, String pageSizeStr,
                                         int totalSlides, Map<String, Object> driveMetadata) {
        String slideObjectId = slide.path("objectId").asText("slide_" + slideIndex);

        // Extract all text from slide elements
        StringBuilder slideText = new StringBuilder();
        String slideTitle = null;
        List<String> bulletPoints = new ArrayList<>();
        List<Map<String, String>> hyperlinks = new ArrayList<>();
        List<Map<String, String>> images = new ArrayList<>();
        List<Map<String, Object>> tables = new ArrayList<>();

        JsonNode pageElements = slide.path("pageElements");
        if (pageElements.isArray()) {
            for (JsonNode element : pageElements) {
                String elementType = identifyElementType(element);

                if ("shape".equals(elementType) || "text".equals(elementType)) {
                    JsonNode textContent = element.path("shape").path("textContent");
                    if (textContent.isMissingNode()) {
                        textContent = element.path("textContent");
                    }
                    String text = extractTextFromTextContent(textContent, hyperlinks, bulletPoints);
                    if (text != null && !text.isBlank()) {
                        // Heuristic: first non-empty text box with placeholder type TITLE or CENTERED_TITLE is the title
                        String placeholderType = element.path("shape").path("placeholder").path("type").asText("");
                        if (slideTitle == null && ("TITLE".equals(placeholderType)
                                || "CENTERED_TITLE".equals(placeholderType))) {
                            slideTitle = text.trim();
                        }
                        slideText.append(text).append("\n");
                    }
                } else if ("image".equals(elementType)) {
                    Map<String, String> imgMeta = new LinkedHashMap<>();
                    String contentUrl = element.path("image").path("contentUrl").asText(null);
                    if (contentUrl != null) imgMeta.put("url", contentUrl);
                    String sourceUrl = element.path("image").path("sourceUrl").asText(null);
                    if (sourceUrl != null) imgMeta.put("sourceUrl", sourceUrl);
                    String description = element.path("description").asText(null);
                    if (description != null) imgMeta.put("altText", description);
                    imgMeta.put("elementId", element.path("objectId").asText(""));
                    images.add(imgMeta);
                } else if ("table".equals(elementType)) {
                    Map<String, Object> tableData = extractTable(element, presentationId, slideIndex);
                    if (tableData != null) {
                        tables.add(tableData);
                    }
                }
            }
        }

        // Extract speaker notes
        String speakerNotes = extractSpeakerNotes(slide);

        // Build document content
        String slideName = slideTitle != null ? slideTitle : "Slide " + (slideIndex + 1);
        StringBuilder content = new StringBuilder();
        content.append("Slide ").append(slideIndex + 1).append(" of ").append(totalSlides);
        content.append(" in '").append(presentationTitle).append("'");
        if (slideTitle != null) {
            content.append(": ").append(slideTitle);
        }
        content.append("\n\n");
        if (!slideText.isEmpty()) {
            content.append(slideText);
        }
        if (speakerNotes != null) {
            content.append("\n\nSpeaker Notes:\n").append(speakerNotes);
        }

        Document doc = new Document(content.toString());
        Map<String, Object> meta = doc.getMetadata();

        // Standard metadata
        meta.put(GraphConstants.META_CONTENT_TYPE, "slide");
        meta.put(GraphConstants.META_DOCUMENT_TYPE, "presentation");
        meta.put(GraphConstants.META_SOURCE, "gdrive:" + presentationId);
        meta.put(GraphConstants.META_SOURCE_PATH, "gdrive:" + presentationId);
        meta.put(GraphConstants.META_SOURCE_TYPE, "GDRIVE");
        meta.put(GraphConstants.META_FILE_NAME, presentationTitle + ".pptx");

        // Slide-specific metadata (matching OfficeGraphExtractor's expected keys)
        meta.put(GraphConstants.META_SLIDE_TITLE, slideName);
        meta.put(GraphConstants.META_SLIDE_NUMBER, slideIndex);
        meta.put("pptx.slideTitle", slideName);
        meta.put("pptx.slideIndex", slideIndex);
        meta.put("pptx.totalSlides", totalSlides);
        meta.put("slideObjectId", slideObjectId);

        // Speaker notes
        if (speakerNotes != null && !speakerNotes.isBlank()) {
            meta.put(GraphConstants.META_SPEAKER_NOTES, speakerNotes);
            meta.put("pptx.speakerNotes", speakerNotes);
        }

        // Bullet points
        if (!bulletPoints.isEmpty()) {
            meta.put("pptx.slideBullets", bulletPoints.stream()
                    .map(b -> Map.of("text", b))
                    .toList());
        }

        // Hyperlinks
        if (!hyperlinks.isEmpty()) {
            meta.put("pptx.slideHyperlinks", hyperlinks);
        }

        // Images
        if (!images.isEmpty()) {
            meta.put("pptx.slideImages", images);
        }

        // Tables — attach cell graph and table metadata
        if (!tables.isEmpty()) {
            // First table's cell graph (if any) goes into META_TABLE_GRAPH
            for (Map<String, Object> tableData : tables) {
                String tableGraphJson = (String) tableData.get("tableGraph");
                if (tableGraphJson != null) {
                    meta.put(GraphConstants.META_TABLE_GRAPH, tableGraphJson);
                    break; // Only one table graph per slide document
                }
            }
        }

        // Slide layout (from properties)
        String layoutId = slide.path("slideProperties").path("layoutObjectId").asText(null);
        if (layoutId != null) {
            meta.put(GraphConstants.META_SLIDE_LAYOUT, layoutId);
        }

        // Page size
        if (pageSizeStr != null) {
            meta.put("slidePageSize", pageSizeStr);
        }

        // Google Drive metadata
        if (driveMetadata != null) {
            driveMetadata.forEach((key, value) -> meta.putIfAbsent(key, value));
        }
        meta.put("gdrive_file_id", presentationId);
        meta.put("gdrive_slide_id", slideObjectId);

        // GWorkspace service marker so GWorkspaceGraphExtractor handles it
        meta.put(GraphConstants.META_GWORKSPACE_SERVICE, "drive");
        meta.put("gworkspace.drive.fileId", presentationId);
        meta.put("gworkspace.drive.fileName", presentationTitle);
        meta.put("gworkspace.drive.mimeType", "application/vnd.google-apps.presentation");

        return doc;
    }

    private String extractSpeakerNotes(JsonNode slide) {
        JsonNode notesPage = slide.path("slideProperties").path("notesPage");
        if (notesPage.isMissingNode()) return null;

        JsonNode notesElements = notesPage.path("pageElements");
        if (!notesElements.isArray()) return null;

        StringBuilder notes = new StringBuilder();
        for (JsonNode element : notesElements) {
            // Speaker notes are in the shape with placeholder type BODY
            String placeholderType = element.path("shape").path("placeholder").path("type").asText("");
            if ("BODY".equals(placeholderType)) {
                JsonNode textContent = element.path("shape").path("textContent");
                String text = extractTextFromTextContent(textContent, null, null);
                if (text != null && !text.isBlank()) {
                    notes.append(text);
                }
            }
        }
        return notes.isEmpty() ? null : notes.toString().trim();
    }

    private String extractTextFromTextContent(JsonNode textContent,
                                               List<Map<String, String>> hyperlinks,
                                               List<String> bulletPoints) {
        if (textContent == null || textContent.isMissingNode()) return null;

        JsonNode textElements = textContent.path("textElements");
        if (!textElements.isArray()) return null;

        StringBuilder text = new StringBuilder();
        boolean isBullet = false;

        for (JsonNode textElement : textElements) {
            // Check for paragraph markers with bullet settings
            JsonNode paragraphMarker = textElement.path("paragraphMarker");
            if (!paragraphMarker.isMissingNode()) {
                JsonNode bullet = paragraphMarker.path("bullet");
                isBullet = !bullet.isMissingNode();
                continue;
            }

            JsonNode textRun = textElement.path("textRun");
            if (textRun.isMissingNode()) continue;

            String content = textRun.path("content").asText("");
            if (content.isEmpty()) continue;

            text.append(content);

            // Track bullet text
            if (isBullet && bulletPoints != null && !content.isBlank()) {
                bulletPoints.add(content.trim());
            }

            // Extract hyperlinks from text style
            JsonNode link = textRun.path("style").path("link");
            if (!link.isMissingNode() && hyperlinks != null) {
                String url = link.path("url").asText(null);
                if (url != null) {
                    Map<String, String> linkMeta = new LinkedHashMap<>();
                    linkMeta.put("url", url);
                    linkMeta.put("text", content.trim());
                    hyperlinks.add(linkMeta);
                }
            }
        }

        return text.isEmpty() ? null : text.toString();
    }

    private Map<String, Object> extractTable(JsonNode element, String presentationId, int slideIndex) {
        JsonNode table = element.path("table");
        if (table.isMissingNode()) return null;

        int rows = table.path("rows").asInt(0);
        int columns = table.path("columns").asInt(0);
        if (rows == 0 || columns == 0) return null;

        JsonNode tableRows = table.path("tableRows");
        if (!tableRows.isArray()) return null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", rows);
        result.put("columnCount", columns);

        List<String> headers = new ArrayList<>();
        List<List<String>> allRows = new ArrayList<>();

        for (JsonNode tableRow : tableRows) {
            JsonNode tableCells = tableRow.path("tableCells");
            if (!tableCells.isArray()) continue;

            List<String> rowValues = new ArrayList<>();
            for (JsonNode cell : tableCells) {
                JsonNode textContent = cell.path("textContent");
                String cellText = extractTextFromTextContent(textContent, null, null);
                rowValues.add(cellText != null ? cellText.trim() : "");
            }
            allRows.add(rowValues);
        }

        if (!allRows.isEmpty()) {
            headers = allRows.get(0);
            result.put("headers", headers);
        }

        // Build cell-level table graph
        if (allRows.size() > 1 && !headers.isEmpty()) {
            String ns = "gslides:" + presentationId + "/slide:" + slideIndex + "/tbl:0";
            TableCellGraphBuilder builder = new TableCellGraphBuilder()
                    .namespace(ns)
                    .tableName("Slide " + (slideIndex + 1) + " Table")
                    .headers(headers);
            for (List<String> row : allRows) {
                builder.addRow(row);
            }
            Graph cellGraph = builder.build();
            if (!cellGraph.getEntities().isEmpty()) {
                result.put("tableGraph", TableCellGraphBuilder.toJson(cellGraph));
            }
        }

        return result;
    }

    private String identifyElementType(JsonNode element) {
        if (element.has("shape")) return "shape";
        if (element.has("image")) return "image";
        if (element.has("table")) return "table";
        if (element.has("video")) return "video";
        if (element.has("sheetsChart")) return "sheetsChart";
        if (element.has("line")) return "line";
        if (element.has("wordArt")) return "wordArt";
        if (element.has("elementGroup")) return "elementGroup";
        return "unknown";
    }

    private JsonNode fetchPresentation(String presentationId, String accessToken) throws Exception {
        String url = SLIDES_API_BASE + "/" + URLEncoder.encode(presentationId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            logger.warn("Google Slides API fetch failed for {}: HTTP {} — {}",
                    presentationId, response.statusCode(), response.body());
            return null;
        }
        return objectMapper.readTree(response.body());
    }
}
