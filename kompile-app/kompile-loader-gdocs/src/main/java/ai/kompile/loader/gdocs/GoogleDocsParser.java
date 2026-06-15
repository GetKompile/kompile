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

package ai.kompile.loader.gdocs;

import ai.kompile.core.graphrag.GraphConstants;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.*;

/**
 * Parses Google Docs API v1 structured document JSON into Spring AI Documents.
 *
 * The Docs API returns a rich structural representation with paragraphs,
 * headings, tables, lists, inline images, footnotes, etc.
 * This parser converts it to readable markdown-flavoured text with metadata.
 */
@Slf4j
public class GoogleDocsParser {

    // Accumulators populated during body rendering, read by parse()
    private List<Map<String, String>> headings;
    private List<Map<String, String>> links;
    private List<Map<String, String>> suggestedEdits;
    private List<String> tableGraphJsons;
    private int tableGraphIndex;
    private String currentDocumentId;

    /**
     * Parses a Google Docs API document response into a Spring AI Document.
     *
     * @param docsJson the full document JSON from the Docs API
     * @param driveMetadata file metadata from the Drive API (id, name, owners, etc.)
     * @return a Document with markdown-like text content and rich metadata
     */
    public Document parse(JsonNode docsJson, JsonNode driveMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // Document identity
        String documentId = docsJson.path("documentId").asText("");
        String title = docsJson.path("title").asText("Untitled");
        String sourcePath = "gdocs://documents/" + documentId;
        metadata.put(GraphConstants.META_SOURCE, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "gdocs");
        metadata.put(GraphConstants.META_LOADER, "Google Docs Loader");
        metadata.put(GraphConstants.META_FILE_NAME, title);
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "Google Doc");
        metadata.put("gdocs.documentId", documentId);
        metadata.put("gdocs.title", title);

        // Drive metadata if available
        if (driveMetadata != null) {
            populateDriveMetadata(driveMetadata, metadata);
        }

        // Initialize accumulators for table graph building
        currentDocumentId = documentId;
        tableGraphJsons = null;
        tableGraphIndex = 0;

        // Extract body content
        String bodyText = extractBody(docsJson.path("body"));

        // Extract headers/footers
        String headersText = extractHeadersFooters(docsJson);

        // Extract footnotes (also populates gdocs.footnotes metadata for graph extractor)
        String footnotesText = extractFootnotes(docsJson.path("footnotes"), metadata);

        // Count structural elements
        int tableCount = countTables(docsJson.path("body"));
        int imageCount = countInlineImages(docsJson.path("body"));
        int listCount = countListItems(docsJson.path("body"));
        metadata.put("gdocs.tableCount", tableCount);
        metadata.put("gdocs.imageCount", imageCount);
        metadata.put("gdocs.listItemCount", listCount);

        // Captured headings and links from body rendering
        if (headings != null && !headings.isEmpty()) {
            metadata.put("gdocs.headings", headings);
        }
        if (links != null && !links.isEmpty()) {
            metadata.put("gdocs.links", links);
        }
        if (suggestedEdits != null && !suggestedEdits.isEmpty()) {
            metadata.put("gdocs.suggestedEdits", suggestedEdits);
        }

        // Attach cell-level table graph(s) for knowledge graph persistence
        if (tableGraphJsons != null && !tableGraphJsons.isEmpty()) {
            if (tableGraphJsons.size() == 1) {
                metadata.put(GraphConstants.META_TABLE_GRAPH, tableGraphJsons.get(0));
            } else {
                // Merge all table graphs into a single combined graph
                ai.kompile.core.graphrag.model.Graph combined = new ai.kompile.core.graphrag.model.Graph();
                combined.setId("gdocs-tables:" + documentId);
                combined.setEntities(new ArrayList<>());
                combined.setRelationships(new ArrayList<>());
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                for (String json : tableGraphJsons) {
                    try {
                        ai.kompile.core.graphrag.model.Graph g = om.readValue(json, ai.kompile.core.graphrag.model.Graph.class);
                        if (g.getEntities() != null) combined.getEntities().addAll(g.getEntities());
                        if (g.getRelationships() != null) combined.getRelationships().addAll(g.getRelationships());
                    } catch (Exception e) {
                        log.debug("Failed to parse table graph JSON from Google Doc: {}", e.getMessage());
                    }
                }
                metadata.put(GraphConstants.META_TABLE_GRAPH, ai.kompile.core.graphrag.table.TableCellGraphBuilder.toJson(combined));
            }
        }

