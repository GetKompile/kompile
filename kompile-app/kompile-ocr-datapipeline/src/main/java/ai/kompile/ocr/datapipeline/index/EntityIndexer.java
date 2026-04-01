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

package ai.kompile.ocr.datapipeline.index;

import ai.kompile.ocr.datapipeline.config.EntityIndexConfig;
import ai.kompile.ocr.datapipeline.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Indexes extracted document entities for vector search.
 * Creates searchable documents from entities according to configuration.
 */
public class EntityIndexer {

    private static final Logger log = LoggerFactory.getLogger(EntityIndexer.class);

    /**
     * Indexes a list of entities according to configuration.
     *
     * @param entities List of extracted entities
     * @param sourceDocId ID of the source document
     * @param config Indexing configuration
     * @return List of Spring AI Documents ready for vector store
     */
    public List<Document> index(List<DocumentEntity> entities, String sourceDocId, EntityIndexConfig config) {
        List<Document> documents = new ArrayList<>();

        for (DocumentEntity entity : entities) {
            try {
                List<Document> indexed = indexEntity(entity, sourceDocId, config);
                documents.addAll(indexed);
            } catch (Exception e) {
                log.warn("Failed to index entity {}: {}", entity.getId(), e.getMessage());
            }
        }

        return documents;
    }

    /**
     * Indexes a single entity.
     */
    private List<Document> indexEntity(DocumentEntity entity, String sourceDocId, EntityIndexConfig config) {
        if (entity instanceof TableEntity table) {
            return indexTable(table, sourceDocId, config);
        } else if (entity instanceof FigureEntity figure) {
            return indexFigure(figure, sourceDocId, config);
        } else if (entity instanceof FormulaEntity formula) {
            return indexFormula(formula, sourceDocId, config);
        } else if (entity instanceof CodeEntity code) {
            return indexCode(code, sourceDocId, config);
        } else if (entity instanceof ListEntity list) {
            return indexList(list, sourceDocId, config);
        } else if (entity instanceof HeadingEntity heading) {
            return indexHeading(heading, sourceDocId, config);
        }
        return List.of();
    }

    /**
     * Indexes a table entity.
     */
    private List<Document> indexTable(TableEntity table, String sourceDocId, EntityIndexConfig config) {
        if (config.getTableMode() == EntityIndexConfig.TableIndexMode.DISABLED) {
            return List.of();
        }

        List<Document> documents = new ArrayList<>();
        Map<String, Object> baseMeta = createBaseMetadata(table, sourceDocId, config);
        baseMeta.put("row_count", table.getRowCount());
        baseMeta.put("col_count", table.getColumnCount());

        if (table.getHeaders() != null && !table.getHeaders().isEmpty()) {
            baseMeta.put("headers", String.join(",", table.getHeaders()));
        }

        if (config.isStoreFullContent()) {
            baseMeta.put("full_content", table.toMarkdown());
            if (table.getHtmlContent() != null) {
                baseMeta.put("html_content", table.getHtmlContent());
            }
        }

        // Semantic index entry
        if (config.getTableMode() == EntityIndexConfig.TableIndexMode.SEMANTIC_ONLY ||
            config.getTableMode() == EntityIndexConfig.TableIndexMode.DUAL) {

            String summary = config.isGenerateSemanticSummary() ?
                    generateTableSummary(table) :
                    (table.getHeaders() != null ? String.join(", ", table.getHeaders()) : "Table");

            Map<String, Object> semanticMeta = new HashMap<>(baseMeta);
            semanticMeta.put("index_mode", "semantic");

            documents.add(new Document(summary, semanticMeta));
        }

        // Content index entry
        if (config.getTableMode() == EntityIndexConfig.TableIndexMode.CONTENT_ONLY ||
            config.getTableMode() == EntityIndexConfig.TableIndexMode.DUAL) {

            Map<String, Object> contentMeta = new HashMap<>(baseMeta);
            contentMeta.put("index_mode", "content");

            documents.add(new Document(table.toMarkdown(), contentMeta));
        }

        return documents;
    }

    /**
     * Generates a semantic summary for a table.
     */
    private String generateTableSummary(TableEntity table) {
        StringBuilder sb = new StringBuilder();

        if (table.getDescription() != null && !table.getDescription().isEmpty()) {
            sb.append(table.getDescription()).append(" ");
        }

        sb.append("Table with ").append(table.getRowCount()).append(" rows and ")
          .append(table.getColumnCount()).append(" columns.");

        if (table.getHeaders() != null && !table.getHeaders().isEmpty()) {
            sb.append(" Columns: ").append(String.join(", ", table.getHeaders())).append(".");
        }

        // Add sample from first data row
        if (table.getRowCount() > 0) {
            List<String> sample = table.getRowValues(0).stream()
                    .limit(3)
                    .collect(Collectors.toList());
            if (!sample.isEmpty()) {
                sb.append(" Sample: ").append(String.join(", ", sample)).append(".");
            }
        }

        return sb.toString();
    }

    /**
     * Indexes a figure entity.
     */
    private List<Document> indexFigure(FigureEntity figure, String sourceDocId, EntityIndexConfig config) {
        if (config.getFigureMode() == EntityIndexConfig.FigureIndexMode.DISABLED) {
            return List.of();
        }

        String text = switch (config.getFigureMode()) {
            case CAPTION_ONLY -> figure.getCaption();
            case WITH_ALT_TEXT -> {
                StringBuilder sb = new StringBuilder();
                if (figure.getCaption() != null) sb.append(figure.getCaption());
                if (figure.getAltText() != null) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(figure.getAltText());
                }
                yield sb.toString();
            }
            case WITH_DESCRIPTION -> figure.getCaption(); // Would need LLM for description
            default -> null;
        };

