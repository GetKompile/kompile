/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.config;

import ai.kompile.app.services.GpuResourceManager;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.event.EmbeddingSubprocessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmbeddingManagedServiceAdapter}.
 * Tests event handling, preemption delegation, and GPU reservation sync.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingManagedServiceAdapter Tests")
class EmbeddingManagedServiceAdapterTest {

    private static final long ONE_GB = 1024L * 1024L * 1024L;
    private static final GpuDevice GPU_4090 = GpuDevice.local(0, 1, "RTX 4090", 24L * ONE_GB);

    @Mock
    private AnseriniEmbeddingModelImpl embeddingModel;

    @Mock
    private ModelLifecycleManager lifecycleManager;

    @Mock
    private GpuResourceManager gpuResourceManager;

    @InjectMocks
    private EmbeddingManagedServiceAdapter adapter;

    // ==================== ManagedService Implementation ====================

    @Nested
    @DisplayName("ManagedService implementation")
    class ManagedServiceImpl {

        @Test
        @DisplayName("getServiceType should return 'embedding'")
        void serviceType() {
            assertEquals(DeviceRoutingConfig.SERVICE_EMBEDDING, adapter.getServiceType());
        }

        @Test
        @DisplayName("suspend should delegate to embeddingModel.suspendForPreemption")
        void suspendDelegates() {
            when(embeddingModel.suspendForPreemption("VLM preemption")).thenReturn(true);

            boolean result = adapter.suspend("VLM preemption");

            assertTrue(result);
            verify(embeddingModel).suspendForPreemption("VLM preemption");
        }

        @Test
        @DisplayName("resume should delegate to embeddingModel.resumeFromPreemption")
        void resumeDelegates() {
            when(embeddingModel.resumeFromPreemption()).thenReturn(true);

            boolean result = adapter.resume();

            assertTrue(result);
            verify(embeddingModel).resumeFromPreemption();
        }

        @Test
        @DisplayName("isRunning should delegate to embeddingModel.isInitialized")
        void isRunningDelegates() {
            when(embeddingModel.isInitialized()).thenReturn(true);

            assertTrue(adapter.isRunning());
            verify(embeddingModel).isInitialized();
        }

        @Test
        @DisplayName("isSuspended should delegate to embeddingModel.isPreempted")
        void isSuspendedDelegates() {
            when(embeddingModel.isPreempted()).thenReturn(true);

            assertTrue(adapter.isSuspended());
            verify(embeddingModel).isPreempted();
        }
    }

    // ==================== Registration ====================

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("register should call lifecycleManager.registerService")
        void registersWithLifecycleManager() {
            when(embeddingModel.isInitialized()).thenReturn(false);
            adapter.register();
            verify(lifecycleManager).registerService(adapter);
        }

        @Test
        @DisplayName("register should create reservation if embedding is already running")
        void registersReservationIfRunning() {
            when(embeddingModel.isInitialized()).thenReturn(true);
            when(embeddingModel.getConfiguredCudaDevice()).thenReturn(1);
            when(gpuResourceManager.getDeviceByCudaRuntimeIndex(1)).thenReturn(Optional.of(GPU_4090));
            when(gpuResourceManager.getMemoryBudget(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(5L * ONE_GB);
            when(gpuResourceManager.reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, GPU_4090, 5L * ONE_GB))
                    .thenReturn(true);

            adapter.register();

            verify(gpuResourceManager).reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, GPU_4090, 5L * ONE_GB);
        }

