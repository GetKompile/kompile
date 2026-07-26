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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.staging.GraphCommitSink;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Turns a partition's staged graph into writes on the knowledge graph.
 *
 * <p>The write itself is the crawl's ordinary one — the same
 * {@link GraphPersistenceHelper#persistConstructedGraphBatch} that every extraction batch goes
 * through, so partitions inherit document-node parenting, confidence stamping, provenance keys and
 * batched RPCs for free rather than growing a second, subtly different persistence path.</p>
 *
 * <p>What a partition adds is that the graph it hands over has already been merged: one entity per
 * subject, one edge per assertion, endpoints keyed so the writer resolves them to single nodes.
 * The interesting work here is therefore not writing but <em>reporting</em> — comparing what was
 * offered to what the store took, so a partition that was quietly half-written says so instead of
 * looking identical to one that landed whole.</p>
 */
@Component
public class PartitionGraphCommitter {

    private static final Logger log = LoggerFactory.getLogger(PartitionGraphCommitter.class);

    private final GraphPersistenceHelper persistence;

    PartitionGraphCommitter(GraphPersistenceHelper persistence) {
        this.persistence = Objects.requireNonNull(persistence, "graph persistence");
    }

    /**
     * A sink that writes staged graphs on behalf of {@code job}.
     *
     * @param job       crawl job the writes belong to; supplies the job id, fact sheet and the
     *                  cancellation flag the writer honours
     * @param config    extraction config, read for the minimum-confidence floor
     * @param documents source documents for the chunks the partition read, when the caller has
     *                  them; used only to resolve source paths, and may be empty
     */
    public GraphCommitSink sinkFor(UnifiedCrawlJob job, GraphExtractionConfig config,
                                   Collection<RetrievedDoc> documents) {
        Objects.requireNonNull(job, "a commit sink needs the job the writes belong to");
        return (key, graph) -> write(key, graph, job, config, documents);
    }

    private GraphCommitSink.CommitOutcome write(PartitionKey key, Graph graph, UnifiedCrawlJob job,
                                                GraphExtractionConfig config,
                                                Collection<RetrievedDoc> documents) {
        if (graph == null) {
            return GraphCommitSink.CommitOutcome.nothing();
        }
        int offeredEntities = graph.getEntities() == null ? 0 : graph.getEntities().size();
        int offeredRelationships = graph.getRelationships() == null
                ? 0 : graph.getRelationships().size();

        GraphPersistenceHelper.GraphPersistResult result =
                persistence.persistConstructedGraphBatch(job, graph, documents, config);

        List<String> problems = shortfalls(offeredEntities, result.entities(),
                offeredRelationships, result.relationships());
        if (!problems.isEmpty()) {
            log.info("[Job {}] Partition {} committed {} of {} entities and {} of {} relationships",
                    job.getJobId(), key.id(), result.entities(), offeredEntities,
                    result.relationships(), offeredRelationships);
        }
        return new GraphCommitSink.CommitOutcome(result.entities(), result.relationships(),
                problems);
    }

    /**
     * What the store did not take.
     *
     * <p>A shortfall is not automatically a fault — an entity below the configured confidence
     * floor is <em>meant</em> to be dropped. It is reported anyway because the partition's
     * coverage claim is about chunks read, not about facts stored, and the gap between the two is
     * exactly what someone reconciling a graph against a manifest needs to see.</p>
     */
    private static List<String> shortfalls(int offeredEntities, int writtenEntities,
                                           int offeredRelationships, int writtenRelationships) {
        List<String> problems = new ArrayList<>(2);
        if (writtenEntities < offeredEntities) {
            problems.add((offeredEntities - writtenEntities) + " of " + offeredEntities
                    + " entities were not written (confidence floor, cancellation, or a store error)");
        }
        if (writtenRelationships < offeredRelationships) {
            problems.add((offeredRelationships - writtenRelationships) + " of "
                    + offeredRelationships + " relationships were not written (an endpoint may not"
                    + " exist in the graph yet)");
        }
        return List.copyOf(problems);
    }

    /**
     * True when this deployment can actually write.
     *
     * <p>Worth asking before a run rather than after: with no graph service behind it the write
     * path returns an empty result instead of failing, so a partition would otherwise read every
     * chunk it could find, report itself complete, and store nothing.</p>
     */
    public boolean canWrite() {
        return persistence.knowledgeGraphService != null;
    }
}
