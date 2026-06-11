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

package ai.kompile.pipeline.management.bridge;

import ai.kompile.modelmanager.llm.dynamic.LlmPipelineDefinition;
import ai.kompile.modelmanager.llm.registry.LlmPipelineRegistry;
import ai.kompile.modelmanager.vlm.dynamic.VlmPipelineDefinition;
import ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry;
import ai.kompile.pipeline.management.service.PipelineManagementService;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Migrates existing LLM and VLM pipeline definitions from their respective
 * registries into the unified pipeline store on application startup.
 *
 * <p>This ensures that all pipeline definitions are visible and executable
 * from the unified {@code /api/pipelines} REST API, regardless of whether
 * they were created via {@code /api/llm/config/pipelines} or
 * {@code /api/vlm/config/pipelines}.</p>
 *
 * <p>The migration is idempotent: definitions already present in the unified
 * store are skipped.</p>
 */
@Component
public class PipelineRegistryMigrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineRegistryMigrator.class);

    private final LlmPipelineBuilderBridge llmBridge = new LlmPipelineBuilderBridge();
    private final VlmPipelineBuilderBridge vlmBridge = new VlmPipelineBuilderBridge();

    @Autowired
    private PipelineManagementService pipelineManagementService;

    @PostConstruct
    public void migrateOnStartup() {
        int llmCount = migrateLlmPipelines();
        int vlmCount = migrateVlmPipelines();
        if (llmCount > 0 || vlmCount > 0) {
            log.info("Pipeline registry migration: {} LLM, {} VLM definitions synced to unified store",
                    llmCount, vlmCount);
        }
    }

    private int migrateLlmPipelines() {
        int count = 0;
        try {
            LlmPipelineRegistry registry = LlmPipelineRegistry.getInstance();
            for (LlmPipelineDefinition llmDef : registry.getAllPipelines()) {
                if (!llmDef.isEnabled()) continue;

                String id = llmDef.getPipelineId();
                if (pipelineManagementService.getUnified(id) != null) {
                    continue; // Already exists
                }

                try {
                    UnifiedPipelineDefinition unified = llmBridge.toUnified(llmDef);
                    pipelineManagementService.saveUnified(unified);
                    count++;
                    log.debug("Migrated LLM pipeline '{}' to unified store", id);
                } catch (Exception e) {
                    log.warn("Failed to migrate LLM pipeline '{}': {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to access LLM pipeline registry: {}", e.getMessage());
        }
        return count;
    }

    private int migrateVlmPipelines() {
        int count = 0;
        try {
            VlmPipelineRegistry registry = VlmPipelineRegistry.getInstance();
            for (VlmPipelineDefinition vlmDef : registry.getAllPipelines()) {
                if (!vlmDef.isEnabled()) continue;

                String id = vlmDef.getPipelineId();
                if (pipelineManagementService.getUnified(id) != null) {
                    continue; // Already exists
                }

                try {
                    UnifiedPipelineDefinition unified = vlmBridge.toUnified(vlmDef);
                    pipelineManagementService.saveUnified(unified);
                    count++;
                    log.debug("Migrated VLM pipeline '{}' to unified store", id);
                } catch (Exception e) {
                    log.warn("Failed to migrate VLM pipeline '{}': {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to access VLM pipeline registry: {}", e.getMessage());
        }
        return count;
    }

    /**
     * Sync a single LLM pipeline definition to the unified store.
     * Called by the controller after create/update.
     */
    public void syncLlmPipeline(LlmPipelineDefinition llmDef) {
        try {
            UnifiedPipelineDefinition unified = llmBridge.toUnified(llmDef);
            pipelineManagementService.saveUnified(unified);
            log.debug("Synced LLM pipeline '{}' to unified store", llmDef.getPipelineId());
        } catch (Exception e) {
            log.warn("Failed to sync LLM pipeline '{}': {}", llmDef.getPipelineId(), e.getMessage());
        }
    }

    /**
     * Sync a single VLM pipeline definition to the unified store.
     * Called by the controller after create/update.
     */
    public void syncVlmPipeline(VlmPipelineDefinition vlmDef) {
        try {
            UnifiedPipelineDefinition unified = vlmBridge.toUnified(vlmDef);
            pipelineManagementService.saveUnified(unified);
            log.debug("Synced VLM pipeline '{}' to unified store", vlmDef.getPipelineId());
        } catch (Exception e) {
            log.warn("Failed to sync VLM pipeline '{}': {}", vlmDef.getPipelineId(), e.getMessage());
        }
    }
}
