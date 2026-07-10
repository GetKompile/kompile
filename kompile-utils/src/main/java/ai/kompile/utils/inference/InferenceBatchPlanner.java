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

import java.util.ArrayList;
import java.util.List;

/**
 * Plans inference micro-batches for SameDiff/ND4J models by a <em>token budget</em>, not an
 * item count. Shared by every SameDiff inference path (embedding encoder, local LLM, VLM) so a
 * "batch" means the same thing everywhere: a group of items whose padded shape fits a bounded
 * amount of native memory while reusing a small set of execution-plan shapes.
 *
 * <p>Two forces shape a batch, and item-count batching ignores both:</p>
 * <ul>
 *   <li><b>Native memory</b> — a forward pass allocates roughly {@code rows * seqLen * hidden}
 *       elements, so a batch must be bounded by {@code rows * seqLen} (a token budget), never by
 *       a raw row count (1 long doc and 1 short doc cost wildly different amounts).</li>
 *   <li><b>DSP plan reuse</b> — ND4J's {@code DynamicShapePlanExecutor} builds a fresh plan for
 *       every distinct input shape. Padding each batch to one of a small set of
 *       {@link Budget#seqBuckets sequence buckets} (e.g. 64/128/256/512) keeps the number of
 *       distinct shapes — and thus plan rebuilds — tiny, while still cutting the padding waste of
 *       a single fixed maximum (a 30-token cell no longer pays for 512).</li>
 * </ul>
 *
 * <p>The planner is pure (no ND4J, no Spring, no I/O): callers feed per-item token lengths plus a
 * model-relative {@link Budget} and receive {@link Batch batches} that reference the original item
 * order. Every number is supplied by the caller — nothing is hardcoded here. A genuinely oversized
 * single item is never dropped or split; it becomes a one-row batch and the caller's reactive
 * out-of-memory handling remains the backstop.</p>
 */
public final class InferenceBatchPlanner {

    private InferenceBatchPlanner() {}

    /**
     * A model/stage-relative batching budget. All fields are caller-supplied so the same planner
     * serves a 512-token BERT encoder and a 4096-token LLM context without any embedded constants.
     */
    public static final class Budget {
        private final int seqHardCap;
        private final int[] seqBuckets;
        private final long maxBatchTokens;
        private final int maxRows;
        private final int minRows;

        private Budget(Builder b) {
            if (b.seqHardCap <= 0) {
                throw new IllegalArgumentException("seqHardCap must be > 0, was " + b.seqHardCap);
            }
            if (b.maxBatchTokens <= 0) {
                throw new IllegalArgumentException("maxBatchTokens must be > 0, was " + b.maxBatchTokens);
            }
            this.seqHardCap = b.seqHardCap;
            this.seqBuckets = normalizeBuckets(b.seqBuckets, b.seqHardCap);
            this.maxBatchTokens = b.maxBatchTokens;
            this.minRows = Math.max(1, b.minRows);
            this.maxRows = b.maxRows <= 0 ? Integer.MAX_VALUE : Math.max(this.minRows, b.maxRows);
        }

        public int seqHardCap() { return seqHardCap; }
        public int[] seqBuckets() { return seqBuckets.clone(); }
        public long maxBatchTokens() { return maxBatchTokens; }
        public int maxRows() { return maxRows; }
        public int minRows() { return minRows; }

        public static Builder builder() { return new Builder(); }

        /** Fluent builder; only {@code seqHardCap} and {@code maxBatchTokens} are required. */
        public static final class Builder {
            private int seqHardCap;
            private int[] seqBuckets;
            private long maxBatchTokens;
            private int maxRows;
            private int minRows = 1;

            /** Maximum sequence length the model accepts; longer items are truncated to this. */
            public Builder seqHardCap(int v) { this.seqHardCap = v; return this; }
            /** Allowed padded sequence lengths (ascending). When null/empty, next-power-of-two buckets are used. */
            public Builder seqBuckets(int[] v) { this.seqBuckets = v; return this; }
            /** Token ceiling per batch ({@code rows * seqBucket}); derive from a native-memory ceiling. */
            public Builder maxBatchTokens(long v) { this.maxBatchTokens = v; return this; }
            /** Optional hard cap on rows per batch (a safety valve); {@code <= 0} means unbounded. */
            public Builder maxRows(int v) { this.maxRows = v; return this; }
            /** Minimum rows per batch when the budget allows (default 1). */
            public Builder minRows(int v) { this.minRows = v; return this; }

            public Budget build() { return new Budget(this); }
        }
    }

    /**
     * One planned micro-batch. {@link #itemIndices} reference positions in the planner's input
     * array, so callers can map results back regardless of the internal length sort.
     */
    public static final class Batch {
        private final int[] itemIndices;
        private final int seqBucket;

