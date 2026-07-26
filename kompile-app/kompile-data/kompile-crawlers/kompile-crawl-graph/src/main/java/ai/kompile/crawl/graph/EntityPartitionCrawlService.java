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

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.maintenance.GraphMaintenanceService;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.DiscoveryChannelProvider;
import ai.kompile.core.graphrag.partition.DiscoveryPolicy;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionDiscoveryCoordinator;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionLifecycle;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.core.graphrag.partition.PartitionStore;
import ai.kompile.core.graphrag.partition.PartitionSubjects;
import ai.kompile.core.graphrag.partition.channels.IdentifierDiscoveryProvider;
import ai.kompile.core.graphrag.partition.channels.SemanticDiscoveryProvider;
import ai.kompile.core.graphrag.partition.grouping.EntityGroup;
import ai.kompile.core.graphrag.partition.grouping.GroupingPlan;
import ai.kompile.core.graphrag.partition.grouping.GroupingPolicy;
import ai.kompile.core.graphrag.partition.reuse.ExtractionLedger;
import ai.kompile.core.graphrag.partition.reuse.ExtractionReuse;
import ai.kompile.core.graphrag.partition.staging.GraphCommitSink;
import ai.kompile.core.graphrag.partition.staging.PartitionGraphTransaction;
import ai.kompile.crawl.graph.partition.ContradictionDiscoveryProvider;
import ai.kompile.crawl.graph.partition.GraphEntityLinks;
import ai.kompile.crawl.graph.partition.GraphNeighbourhoodDiscoveryProvider;
import ai.kompile.crawl.graph.partition.PartitionFactSheets;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Assembles and runs an entity partition against a crawl's graph and vector index.
 *
 * <p>Everything the partition control plane needs lives in different places: the channels that
 * find evidence are split between the core (vector-store retrieval) and this module (graph
 * traversal, contradiction detection); the ordering of evidence is core's; the packing of work
 * into batches is the crawl's. This service is where those meet, and it is the only thing outside
 * the crawl that has to know the crawl's batch planner exists.</p>
 *
 * <p><strong>It refuses to run a policy it cannot honour.</strong> If a policy names the semantic
 * or identifier channel and no vector store is configured, assembling the coordinator fails
 * instead of quietly running with fewer ways of looking — a partition that reports itself complete
 * because nobody noticed a whole channel was missing is the exact failure this design exists to
 * prevent. {@link #graphOnlyPolicy()} is the deliberate way to run without one, and it carries its
 * own policy version so its coverage is never compared to a run that had the index.</p>
 */
@Service
public class EntityPartitionCrawlService {

    private static final Logger log = LoggerFactory.getLogger(EntityPartitionCrawlService.class);

    /** Marks a policy version that had to run without the index; see {@link #graphOnlyPolicy()}. */
    public static final String GRAPH_ONLY_SUFFIX = "-graph-only";

    /** Version for a run that deliberately has no vector index behind it. */
    public static final String GRAPH_ONLY_POLICY_VERSION =
            DiscoveryPolicy.DEFAULT_VERSION + GRAPH_ONLY_SUFFIX;

    /** Default members per mini-batch when a request does not say. */
    public static final int DEFAULT_MAX_ITEMS_PER_BATCH = 16;

    private static final Set<DiscoveryChannel> VECTOR_CHANNELS =
            Set.copyOf(EnumSet.of(DiscoveryChannel.SEMANTIC, DiscoveryChannel.DIRECT_IDENTIFIER));

    private final CrawlBatchPlanner batchPlanner;
    private final KnowledgeGraphService graph;
    private final GraphMaintenanceService maintenance;
    private final ObjectProvider<VectorStore> vectorStores;

    public EntityPartitionCrawlService(CrawlBatchPlanner batchPlanner,
                                       KnowledgeGraphService graph,
                                       GraphMaintenanceService maintenance,
                                       ObjectProvider<VectorStore> vectorStores) {
        this.batchPlanner = Objects.requireNonNull(batchPlanner, "the crawl batch planner");
        this.graph = Objects.requireNonNull(graph, "a knowledge graph service");
        this.maintenance = Objects.requireNonNull(maintenance, "a graph maintenance service");
        this.vectorStores = vectorStores;
    }

    /**
     * What to run a partition over.
     *
     * <p>{@code subject} names the partition and {@code members} says who it is actually about.
     * For a single entity those are the same thing. For a group they are not: the subject is the
     * group's id, which is derived from its members and names no node in any graph, so the
     * membership has to travel separately — see {@link PartitionSubjects}.</p>
     *
     * @param subject            what the partition is called: an entity as the graph spells it, or
     *                           a group id
     * @param members            every entity the partition reads; defaults to the subject alone
     * @param groupingVersion    grouping policy that chose those members, or null when none did
     * @param factSheetId        graph scope; pinned onto the partition so the claim records where
     *                           its evidence came from
     * @param snapshotId         corpus snapshot this coverage is relative to, or null
     * @param policy             which channels run and how proposals are banded
     * @param identifiers        known surface forms for the subject, best first
     * @param chunks             chunk id to document; prices batches, and gives a staged run the
     *                           text it needs to tell one chunk's work from another's; may be empty
     * @param maxItemsPerBatch   hard cap on members per mini-batch
     * @param targetCostPerBatch soft cost cap per mini-batch, 0 for none
     */
    public record PartitionRequest(
            String subject,
            List<String> members,
            String groupingVersion,
            Long factSheetId,
            String snapshotId,
            DiscoveryPolicy policy,
            List<String> identifiers,
            Map<String, Document> chunks,
            int maxItemsPerBatch,
            long targetCostPerBatch) {

        public PartitionRequest {
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("a partition needs a subject");
            }
            subject = subject.trim();
            members = membersOf(subject, members);
            groupingVersion = groupingVersion == null || groupingVersion.isBlank()
                    ? null : groupingVersion.trim();
            policy = policy == null ? DiscoveryPolicy.defaults() : policy;
            identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
            chunks = chunks == null ? Map.of() : Map.copyOf(chunks);
            maxItemsPerBatch = maxItemsPerBatch > 0 ? maxItemsPerBatch : DEFAULT_MAX_ITEMS_PER_BATCH;
            targetCostPerBatch = Math.max(0L, targetCostPerBatch);
        }

        public static PartitionRequest forEntity(String subject, Long factSheetId) {
            return new PartitionRequest(subject, List.of(), null, factSheetId, null,
                    DiscoveryPolicy.defaults(), List.of(), Map.of(), DEFAULT_MAX_ITEMS_PER_BATCH,
                    0L);
        }

        /**
         * A partition about a whole group, named after it and reading all of it.
         *
         * <p>The bridges a group shares are members like any other: the group was given them so it
         * could see them, and a channel that skipped them would produce evidence the group was
         * grouped specifically to have.</p>
         */
        public static PartitionRequest forGroup(EntityGroup group, GroupingPolicy grouping,
                                                Long factSheetId) {
            Objects.requireNonNull(group, "a group request needs a group");
            return new PartitionRequest(group.id(), group.members(),
                    grouping == null ? null : grouping.version(), factSheetId, null,
                    DiscoveryPolicy.defaults(), List.of(), Map.of(), DEFAULT_MAX_ITEMS_PER_BATCH,
                    0L);
        }

        public PartitionRequest withPolicy(DiscoveryPolicy newPolicy) {
            return new PartitionRequest(subject, members, groupingVersion, factSheetId, snapshotId,
                    newPolicy, identifiers, chunks, maxItemsPerBatch, targetCostPerBatch);
        }

        public PartitionRequest withSnapshot(String newSnapshotId) {
            return new PartitionRequest(subject, members, groupingVersion, factSheetId,
                    newSnapshotId, policy, identifiers, chunks, maxItemsPerBatch,
                    targetCostPerBatch);
        }

        public PartitionRequest withIdentifiers(List<String> newIdentifiers) {
            return new PartitionRequest(subject, members, groupingVersion, factSheetId, snapshotId,
                    policy, newIdentifiers, chunks, maxItemsPerBatch, targetCostPerBatch);
        }

        /**
         * Chunk documents, used to price batches and to key extraction reuse.
         *
         * <p>Worth supplying for a staged run: without the text, two chunks holding the same words
         * cannot be shown to be the same unit of work, and each is read on its own.</p>
         */
        public PartitionRequest withChunks(Map<String, Document> newChunks) {
            return new PartitionRequest(subject, members, groupingVersion, factSheetId, snapshotId,
                    policy, identifiers, newChunks, maxItemsPerBatch, targetCostPerBatch);
        }

        public PartitionRequest withBatching(int newMaxItems, long newTargetCost) {
            return new PartitionRequest(subject, members, groupingVersion, factSheetId, snapshotId,
                    policy, identifiers, chunks, newMaxItems, newTargetCost);
        }

        /** The same request, about several entities under the grouping that chose them. */
        public PartitionRequest withMembers(List<String> newMembers, String newGroupingVersion) {
            return new PartitionRequest(subject, newMembers, newGroupingVersion, factSheetId,
                    snapshotId, policy, identifiers, chunks, maxItemsPerBatch, targetCostPerBatch);
        }

        /** True when this request is about more than one entity. */
        public boolean isGrouped() {
            return members.size() > 1;
        }

        /**
         * Identity of the partition this request would produce.
         *
         * <p>A request about one entity is keyed on the entity even when a grouping produced it:
         * a group of one genuinely is an entity partition, and keying it as a group would make the
         * same claim look like a different one every time the grouping happened to split
         * differently.</p>
         */
        public PartitionKey key() {
            return isGrouped()
                    ? PartitionKey.forGroup(subject, policy.version(), snapshotId)
                    : PartitionKey.forEntity(subject, policy.version(), snapshotId);
        }

        private static List<String> membersOf(String subject, List<String> members) {
            if (members == null || members.isEmpty()) {
                return List.of(subject);
            }
            Set<String> cleaned = new LinkedHashSet<>();
            for (String member : members) {
                if (member != null && !member.isBlank()) {
                    cleaned.add(member.trim());
                }
            }
            return cleaned.isEmpty() ? List.of(subject) : List.copyOf(cleaned);
        }
    }

    /**
     * The default policy minus the channels that need a vector index, under its own version.
     *
     * <p>The version has to differ: a run that only walked the graph looked in fewer places, and
     * calling its coverage the same thing as a run that also searched the index would make the two
     * comparable when they are not.</p>
     */
    public static DiscoveryPolicy graphOnlyPolicy() {
        return DiscoveryPolicy.defaults().withChannels(GRAPH_ONLY_POLICY_VERSION,
                DiscoveryChannel.SEED, DiscoveryChannel.MANUAL,
                DiscoveryChannel.STRUCTURED_RELATIONSHIP, DiscoveryChannel.CONTRADICTION);
    }

    /**
     * The same narrowing {@link #graphOnlyPolicy()} applies, but to a policy somebody configured.
     *
     * <p>A configured policy still has to be honourable: if it names the semantic or identifier
     * channel and there is no searchable index behind this run, assembling it would throw and the
     * whole pass would be lost. Dropping those channels under a derived version keeps the pass
     * running and keeps the shortfall in the record — the claim it files can never be compared to
     * one made with the index, because the version says so.</p>
     *
     * @param policy the configured policy, or null to fall back to the stock graph-only one
     * @return {@code policy} unchanged when it never needed the index
     */
    public static DiscoveryPolicy withoutVectorChannels(DiscoveryPolicy policy) {
        if (policy == null) {
            return graphOnlyPolicy();
        }
        Set<DiscoveryChannel> kept = EnumSet.noneOf(DiscoveryChannel.class);
        for (DiscoveryChannel channel : policy.channels()) {
            if (!VECTOR_CHANNELS.contains(channel)) {
                kept.add(channel);
            }
        }
        if (kept.size() == policy.channels().size()) {
            return policy;
        }
        if (kept.isEmpty()) {
            // Every channel it named needed the index. It still has to look somewhere, or the
            // partition closes having read nothing and calls that coverage: SEED is what the
            // partition was opened with and MANUAL is what a caller pinned, neither of which
            // needs an index to be true.
            kept.add(DiscoveryChannel.SEED);
            kept.add(DiscoveryChannel.MANUAL);
        }
        return policy.withChannels(policy.version() + GRAPH_ONLY_SUFFIX, kept);
    }

    /**
     * How the entities of a fact sheet divide into partitions under {@code grouping}.
     *
     * <p>Exposed separately from {@link #groupedRequests} because the plan carries the notes —
     * links that named nothing, components the size cap had to split, bridges that ended up alone.
     * Those are how a caller finds out that its corpus is shaped differently from what the policy
     * assumed, and they would be invisible if the only output were a list of requests.</p>
     */
    public GroupingPlan groupingFor(Long factSheetId, GroupingPolicy grouping) {
        return GraphEntityLinks.plan(graph, factSheetId,
                grouping == null ? GroupingPolicy.defaults() : grouping);
    }

    /** The partitions of a fact sheet under the default hybrid grouping. */
    public List<PartitionRequest> groupedRequests(Long factSheetId) {
        return groupedRequests(factSheetId, GroupingPolicy.defaults(), null);
    }

    /**
     * One request per group, ready to run.
     *
     * <p>This is the shape the spec calls hybrid, and it is the default for a reason: a partition
     * per entity re-reads every shared chunk once per entity, and a partition per corpus never
     * closes. Grouping puts the entities that share evidence in the same partition, so the chunk
     * they share is read once, and keeps the group small enough to finish.</p>
     *
     * @param grouping  how to group; the default hybrid policy when null
     * @param discovery which channels each partition runs; the default policy when null
     */
    public List<PartitionRequest> groupedRequests(Long factSheetId, GroupingPolicy grouping,
                                                  DiscoveryPolicy discovery) {
        GroupingPlan plan = groupingFor(factSheetId, grouping);
        for (String note : plan.notes()) {
            // Said out loud rather than buried in the plan: every one of these is a place the
            // corpus did not fit the policy, and the partitions below are the compromise.
            log.info("Grouping fact sheet {} under {}: {}", factSheetId, plan.policy().version(),
                    note);
        }
        List<PartitionRequest> requests = new ArrayList<>(plan.groups().size());
        for (EntityGroup group : plan.groups()) {
            PartitionRequest request =
                    PartitionRequest.forGroup(group, plan.policy(), factSheetId);
            requests.add(discovery == null ? request : request.withPolicy(discovery));
        }
        return requests;
    }

    /** True when a vector index is available for the retrieval channels. */
    public boolean hasVectorStore() {
        return vectorStore() != null;
    }

    private VectorStore vectorStore() {
        return vectorStores == null ? null : vectorStores.getIfAvailable();
    }

    /**
     * Builds the channel providers for {@code request}, in no particular order — the coordinator
     * sorts them by evidence priority.
     *
     * @throws IllegalStateException if the policy names a channel this deployment cannot supply
     */
    public List<DiscoveryChannelProvider> providersFor(PartitionRequest request) {
        DiscoveryPolicy policy = request.policy();
        List<DiscoveryChannelProvider> providers = new ArrayList<>(4);

        // The membership is bound from the request rather than read back off the partition: the
        // request is what the caller actually asked for, and a channel assembled before the first
        // round would otherwise have nothing but a group id to go on.
        Function<EntityPartition, List<String>> subjects =
                PartitionSubjects.fixed(request.members());

        if (policy.runs(DiscoveryChannel.STRUCTURED_RELATIONSHIP)) {
            providers.add(new GraphNeighbourhoodDiscoveryProvider(graph,
                    PartitionFactSheets.fromPinOrSnapshot(), subjects,
                    GraphNeighbourhoodDiscoveryProvider.DEFAULT_MAX_HOPS,
                    GraphNeighbourhoodDiscoveryProvider.DEFAULT_BASE_CONFIDENCE,
                    GraphNeighbourhoodDiscoveryProvider.DEFAULT_HOP_DECAY));
        }
        if (policy.runs(DiscoveryChannel.CONTRADICTION)) {
            providers.add(new ContradictionDiscoveryProvider(maintenance, graph,
                    PartitionFactSheets.fromPinOrSnapshot(), subjects,
                    ContradictionDiscoveryProvider.DEFAULT_CONFIDENCE));
        }

        boolean wantsVectors = VECTOR_CHANNELS.stream().anyMatch(policy::runs);
        if (!wantsVectors) {
            return providers;
        }
        VectorStore store = vectorStore();
        if (store == null) {
            throw new IllegalStateException("policy " + policy.version() + " runs "
                    + VECTOR_CHANNELS.stream().filter(policy::runs).toList()
                    + " but no vector store is configured; run graphOnlyPolicy() instead so the "
                    + "partition records that it never searched the index");
        }
        if (policy.runs(DiscoveryChannel.DIRECT_IDENTIFIER)) {
            // Retrieval is gated at the policy's own exclusion floor: a hit the policy would
            // reject anyway is not worth carrying back from the store.
            List<String> identifiers = request.identifiers();
            providers.add(new IdentifierDiscoveryProvider(store, partition -> identifiers,
                    policy.excludeBelow()));
        }
        if (policy.runs(DiscoveryChannel.SEMANTIC)) {
            providers.add(new SemanticDiscoveryProvider(store, policy.excludeBelow()));
        }
        return providers;
    }

    /** The coordinator for {@code request}; channels the policy does not name are dropped by it. */
    public PartitionDiscoveryCoordinator coordinatorFor(PartitionRequest request) {
        return new PartitionDiscoveryCoordinator(providersFor(request), request.policy());
    }

    /** Packs each evidence stratum with the crawl's cost planner. */
    Function<List<PartitionMember>, List<List<PartitionMember>>> packerFor(
            PartitionRequest request) {
        return PartitionBatchPacker.byCost(batchPlanner,
                PartitionBatchPacker.costOfChunks(batchPlanner, request.chunks()),
                request.maxItemsPerBatch(), request.targetCostPerBatch()).asPacker();
    }

    /** The lifecycle for {@code request}, ready to run. */
    public PartitionLifecycle lifecycleFor(PartitionRequest request, PartitionStore store) {
        return new PartitionLifecycle(store == null ? PartitionStore.inMemory() : store,
                coordinatorFor(request), packerFor(request));
    }

    /**
     * Runs {@code request} to closure, or until the policy's round budget is spent.
     *
     * <p>The fact sheet is pinned onto the partition before the first round, so the claim carries
     * the scope its evidence came from rather than depending on the caller remembering.</p>
     *
     * @param store     where partitions are kept between rounds and between runs; in-memory when
     *                  null, which means the coverage claim does not outlive this run
     * @param processor runs the actual extraction for one chunk
     */
    public PartitionLifecycle.Result run(PartitionRequest request, PartitionStore store,
                                         PartitionLifecycle.ChunkProcessor processor) {
        Objects.requireNonNull(request, "a run needs a request");
        PartitionStore target = store == null ? PartitionStore.inMemory() : store;
        PartitionKey key = request.key();
        target.save(pinned(target.loadOrOpen(key), request));

        PartitionLifecycle.Result result = lifecycleFor(request, target).run(key, processor);
        if (!result.isProvisionallyComplete()) {
            log.info("Partition {} did not close: {}", result.partition().id(), result.describe());
        }
        return result;
    }

    /**
     * Runs the extraction for one chunk and says what it learned.
     *
     * <p>Returning {@code null} means the chunk was read and taught the partition nothing, which
     * is a real and common outcome. Throwing leaves the chunk outstanding: the lifecycle defers it
     * with the reason attached, so a later round can try again — and nothing the failed call might
     * have half-produced reaches the staged graph.</p>
     */
    @FunctionalInterface
    public interface StagedExtractor {

        Graph extract(PartitionMember member, EntityPartition partition);
    }

    /**
     * What a staged run did.
     *
     * @param run         the partition run itself, with its coverage manifest
     * @param transaction the transaction the run staged into; still open when no sink was given
     * @param commit      what the commit did, or null when no sink was given
     */
    public record StagedRunResult(
            PartitionLifecycle.Result run,
            PartitionGraphTransaction transaction,
            PartitionGraphTransaction.CommitReport commit) {

        /** True when the partition closed <em>and</em> what it learned reached the graph. */
        public boolean isCommittedAndComplete() {
            return run.isProvisionallyComplete() && commit != null;
        }

        public String describe() {
            return run.describe() + (commit == null
                    ? " | staged but not committed: " + transaction.staged().describe()
                    : " | " + commit.describe());
        }
    }

    /**
     * Runs {@code request}, staging everything the extractor produces and committing it once.
     *
     * <p>Extraction happens per chunk; the graph should hear about the partition once. Staging is
     * what makes those compatible — the same company mentioned in nine chunks is merged before the
     * write, so the store never has to deduplicate and two chunks disagreeing is recorded rather
     * than resolved by whichever wrote last.</p>
     *
     * <p>A run that throws abandons the transaction: nothing it read reaches the graph. A run that
     * finishes commits what it read even if the partition did not close, because chunks that were
     * genuinely read are evidence, and throwing them away for a channel failure elsewhere would
     * lose real work. The manifest on {@link StagedRunResult#run()} is what says how complete that
     * evidence is.</p>
     *
     * @param sink where the staged graph goes; when null the transaction is returned still open,
     *             for a caller that wants to inspect or commit it itself
     */
    public StagedRunResult runStaged(PartitionRequest request, PartitionStore store,
                                     StagedExtractor extractor, GraphCommitSink sink) {
        return runStaged(request, store, extractor, sink, ExtractionReuse.forRun());
    }

    /**
     * Runs {@code request} staged, reading a chunk only if the same text has not been read already.
     *
     * <p>The ledger is what makes partitions cheap to overlap. A chunk naming two subjects belongs
     * to both of their partitions and is admitted by both; a document's repeated boilerplate is
     * every section's evidence. Sharing one {@code reuse} across those runs means the extractor
     * sees each distinct text once, while every partition still stages what that text said — the
     * remembered graph is staged under the reusing partition's own member, so its coverage claim
     * and its graph agree.</p>
     *
     * @param reuse ledger and extractor identity to reuse against; a fresh per-run ledger when null
     */
    public StagedRunResult runStaged(PartitionRequest request, PartitionStore store,
                                     StagedExtractor extractor, GraphCommitSink sink,
                                     ExtractionReuse reuse) {
        Objects.requireNonNull(request, "a staged run needs a request");
        Objects.requireNonNull(extractor, "a staged run needs an extractor");
        ExtractionReuse reusing = reuse == null ? ExtractionReuse.forRun() : reuse;
        PartitionGraphTransaction transaction =
                PartitionGraphTransaction.openOn(request.key(), request.factSheetId());

        PartitionLifecycle.Result result;
        try {
            result = run(request, store, (member, partition) -> {
                ExtractionReuse.Outcome outcome = reusing.extract(member, textOf(request, member),
                        partition, extractor::extract);
                Graph produced = outcome.produced();
                if (produced == null) {
                    return PartitionLifecycle.ProcessOutcome.processed(
                            noted(outcome, "nothing extracted"));
                }
                transaction.stage(member, produced);
                return PartitionLifecycle.ProcessOutcome.processed(outcome.note());
            });
        } catch (RuntimeException e) {
            transaction.abandon("partition run failed: " + e);
            throw e;
        }

        if (sink == null) {
            return new StagedRunResult(result, transaction, null);
        }
        PartitionGraphTransaction.CommitReport report = transaction.commit(sink);
        log.debug("Partition {} committed: {}", result.partition().id(), report.describe());
        return new StagedRunResult(result, transaction, report);
    }

    /**
     * What a staged run over several partitions did.
     *
     * @param runs   result per subject, in request order; a subject whose run threw is absent
     * @param failed subjects that were asked for and produced no result, in request order
     * @param reuse  how much of the extraction was work an earlier partition had already done
     */
    public record StagedRunAllResult(Map<String, StagedRunResult> runs,
                                     List<String> failed,
                                     ExtractionLedger.Stats reuse) {

        public StagedRunAllResult {
            runs = runs == null ? Map.of() : runs;
            failed = failed == null ? List.of() : List.copyOf(failed);
        }

        /**
         * True when every partition closed <em>and</em> what each learned reached the graph.
         *
         * <p>A subject whose run fell over makes this false even though the others landed: the
         * question was asked about a set of subjects, and a set with a hole in it is not
         * covered. The partitions that did close are still committed and still say so.</p>
         */
        public boolean isCommittedAndComplete() {
            return !runs.isEmpty() && failed.isEmpty()
                    && runs.values().stream().allMatch(StagedRunResult::isCommittedAndComplete);
        }

        public String describe() {
            return runs.size() + " partition(s)"
                    + (failed.isEmpty() ? "" : " | failed: " + String.join(", ", failed))
                    + " | " + reuse.describe();
        }
    }

    /**
     * Runs every request staged, sharing one reuse ledger across them.
     *
     * <p>This is where the ledger earns its keep: the overlap between partitions is the point of
     * partitioning — a chunk about two subjects is evidence for both — and without a shared ledger
     * that chunk is read once per subject that claims it. Each partition still gets its own
     * transaction and its own commit, because the partition is the unit the graph hears about.</p>
     *
     * @return results per subject plus what the sharing saved; a subject whose run threw is absent
     *         from {@code runs}, named in {@code failed} and logged, because one subject failing
     *         is not the others failing — but it is also not a covered subject
     */
    public StagedRunAllResult runAllStaged(List<PartitionRequest> requests, PartitionStore store,
                                           StagedExtractor extractor, GraphCommitSink sink) {
        return runAllStaged(requests, store, extractor, sink, ExtractionReuse.forRun());
    }

    /** Runs every request staged against a ledger the caller owns, so reuse can outlive this call. */
    public StagedRunAllResult runAllStaged(List<PartitionRequest> requests, PartitionStore store,
                                           StagedExtractor extractor, GraphCommitSink sink,
                                           ExtractionReuse reuse) {
        ExtractionReuse reusing = reuse == null ? ExtractionReuse.forRun() : reuse;
        Map<String, StagedRunResult> results = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        if (requests == null || requests.isEmpty()) {
            return new StagedRunAllResult(results, failed, reusing.stats());
        }
        PartitionStore target = store == null ? PartitionStore.inMemory() : store;
        for (PartitionRequest request : requests) {
            if (request == null) {
                continue;
            }
            try {
                results.put(request.subject(),
                        runStaged(request, target, extractor, sink, reusing));
            } catch (RuntimeException e) {
                failed.add(request.subject());
                log.warn("Staged partition run for subject {} failed: {}", request.subject(),
                        e.toString());
            }
        }
        return new StagedRunAllResult(results, failed, reusing.stats());
    }

    /**
     * The chunk's text, when the request carries it.
     *
     * <p>Null is a normal answer — a request assembled only to price batches has no documents —
     * and it costs reuse across chunks that merely hold the same words, not reuse of the same
     * chunk read for another partition.</p>
     */
    private static String textOf(PartitionRequest request, PartitionMember member) {
        Document document = request.chunks().get(member.chunkId());
        return document == null ? null : document.getText();
    }

    /** Keeps both halves when the reuse and the extraction each have something to say. */
    private static String noted(ExtractionReuse.Outcome outcome, String base) {
        return outcome.note() == null ? base : outcome.note() + "; " + base;
    }

    /**
     * Records the graph scope and the membership on the partition itself, so the claim says where
     * it looked and who it was about.
     *
     * <p>The membership is written only for a group, and only because the key cannot carry it:
     * a group id resolves to no node, so a partition re-opened from the store without the pin
     * could not be re-run at all. A single-entity claim is already keyed on its subject, and
     * repeating it in a pin would be a second answer to a question the key answers better.</p>
     *
     * <p>The grouping version is recorded whenever one chose the subject, grouped or not — a
     * coverage claim that does not say how its subject was chosen cannot be compared to the next
     * run's, and a per-entity grouping is a decision like any other.</p>
     */
    private static EntityPartition pinned(EntityPartition partition, PartitionRequest request) {
        EntityPartition scoped = request.factSheetId() == null ? partition
                : partition.withPin(PartitionFactSheets.FACT_SHEET_PIN,
                        String.valueOf(request.factSheetId()));
        List<String> membership = request.isGrouped() ? request.members() : List.of();
        return PartitionSubjects.pinnedOn(scoped, membership, request.groupingVersion());
    }

    /**
     * Runs a partition per subject, sharing one store so evidence admitted for one subject is
     * visible when the next one runs.
     *
     * @return results in subject order; a subject whose run threw is absent and logged
     */
    public Map<String, PartitionLifecycle.Result> runAll(List<PartitionRequest> requests,
                                                         PartitionStore store,
                                                         PartitionLifecycle.ChunkProcessor processor) {
        Map<String, PartitionLifecycle.Result> results = new LinkedHashMap<>();
        if (requests == null || requests.isEmpty()) {
            return results;
        }
        PartitionStore target = store == null ? PartitionStore.inMemory() : store;
        for (PartitionRequest request : requests) {
            if (request == null) {
                continue;
            }
            try {
                results.put(request.subject(), run(request, target, processor));
            } catch (RuntimeException e) {
                // One subject failing is not the others failing; the gap is logged and the
                // absent key is how a caller sees it.
                log.warn("Partition run for subject {} failed: {}", request.subject(),
                        e.toString());
            }
        }
        return results;
    }
}
