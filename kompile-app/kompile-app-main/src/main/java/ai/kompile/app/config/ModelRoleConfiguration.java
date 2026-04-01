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

package ai.kompile.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Configuration for role-based model assignment in the RAG pipeline.
 *
 * Models can be assigned to specific roles:
 * - dense-retrieval: Models for semantic vector search (bge-base-en-v1.5, arctic-embed-l)
 * - sparse-retrieval: Models for learned sparse representations (splade-pp-ed)
 * - reranking: Cross-encoder models for reranking (ms-marco-MiniLM-L-6-v2)
 *
 * These settings work with both built-in models (ModelConstants) and
 * imported models (registry.json via archive import).
 */
@Configuration
public class ModelRoleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelRoleConfiguration.class);

    // Singleton instance for static access
    private static volatile ModelRoleConfiguration instance;

    @Value("${kompile.models.roles.dense-retrieval:bge-base-en-v1.5}")
    private String denseRetrievalModel;

    @Value("${kompile.models.roles.sparse-retrieval:}")
    private String sparseRetrievalModel;

    @Value("${kompile.models.roles.reranking:ms-marco-MiniLM-L-6-v2}")
    private String rerankingModel;

    @Value("${kompile.models.hybrid.enabled:false}")
    private boolean hybridEnabled;

    @Value("${kompile.models.hybrid.dense-weight:0.7}")
    private double hybridDenseWeight;

    @Value("${kompile.models.reranking.enabled:true}")
    private boolean rerankingEnabled;

    @Value("${kompile.models.reranking.top-k:50}")
    private int rerankingTopK;

    @Value("${kompile.models.registry.refresh-interval-seconds:300}")
    private int registryRefreshIntervalSeconds;

    @Value("${kompile.models.registry.path:${user.home}/.kompile/models/registry.json}")
    private String registryPath;

    @PostConstruct
    public void init() {
        instance = this;
        log.info("RAG Pipeline Model Configuration initialized:");
        log.info("  Dense Retrieval Model: {}", denseRetrievalModel);
        if (sparseRetrievalModel != null && !sparseRetrievalModel.isEmpty()) {
            log.info("  Sparse Retrieval Model: {}", sparseRetrievalModel);
        }
        log.info("  Reranking Model: {}", rerankingModel);
        log.info("  Hybrid Enabled: {}", hybridEnabled);
        log.info("  Reranking Enabled: {}", rerankingEnabled);
        log.info("  Registry Refresh Interval: {} seconds", registryRefreshIntervalSeconds);
    }

    // Getters

    public String getDenseRetrievalModel() {
        return denseRetrievalModel;
    }

    public String getSparseRetrievalModel() {
        return sparseRetrievalModel;
    }

    public String getRerankingModel() {
        return rerankingModel;
    }

    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    public double getHybridDenseWeight() {
        return hybridDenseWeight;
    }

    public boolean isRerankingEnabled() {
        return rerankingEnabled;
    }

    public int getRerankingTopK() {
        return rerankingTopK;
    }

    public int getRegistryRefreshIntervalSeconds() {
        return registryRefreshIntervalSeconds;
    }

    public String getRegistryPath() {
        return registryPath;
    }

    // Static accessors for use in factories (where Spring DI isn't available)

    public static ModelRoleConfiguration getInstance() {
        return instance;
    }

    public static String getConfiguredDenseModel() {
        if (instance != null && instance.denseRetrievalModel != null && !instance.denseRetrievalModel.isEmpty()) {
            return instance.denseRetrievalModel;
        }
        return "bge-base-en-v1.5"; // Default
    }

    public static String getConfiguredSparseModel() {
        if (instance != null && instance.sparseRetrievalModel != null && !instance.sparseRetrievalModel.isEmpty()) {
            return instance.sparseRetrievalModel;
        }
        return null; // No default sparse model
    }

    public static String getConfiguredRerankingModel() {
        if (instance != null && instance.rerankingModel != null && !instance.rerankingModel.isEmpty()) {
            return instance.rerankingModel;
        }
        return "ms-marco-MiniLM-L-6-v2"; // Default
    }

    public static boolean isHybridConfigured() {
        return instance != null && instance.hybridEnabled;
    }

    public static boolean isRerankingConfigured() {
        return instance != null && instance.rerankingEnabled;
    }
}
