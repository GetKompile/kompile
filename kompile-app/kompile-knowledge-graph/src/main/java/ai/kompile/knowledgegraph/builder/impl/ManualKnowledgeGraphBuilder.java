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
package ai.kompile.knowledgegraph.builder.impl;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Manual knowledge graph builder.
 *
 * <p>This builder does not perform automatic extraction. Instead, it provides
 * a UI-driven workflow where users manually create triples. When buildFromChunks
 * is called, it simply returns an empty list (no automatic proposals).
 *
 * <p>Manual triples are created directly via the API/UI and don't go through
 * the extraction pipeline.
 */
@Component
public class ManualKnowledgeGraphBuilder implements KnowledgeGraphBuilder {

    public static final String BUILDER_ID = "manual-builder";

    private BuilderConfig config;

    @Override
    public String getId() {
        return BUILDER_ID;
    }

    @Override
    public String getDisplayName() {
        return "Manual Graph Builder";
    }

    @Override
    public GraphBuilderType getType() {
        return GraphBuilderType.MANUAL;
    }

    @Override
    public String getDescription() {
        return "Create knowledge graph triples manually through the UI. " +
               "No automatic extraction is performed - users define entities and " +
               "relationships directly.";
    }

    @Override
    public void configure(BuilderConfig config) {
        this.config = config;
    }

    @Override
    public BuilderConfig getConfig() {
        return config;
    }

    /**
     * Manual builder does not extract from chunks automatically.
     * Returns an empty list - all triples are created manually via the API.
     */
    @Override
    public List<ProposedTriple> buildFromChunks(
            List<RetrievedDoc> chunks,
            GraphBuildContext context,
            Consumer<BuildProgress> progressCallback) {

        // Notify completion immediately since there's nothing to process
        if (progressCallback != null) {
            progressCallback.accept(BuildProgress.completed(
                context.jobId(),
                chunks != null ? chunks.size() : 0,
                0
            ));
        }

        // Manual builder does not produce automatic proposals
        return Collections.emptyList();
    }

    /**
     * Manual builder does not produce extraction logs.
     */
    @Override
    public Optional<List<ExtractionLogEntry>> getExtractionLog(String jobId) {
        return Optional.empty();
    }

    /**
     * Manual builder does not support extraction logging.
     */
    @Override
    public boolean supportsExtractionLog() {
        return false;
    }

    /**
     * Manual builder can run concurrently but doesn't need to.
     */
    @Override
    public boolean supportsConcurrentIndexing() {
        return true;
    }
}
