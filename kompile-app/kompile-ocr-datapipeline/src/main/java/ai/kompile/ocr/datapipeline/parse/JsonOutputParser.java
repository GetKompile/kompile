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

package ai.kompile.ocr.datapipeline.parse;

import ai.kompile.ocr.datapipeline.config.OutputParseConfig;
import ai.kompile.ocr.datapipeline.entity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Parses JSON output (e.g., from LayoutLM or custom models) into document entities.
 */
public class JsonOutputParser {

    private static final Logger log = LoggerFactory.getLogger(JsonOutputParser.class);
    private final ObjectMapper objectMapper;

    public JsonOutputParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parses JSON content into entities.
     */
    public List<DocumentEntity> parse(String json, OutputParseConfig config) {
        List<DocumentEntity> entities = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);

            // Handle array of entities
            if (root.isArray()) {
                for (JsonNode node : root) {
                    DocumentEntity entity = parseNode(node, config);
                    if (entity != null) {
                        entities.add(entity);
                    }
                }
            }
            // Handle object with entities field
            else if (root.isObject()) {
                JsonNode entitiesNode = root.get("entities");
                if (entitiesNode != null && entitiesNode.isArray()) {
                    for (JsonNode node : entitiesNode) {
                        DocumentEntity entity = parseNode(node, config);
                        if (entity != null) {
                            entities.add(entity);
                        }
                    }
                }

                // Check for specific entity arrays
                parseEntityArray(root, "tables", DocumentEntity.EntityType.TABLE, entities, config);
                parseEntityArray(root, "figures", DocumentEntity.EntityType.FIGURE, entities, config);
                parseEntityArray(root, "formulas", DocumentEntity.EntityType.FORMULA, entities, config);
                parseEntityArray(root, "code_blocks", DocumentEntity.EntityType.CODE, entities, config);
            }

        } catch (Exception e) {
            log.error("Failed to parse JSON output: {}", e.getMessage());
        }

        return entities;
    }

    /**
     * Parses a single JSON node into an entity.
     */
    private DocumentEntity parseNode(JsonNode node, OutputParseConfig config) {
        String type = getTextValue(node, "type", getTextValue(node, "entity_type", "unknown"));

        return switch (type.toLowerCase()) {
            case "table" -> parseTableNode(node, config);
            case "figure", "image" -> config.isExtractFigures() ? parseFigureNode(node) : null;
            case "formula", "equation" -> config.isExtractFormulas() ? parseFormulaNode(node) : null;
            case "code" -> config.isExtractCode() ? parseCodeNode(node) : null;
            case "list" -> config.isExtractLists() ? parseListNode(node) : null;
            case "heading", "title" -> config.isExtractHeadings() ? parseHeadingNode(node) : null;
            default -> null;
        };
    }

    /**
     * Parses entity array from a named field.
     */
    private void parseEntityArray(JsonNode root, String fieldName, DocumentEntity.EntityType type,
                                   List<DocumentEntity> entities, OutputParseConfig config) {
        JsonNode arrayNode = root.get(fieldName);
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode node : arrayNode) {
                DocumentEntity entity = switch (type) {
                    case TABLE -> parseTableNode(node, config);
                    case FIGURE -> config.isExtractFigures() ? parseFigureNode(node) : null;
                    case FORMULA -> config.isExtractFormulas() ? parseFormulaNode(node) : null;
                    case CODE -> config.isExtractCode() ? parseCodeNode(node) : null;
                    default -> null;
                };
                if (entity != null) {
                    entities.add(entity);
                }
            }
        }
    }

    /**
     * Parses a table from JSON.
     */
    private TableEntity parseTableNode(JsonNode node, OutputParseConfig config) {
        List<List<TableEntity.TableCell>> rows = new ArrayList<>();
        List<String> headers = null;

        // Parse headers
        JsonNode headersNode = node.get("headers");
        if (headersNode != null && headersNode.isArray()) {
            headers = new ArrayList<>();
            for (JsonNode h : headersNode) {
                headers.add(h.asText());
            }
        }

        // Parse rows
        JsonNode rowsNode = node.get("rows");
        if (rowsNode != null && rowsNode.isArray()) {
            for (JsonNode rowNode : rowsNode) {
                List<TableEntity.TableCell> row = new ArrayList<>();
                if (rowNode.isArray()) {
                    for (JsonNode cellNode : rowNode) {
                        if (cellNode.isObject()) {
                            String text = getTextValue(cellNode, "text", getTextValue(cellNode, "value", ""));
                            int colspan = getIntValue(cellNode, "colspan", 1);
                            int rowspan = getIntValue(cellNode, "rowspan", 1);
                            row.add(new TableEntity.TableCell(text, colspan, rowspan, false));
                        } else {
                            row.add(new TableEntity.TableCell(cellNode.asText()));
                        }
                    }
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        }

        // Parse cells array format
        JsonNode cellsNode = node.get("cells");
        if (cellsNode != null && cellsNode.isArray() && rows.isEmpty()) {
            // Reconstruct rows from cells
            // This requires row/col indices in cells
            // For simplicity, just create a single row per cell for now
            List<TableEntity.TableCell> singleRow = new ArrayList<>();
            for (JsonNode cellNode : cellsNode) {
                String text = getTextValue(cellNode, "text", "");
                singleRow.add(new TableEntity.TableCell(text));
            }
            if (!singleRow.isEmpty()) {
                rows.add(singleRow);
            }
        }

        if (rows.isEmpty()) {
            return null;
        }

        return TableEntity.builder()
                .id(getTextValue(node, "id", UUID.randomUUID().toString()))
                .type(DocumentEntity.EntityType.TABLE)
                .rows(rows)
                .headers(headers)
                .htmlContent(getTextValue(node, "html", null))
                .markdownContent(getTextValue(node, "markdown", null))
                .description(getTextValue(node, "description", null))
                .confidence(getDoubleValue(node, "confidence", 1.0))
                .pageNumber(getIntValue(node, "page", 0))
                .build();
    }

    /**
     * Parses a figure from JSON.
     */
    private FigureEntity parseFigureNode(JsonNode node) {
        return FigureEntity.builder()
                .id(getTextValue(node, "id", UUID.randomUUID().toString()))
                .type(DocumentEntity.EntityType.FIGURE)
                .caption(getTextValue(node, "caption", null))
                .altText(getTextValue(node, "alt_text", getTextValue(node, "alt", null)))
                .reference(getTextValue(node, "reference", null))
                .imageData(getTextValue(node, "image_data", null))
                .imageFormat(getTextValue(node, "format", null))
                .confidence(getDoubleValue(node, "confidence", 1.0))
                .pageNumber(getIntValue(node, "page", 0))
                .build();
    }

    /**
     * Parses a formula from JSON.
     */
    private FormulaEntity parseFormulaNode(JsonNode node) {
        return FormulaEntity.builder()
                .id(getTextValue(node, "id", UUID.randomUUID().toString()))
                .type(DocumentEntity.EntityType.FORMULA)
                .latex(getTextValue(node, "latex", getTextValue(node, "content", null)))
                .mathml(getTextValue(node, "mathml", null))
                .plainText(getTextValue(node, "plain_text", null))
                .description(getTextValue(node, "description", null))
                .inline(getBooleanValue(node, "inline", false))
                .equationNumber(getTextValue(node, "equation_number", null))
                .confidence(getDoubleValue(node, "confidence", 1.0))
                .pageNumber(getIntValue(node, "page", 0))
                .build();
    }

    /**
     * Parses a code block from JSON.
     */
    private CodeEntity parseCodeNode(JsonNode node) {
        return CodeEntity.builder()
                .id(getTextValue(node, "id", UUID.randomUUID().toString()))
                .type(DocumentEntity.EntityType.CODE)
                .code(getTextValue(node, "code", getTextValue(node, "content", "")))
                .language(getTextValue(node, "language", null))
                .summary(getTextValue(node, "summary", null))
                .filename(getTextValue(node, "filename", null))
                .confidence(getDoubleValue(node, "confidence", 1.0))
                .pageNumber(getIntValue(node, "page", 0))
                .build();
    }

    /**
     * Parses a list from JSON.
     */
    private ListEntity parseListNode(JsonNode node) {
        List<String> items = new ArrayList<>();
        JsonNode itemsNode = node.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode item : itemsNode) {
                items.add(item.asText());
            }
        }

        if (items.isEmpty()) {
            return null;
        }

        return ListEntity.builder()
                .id(getTextValue(node, "id", UUID.randomUUID().toString()))
                .type(DocumentEntity.EntityType.LIST)
                .items(items)
                .ordered(getBooleanValue(node, "ordered", false))
                .title(getTextValue(node, "title", null))
                .confidence(getDoubleValue(node, "confidence", 1.0))
                .pageNumber(getIntValue(node, "page", 0))
                .build();
    }

    /**
     * Parses a heading from JSON.
     */
    private HeadingEntity parseHeadingNode(JsonNode node) {
        String text = getTextValue(node, "text", getTextValue(node, "content", null));
        if (text == null || text.isEmpty()) {
            return null;
        }

        return HeadingEntity.builder()
                .id(getTextValue(node, "id", UUID.randomUUID().toString()))
                .type(DocumentEntity.EntityType.HEADING)
                .text(text)
                .level(getIntValue(node, "level", 1))
                .sectionNumber(getTextValue(node, "section_number", null))
                .confidence(getDoubleValue(node, "confidence", 1.0))
                .pageNumber(getIntValue(node, "page", 0))
                .build();
    }

    // Helper methods for safe JSON value extraction

    private String getTextValue(JsonNode node, String field, String defaultValue) {
        JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull() ? fieldNode.asText() : defaultValue;
    }

    private int getIntValue(JsonNode node, String field, int defaultValue) {
        JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull() ? fieldNode.asInt(defaultValue) : defaultValue;
    }

    private double getDoubleValue(JsonNode node, String field, double defaultValue) {
        JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull() ? fieldNode.asDouble(defaultValue) : defaultValue;
    }

    private boolean getBooleanValue(JsonNode node, String field, boolean defaultValue) {
        JsonNode fieldNode = node.get(field);
        return fieldNode != null && !fieldNode.isNull() ? fieldNode.asBoolean(defaultValue) : defaultValue;
    }
}