        Batch(int[] itemIndices, int seqBucket) {
            this.itemIndices = itemIndices;
            this.seqBucket = seqBucket;
        }

        /** Indices into the original {@code tokenLengths} array, in ascending-length order. */
        public int[] itemIndices() { return itemIndices; }
        /** Padded sequence length for this batch (one of the budget's buckets). */
        public int seqBucket() { return seqBucket; }
        /** Number of items (rows) in this batch. */
        public int rows() { return itemIndices.length; }
        /** Padded token count this batch will materialize: {@code rows * seqBucket}. */
        public long tokens() { return (long) itemIndices.length * seqBucket; }

        @Override
        public String toString() {
            return rows() + "x" + seqBucket + " (" + tokens() + " tok)";
        }
    }

    /**
     * Group items into token-budgeted, bucket-padded batches.
     *
     * <p>Items are sorted by (capped) length ascending so similar-length items share a batch,
     * minimizing both padding and the number of distinct shapes. A new item joins the open batch
     * only while {@code (rows+1) * bucketFor(maxLen) <= maxBatchTokens} and {@code rows+1 <= maxRows};
     * otherwise the open batch is emitted and a new one begins.</p>
     *
     * @param tokenLengths real token length of each item (e.g. from the tokenizer); values are
     *                     clamped to {@code [1, seqHardCap]}
     * @param budget       the model/stage-relative budget
     * @return batches covering every input item exactly once; empty when {@code tokenLengths} is empty
     */
    public static List<Batch> plan(int[] tokenLengths, Budget budget) {
        List<Batch> batches = new ArrayList<>();
        if (tokenLengths == null || tokenLengths.length == 0) {
            return batches;
        }
        int n = tokenLengths.length;
        // Capped lengths + an index array sorted by them (stable enough; ascending by length).
        int[] capped = new int[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            capped[i] = Math.max(1, Math.min(budget.seqHardCap, tokenLengths[i]));
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(capped[a], capped[b]));

        List<Integer> current = new ArrayList<>();
        int currentMaxLen = 0;
        for (int k = 0; k < n; k++) {
            int idx = order[k];
            int len = capped[idx];
            int candidateBucket = bucketFor(Math.max(currentMaxLen, len), budget.seqBuckets, budget.seqHardCap);
            int candidateRows = current.size() + 1;
            boolean fitsTokens = (long) candidateRows * candidateBucket <= budget.maxBatchTokens;
            boolean fitsRows = candidateRows <= budget.maxRows;
            if (current.isEmpty() || (fitsTokens && fitsRows)) {
                current.add(idx);
                currentMaxLen = Math.max(currentMaxLen, len);
            } else {
                batches.add(emit(current, currentMaxLen, budget));
                current = new ArrayList<>();
                current.add(idx);
                currentMaxLen = len;
            }
        }
        if (!current.isEmpty()) {
            batches.add(emit(current, currentMaxLen, budget));
        }
        return batches;
    }

    private static Batch emit(List<Integer> items, int maxLen, Budget budget) {
        int[] arr = new int[items.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = items.get(i);
        }
        return new Batch(arr, bucketFor(maxLen, budget.seqBuckets, budget.seqHardCap));
    }

    /**
     * Smallest allowed bucket {@code >= len}: the smallest configured bucket that covers {@code len},
     * or the next power of two when no explicit buckets are configured. Always within
     * {@code [1, seqHardCap]}.
     */
    public static int bucketFor(int len, int[] seqBuckets, int seqHardCap) {
        int want = Math.max(1, Math.min(seqHardCap, len));
        if (seqBuckets != null && seqBuckets.length > 0) {
            for (int b : seqBuckets) {
                if (b >= want) {
                    return Math.min(b, seqHardCap);
                }
            }
            return seqHardCap; // nothing covered it; fall back to the hard cap
        }
        return Math.min(seqHardCap, nextPowerOfTwo(want));
    }

    /**
     * Derive a {@code rows * seqLen} token budget from a native-memory ceiling and the model's
     * geometry. {@code bytesPerToken ≈ hiddenSize * bytesPerElement * activationFactor}, where
     * {@code activationFactor} captures per-token intermediate/attention buffers and is tuned by the
     * caller (it is the one fuzzy term; reactive OOM handling remains the backstop). Never returns
     * less than {@code seqHardCap} so at least one full-length row is always plannable.
     *
     * <p><b>Linear only.</b> This models native cost as {@code rows * seqLen} (linear in sequence),
     * which fits autoregressive decode (KV cache grows linearly) but <em>under-counts a full
     * bidirectional encoder forward pass</em>, whose attention scores cost {@code rows * seqLen^2}.
     * For such models size with {@link #estimateMaxRowsForSeq} instead.</p>
     */
    public static long estimateMaxBatchTokens(long memoryCeilingBytes, int hiddenSize,
                                              int bytesPerElement, double safetyFraction,
                                              double activationFactor, int seqHardCap) {
        double bytesPerToken = Math.max(1.0, (double) hiddenSize * bytesPerElement * activationFactor);
        double usable = memoryCeilingBytes * Math.max(0.01, Math.min(1.0, safetyFraction));
        long tokens = (long) Math.floor(usable / bytesPerToken);
        return Math.max(seqHardCap, tokens);
    }

