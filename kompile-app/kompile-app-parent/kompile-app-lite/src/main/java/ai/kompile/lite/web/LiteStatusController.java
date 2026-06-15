/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.lite.web;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.llm.LanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Status and model management endpoints for Kompile Lite.
 */
@RestController
@RequestMapping("/api/lite")
public class LiteStatusController {

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private GraphRagService graphRagService;

    @Autowired(required = false)
    private LanguageModel languageModel;

    /**
     * Application status overview.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("application", "kompile-lite");
        status.put("version", "0.1.0-SNAPSHOT");

        // Embedding model status
        Map<String, Object> embedding = new LinkedHashMap<>();
        if (embeddingModel != null) {
            embedding.put("available", true);
            embedding.put("model", embeddingModel.getModelName());
            embedding.put("dimensions", embeddingModel.dimensions());
            embedding.put("initialized", embeddingModel.isInitialized());
        } else {
            embedding.put("available", false);
        }
        status.put("embedding", embedding);

        // Vector store status
        Map<String, Object> vector = new LinkedHashMap<>();
        if (vectorStore != null) {
            vector.put("available", vectorStore.isVectorStoreAvailable());
            vector.put("documentCount", vectorStore.getApproxVectorCount());
            vector.put("path", vectorStore.getVectorStorePath());
        } else {
            vector.put("available", false);
        }
        status.put("vectorStore", vector);

        // Graph RAG status
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("available", graphRagService != null);
        status.put("graphRag", graph);

        // LLM status
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("available", languageModel != null);
        if (languageModel != null) {
            llm.put("provider", languageModel.getClass().getSimpleName());
        }
        status.put("llm", llm);

        return ResponseEntity.ok(status);
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
