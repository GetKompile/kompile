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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.EvidenceManifest;
import ai.kompile.core.graphrag.partition.MembershipState;
import ai.kompile.core.graphrag.partition.PartitionKey;
import ai.kompile.core.graphrag.partition.PartitionLifecycle;
import ai.kompile.core.graphrag.partition.reuse.ExtractionReuse;
import ai.kompile.core.graphrag.partition.staging.GraphCommitSink;
import ai.kompile.core.graphrag.partition.staging.PartitionGraphTransaction;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedRunAllResult;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedRunResult;
import ai.kompile.crawl.graph.EntityPartitionCrawlStep.Outcome;
import ai.kompile.crawl.graph.EntityPartitionCrawlStep.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a finished crawl says about the subjects it does and does not have a coverage claim for.
 *
 * <p>The partition pass is non-fatal, so the crawl completes whether or not every subject got a
 * claim. That makes what the job reports the only place the difference shows: a subject with no
 * claim is one an answer drawn from this graph is unbounded about, and it has to be named on the
 * job rather than absorbed into a green tick.</p>
 *
 * <p>Covered here means "a durable claim was recorded", not "the claim is complete" — how good each
 * claim is belongs to the coverage report, which is a different question with a different surface.
 * These tests hold the job counters to the weaker, honest meaning.</p>
 */
@DisplayName("What a crawl reports about its own partition coverage")
class PartitionCoverageJobReportingTest {

    private static final long FACT_SHEET = 7L;

    private static UnifiedCrawlJob job() {
        return UnifiedCrawlJob.builder()
                .jobId("coverage-report-test")
                .request(UnifiedCrawlRequest.builder().factSheetId(FACT_SHEET).build())
                .build();
    }

    /** A subject whose partition closed and committed. */
    private static StagedRunResult covered(String subject) {
        PartitionKey key = PartitionKey.forEntity(subject, "policy-v1", String.valueOf(FACT_SHEET));
        EvidenceManifest manifest = new EvidenceManifest(key, 1,
                Map.of(MembershipState.PROCESSED, 1), Map.of(DiscoveryChannel.SEED, 1),
                List.of(), List.of(), List.of(), List.of(), true);
        PartitionLifecycle.Result run = new PartitionLifecycle.Result(EntityPartition.open(key),
                manifest, 1, 1, 0, true, Map.of(), List.of());
        PartitionGraphTransaction transaction = PartitionGraphTransaction.openOn(key, FACT_SHEET);
        return new StagedRunResult(run, transaction,
                transaction.commit((k, graph) -> GraphCommitSink.CommitOutcome.nothing()));
    }

    private static StagedRunAllResult ranOver(List<String> claimed, List<String> failed) {
        Map<String, StagedRunResult> runs = new LinkedHashMap<>();
        for (String subject : claimed) {
            runs.put(subject, covered(subject));
        }
        return new StagedRunAllResult(runs, failed, ExtractionReuse.forRun().stats());
    }