        @Test
        @DisplayName("register should skip reservation if embedding is not initialized")
        void skipsReservationIfNotRunning() {
            when(embeddingModel.isInitialized()).thenReturn(false);
            adapter.register();
            verify(gpuResourceManager, never()).reserve(anyString(), any(), anyLong());
        }
    }

    // ==================== Event Handling ====================

    @Nested
    @DisplayName("Event handling")
    class EventHandling {

        @Test
        @DisplayName("MODEL_LOADED event should register reservation when none exists")
        void modelLoadedRegistersReservation() {
            when(gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(false);
            when(embeddingModel.isInitialized()).thenReturn(true);
            when(embeddingModel.getConfiguredCudaDevice()).thenReturn(1);
            when(gpuResourceManager.getDeviceByCudaRuntimeIndex(1)).thenReturn(Optional.of(GPU_4090));
            when(gpuResourceManager.getMemoryBudget(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(5L * ONE_GB);
            when(gpuResourceManager.reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, GPU_4090, 5L * ONE_GB))
                    .thenReturn(true);

            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.modelLoaded(
                    this, "bge-base-en-v1.5", 768, "samediff");
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager).reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, GPU_4090, 5L * ONE_GB);
        }

        @Test
        @DisplayName("MODEL_LOADED event should skip registration when reservation already exists")
        void modelLoadedSkipsWhenReservationExists() {
            when(gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(true);

            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.modelLoaded(
                    this, "bge-base-en-v1.5", 768, "samediff");
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager, never()).reserve(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("SUBPROCESS_STOPPED event should release reservation when not preempted")
        void subprocessStoppedReleasesReservation() {
            when(embeddingModel.isPreempted()).thenReturn(false);
            when(gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(true);

            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.subprocessStopped(
                    this, "bge-base-en-v1.5");
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager).releaseAllForService(DeviceRoutingConfig.SERVICE_EMBEDDING);
        }

        @Test
        @DisplayName("SUBPROCESS_STOPPED event should NOT release reservation when preempted")
        void subprocessStoppedPreemptedKeepsReservation() {
            when(embeddingModel.isPreempted()).thenReturn(true);

            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.subprocessStopped(
                    this, "bge-base-en-v1.5");
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager, never()).releaseAllForService(anyString());
        }

        @Test
        @DisplayName("SUBPROCESS_CRASHED event should release reservation when not preempted")
        void subprocessCrashedReleasesReservation() {
            when(embeddingModel.isPreempted()).thenReturn(false);
            when(gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(true);

            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.subprocessCrashed(
                    this, "bge-base-en-v1.5", "OOM");
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager).releaseAllForService(DeviceRoutingConfig.SERVICE_EMBEDDING);
        }

        @Test
        @DisplayName("SUBPROCESS_CRASHED event should NOT release reservation when preempted")
        void subprocessCrashedPreemptedKeepsReservation() {
            when(embeddingModel.isPreempted()).thenReturn(true);

            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.subprocessCrashed(
                    this, "bge-base-en-v1.5", "OOM");
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager, never()).releaseAllForService(anyString());
        }

        @Test
        @DisplayName("SUBPROCESS_RESTART_SUCCESS event should re-register reservation")
        void restartSuccessReRegistersReservation() {
            when(gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(false);
            when(embeddingModel.isInitialized()).thenReturn(true);
            when(embeddingModel.getConfiguredCudaDevice()).thenReturn(1);
            when(gpuResourceManager.getDeviceByCudaRuntimeIndex(1)).thenReturn(Optional.of(GPU_4090));
            when(gpuResourceManager.getMemoryBudget(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(5L * ONE_GB);
            when(gpuResourceManager.reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, GPU_4090, 5L * ONE_GB))
                    .thenReturn(true);

            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.subprocessRestartSuccess(
                    this, "bge-base-en-v1.5", 1);
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager).reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, GPU_4090, 5L * ONE_GB);
        }

        @Test
        @DisplayName("SUBPROCESS_RESTART_SUCCESS event should skip when reservation exists")
        void restartSuccessSkipsWhenReservationExists() {
            when(gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(true);

            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.subprocessRestartSuccess(
                    this, "bge-base-en-v1.5", 1);
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager, never()).reserve(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("unhandled events should not trigger reservation changes")
        void unhandledEvents() {
            EmbeddingSubprocessEvent event = EmbeddingSubprocessEvent.progress(
                    this, "bge-base-en-v1.5", "loading", 50, "Loading model");
            adapter.onEmbeddingEvent(event);

            verify(gpuResourceManager, never()).reserve(anyString(), any(), anyLong());
            verify(gpuResourceManager, never()).releaseAllForService(anyString());
        }
    }

    // ==================== GPU Reservation Helpers ====================

    @Nested
    @DisplayName("GPU reservation helpers")
    class ReservationHelpers {

        @Test
        @DisplayName("should use default device 0 when no cuda device configured")
        void defaultCudaDevice() {
            when(embeddingModel.isInitialized()).thenReturn(true);
            when(embeddingModel.getConfiguredCudaDevice()).thenReturn(null);
            when(gpuResourceManager.getDeviceByCudaRuntimeIndex(0)).thenReturn(Optional.of(GPU_4090));
            when(gpuResourceManager.getMemoryBudget(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(5L * ONE_GB);
            when(gpuResourceManager.reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, GPU_4090, 5L * ONE_GB))
                    .thenReturn(true);

            adapter.register();

            verify(gpuResourceManager).getDeviceByCudaRuntimeIndex(0);
        }

        @Test
        @DisplayName("should handle missing CUDA device gracefully")
        void missingCudaDevice() {
            when(embeddingModel.isInitialized()).thenReturn(true);
            when(embeddingModel.getConfiguredCudaDevice()).thenReturn(5);
            when(gpuResourceManager.getDeviceByCudaRuntimeIndex(5)).thenReturn(Optional.empty());

            adapter.register();

            // Should not crash, and should not attempt reservation
            verify(gpuResourceManager, never()).reserve(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("should handle reservation failure gracefully")
        void reservationFailure() {
            when(embeddingModel.isInitialized()).thenReturn(true);
            when(embeddingModel.getConfiguredCudaDevice()).thenReturn(1);
            when(gpuResourceManager.getDeviceByCudaRuntimeIndex(1)).thenReturn(Optional.of(GPU_4090));
            when(gpuResourceManager.getMemoryBudget(DeviceRoutingConfig.SERVICE_EMBEDDING))
                    .thenReturn(5L * ONE_GB);
            when(gpuResourceManager.reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, GPU_4090, 5L * ONE_GB))
                    .thenThrow(new IllegalStateException("Not enough memory"));

            // Should not throw — logs a warning instead
            assertDoesNotThrow(() -> adapter.register());
        }
    }
}
