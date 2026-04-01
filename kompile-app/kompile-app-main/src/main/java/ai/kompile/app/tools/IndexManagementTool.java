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

package ai.kompile.app.tools;

import ai.kompile.app.web.controllers.IndexBrowserController;
import ai.kompile.app.web.controllers.IndexerController;
import ai.kompile.app.web.controllers.VectorPopulationController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for index management operations.
 * Exposes index browsing, indexer control, and vector population functionality.
 */
@Component
public class IndexManagementTool {

    private static final Logger logger = LoggerFactory.getLogger(IndexManagementTool.class);

    private final IndexBrowserController indexBrowserController;
    private final IndexerController indexerController;
    private final VectorPopulationController vectorPopulationController;

    @Autowired
    public IndexManagementTool(
            @Autowired(required = false) IndexBrowserController indexBrowserController,
            @Autowired(required = false) IndexerController indexerController,
            @Autowired(required = false) VectorPopulationController vectorPopulationController) {
        this.indexBrowserController = indexBrowserController;
        this.indexerController = indexerController;
        this.vectorPopulationController = vectorPopulationController;
    }

    // Input records
    public record GetIndexBrowserStatusInput() {}
    public record ListIndexedDocumentsInput(Integer offset, Integer limit) {}
    public record GetIndexedDocumentInput(String docId) {}
    public record SearchIndexedDocumentsInput(String query, Integer maxResults, Double similarityThreshold) {}
    public record ListVectorStoreDocumentsInput(Integer offset, Integer limit) {}
    public record SearchVectorStoreInput(String query, Integer maxResults, Double similarityThreshold) {}
    public record GetIndexStatusInput() {}
    public record RebuildAllSourcesInput() {}
    public record StartVectorIndexInput() {}
    public record CancelVectorIndexInput() {}
    public record GetVectorIndexJobStatusInput() {}
    public record GetVectorPopulationServiceStatusInput() {}
    public record StartVectorPopulationInput() {}
    public record GetPopulationTaskStatusInput(String taskId) {}
    public record CancelPopulationTaskInput(String taskId) {}
    public record GetActivePopulationTasksInput() {}
    public record GetPopulationSummaryInput() {}