    /**
     * Maximum rows that fit a native-memory ceiling at a given padded sequence length, modeling the
     * TWO native costs a full-attention encoder forward pass pays — costs a flat {@code rows*seqLen}
     * token budget conflates and therefore under-counts:
     * <ul>
     *   <li><b>Linear activations</b> — per row, each layer materializes a handful of
     *       {@code seqLen * hidden} buffers (QKV, attention output, the {@code 4*hidden} FFN
     *       intermediate, residuals): {@code ≈ linActivationFactor * hidden * seqLen} elements.</li>
     *   <li><b>Quadratic attention</b> — per row, each layer materializes {@code heads * seqLen^2}
     *       score/softmax buffers: {@code ≈ attentionFactor * seqLen^2} elements. This term DOMINATES
     *       at long sequences and is exactly what a token budget cannot see, so a batch that looks
     *       affordable by token count can still balloon native memory and trip an OOM kill.</li>
     * </ul>
     *
     * <p>Both factors fold the layer count, head count and the allocator's retention/overhead into a
     * single per-model coefficient — the encoder runs SameDiff without per-layer workspace recycling,
     * so intermediates accumulate across the whole graph — and are caller-supplied; nothing
     * model-specific is hardcoded here. Evaluate at the model's {@code seqHardCap} to get a row cap
     * that is safe for every smaller bucket too (fewer tokens AND a smaller quadratic term). Always
     * returns at least 1 so a single oversized row is still plannable; reactive OOM is the backstop.
     *
     * @return max rows per batch at {@code seqLen}, clamped to {@code [1, Integer.MAX_VALUE]}
     */
    public static int estimateMaxRowsForSeq(long memoryCeilingBytes, int hiddenSize, int bytesPerElement,
                                            double safetyFraction, double linActivationFactor,
                                            double attentionFactor, int seqLen) {
        int seq = Math.max(1, seqLen);
        int hidden = Math.max(1, hiddenSize);
        int elem = Math.max(1, bytesPerElement);
        double usable = Math.max(0L, memoryCeilingBytes) * Math.max(0.01, Math.min(1.0, safetyFraction));
        double linBytesPerRow = (double) elem * Math.max(0.0, linActivationFactor) * hidden * seq;
        double quadBytesPerRow = (double) elem * Math.max(0.0, attentionFactor) * (double) seq * seq;
        double bytesPerRow = Math.max(1.0, linBytesPerRow + quadBytesPerRow);
        long rows = (long) Math.floor(usable / bytesPerRow);
        if (rows < 1) {
            return 1;
        }
        return rows > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rows;
    }

    /**
     * Parse a JavaCPP-style byte-size string into bytes. Accepts an optional {@code k}/{@code m}/
     * {@code g} suffix (case-insensitive) — the form the subprocess launcher uses for
     * {@code -Dorg.bytedeco.javacpp.maxphysicalbytes} (e.g. {@code "49152m"}) — or a raw byte count.
     * Returns 0 for null/blank/unparseable input so callers can treat it as "unset". Shared so every
     * SameDiff path reads the native-memory ceiling identically.
     */
    public static long parseByteSize(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        long mult = 1L;
        if (t.endsWith("g")) {
            mult = 1L << 30;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1L << 20;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("k")) {
            mult = 1L << 10;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return (long) (Double.parseDouble(t.trim()) * mult);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int[] normalizeBuckets(int[] raw, int seqHardCap) {
        if (raw == null || raw.length == 0) {
            return new int[0];
        }
        int[] sorted = raw.clone();
        java.util.Arrays.sort(sorted);
        // Keep distinct, positive, <= seqHardCap; guarantee the hard cap is the top bucket.
        List<Integer> out = new ArrayList<>(sorted.length + 1);
        for (int b : sorted) {
            if (b > 0 && b <= seqHardCap && (out.isEmpty() || out.get(out.size() - 1) != b)) {
                out.add(b);
            }
        }
        if (out.isEmpty() || out.get(out.size() - 1) != seqHardCap) {
            out.add(seqHardCap);
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    private static int nextPowerOfTwo(int v) {
        if (v <= 1) {
            return 1;
        }
        int highest = Integer.highestOneBit(v);
        return highest == v ? v : highest << 1;
    }
}
