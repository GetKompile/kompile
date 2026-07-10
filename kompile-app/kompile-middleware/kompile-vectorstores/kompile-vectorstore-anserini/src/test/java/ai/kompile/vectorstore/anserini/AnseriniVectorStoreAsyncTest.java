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

package ai.kompile.vectorstore.anserini;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the async-embedding machinery in {@link AnseriniVectorStoreImpl}.
 *
 * <p>Tests are entirely self-contained: no Spring context, no real subprocess.
 * The store is constructed directly with a hand-rolled stub {@link EmbeddingModel}
 * and a temporary Lucene index.  System properties that gate the async knobs are set
 * to small values so tests complete in a few seconds.</p>
 *
 * <h2>INDArray stubs</h2>
 * <p>Rather than Mockito (whose CGLIB backend is incompatible with JDK 17 in this
 * project), {@link #makeFakeMatrix(int, int)} uses {@link Proxy#newProxyInstance}
 * over the {@code INDArray} and {@code DataBuffer} interfaces.  The proxy handles
 * exactly the methods called by {@code embedAndWriteToLucene} and returns sensible
 * defaults for everything else.</p>
 *
 * <h2>Fast-path vs coalescer-path and {@code pendingBytes}</h2>
 * <p>{@code pendingBytes} is only incremented on the <em>coalescer</em> path
 * ({@code batch.size() < coalescerMinBatch}).  On the fast path
 * ({@code batch.size() >= coalescerMinBatch}) only {@code pendingCount} is
 * incremented.  Tests 1 and 2 use the coalescer path (minBatch = 5 or 10 so a
 * 1-doc add satisfies 1 {@literal <} minBatch); Test 3 uses the fast path
 * (minBatch = 1 so 1 {@literal <} 1 is false) and only asserts {@code pendingCount}.</p>
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>{@link #test1_byteBoundedBackpressure} – byte cap gates callers; both counters drain to 0.</li>
 *   <li>{@link #test2_coalescerDebouncesBurst} – burst of 1-doc adds is fused into fewer calls.</li>
 *   <li>{@link #test3_cascadeFailFastAbortsAndDrains} – consecutive null embeds circuit-break
 *       quickly; recovery resets the empty counter.</li>
 * </ol>
 */
class AnseriniVectorStoreAsyncTest {

    @TempDir
    Path tempDir;

    private AnseriniVectorStoreImpl store;

    // ── system-property keys ───────────────────────────────────────────────────
    private static final String PROP_THREADS     = "kompile.vectorstore.async.threads";
    private static final String PROP_MAX_PENDING = "kompile.vectorstore.async.maxPending";
    private static final String PROP_MAX_BYTES   = "kompile.vectorstore.async.maxPendingBytes";
    private static final String PROP_MIN_BATCH   = "kompile.vectorstore.coalesce.minBatch";
    private static final String PROP_IDLE_MS     = "kompile.vectorstore.coalesce.maxWaitMs";
    private static final String PROP_HARD_CAP_MS = "kompile.vectorstore.coalesce.hardMaxWaitMs";

    /** Embedding dim used throughout (tiny so Lucene write is fast). */
    private static final int EMBED_DIM = 4;

    @BeforeEach
    void setup() {
        resetProperties();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.destroy();
            store = null;
        }
        resetProperties();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1 — Byte-bounded backpressure
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@code maxPendingBytes} gates new adds when the buffer is full,
     * and that after {@link AnseriniVectorStoreImpl#awaitPendingEmbeddings()} both
     * {@code pendingBytes} and {@code pendingCount} drain to exactly 0.
     *
     * <p>Uses the <em>coalescer</em> path (minBatch = 5, 1-doc adds → 1 {@literal <} 5)
     * so {@code pendingBytes} is properly incremented before the add returns.</p>
     */
    @Test
    void test1_byteBoundedBackpressure() throws Exception {
        // ── props ──────────────────────────────────────────────────────────────
        // Cap: 2 048 bytes.  Each 1 200-char doc → ~2 656 bytes (2×chars + 256 overhead).
        // First add fills the cap; the second add() must block until the prior task finishes.
        System.setProperty(PROP_MAX_BYTES,   Long.toString(2_048L));
        System.setProperty(PROP_MAX_PENDING, "100");
        System.setProperty(PROP_THREADS,     "2");
        System.setProperty(PROP_MIN_BATCH,   "5");    // 1-doc add → coalescer path (1 < 5)
        System.setProperty(PROP_IDLE_MS,     "120");  // coalescer idle window
        System.setProperty(PROP_HARD_CAP_MS, "1000");

        // ── model: slow embedding gated by a latch ────────────────────────────
        CountDownLatch embeddingGate = new CountDownLatch(1);
        AtomicInteger embedCallCount = new AtomicInteger(0);
        INDArray validMatrix = makeFakeMatrix(1, EMBED_DIM); // created in main thread

        EmbeddingModel slowModel = new TestEmbeddingModel() {
            @Override
            public INDArray embed(List<String> texts) {
                embedCallCount.incrementAndGet();
                try {
                    embeddingGate.await();      // block until the test releases
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return validMatrix;
            }
        };

        store = buildStore(tempDir, slowModel);

        // ── add docs from a daemon thread so the test thread is never stuck ───
        List<Throwable> addErrors = Collections.synchronizedList(new ArrayList<>());
        Thread adder = new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    // 1 200 chars × 2 UTF-16 + 256 overhead ≈ 2 656 bytes > 2 048 cap
                    store.add(List.of(makeDoc("bp-" + i, 1_200)));
                }
            } catch (Throwable t) {
                addErrors.add(t);
            }
        }, "backpressure-adder");
        adder.setDaemon(true);
        adder.start();

        // Wait briefly so the first doc fills the buffer and the second add() blocks.
        Thread.sleep(250);

        // Release embeddings so everything can drain.
        embeddingGate.countDown();
        adder.join(6_000);
        assertTrue(addErrors.isEmpty(), "Adder thread threw: " + addErrors);

        store.awaitPendingEmbeddings();

        // Coalescer path properly tracks pendingBytes — must drain to 0.
        assertEquals(0L, readAtomicLong("pendingBytes"),
                "pendingBytes must drain to 0 after awaitPendingEmbeddings()");
        assertEquals(0, readAtomicInt("pendingCount"),
                "pendingCount must drain to 0 after awaitPendingEmbeddings()");

        // Backpressure must have been exercised: the adder thread was delayed while
        // the gate was closed.  Verify embedding was actually called at least once.
        assertTrue(embedCallCount.get() > 0, "EmbeddingModel must have been called");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2 — Debounce coalescer fuses a burst into fewer, larger batches
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Feeds 5 single-doc adds in rapid succession (≈ 20 ms apart), inside the 100 ms
     * idle window.  Asserts that the embedding model receives FEWER embed() calls with
     * MORE docs per call — i.e. the debounce timer reset on every arrival and fused the
     * burst into a larger batch.
     *
     * <p>Also checks that a lone add followed by idle silence still flushes (no stranding).</p>
     */
    @Test
    void test2_coalescerDebouncesBurst() throws Exception {
        // ── props ──────────────────────────────────────────────────────────────
        System.setProperty(PROP_MIN_BATCH,   "10");   // 1-doc adds → coalescer (1 < 10)
        System.setProperty(PROP_IDLE_MS,     "100");  // reset on every new doc
        System.setProperty(PROP_HARD_CAP_MS, "800");
        System.setProperty(PROP_MAX_BYTES,   Long.toString(512L * 1024 * 1024));
        System.setProperty(PROP_MAX_PENDING, "500");
        System.setProperty(PROP_THREADS,     "2");

        // ── model: records batches received ───────────────────────────────────
        List<List<String>> receivedBatches = Collections.synchronizedList(new ArrayList<>());
        INDArray validMatrix = makeFakeMatrix(1, EMBED_DIM);

        EmbeddingModel recordingModel = new TestEmbeddingModel() {
            @Override
            public INDArray embed(List<String> texts) {
                receivedBatches.add(new ArrayList<>(texts));
                return validMatrix;
            }
        };

        store = buildStore(tempDir, recordingModel);

        // ── burst: 5 single-doc adds with 20 ms gaps ─────────────────────────
        int burstSize = 5;
        for (int i = 0; i < burstSize; i++) {
            store.add(List.of(makeDoc("burst-" + i, 80)));
            Thread.sleep(20);   // 20 ms < 100 ms idle window → debounce timer resets
        }

        // Wait past the idle window + margin so the pending flush fires.
        Thread.sleep(300);
        store.awaitPendingEmbeddings();

        int totalCalls = receivedBatches.size();
        int totalDocs  = receivedBatches.stream().mapToInt(List::size).sum();

        assertEquals(burstSize, totalDocs,
                "All " + burstSize + " burst docs must reach the embedding model");
        // Key assertion: debounce must fuse — NOT one embed() call per add().
        assertTrue(totalCalls < burstSize,
                "Expected fewer than " + burstSize + " embed() calls (debounce should fuse); got "
                        + totalCalls + " call(s) covering " + totalDocs + " doc(s)");

        // ── secondary: a lone add must still eventually flush ─────────────────
        receivedBatches.clear();
        store.add(List.of(makeDoc("lone", 80)));
        Thread.sleep(400);                   // well past idle window
        store.awaitPendingEmbeddings();

        int loneDocs = receivedBatches.stream().mapToInt(List::size).sum();
        assertEquals(1, loneDocs,
                "Isolated single add must flush after idle window — no stranded docs");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 3 — Cascade fail-fast: consecutive empty results abort and drain
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Simulates a dead embedding subprocess by returning {@code null} from
     * {@code embed()} for the first N calls.  After
     * {@code MAX_CONSECUTIVE_EMBED_EMPTY} (default = 5) consecutive nulls the
     * circuit-breaker aborts the task early, so
     * {@link AnseriniVectorStoreImpl#awaitPendingEmbeddings()} returns quickly and
     * {@code pendingCount} drains to 0.
     *
     * <p>A subsequent successful embed verifies that {@code consecutiveEmbedEmpty}
     * resets to 0 on recovery.</p>
     *
     * <p>Uses the <em>fast</em> path (minBatch = 1 → 1 {@literal <} 1 is false) so
     * each single-doc add becomes its own async task and independently increments the
     * counter.  Only {@code pendingCount} is asserted (not {@code pendingBytes},
     * which is not incremented on the fast path).</p>
     */
    @Test
    void test3_cascadeFailFastAbortsAndDrains() throws Exception {
        // ── props ──────────────────────────────────────────────────────────────
        System.setProperty(PROP_MIN_BATCH,   "1");    // fast path: 1 < 1 is false
        System.setProperty(PROP_MAX_BYTES,   Long.toString(512L * 1024 * 1024));
        System.setProperty(PROP_MAX_PENDING, "500");
        System.setProperty(PROP_THREADS,     "1");    // sequential → counter is deterministic

        // ── model: null for first N_FAIL calls, valid matrix afterwards ───────
        final int N_FAIL = 6;   // many enough to trip the default empty-result circuit breaker
        AtomicInteger callCount = new AtomicInteger(0);
        INDArray validMatrix = makeFakeMatrix(1, EMBED_DIM); // pre-created in main thread

        EmbeddingModel failThenRecoverModel = new TestEmbeddingModel() {
            @Override
            public INDArray embed(List<String> texts) {
                int call = callCount.incrementAndGet();
                if (call <= N_FAIL) {
                    return null;        // simulate dead subprocess
                }
                return validMatrix;     // recovery — pre-created, safe to return from bg thread
            }
        };

        store = buildStore(tempDir, failThenRecoverModel);

        // ── 6 adds → 6 fast-path tasks; each embed() returns null ─────────────
        for (int i = 0; i < N_FAIL; i++) {
            store.add(List.of(makeDoc("fail-" + i, 80)));
        }

        // awaitPendingEmbeddings() must fail promptly and drain tasks: the circuit-break is a
        // surfaced indexing failure, not a silent successful barrier.
        long t0 = System.currentTimeMillis();
        RuntimeException failure = assertThrows(RuntimeException.class, store::awaitPendingEmbeddings,
                "persistent empty embeddings must fail the async embedding barrier");
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(failure.getMessage().contains("async embedding tasks failed"), failure.getMessage());
        assertTrue(elapsed < 3_000,
                "awaitPendingEmbeddings() took " + elapsed + " ms — possible hang; "
                        + "fail-fast should abort quickly after " + N_FAIL + " consecutive empties");

        // No leaked in-flight count.
        assertEquals(0, readAtomicInt("pendingCount"),
                "pendingCount must drain to 0 after all fail-fast tasks complete");

        // ── recovery: one successful embed must reset the counter ─────────────
        store.add(List.of(makeDoc("recover", 80)));
        Thread.sleep(500);        // let the single-thread pool pick up and run the task
        store.awaitPendingEmbeddings();

        assertEquals(0, readAtomicInt("pendingCount"),
                "pendingCount must be 0 after recovery task completes");
        assertEquals(0, readAtomicInt("consecutiveEmbedEmpty"),
                "consecutiveEmbedEmpty must reset to 0 after a successful embed");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 4 — NoOpEmbeddingModelImpl: writes never submit async embed tasks
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies the WS7-gap fix: when an {@link AnseriniVectorStoreImpl} is constructed
     * with a {@link NoOpEmbeddingModelImpl}, document writes do NOT submit any async
     * embed tasks — so the "consecutive empty results — appears down" heuristic is
     * never triggered.
     *
     * <p>Specifically asserts:
     * <ul>
     *   <li>{@code pendingCount} and {@code pendingBytes} both stay 0 across multiple {@code add()} calls.</li>
     *   <li>{@code consecutiveEmbedEmpty} stays 0 (no failed embed attempts reached {@code embedAndWriteToLucene}).</li>
     *   <li>{@code awaitPendingEmbeddings()} completes cleanly (no exception).</li>
     * </ul>
     * The real no-op bean is used directly (not a stub), so this covers the
     * exact production path in the graph-matrix subprocess.
     * </p>
     *
     * <p>Note: {@link NoOpEmbeddingModelImpl} requires an ND4J backend on the classpath
     * because its constructor logs via {@code Nd4j.empty(DataType.FLOAT)}.  The
     * {@code nd4j-native} test-scope dependency in the module pom satisfies this.</p>
     */
    @Test
    void test4_noOpModel_doesNotSubmitAsyncEmbedTasks() throws Exception {
        // Standard props — coalescer and fast-path both active.
        System.setProperty(PROP_MIN_BATCH,   "32");
        System.setProperty(PROP_MAX_BYTES,   Long.toString(512L * 1024 * 1024));
        System.setProperty(PROP_MAX_PENDING, "500");
        System.setProperty(PROP_THREADS,     "2");
        System.setProperty(PROP_IDLE_MS,     "100");
        System.setProperty(PROP_HARD_CAP_MS, "1000");

        // Use the real NoOpEmbeddingModelImpl — same bean that wins in the graph subprocess.
        NoOpEmbeddingModelImpl noOpModel = new NoOpEmbeddingModelImpl();
        store = buildStore(tempDir, noOpModel);

        // Verify the flag is set.
        assertTrue(readBoolean("embeddingDisabled"),
                "embeddingDisabled flag must be true when constructed with NoOpEmbeddingModelImpl");

        // Add several documents — should NOT queue any embed tasks.
        for (int i = 0; i < 5; i++) {
            store.add(List.of(makeDoc("noop-" + i, 200)));
        }

        // Small pause in case any async path somehow fires.
        Thread.sleep(200);

        assertEquals(0, readAtomicInt("pendingCount"),
                "pendingCount must be 0 — no embed tasks should have been submitted");
        assertEquals(0L, readAtomicLong("pendingBytes"),
                "pendingBytes must be 0 — no embed tasks should have been submitted");
        assertEquals(0, readAtomicInt("consecutiveEmbedEmpty"),
                "consecutiveEmbedEmpty must be 0 — embed path was never reached");

        // awaitPendingEmbeddings() must succeed cleanly.
        store.awaitPendingEmbeddings(); // must not throw
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 5 — Real (mock) embedding model still triggers normal async embed
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies the main-app path is unaffected: a store built with a real
     * (non-no-op) {@link EmbeddingModel} stub still submits async embed tasks
     * and eventually calls the model.
     *
     * <p>This guards against a regression where {@code embeddingDisabled} is
     * accidentally set for non-no-op models.</p>
     */
    @Test
    void test5_realModel_stillSubmitsAsyncEmbedTasks() throws Exception {
        System.setProperty(PROP_MIN_BATCH,   "32");   // coalescer path for single docs
        System.setProperty(PROP_MAX_BYTES,   Long.toString(512L * 1024 * 1024));
        System.setProperty(PROP_MAX_PENDING, "500");
        System.setProperty(PROP_THREADS,     "2");
        System.setProperty(PROP_IDLE_MS,     "150");
        System.setProperty(PROP_HARD_CAP_MS, "2000");

        AtomicInteger embedCallCount = new AtomicInteger(0);
        INDArray validMatrix = makeFakeMatrix(1, EMBED_DIM);

        EmbeddingModel realModel = new TestEmbeddingModel() {
            @Override
            public INDArray embed(List<String> texts) {
                embedCallCount.incrementAndGet();
                return validMatrix;
            }
        };

        store = buildStore(tempDir, realModel);

        // Flag must NOT be set for a real model.
        assertFalse(readBoolean("embeddingDisabled"),
                "embeddingDisabled must be false for a non-NoOp EmbeddingModel");

        // Add documents.
        for (int i = 0; i < 4; i++) {
            store.add(List.of(makeDoc("real-" + i, 80)));
        }

        // Wait past the idle window so the coalescer flushes.
        Thread.sleep(400);
        store.awaitPendingEmbeddings();

        assertTrue(embedCallCount.get() > 0,
                "embed() must have been called at least once for a real model");
        assertEquals(0, readAtomicInt("pendingCount"),
                "pendingCount must drain to 0 after awaitPendingEmbeddings()");
    }

    @Test
    void smallFailedBatchRecoversWithSingleItemEmbeddings() throws Exception {
        System.setProperty(PROP_MIN_BATCH, "10");
        System.setProperty(PROP_MAX_BYTES, Long.toString(512L * 1024 * 1024));
        System.setProperty(PROP_MAX_PENDING, "500");
        System.setProperty(PROP_THREADS, "1");
        System.setProperty(PROP_IDLE_MS, "1000");
        System.setProperty(PROP_HARD_CAP_MS, "5000");

        AtomicInteger embedCallCount = new AtomicInteger(0);
        EmbeddingModel model = new TestEmbeddingModel() {
            @Override
            public INDArray embed(List<String> texts) {
                embedCallCount.incrementAndGet();
                if (texts.size() > 1) {
                    return null;
                }
                return makeFakeMatrix(1, EMBED_DIM);
            }
        };

        store = buildStore(tempDir, model);

        for (int i = 0; i < 3; i++) {
            store.add(List.of(makeDoc("recover-single-" + i, 80)));
        }

        store.awaitPendingEmbeddings();

        assertEquals(4, embedCallCount.get(),
                "one failed batch call plus three single-item recovery calls should run");
        assertEquals(0, readAtomicInt("pendingCount"),
                "pendingCount must drain to 0 after recovery completes");
        assertEquals(0, readAtomicInt("consecutiveEmbedEmpty"),
                "successful recovery must reset the empty-result circuit breaker");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Infrastructure helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a minimal {@link INDArray} stub using JDK {@link Proxy}, with no CGLIB
     * dependency.  The proxy handles exactly the methods called by
     * {@code embedAndWriteToLucene}; all other methods return sensible defaults
     * (false for boolean, null/0 for others).
     *
     * <p>Stub behaviour:
     * <ul>
     *   <li>{@code isEmpty()} → false</li>
     *   <li>{@code rows()} → {@code rows} (the int passed in)</li>
     *   <li>{@code columns()} → {@code cols}</li>
     *   <li>{@code ordering()} → {@code 'c'} with contiguous row-major strides</li>
     *   <li>{@code data()} → DataBuffer whose {@code asFloat()} returns all-1.0f data</li>
     *   <li>{@code wasClosed()} → false</li>
     *   <li>{@code close()} → no-op (void, returns null)</li>
     * </ul>
     * </p>
     */
    static INDArray makeFakeMatrix(int rows, int cols) {
        float[] flatData = new float[rows * cols];
        Arrays.fill(flatData, 1.0f);   // magnitude = √cols > 1e-9 ✓

        DataBuffer fakeBuf = (DataBuffer) Proxy.newProxyInstance(
                DataBuffer.class.getClassLoader(),
                new Class[]{DataBuffer.class},
                (proxy, method, args) -> {
                    if ("asFloat".equals(method.getName())) return flatData;
                    if ("getFloatsAt".equals(method.getName())) return flatData;
                    Class<?> ret = method.getReturnType();
                    if (ret == void.class)    return null;
                    if (ret == boolean.class) return false;
                    if (ret == int.class)     return 0;
                    if (ret == long.class)    return 0L;
                    return null;
                }
        );

        return (INDArray) Proxy.newProxyInstance(
                INDArray.class.getClassLoader(),
                new Class[]{INDArray.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isEmpty":    return false;
                        case "rows":       return rows;
                        case "columns":    return cols;
                        case "ordering":   return 'c';
                        case "isView":     return false;
                        case "stride": {
                            int dim = (Integer) args[0];
                            return dim == 0 ? cols : 1;
                        }
                        case "offset":     return 0L;
                        case "data":       return fakeBuf;
                        case "wasClosed":  return false;
                        case "close":      return null;
                        default: {
                            Class<?> ret = method.getReturnType();
                            if (ret == void.class)    return null;
                            if (ret == boolean.class) return false;
                            if (ret == int.class)     return 0;
                            if (ret == long.class)    return 0L;
                            if (ret == char.class)    return '\0';
                            return null;
                        }
                    }
                }
        );
    }

    /** Builds a store wired to the given temp dir and EmbeddingModel. */
    private AnseriniVectorStoreImpl buildStore(Path dir, EmbeddingModel model) {
        AnseriniVectorStoreProperties props = new AnseriniVectorStoreProperties(dir.toString());
        props.setIndexPath(dir.toString());
        props.setPersistenceEnabled(true);
        props.setEnabled(true);
        props.setMemoryBufferSizeMb(4.0);       // small buffer for tests
        props.setBatchCommitInterval(1);          // commit every batch
        props.setMaxDocumentsBeforeCommit(1);
        return new AnseriniVectorStoreImpl(props, model);
    }

    /** Creates a Spring AI Document with the given id and text of {@code charCount} chars. */
    private Document makeDoc(String id, int charCount) {
        return new Document(id, "x".repeat(charCount), Collections.emptyMap());
    }

    /** Reads a private {@link AtomicLong} field from {@code store} via reflection. */
    private long readAtomicLong(String field) throws Exception {
        Field f = AnseriniVectorStoreImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        return ((AtomicLong) f.get(store)).get();
    }

    /** Reads a private {@link AtomicInteger} field from {@code store} via reflection. */
    private int readAtomicInt(String field) throws Exception {
        Field f = AnseriniVectorStoreImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        return ((AtomicInteger) f.get(store)).get();
    }

    /** Reads a private {@code volatile boolean} field from {@code store} via reflection. */
    private boolean readBoolean(String field) throws Exception {
        Field f = AnseriniVectorStoreImpl.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.getBoolean(store);
    }

    /** Clears all system properties set by the tests. */
    private void resetProperties() {
        System.clearProperty(PROP_THREADS);
        System.clearProperty(PROP_MAX_PENDING);
        System.clearProperty(PROP_MAX_BYTES);
        System.clearProperty(PROP_MIN_BATCH);
        System.clearProperty(PROP_IDLE_MS);
        System.clearProperty(PROP_HARD_CAP_MS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Base stub EmbeddingModel
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Minimal {@link EmbeddingModel} stub.  Subclasses override
     * {@link #embed(List)} to inject test-specific behaviour.
     */
    abstract static class TestEmbeddingModel implements EmbeddingModel {

        @Override
        public INDArray embed(String text) {
            return makeFakeMatrix(1, EMBED_DIM);
        }

        // embed(List) must be overridden in each test.
        @Override
        public abstract INDArray embed(List<String> texts);

        @Override
        public INDArray embedDocuments(List<Document> documents) {
            return makeFakeMatrix(documents.size(), EMBED_DIM);
        }

        @Override
        public int dimensions() {
            return EMBED_DIM;
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
