/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import org.springframework.stereotype.Component;

/**
 * Freezes one deterministic schema overlay for a unified corpus snapshot.
 *
 * <p>The job and snapshot arguments remain part of the boundary for crawl observability, but no
 * language-model call participates in ontology inference. Prompt/parser components remain available
 * for compatibility experiments outside the production pre-step.</p>
 */
@Component
final class CorpusSchemaUnifier {

    GraphSchema unify(
            CorpusSchemaCandidates.Inventory inventory,
            GraphSchema configuredSchema,
            UnifiedCrawlJob job,
            String corpusSnapshotId) {
        return DeterministicCorpusSchemaInferencer.infer(inventory, configuredSchema);
    }
}