        // Compose full content
        StringBuilder content = new StringBuilder();
        content.append("# ").append(title).append("\n\n");
        content.append(bodyText);

        if (!footnotesText.isEmpty()) {
            content.append("\n\n---\n## Footnotes\n").append(footnotesText);
        }

        if (!headersText.isEmpty()) {
            content.append("\n\n---\n").append(headersText);
        }

        return new Document(content.toString().trim(), metadata);
    }

    /**
     * Parses a document using only Drive metadata and plain text export content.
     * Used as fallback when the Docs API structured content is unavailable.
     */
    public Document parseFromPlainText(String plainText, JsonNode driveMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        String fileId = driveMetadata.path("id").asText("");
        String name = driveMetadata.path("name").asText("Untitled");

        String sourcePath = "gdocs://documents/" + fileId;
        metadata.put(GraphConstants.META_SOURCE, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "gdocs");
        metadata.put(GraphConstants.META_LOADER, "Google Docs Loader");
        metadata.put(GraphConstants.META_FILE_NAME, name);
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "Google Doc");
        metadata.put("gdocs.documentId", fileId);
        metadata.put("gdocs.title", name);
        metadata.put("gdocs.parseMode", "plaintext_fallback");
        populateDriveMetadata(driveMetadata, metadata);

        return new Document(plainText != null ? plainText : "", metadata);
    }

    // ── Body extraction ───────────────────────────────────────────────────

    String extractBody(JsonNode body) {
        if (body == null || body.isMissingNode()) return "";

        JsonNode content = body.get("content");
        if (content == null || !content.isArray()) return "";

        headings = new ArrayList<>();
        links = new ArrayList<>();
        suggestedEdits = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        for (JsonNode element : content) {
            sb.append(renderStructuralElement(element));
        }
        return sb.toString().trim();
    }

    private String renderStructuralElement(JsonNode element) {
        if (element.has("paragraph")) {
            return renderParagraph(element.get("paragraph"));
        }
        if (element.has("table")) {
            return renderTable(element.get("table"));
        }
        if (element.has("sectionBreak")) {
            return "\n---\n\n";
        }
        if (element.has("tableOfContents")) {
            return renderTableOfContents(element.get("tableOfContents"));
        }
        return "";
    }

    private String renderParagraph(JsonNode paragraph) {
        StringBuilder sb = new StringBuilder();

        // Detect heading level
        JsonNode style = paragraph.path("paragraphStyle");
        String namedStyle = style.path("namedStyleType").asText("");
        String headingPrefix = headingPrefix(namedStyle);

        // Detect list item
        JsonNode bullet = paragraph.path("bullet");
        String listPrefix = "";
        if (!bullet.isMissingNode()) {
            int nestingLevel = bullet.path("nestingLevel").asInt(0);
            listPrefix = "  ".repeat(nestingLevel) + "- ";
        }

        // Extract text from elements
        JsonNode elements = paragraph.get("elements");
        if (elements != null && elements.isArray()) {
            sb.append(headingPrefix);
            sb.append(listPrefix);
            for (JsonNode elem : elements) {
                sb.append(renderParagraphElement(elem));
            }
        }

        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }

        // Add extra blank line after headings
        if (!headingPrefix.isEmpty()) {
            sb.append('\n');
            // Capture heading into metadata accumulator
            if (headings != null) {
                String headingText = sb.toString().replace(headingPrefix, "").replace(listPrefix, "").trim();
                if (!headingText.isEmpty()) {
                    int level = headingLevel(namedStyle);
                    Map<String, String> heading = new LinkedHashMap<>();
                    heading.put("text", headingText);
                    heading.put("level", String.valueOf(level));
                    heading.put("index", String.valueOf(headings.size()));
                    headings.add(heading);
                }
            }
        }

        return sb.toString();
    }

    private String renderParagraphElement(JsonNode element) {
        // Track suggested insertions/deletions on this element
        if (suggestedEdits != null && suggestedEdits.size() < 200) {
            captureSuggestedEdits(element);
        }

        // Text run
        JsonNode textRun = element.get("textRun");
        if (textRun != null) {
            String text = textRun.path("content").asText("");

            // Track suggested insertions/deletions on the text run itself
            if (suggestedEdits != null && suggestedEdits.size() < 200) {
                captureSuggestedEdits(textRun);
            }

            // Apply bold/italic from text style
            JsonNode textStyle = textRun.path("textStyle");
            boolean bold = textStyle.path("bold").asBoolean(false);
            boolean italic = textStyle.path("italic").asBoolean(false);

            // Capture hyperlink if present
            JsonNode link = textStyle.path("link");
            if (!link.isMissingNode() && links != null) {
                String url = link.path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    String linkText = text.trim();
                    Map<String, String> linkEntry = new LinkedHashMap<>();
                    linkEntry.put("url", url);
                    linkEntry.put("text", linkText.isEmpty() ? url : linkText);
                    links.add(linkEntry);
                }
            }

            if (bold && italic) return "***" + text.stripTrailing() + "***" + trailingWhitespace(text);
            if (bold) return "**" + text.stripTrailing() + "**" + trailingWhitespace(text);
            if (italic) return "*" + text.stripTrailing() + "*" + trailingWhitespace(text);
            return text;
        }

        // Inline object (image)
        if (element.has("inlineObjectElement")) {
            String objectId = element.path("inlineObjectElement").path("inlineObjectId").asText("");
            return "[Image: " + objectId + "]";
        }

        // Footnote reference
        if (element.has("footnoteReference")) {
            String footnoteId = element.path("footnoteReference").path("footnoteId").asText("");
            return "[^" + footnoteId + "]";
        }

        // Page break, column break, etc.
        return "";
    }

    // ── Table rendering ───────────────────────────────────────────────────

    private String renderTable(JsonNode table) {
        StringBuilder sb = new StringBuilder("\n");
        JsonNode rows = table.get("tableRows");
        if (rows == null || !rows.isArray()) return "";

        // Accumulate cell data for TableCellGraphBuilder
        List<List<String>> cellData = new ArrayList<>();

        boolean isHeader = true;
        for (JsonNode row : rows) {
            JsonNode cells = row.get("tableCells");
            if (cells == null) continue;

            List<String> rowValues = new ArrayList<>();
            sb.append("| ");
            for (JsonNode cell : cells) {
                String cellText = extractCellText(cell).replace("\n", " ").trim();
                sb.append(cellText).append(" | ");
                rowValues.add(cellText);
            }
            cellData.add(rowValues);
            sb.append("\n");

            // Add markdown header separator after first row
            if (isHeader) {
                sb.append("| ");
                for (int i = 0; i < cells.size(); i++) {
                    sb.append("--- | ");
                }
                sb.append("\n");
                isHeader = false;
            }
        }
        sb.append("\n");

        // Build cell-level graph for this table
        if (cellData.size() >= 2) {
            try {
                ai.kompile.core.graphrag.table.TableCellGraphBuilder builder =
                        new ai.kompile.core.graphrag.table.TableCellGraphBuilder()
                                .namespace("gdocs:" + currentDocumentId + "/table:" + tableGraphIndex)
                                .tableName("Table-" + (tableGraphIndex + 1))
                                .firstRowIsHeader(true);
                for (List<String> rowVals : cellData) {
                    builder.addRow(rowVals);
                }
                // Set headers from first row
                if (!cellData.isEmpty()) {
                    builder.headers(cellData.get(0));
                }
                ai.kompile.core.graphrag.model.Graph cellGraph = builder.build();
                if (cellGraph.getEntities() != null && !cellGraph.getEntities().isEmpty()) {
                    if (tableGraphJsons == null) tableGraphJsons = new ArrayList<>();
                    tableGraphJsons.add(ai.kompile.core.graphrag.table.TableCellGraphBuilder.toJson(cellGraph));
                }
            } catch (Exception e) {
                log.debug("Failed to build table graph for table {}: {}", tableGraphIndex, e.getMessage());
            }
            tableGraphIndex++;
        }

        return sb.toString();
    }

    private String extractCellText(JsonNode cell) {
        JsonNode content = cell.get("content");
        if (content == null || !content.isArray()) return "";

        StringBuilder sb = new StringBuilder();
        for (JsonNode element : content) {
            if (element.has("paragraph")) {
                JsonNode elements = element.path("paragraph").get("elements");
                if (elements != null) {
                    for (JsonNode elem : elements) {
                        JsonNode textRun = elem.get("textRun");
                        if (textRun != null) {
                            sb.append(textRun.path("content").asText(""));
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    // ── Table of Contents ─────────────────────────────────────────────────

    private String renderTableOfContents(JsonNode toc) {
        JsonNode content = toc.get("content");
        if (content == null || !content.isArray()) return "";

        StringBuilder sb = new StringBuilder("## Table of Contents\n");
        for (JsonNode element : content) {
            if (element.has("paragraph")) {
                JsonNode elements = element.path("paragraph").get("elements");
                if (elements != null) {
                    for (JsonNode elem : elements) {
                        JsonNode textRun = elem.get("textRun");
                        if (textRun != null) {
                            String text = textRun.path("content").asText("").trim();
                            if (!text.isEmpty()) {
                                sb.append("- ").append(text).append("\n");
                            }
                        }
                    }
                }
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    // ── Headers/Footers ───────────────────────────────────────────────────

    private String extractHeadersFooters(JsonNode docsJson) {
        StringBuilder sb = new StringBuilder();
        JsonNode headers = docsJson.get("headers");
        if (headers != null && headers.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = headers.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode headerContent = entry.getValue().path("content");
                String text = extractElementsText(headerContent);
                if (!text.isBlank()) {
                    sb.append("Header: ").append(text.trim()).append("\n");
                }
            }
        }

        JsonNode footers = docsJson.get("footers");
        if (footers != null && footers.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = footers.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode footerContent = entry.getValue().path("content");
                String text = extractElementsText(footerContent);
                if (!text.isBlank()) {
                    sb.append("Footer: ").append(text.trim()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ── Footnotes ─────────────────────────────────────────────────────────

    String extractFootnotes(JsonNode footnotes) {
        return extractFootnotes(footnotes, null);
    }

    String extractFootnotes(JsonNode footnotes, Map<String, Object> metadata) {
        if (footnotes == null || !footnotes.isObject()) return "";

        StringBuilder sb = new StringBuilder();
        List<Map<String, String>> footnoteList = metadata != null ? new ArrayList<>() : null;
        for (Iterator<Map.Entry<String, JsonNode>> it = footnotes.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String footnoteId = entry.getKey();
            JsonNode footnoteContent = entry.getValue().path("content");
            String text = extractElementsText(footnoteContent);
            if (!text.isBlank()) {
                sb.append("[^").append(footnoteId).append("]: ").append(text.trim()).append("\n");
                if (footnoteList != null) {
                    Map<String, String> fn = new LinkedHashMap<>();
                    fn.put("id", footnoteId);
                    fn.put("text", text.trim());
                    footnoteList.add(fn);
                }
            }
        }
        if (footnoteList != null && !footnoteList.isEmpty()) {
            metadata.put("gdocs.footnotes", footnoteList);
        }
        return sb.toString();
    }

    // ── Counting helpers ──────────────────────────────────────────────────

    int countTables(JsonNode body) {
        if (body == null || body.isMissingNode()) return 0;
        JsonNode content = body.get("content");
        if (content == null) return 0;
        int count = 0;
        for (JsonNode element : content) {
            if (element.has("table")) count++;
        }
        return count;
    }

    int countInlineImages(JsonNode body) {
        if (body == null || body.isMissingNode()) return 0;
        JsonNode content = body.get("content");
        if (content == null) return 0;
        int count = 0;
        for (JsonNode element : content) {
            if (element.has("paragraph")) {
                JsonNode elements = element.path("paragraph").get("elements");
                if (elements != null) {
                    for (JsonNode elem : elements) {
                        if (elem.has("inlineObjectElement")) count++;
                    }
                }
            }
        }
        return count;
    }

    int countListItems(JsonNode body) {
        if (body == null || body.isMissingNode()) return 0;
        JsonNode content = body.get("content");
        if (content == null) return 0;
        int count = 0;
        for (JsonNode element : content) {
            if (element.has("paragraph")) {
                JsonNode bullet = element.path("paragraph").path("bullet");
                if (!bullet.isMissingNode()) count++;
            }
        }
        return count;
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    private String extractElementsText(JsonNode contentArray) {
        if (contentArray == null || !contentArray.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode element : contentArray) {
            if (element.has("paragraph")) {
                JsonNode elements = element.path("paragraph").get("elements");
                if (elements != null) {
                    for (JsonNode elem : elements) {
                        JsonNode textRun = elem.get("textRun");
                        if (textRun != null) {
                            sb.append(textRun.path("content").asText(""));
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private void populateDriveMetadata(JsonNode driveMetadata, Map<String, Object> metadata) {
        if (driveMetadata.has("name")) {
            metadata.put("gdocs.fileName", driveMetadata.get("name").asText());
        }
        if (driveMetadata.has("modifiedTime")) {
            metadata.put("gdocs.modifiedTime", driveMetadata.get("modifiedTime").asText());
        }
        if (driveMetadata.has("createdTime")) {
            metadata.put("gdocs.createdTime", driveMetadata.get("createdTime").asText());
        }
        if (driveMetadata.has("webViewLink")) {
            metadata.put("gdocs.webViewLink", driveMetadata.get("webViewLink").asText());
        }
        if (driveMetadata.has("version")) {
            metadata.put("gdocs.version", driveMetadata.get("version").asText());
        }
        // Owner info
        JsonNode owners = driveMetadata.get("owners");
        if (owners != null && owners.isArray() && !owners.isEmpty()) {
            JsonNode owner = owners.get(0);
            if (owner.has("displayName")) {
                metadata.put("gdocs.owner", owner.get("displayName").asText());
            }
            if (owner.has("emailAddress")) {
                metadata.put("gdocs.ownerEmail", owner.get("emailAddress").asText());
            }
            // Store additional co-owners (beyond the primary)
            if (owners.size() > 1) {
                List<Map<String, String>> additionalOwners = new ArrayList<>();
                for (int oi = 1; oi < owners.size(); oi++) {
                    JsonNode coOwner = owners.get(oi);
                    Map<String, String> ownerMap = new LinkedHashMap<>();
                    if (coOwner.has("displayName")) ownerMap.put("displayName", coOwner.get("displayName").asText());
                    if (coOwner.has("emailAddress")) ownerMap.put("emailAddress", coOwner.get("emailAddress").asText());
                    if (!ownerMap.isEmpty()) additionalOwners.add(ownerMap);
                }
                if (!additionalOwners.isEmpty()) {
                    metadata.put("gdocs.additionalOwners", additionalOwners);
                }
            }
        }
        // Parent folder(s) — Drive API returns an array; typically one entry
        JsonNode parents = driveMetadata.get("parents");
        if (parents != null && parents.isArray() && !parents.isEmpty()) {
            metadata.put("gdocs.folderId", parents.get(0).asText());
        }

        // Last modifier
        JsonNode lastModifier = driveMetadata.get("lastModifyingUser");
        if (lastModifier != null) {
            if (lastModifier.has("displayName")) {
                metadata.put("gdocs.lastModifiedBy", lastModifier.get("displayName").asText());
            }
            if (lastModifier.has("emailAddress")) {
                metadata.put("gdocs.lastModifiedByEmail", lastModifier.get("emailAddress").asText());
            }
        }
    }

    private static String headingPrefix(String namedStyle) {
        return switch (namedStyle) {
            case "HEADING_1" -> "# ";
            case "HEADING_2" -> "## ";
            case "HEADING_3" -> "### ";
            case "HEADING_4" -> "#### ";
            case "HEADING_5" -> "##### ";
            case "HEADING_6" -> "###### ";
            case "TITLE" -> "# ";
            case "SUBTITLE" -> "## ";
            default -> "";
        };
    }

    private static int headingLevel(String namedStyle) {
        return switch (namedStyle) {
            case "HEADING_1", "TITLE" -> 1;
            case "HEADING_2", "SUBTITLE" -> 2;
            case "HEADING_3" -> 3;
            case "HEADING_4" -> 4;
            case "HEADING_5" -> 5;
            case "HEADING_6" -> 6;
            default -> 0;
        };
    }

    private static String trailingWhitespace(String text) {
        int len = text.length();
        int stripped = text.stripTrailing().length();
        if (stripped < len) {
            return text.substring(stripped);
        }
        return "";
    }

    /**
     * Captures suggested insertion/deletion IDs from a JSON node.
     * The Docs API places suggestedInsertionIds and suggestedDeletionIds arrays
     * on text runs and paragraph elements when suggestions are pending.
     */
    private void captureSuggestedEdits(JsonNode node) {
        JsonNode insertionIds = node.get("suggestedInsertionIds");
        if (insertionIds != null && insertionIds.isArray()) {
            for (JsonNode id : insertionIds) {
                String suggestionId = id.asText(null);
                if (suggestionId != null && !suggestionId.isBlank()) {
                    String content = "";
                    if (node.has("content")) {
                        content = node.path("content").asText("");
                    } else if (node.has("textRun")) {
                        content = node.path("textRun").path("content").asText("");
                    }
                    Map<String, String> edit = new LinkedHashMap<>();
                    edit.put("suggestionId", suggestionId);
                    edit.put("type", "insertion");
                    if (!content.isBlank()) {
                        edit.put("text", content.length() > 500 ? content.substring(0, 500) : content.trim());
                    }
                    suggestedEdits.add(edit);
                }
            }
        }
        JsonNode deletionIds = node.get("suggestedDeletionIds");
        if (deletionIds != null && deletionIds.isArray()) {
            for (JsonNode id : deletionIds) {
                String suggestionId = id.asText(null);
                if (suggestionId != null && !suggestionId.isBlank()) {
                    String content = "";
                    if (node.has("content")) {
                        content = node.path("content").asText("");
                    } else if (node.has("textRun")) {
                        content = node.path("textRun").path("content").asText("");
                    }
                    Map<String, String> edit = new LinkedHashMap<>();
                    edit.put("suggestionId", suggestionId);
                    edit.put("type", "deletion");
                    if (!content.isBlank()) {
                        edit.put("text", content.length() > 500 ? content.substring(0, 500) : content.trim());
                    }
                    suggestedEdits.add(edit);
                }
            }
        }
    }
}
