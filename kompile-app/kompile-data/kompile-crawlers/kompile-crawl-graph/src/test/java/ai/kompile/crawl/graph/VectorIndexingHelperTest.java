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

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.core.embeddings.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VectorIndexingHelperTest {

    @Test
    void formatVectorBatchLabelClampsRetrySplitPastPlannedTotal() {
        assertEquals("Indexed vector batch 2/2 (retry split)",
                VectorIndexingHelper.formatVectorBatchLabel("Indexed vector batch", 3, 2));
    }

    @Test
    void formatVectorBatchLabelKeepsNormalPlannedBatchNumbers() {
        assertEquals("Embedding/indexing batch 1/2",
                VectorIndexingHelper.formatVectorBatchLabel("Embedding/indexing batch", 1, 2));
        assertEquals("Embedding/indexing batch 2/2",
                VectorIndexingHelper.formatVectorBatchLabel("Embedding/indexing batch", 2, 2));
    }

    @Test
    void explicitCollectionOverridesFactSheetAndDefaultPaths() {
        VectorIndexConfig config = VectorIndexConfig.builder()
                .collectionName(" /indices/custom ")
                .build();
        UnifiedCrawlJob job = jobScopedTo(41L);

        assertEquals("/indices/custom",
                VectorIndexingHelper.resolveEffectiveCollectionName(config, job, "/indices/default"));
    }

    @Test
    void blankCollectionDerivesStableFactSheetPath() {
        assertEquals("fact-sheet-41",
                VectorIndexingHelper.resolveEffectiveCollectionName(
                        VectorIndexConfig.builder().build(), jobScopedTo(41L), "/indices/default"));
    }

    @Test
    void unscopedStandaloneIndexingReturnsToCapturedDefaultPath() {
        assertEquals("/indices/default",
                VectorIndexingHelper.resolveEffectiveCollectionName(
                        VectorIndexConfig.builder().build(), UnifiedCrawlJob.builder().build(),
                        " /indices/default "));
    }

    @Test
    void collectionSwitchFailureIsFailClosedWhenCurrentPathIsUnknown() {
        VectorIndexingHelper helper = new VectorIndexingHelper();
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.switchIndexPath("fact-sheet-41")).thenReturn(false);
        ReflectionTestUtils.setField(helper, "vectorStore", vectorStore);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> helper.switchToEffectiveCollection("fact-sheet-41", null));

        assertTrue(error.getMessage().contains("fact-sheet-41"));
    }

    @Test
    void storeExecutorShutdownWaitsForOwnedWriteToQuiesce() throws Exception {
        VectorIndexingHelper helper = new VectorIndexingHelper();
        ExecutorService storeExecutor = Executors.newSingleThreadExecutor();
        ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch storeStarted = new CountDownLatch(1);
        CountDownLatch storeInterrupted = new CountDownLatch(1);
        CountDownLatch releaseStore = new CountDownLatch(1);

        Future<?> pendingStore = storeExecutor.submit(() -> {
            storeStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseStore.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    storeInterrupted.countDown();
                }
            }
        });

        try {
            assertTrue(storeStarted.await(1, TimeUnit.SECONDS));
            Future<?> shutdown = shutdownExecutor.submit(
                    () -> helper.shutdownStoreExecutor(storeExecutor, pendingStore, jobScopedTo(41L)));

            assertTrue(storeInterrupted.await(1, TimeUnit.SECONDS));
            assertFalse(shutdown.isDone(), "Shutdown must retain ownership while the store task is alive");

            releaseStore.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
            assertTrue(storeExecutor.isTerminated());
        } finally {
            releaseStore.countDown();
            storeExecutor.shutdownNow();
            shutdownExecutor.shutdownNow();
        }
    }

    @Test
    void pendingStoreWaitCancelsTheOwnedFutureWhenTheJobIsCancelling() {
        VectorIndexingHelper helper = new VectorIndexingHelper();
        CompletableFuture<Integer> pendingStore = new CompletableFuture<>();
        UnifiedCrawlJob job = jobScopedTo(41L);
        job.getStatus().set(UnifiedCrawlJob.Status.CANCELLING);

        assertThrows(CancellationException.class, () -> helper.collectPendingStoreResult(
                pendingStore, List.of(), 1, 1, System.nanoTime(), null, null, job));

        assertTrue(pendingStore.isCancelled());
    }

    private static UnifiedCrawlJob jobScopedTo(Long factSheetId) {
        return UnifiedCrawlJob.builder()
                .request(UnifiedCrawlRequest.builder().factSheetId(factSheetId).build())
                .build();
    }
}