    @Tool(name = "get_index_browser_status",
            description = "Gets comprehensive index browser status including indexer, retriever, vector store, and embedding model availability.")
    public Map<String, Object> getIndexBrowserStatus(GetIndexBrowserStatusInput input) {
        try {
            if (indexBrowserController == null) return Map.of("status", "error", "error", "Index browser not available");
            ResponseEntity<?> response = indexBrowserController.getIndexBrowserStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting index browser status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_indexed_documents",
            description = "Lists paginated indexed documents from the keyword indexer. Specify offset and limit for pagination.")
    public Map<String, Object> listIndexedDocuments(ListIndexedDocumentsInput input) {
        try {
            if (indexBrowserController == null) return Map.of("status", "error", "error", "Index browser not available");
            int offset = input.offset() != null ? input.offset() : 0;
            int limit = input.limit() != null ? input.limit() : 10;
            ResponseEntity<?> response = indexBrowserController.listIndexedDocuments(offset, limit);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing indexed documents: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_indexed_document",
            description = "Retrieves a specific indexed document by its ID.")
    public Map<String, Object> getIndexedDocument(GetIndexedDocumentInput input) {
        try {
            if (indexBrowserController == null) return Map.of("status", "error", "error", "Index browser not available");
            if (input.docId() == null) return Map.of("status", "error", "error", "Document ID is required");
            ResponseEntity<?> response = indexBrowserController.getIndexedDocument(input.docId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting indexed document: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "search_keyword_index",
            description = "Searches the keyword index for documents matching a query. Returns results with metadata and preview.")
    public Map<String, Object> searchIndexedDocuments(SearchIndexedDocumentsInput input) {
        try {
            if (indexBrowserController == null) return Map.of("status", "error", "error", "Index browser not available");
            if (input.query() == null) return Map.of("status", "error", "error", "Query is required");
            var request = new IndexBrowserController.SearchRequest(input.query(), input.maxResults(), input.similarityThreshold());
            ResponseEntity<?> response = indexBrowserController.searchIndexedDocuments(request);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error searching indexed documents: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_vector_store_documents",
            description = "Lists paginated documents from the vector store.")
    public Map<String, Object> listVectorStoreDocuments(ListVectorStoreDocumentsInput input) {
        try {
            if (indexBrowserController == null) return Map.of("status", "error", "error", "Index browser not available");
            int offset = input.offset() != null ? input.offset() : 0;
            int limit = input.limit() != null ? input.limit() : 10;
            ResponseEntity<?> response = indexBrowserController.listVectorStoreDocuments(offset, limit);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing vector store documents: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "search_vector_store_documents",
            description = "Performs semantic search on the vector store with similarity threshold filtering.")
    public Map<String, Object> searchVectorStore(SearchVectorStoreInput input) {
        try {
            if (indexBrowserController == null) return Map.of("status", "error", "error", "Index browser not available");
            if (input.query() == null) return Map.of("status", "error", "error", "Query is required");
            var request = new IndexBrowserController.SearchRequest(input.query(), input.maxResults(), input.similarityThreshold());
            ResponseEntity<?> response = indexBrowserController.searchVectorStore(request);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error searching vector store: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_indexer_status",
            description = "Gets comprehensive index status including keyword index availability, vector store availability, and document counts.")
    public Map<String, Object> getIndexStatus(GetIndexStatusInput input) {
        try {
            if (indexerController == null) return Map.of("status", "error", "error", "Indexer not available");
            ResponseEntity<?> response = indexerController.getIndexStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting index status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "rebuild_all_sources_index",
            description = "Initiates reprocessing and re-indexing of all configured document sources. This is a long-running operation.")
    public Map<String, Object> rebuildAllSources(RebuildAllSourcesInput input) {
        try {
            if (indexerController == null) return Map.of("status", "error", "error", "Indexer not available");
            ResponseEntity<?> response = indexerController.rebuildAllSourcesIndex();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error rebuilding sources index: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "start_vector_index_creation",
            description = "Starts async vector index creation from the existing Lucene keyword index.")
    public Map<String, Object> startVectorIndex(StartVectorIndexInput input) {
        try {
            if (indexerController == null) return Map.of("status", "error", "error", "Indexer not available");
            ResponseEntity<?> response = indexerController.startVectorIndexCreation();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error starting vector index: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "cancel_vector_index_creation",
            description = "Cancels the currently running vector index creation job.")
    public Map<String, Object> cancelVectorIndex(CancelVectorIndexInput input) {
        try {
            if (indexerController == null) return Map.of("status", "error", "error", "Indexer not available");
            ResponseEntity<?> response = indexerController.cancelVectorIndexCreation();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error canceling vector index: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vector_index_job_status",
            description = "Gets the status of the vector index creation job.")
    public Map<String, Object> getVectorIndexJobStatus(GetVectorIndexJobStatusInput input) {
        try {
            if (indexerController == null) return Map.of("status", "error", "error", "Indexer not available");
            ResponseEntity<?> response = indexerController.getVectorIndexJobStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting vector index job status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vector_population_service_status",
            description = "Gets availability of all vector population services and active task counts.")
    public Map<String, Object> getVectorPopulationServiceStatus(GetVectorPopulationServiceStatusInput input) {
        try {
            if (vectorPopulationController == null) return Map.of("status", "error", "error", "Vector population not available");
            ResponseEntity<?> response = vectorPopulationController.getServiceStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting vector population service status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "start_vector_population",
            description = "Starts async in-process vector population. Returns a task ID to track progress.")
    public Map<String, Object> startVectorPopulation(StartVectorPopulationInput input) {
        try {
            if (vectorPopulationController == null) return Map.of("status", "error", "error", "Vector population not available");
            ResponseEntity<?> response = vectorPopulationController.startPopulation();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error starting vector population: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_population_task_status",
            description = "Gets the status of a vector population task by its task ID.")
    public Map<String, Object> getPopulationTaskStatus(GetPopulationTaskStatusInput input) {
        try {
            if (vectorPopulationController == null) return Map.of("status", "error", "error", "Vector population not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = vectorPopulationController.getTaskStatus(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting population task status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "cancel_population_task",
            description = "Cancels a running vector population task.")
    public Map<String, Object> cancelPopulationTask(CancelPopulationTaskInput input) {
        try {
            if (vectorPopulationController == null) return Map.of("status", "error", "error", "Vector population not available");
            if (input.taskId() == null) return Map.of("status", "error", "error", "Task ID is required");
            ResponseEntity<?> response = vectorPopulationController.cancelTask(input.taskId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error canceling population task: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_active_population_tasks",
            description = "Returns all active vector population tasks.")
    public Map<String, Object> getActivePopulationTasks(GetActivePopulationTasksInput input) {
        try {
            if (vectorPopulationController == null) return Map.of("status", "error", "error", "Vector population not available");
            ResponseEntity<?> response = vectorPopulationController.getActiveTasks();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting active population tasks: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vector_population_summary",
            description = "Gets summary statistics for all vector population services.")
    public Map<String, Object> getPopulationSummary(GetPopulationSummaryInput input) {
        try {
            if (vectorPopulationController == null) return Map.of("status", "error", "error", "Vector population not available");
            ResponseEntity<?> response = vectorPopulationController.getSummary();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting population summary: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
