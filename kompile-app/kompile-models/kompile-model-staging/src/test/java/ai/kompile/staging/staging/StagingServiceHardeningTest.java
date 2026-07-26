/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.staging;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.DownloadResult;
import ai.kompile.staging.download.DownloadService;
import ai.kompile.staging.download.StagingCancellation;
import ai.kompile.staging.optimization.OptimizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StagingServiceHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsTraversalAndAbsoluteModelIdsBeforeStateOrFilesystemAccess() {
        StagingService service = service(mock(DownloadService.class));

        for (String modelId : new String[]{"../escape", "/absolute", "nested/model", "", "a".repeat(129)}) {
            DownloadRequest request = baseRequest(modelId);
            assertThrows(IllegalArgumentException.class, () -> service.stageModelAsync(request));
            assertThrows(IllegalArgumentException.class, () -> service.getStagingModel(modelId));
            assertThrows(IllegalArgumentException.class, () -> service.getStagedOutput(modelId));
            assertThrows(IllegalArgumentException.class, () -> service.cancelStaging(modelId));
        }

        assertFalse(Files.exists(tempDir.resolve("escape")));
        assertFalse(Files.exists(tempDir.resolve("absolute")));
    }

    @Test
    void rejectsDuplicateActiveOperationAndCancelsOnlyAfterQuiescentCleanup() throws Exception {
        DownloadService downloader = mock(DownloadService.class);
        CountDownLatch entered = new CountDownLatch(1);
        when(downloader.canHandle("fixture")).thenReturn(true);
        when(downloader.download(any(), any(), any(), any())).thenAnswer(invocation -> {
            StagingCancellation cancellation = invocation.getArgument(3);
            entered.countDown();
            while (!cancellation.isCancellationRequested()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            cancellation.checkpoint();
            throw new AssertionError("unreachable");
        });
        StagingService service = service(downloader);
        DownloadRequest request = baseRequest("cancel-me");

        CompletableFuture<StagingModelInfo> first = service.stageModelAsync(request);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> service.stageModelAsync(request));

        assertTrue(service.cancelStaging("cancel-me"));
        StagingModelInfo cancelled = first.get(5, TimeUnit.SECONDS);
        assertEquals(StagingStatus.CANCELLED, cancelled.getStatus());
        assertFalse(Files.exists(tempDir.resolve(".staging/pending/cancel-me")));
        assertFalse(Files.exists(tempDir.resolve(".staging/verified/cancel-me")));
    }

    @Test
    void failedOperationReleasesDuplicateGuardAndCleansPendingWorkspace() throws Exception {
        DownloadService downloader = mock(DownloadService.class);
        when(downloader.canHandle("fixture")).thenReturn(true);
        when(downloader.download(any(), any(), any(), any()))
                .thenReturn(DownloadResult.failure("fixture failure"));
        StagingService service = service(downloader);

        StagingModelInfo first = service.stageModel(baseRequest("retryable"));
        StagingModelInfo second = service.stageModel(baseRequest("retryable"));

        assertEquals(StagingStatus.FAILED, first.getStatus());
        assertEquals(StagingStatus.FAILED, second.getStatus());
        assertFalse(Files.exists(tempDir.resolve(".staging/pending/retryable")));
    }

    private StagingService service(DownloadService downloader) {
        RegistryService registry = new RegistryService(tempDir);
        ConversionService conversion = mock(ConversionService.class);
        OptimizationService optimization = mock(OptimizationService.class);
        return new StagingService(
                registry,
                conversion,
                List.of(downloader),
                optimization);
    }

    private DownloadRequest baseRequest(String modelId) {
        return DownloadRequest.builder()
                .source("fixture")
                .repository("owner/repository")
                .modelId(modelId)
                .modelType(ModelType.DENSE_ENCODER)
                .format("sdz")
                .build();
    }
}
