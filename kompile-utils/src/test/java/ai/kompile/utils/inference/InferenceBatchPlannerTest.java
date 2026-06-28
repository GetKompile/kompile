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

package ai.kompile.utils.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InferenceBatchPlannerTest {

    private static InferenceBatchPlanner.Budget budget(long maxTokens, int maxRows) {
        return InferenceBatchPlanner.Budget.builder()
                .seqHardCap(512)
                .seqBuckets(new int[]{64, 128, 256, 512})
                .maxBatchTokens(maxTokens)
                .maxRows(maxRows)
                .build();
    }

    @Test
    void emptyInputYieldsNoBatches() {
        assertTrue(InferenceBatchPlanner.plan(new int[0], budget(8192, 0)).isEmpty());
        assertTrue(InferenceBatchPlanner.plan(null, budget(8192, 0)).isEmpty());
    }

    @Test
    void everyItemIsCoveredExactlyOnce() {
        int[] lengths = {10, 500, 30, 200, 64, 65, 5, 400, 128, 129};
        List<InferenceBatchPlanner.Batch> batches = InferenceBatchPlanner.plan(lengths, budget(2048, 0));
        Set<Integer> seen = new HashSet<>();
        for (InferenceBatchPlanner.Batch b : batches) {
            for (int idx : b.itemIndices()) {
                assertTrue(seen.add(idx), "index " + idx + " appeared twice");
            }
        }
        assertEquals(lengths.length, seen.size(), "all items must be covered once");
    }

    @Test
    void shortTextsPackManyRowsIntoSmallBucket() {
        // 20 short (~30-token) texts; budget 2048 tokens, bucket 64 -> up to 32 rows fit one batch.
        int[] lengths = new int[20];
        java.util.Arrays.fill(lengths, 30);
        List<InferenceBatchPlanner.Batch> batches = InferenceBatchPlanner.plan(lengths, budget(2048, 0));
        assertEquals(1, batches.size(), "20 short texts should fit one budgeted batch");
        assertEquals(64, batches.get(0).seqBucket(), "short texts pad to the 64 bucket, not 512");
        assertEquals(20, batches.get(0).rows());
    }

    @Test
    void tokenBudgetBoundsRowsTimesBucket() {
        int[] lengths = new int[100];
        java.util.Arrays.fill(lengths, 200); // bucket 256
        InferenceBatchPlanner.Budget b = budget(2048, 0); // 2048/256 = 8 rows max per batch
        List<InferenceBatchPlanner.Batch> batches = InferenceBatchPlanner.plan(lengths, b);
        for (InferenceBatchPlanner.Batch batch : batches) {
            assertEquals(256, batch.seqBucket());
            assertTrue(batch.tokens() <= 2048, "batch " + batch + " exceeds token budget");
            assertTrue(batch.rows() <= 8, "row count must respect the token budget");
        }
    }

    @Test
    void maxRowsCapIsRespected() {
        int[] lengths = new int[100];
        java.util.Arrays.fill(lengths, 10); // bucket 64; token budget would allow many rows
        List<InferenceBatchPlanner.Batch> batches = InferenceBatchPlanner.plan(lengths, budget(100_000, 16));
        for (InferenceBatchPlanner.Batch batch : batches) {
            assertTrue(batch.rows() <= 16, "rows must respect maxRows cap");
        }
    }

    @Test
    void oversizedSingleItemBecomesOneRowBatchNeverDropped() {
        // One item far larger than the token budget allows even at one row.
        int[] lengths = {500};
        InferenceBatchPlanner.Budget b = budget(64, 0); // budget smaller than a single 512-bucket row
        List<InferenceBatchPlanner.Batch> batches = InferenceBatchPlanner.plan(lengths, b);
        assertEquals(1, batches.size());
        assertEquals(1, batches.get(0).rows());
        assertEquals(512, batches.get(0).seqBucket(), "capped to the hard cap bucket");
    }

    @Test
    void bucketForPicksSmallestCoveringBucket() {
        int[] buckets = {64, 128, 256, 512};
        assertEquals(64, InferenceBatchPlanner.bucketFor(1, buckets, 512));
        assertEquals(64, InferenceBatchPlanner.bucketFor(64, buckets, 512));
        assertEquals(128, InferenceBatchPlanner.bucketFor(65, buckets, 512));
        assertEquals(256, InferenceBatchPlanner.bucketFor(200, buckets, 512));
        assertEquals(512, InferenceBatchPlanner.bucketFor(513, buckets, 512), "clamped to hard cap");
    }

    @Test
    void bucketForFallsBackToPowerOfTwoWithoutExplicitBuckets() {
        assertEquals(32, InferenceBatchPlanner.bucketFor(30, null, 4096));
        assertEquals(64, InferenceBatchPlanner.bucketFor(33, null, 4096));
        assertEquals(256, InferenceBatchPlanner.bucketFor(129, null, 4096));
    }

    @Test
    void estimateMaxBatchTokensScalesWithMemoryAndNeverBelowHardCap() {
        // 16 GB ceiling, hidden 768, fp32 (4 bytes), 50% safety, activation factor 16.
        long tokens = InferenceBatchPlanner.estimateMaxBatchTokens(
                16L * 1024 * 1024 * 1024, 768, 4, 0.5, 16.0, 512);
        // ~ 16Gi*0.5 / (768*4*16) = 8Gi / 49152 ~= 174762 tokens
        assertTrue(tokens > 100_000 && tokens < 300_000, "got " + tokens);

        long tiny = InferenceBatchPlanner.estimateMaxBatchTokens(1024, 4096, 4, 0.5, 16.0, 512);
        assertEquals(512, tiny, "never below the hard cap so one full row is always plannable");
    }

    @Test
    void parseByteSizeHandlesUnitSuffixesAndRawCounts() {
        assertEquals(49152L << 20, InferenceBatchPlanner.parseByteSize("49152m"));
        assertEquals(48L << 30, InferenceBatchPlanner.parseByteSize("48g"));
        assertEquals(1024L << 10, InferenceBatchPlanner.parseByteSize("1024K"));
        assertEquals(123456789L, InferenceBatchPlanner.parseByteSize("123456789"));
        assertEquals(0L, InferenceBatchPlanner.parseByteSize(null));
        assertEquals(0L, InferenceBatchPlanner.parseByteSize(""));
        assertEquals(0L, InferenceBatchPlanner.parseByteSize("not-a-number"));
    }

    @Test
    void itemsAreOrderedByAscendingLengthWithinPlan() {
        int[] lengths = {500, 10, 256, 64};
        List<InferenceBatchPlanner.Batch> batches = InferenceBatchPlanner.plan(lengths, budget(64, 0));
        // budget 64 forces one item per batch; verify ascending-length emission order.
        assertEquals(4, batches.size());
        assertEquals(1, batches.get(0).itemIndices()[0]); // length 10
        assertEquals(3, batches.get(1).itemIndices()[0]); // length 64
        assertEquals(2, batches.get(2).itemIndices()[0]); // length 256
        assertEquals(0, batches.get(3).itemIndices()[0]); // length 500
    }
}