    private static List<String> subjects(String prefix, int count) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(prefix + "-" + i);
        }
        return names;
    }

    @Nested
    @DisplayName("The counters")
    class Counters {

        @Test
        @DisplayName("claimed subjects are counted, failed ones are counted apart")
        void bothSidesAreCounted() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN,
                    "2 covered", ranOver(List.of("acme", "beta"), List.of("gamma"))));

            assertEquals(2, job.getPartitionsCovered().get());
            assertEquals(1, job.getPartitionsUncovered().get());
        }

        @Test
        @DisplayName("a pass that ran and covered nothing says zero rather than nothing")
        void everySubjectFailingIsStillAReport() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.FAILED,
                    "0 partition(s)", ranOver(List.of(), List.of("acme", "beta"))));

            assertEquals(0, job.getPartitionsCovered().get());
            assertEquals(2, job.getPartitionsUncovered().get());
            assertEquals(List.of("acme", "beta"), job.getUncoveredPartitionSubjects());
        }

        @Test
        @DisplayName("a second pass replaces the first rather than appending to it")
        void reportingTwiceDoesNotAccumulate() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN, "a",
                    ranOver(List.of("acme"), List.of("gamma"))));
            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN, "b",
                    ranOver(List.of("acme", "beta"), List.of("delta"))));

            assertEquals(2, job.getPartitionsCovered().get());
            assertEquals(1, job.getPartitionsUncovered().get());
            assertEquals(List.of("delta"), job.getUncoveredPartitionSubjects());
        }
    }

    @Nested
    @DisplayName("The subjects with no claim")
    class UncoveredSubjects {

        @Test
        @DisplayName("they are named, not just counted")
        void failuresAreNamed() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN,
                    "1 covered", ranOver(List.of("acme"), List.of("gamma", "delta"))));

            assertEquals(List.of("gamma", "delta"), job.getUncoveredPartitionSubjects(),
                    "a count gives nobody anything to act on");
        }

        @Test
        @DisplayName("a clean pass names nobody")
        void nothingUncoveredNamesNobody() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN,
                    "2 covered", ranOver(List.of("acme", "beta"), List.of())));

            assertTrue(job.getUncoveredPartitionSubjects().isEmpty());
            assertEquals(0, job.getPartitionsUncovered().get());
        }

        @Test
        @DisplayName("the naming is capped, but the count is not")
        void theListIsBoundedAndTheCountIsNot() {
            int over = UnifiedCrawlGraphServiceImpl.MAX_NAMED_UNCOVERED_SUBJECTS + 7;
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.FAILED,
                    "everything fell over", ranOver(List.of(), subjects("subject", over))));

            assertEquals(over, job.getPartitionsUncovered().get(),
                    "truncating the list must not truncate the fact");
            assertEquals(UnifiedCrawlGraphServiceImpl.MAX_NAMED_UNCOVERED_SUBJECTS,
                    job.getUncoveredPartitionSubjects().size());
            assertEquals("subject-0", job.getUncoveredPartitionSubjects().get(0));
        }
    }

    @Nested
    @DisplayName("The sentence the operator reads")
    class Detail {

        @Test
        @DisplayName("a pass that ran speaks for itself")
        void ranPassesItsOwnDetailThrough() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN,
                    "3 partition(s) | 12 chunk(s)", ranOver(List.of("acme"), List.of())));

            assertEquals("3 partition(s) | 12 chunk(s)", job.getPartitionCoverageDetail());
        }

        @Test
        @DisplayName("anything else carries its status, because the detail never says it failed")
        void aNonRanStatusTravelsWithTheDetail() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.FAILED,
                    "0 partition(s) | failed: acme", ranOver(List.of(), List.of("acme"))));

            String detail = job.getPartitionCoverageDetail();
            assertNotNull(detail);
            assertTrue(detail.startsWith(Status.FAILED.name()),
                    "\"0 partition(s)\" on its own reads like a quiet no-op: " + detail);
            assertTrue(detail.contains("failed: acme"), detail);
        }

        @Test
        @DisplayName("a skipped pass still says why, with no coverage claimed")
        void skippedIsReportedWithoutCounters() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, Outcome.of(Status.SKIPPED,
                    "no knowledge graph is configured"));

            assertTrue(job.getPartitionCoverageDetail().startsWith(Status.SKIPPED.name()));
            assertTrue(job.getPartitionCoverageDetail().contains("no knowledge graph"));
            assertEquals(0, job.getPartitionsCovered().get());
            assertEquals(0, job.getPartitionsUncovered().get());
            assertTrue(job.getUncoveredPartitionSubjects().isEmpty(),
                    "nothing ran, so there is no subject to blame");
        }

        @Test
        @DisplayName("a status with no detail does not grow a dangling separator")
        void aMissingDetailIsNotPunctuated() {
            UnifiedCrawlJob job = job();

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job,
                    Outcome.of(Status.CANCELLED, null));

            assertEquals(Status.CANCELLED.name(), job.getPartitionCoverageDetail());
        }

        @Test
        @DisplayName("a pass that never ran leaves the counters alone")
        void noResultMeansNoCounters() {
            UnifiedCrawlJob job = job();
            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN, "a",
                    ranOver(List.of("acme"), List.of("gamma"))));

            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job,
                    Outcome.of(Status.NOTHING_TO_PARTITION, "no entity groups"));

            assertTrue(job.getPartitionCoverageDetail().contains("no entity groups"));
            assertEquals(1, job.getPartitionsCovered().get(),
                    "a later pass with no result must not erase what an earlier one covered");
            assertEquals(List.of("gamma"), job.getUncoveredPartitionSubjects());
        }
    }

    @Nested
    @DisplayName("What survives serialization, and what refuses to be called")
    class Surfacing {

        @Test
        @DisplayName("the snapshot carries counts, names and the sentence")
        void theSnapshotCarriesTheClaim() {
            UnifiedCrawlJob job = job();
            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN,
                    "2 partition(s)", ranOver(List.of("acme", "beta"), List.of("gamma"))));

            UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();

            assertEquals(2, snapshot.getPartitionsCovered());
            assertEquals(1, snapshot.getPartitionsUncovered());
            assertEquals(List.of("gamma"), snapshot.getUncoveredPartitionSubjects());
            assertEquals("2 partition(s)", snapshot.getPartitionCoverageDetail());
        }

        @Test
        @DisplayName("a crawl with nothing uncovered sends no empty list")
        void anEmptyListIsOmittedFromTheSnapshot() {
            UnifiedCrawlJob job = job();
            UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job, new Outcome(Status.RAN,
                    "2 partition(s)", ranOver(List.of("acme", "beta"), List.of())));

            assertNull(job.toProgressSnapshot().getUncoveredPartitionSubjects());
        }

        @Test
        @DisplayName("nothing to report is not a reason to fall over")
        void nullsAreTolerated() {
            assertDoesNotThrow(() -> UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(null,
                    Outcome.of(Status.RAN, "x")));
            assertDoesNotThrow(
                    () -> UnifiedCrawlGraphServiceImpl.recordPartitionCoverage(job(), null));
        }

        @Test
        @DisplayName("an outcome nobody recorded leaves the job saying nothing about coverage")
        void anUnreportedPassClaimsNothing() {
            UnifiedCrawlJob job = job();

            assertNull(job.getPartitionCoverageDetail());
            assertEquals(0, job.getPartitionsCovered().get());
            assertEquals(0, job.getPartitionsUncovered().get());
        }
    }
}