        if (text == null || text.isBlank()) {
            return List.of();
        }

        Map<String, Object> meta = createBaseMetadata(figure, sourceDocId, config);
        if (figure.getReference() != null) {
            meta.put("reference", figure.getReference());
        }
        if (figure.getFigureType() != null) {
            meta.put("figure_type", figure.getFigureType().name().toLowerCase());
        }

        return List.of(new Document(text, meta));
    }

    /**
     * Indexes a formula entity.
     */
    private List<Document> indexFormula(FormulaEntity formula, String sourceDocId, EntityIndexConfig config) {
        if (config.getFormulaMode() == EntityIndexConfig.FormulaIndexMode.DISABLED) {
            return List.of();
        }

        String text = switch (config.getFormulaMode()) {
            case LATEX_TEXT -> formula.getLatex();
            case WITH_DESCRIPTION -> {
                if (formula.getDescription() != null) {
                    yield formula.getDescription();
                }
                yield formula.getLatex();
            }
            default -> null;
        };

        if (text == null || text.isBlank()) {
            return List.of();
        }

        Map<String, Object> meta = createBaseMetadata(formula, sourceDocId, config);
        meta.put("latex", formula.getLatex() != null ? formula.getLatex() : "");
        meta.put("inline", formula.isInline());
        if (formula.getEquationNumber() != null) {
            meta.put("equation_number", formula.getEquationNumber());
        }

        return List.of(new Document(text, meta));
    }

    /**
     * Indexes a code entity.
     */
    private List<Document> indexCode(CodeEntity code, String sourceDocId, EntityIndexConfig config) {
        if (config.getCodeMode() == EntityIndexConfig.CodeIndexMode.DISABLED) {
            return List.of();
        }

        String text = switch (config.getCodeMode()) {
            case CONTENT_ONLY -> code.getCode();
            case WITH_LANGUAGE -> {
                if (code.getLanguage() != null) {
                    yield code.getLanguage() + " code: " + code.getCode();
                }
                yield code.getCode();
            }
            case WITH_SUMMARY -> {
                if (code.getSummary() != null) {
                    yield code.getSummary();
                }
                yield code.getCode();
            }
            default -> null;
        };

        if (text == null || text.isBlank()) {
            return List.of();
        }

        Map<String, Object> meta = createBaseMetadata(code, sourceDocId, config);
        meta.put("language", code.getLanguage() != null ? code.getLanguage() : "unknown");
        if (config.isStoreFullContent()) {
            meta.put("full_code", code.getCode());
        }
        if (code.getFilename() != null) {
            meta.put("filename", code.getFilename());
        }

        return List.of(new Document(text, meta));
    }

    /**
     * Indexes a list entity.
     */
    private List<Document> indexList(ListEntity list, String sourceDocId, EntityIndexConfig config) {
        // Lists are typically part of document text, index as supporting content
        String text = list.toMarkdown();
        if (text.isBlank()) {
            return List.of();
        }

        Map<String, Object> meta = createBaseMetadata(list, sourceDocId, config);
        meta.put("ordered", list.isOrdered());
        meta.put("item_count", list.getItems() != null ? list.getItems().size() : 0);

        return List.of(new Document(text, meta));
    }

    /**
     * Indexes a heading entity.
     */
    private List<Document> indexHeading(HeadingEntity heading, String sourceDocId, EntityIndexConfig config) {
        // Headings provide structure, index for navigation
        String text = heading.getText();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Map<String, Object> meta = createBaseMetadata(heading, sourceDocId, config);
        meta.put("heading_level", heading.getLevel());
        if (heading.getSectionNumber() != null) {
            meta.put("section_number", heading.getSectionNumber());
        }

        return List.of(new Document(text, meta));
    }

    /**
     * Creates base metadata common to all entities.
     */
    private Map<String, Object> createBaseMetadata(DocumentEntity entity, String sourceDocId, EntityIndexConfig config) {
        Map<String, Object> meta = new HashMap<>();

        meta.put("entity_type", entity.getType() != null ? entity.getType().name().toLowerCase() : "unknown");
        meta.put("entity_id", config.getEntityIdPrefix() + entity.getId());

        if (config.isIncludeSourceReference()) {
            meta.put("source_doc_id", sourceDocId);
        }

        if (config.isIncludePageNumber() && entity.getPageNumber() > 0) {
            meta.put("page_number", entity.getPageNumber());
        }

        if (config.isIncludeBoundingBox() && entity.getBounds() != null) {
            meta.put("bbox_x", entity.getBounds().getX());
            meta.put("bbox_y", entity.getBounds().getY());
            meta.put("bbox_width", entity.getBounds().getWidth());
            meta.put("bbox_height", entity.getBounds().getHeight());
        }

        meta.put("confidence", entity.getConfidence());

        // Add entity's own metadata
        if (entity.getMetadata() != null) {
            for (String field : config.getCustomMetadataFields()) {
                if (entity.getMetadata().containsKey(field)) {
                    meta.put(field, entity.getMetadata().get(field));
                }
            }
        }

        return meta;
    }

    /**
     * Result of indexing operation.
     */
    public record IndexingResult(
            int totalEntities,
            int indexedEntities,
            int documentsCreated,
            List<String> errors
    ) {
        public static IndexingResult success(int total, int indexed, int docs) {
            return new IndexingResult(total, indexed, docs, List.of());
        }

        public static IndexingResult withErrors(int total, int indexed, int docs, List<String> errors) {
            return new IndexingResult(total, indexed, docs, errors);
        }
    }
}
