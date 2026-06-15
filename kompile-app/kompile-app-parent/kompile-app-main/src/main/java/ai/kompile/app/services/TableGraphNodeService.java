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

package ai.kompile.app.services;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Promotes table Documents to GraphNode(TABLE) with CONTAINS edges
 * from parent DOCUMENT nodes. Ensures source attribution chain:
 * SOURCE → DOCUMENT → TABLE → SNIPPET.
 */
@Service
public class TableGraphNodeService {

    private static final Logger logger = LoggerFactory.getLogger(TableGraphNodeService.class);

    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphNodeRepository graphNodeRepository;

    public TableGraphNodeService(KnowledgeGraphService knowledgeGraphService,
                                  GraphNodeRepository graphNodeRepository) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.graphNodeRepository = graphNodeRepository;
    }

    /**
     * Scans documents for content_type=table and creates GraphNode(TABLE) entries
     * with CONTAINS edges linking them to their parent DOCUMENT nodes.
     *
     * @param documents the documents loaded in this ingest batch
     * @param taskId    the ingest task ID for logging
     * @return number of table nodes created
     */
    public int promoteTableNodes(List<Document> documents, String taskId) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }

        int created = 0;
        for (Document doc : documents) {
            String contentType = (String) doc.getMetadata().get(GraphConstants.META_CONTENT_TYPE);
            if (!"table".equals(contentType)) {
                continue;
            }

            try {
                created += promoteOneTable(doc, taskId);
            } catch (Exception e) {
                logger.warn("[Task {}] Failed to promote table to graph node: {}",
                        taskId, e.getMessage());
            }
        }

        if (created > 0) {
            logger.info("[Task {}] Promoted {} tables to graph nodes", taskId, created);
        }
        return created;
    }

    private int promoteOneTable(Document doc, String taskId) {
        Map<String, Object> meta = doc.getMetadata();

        // Build table identity
        String sheetName = (String) meta.get(GraphConstants.META_SHEET_NAME);
        String sourcePath = (String) meta.get(GraphConstants.META_SOURCE_PATH);
        String fileName = (String) meta.get("source_filename");
        String structuralSection = (String) meta.get("structural_section");

        // Generate a stable external ID for the table
        String tableTitle = sheetName != null ? sheetName
                : structuralSection != null ? structuralSection
                : "Table";
        String externalId = "table:" + (sourcePath != null ? sourcePath : fileName != null ? fileName : taskId) + ":" + tableTitle;

        // Extract table dimensions
        int rowCount = meta.get(GraphConstants.META_TABLE_ROW_COUNT) instanceof Number
                ? ((Number) meta.get(GraphConstants.META_TABLE_ROW_COUNT)).intValue() : 0;
        int columnCount = meta.get(GraphConstants.META_TABLE_COLUMN_COUNT) instanceof Number
                ? ((Number) meta.get(GraphConstants.META_TABLE_COLUMN_COUNT)).intValue() : 0;

        // Extract headers
        String headerStr = (String) meta.get(GraphConstants.META_TABLE_HEADERS);
        List<String> headers = headerStr != null
                ? Arrays.asList(headerStr.split(",")) : List.of();

        // Content preview (first 500 chars of full table content)
        String fullContent = (String) meta.get("full_table_content");
        String preview = fullContent != null && fullContent.length() > 500
                ? fullContent.substring(0, 500) + "..." : fullContent;

        // Find the parent DOCUMENT node (by nodeId UUID)
        String parentNodeId = findParentDocumentNode(sourcePath, fileName);
        if (parentNodeId == null) {
            logger.warn("[Task {}] No DOCUMENT node found for table '{}' (source: {}), skipping table promotion",
                    taskId, tableTitle, sourcePath);
            return 0;
        }

        // Additional metadata for the table node
        Map<String, Object> tableMeta = new LinkedHashMap<>();
        if (meta.get("table_index") instanceof Number) {
            tableMeta.put("tableIndex", ((Number) meta.get("table_index")).intValue());
        }
        if (meta.get("dq_flag_count") instanceof Number) {
            tableMeta.put("dqFlagCount", ((Number) meta.get("dq_flag_count")).intValue());
        }
        if (meta.get(GraphConstants.META_FORMULA_COUNT) instanceof Number) {
            tableMeta.put("formulaCount", ((Number) meta.get(GraphConstants.META_FORMULA_COUNT)).intValue());
        }

        // Create the table node with CONTAINS edge from parent
        GraphNode tableNode = knowledgeGraphService.createTableNode(
                parentNodeId, externalId, tableTitle,
                rowCount, columnCount, headers,
                preview, tableMeta);

        if (tableNode != null) {
            logger.debug("[Task {}] Created TABLE graph node '{}' ({}x{}) under parent '{}'",
                    taskId, tableTitle, rowCount, columnCount, parentNodeId);
            return 1;
        }
        return 0;
    }

    /**
     * Finds an existing DOCUMENT-level graph node matching the source path or filename.
     * Returns the node's nodeId (UUID), or null if not found.
     */
    private String findParentDocumentNode(String sourcePath, String fileName) {
        if (sourcePath != null) {
            Optional<GraphNode> node = graphNodeRepository.findByExternalIdAndNodeType(sourcePath, NodeLevel.DOCUMENT);
            if (node.isPresent()) {
                return node.get().getNodeId();
            }
        }

        // Fallback: search by filename in title
        if (fileName != null) {
            List<GraphNode> matches = graphNodeRepository.findByNodeType(NodeLevel.DOCUMENT);
            for (GraphNode node : matches) {
                if (fileName.equals(node.getTitle())) {
                    return node.getNodeId();
                }
            }
        }

        return null;
    }
}
