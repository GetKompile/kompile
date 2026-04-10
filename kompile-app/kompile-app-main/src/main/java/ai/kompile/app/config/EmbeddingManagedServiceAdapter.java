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

package ai.kompile.app.config;

import ai.kompile.app.services.GpuResourceManager;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.event.EmbeddingSubprocessEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.Optional;

/**
 * Bridges the AnseriniEmbeddingModelImpl (in kompile-embedding-anserini module)
 * with the ModelLifecycleManager (in kompile-app-main module).
 *
 * <p>This adapter implements {@link ModelLifecycleManager.ManagedService} by
 * delegating to {@link AnseriniEmbeddingModelImpl#suspendForPreemption(String)}
 * and {@link AnseriniEmbeddingModelImpl#resumeFromPreemption()}.</p>
 *
 * <p>It also listens for embedding subprocess lifecycle events to automatically
 * register and release GPU reservations:
 * <ul>
 *   <li>{@code MODEL_LOADED} — registers a GPU reservation</li>
 *   <li>{@code SUBPROCESS_STOPPED} / {@code SUBPROCESS_CRASHED} — releases the reservation</li>
 *   <li>{@code SUBPROCESS_RESTART_SUCCESS} — re-registers the reservation</li>
 * </ul>
 *
 * <p>Only activates when both beans are present in the application context.</p>
 */
@Configuration
@ConditionalOnBean({AnseriniEmbeddingModelImpl.class, ModelLifecycleManager.class})
public class EmbeddingManagedServiceAdapter implements ModelLifecycleManager.ManagedService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingManagedServiceAdapter.class);

    @Autowired
    private AnseriniEmbeddingModelImpl embeddingModel;

    @Autowired
    private ModelLifecycleManager lifecycleManager;

    @Autowired
    private GpuResourceManager gpuResourceManager;

    @PostConstruct
    public void register() {
        lifecycleManager.registerService(this);
        log.info("Registered AnseriniEmbeddingModelImpl as managed service for lifecycle coordination");

        // If the embedding subprocess is already running, register a GPU reservation
        // so the lifecycle manager knows about the existing GPU memory usage.
        registerGpuReservationIfRunning();
    }

    // ==================== Spring Event Listeners ====================

    /**
     * Listen for embedding subprocess lifecycle events to keep GPU reservations in sync.
     */
    @EventListener
    public void onEmbeddingEvent(EmbeddingSubprocessEvent event) {
        switch (event.getEventType()) {
            case MODEL_LOADED:
                // Embedding model is loaded on GPU — register reservation if not already held
                if (!gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING)) {
                    log.info("Embedding model loaded — registering GPU reservation");
                    registerGpuReservationIfRunning();
                }
                break;

            case SUBPROCESS_STOPPED:
                // Embedding subprocess stopped — release reservation unless preempted
                // (if preempted, the lifecycle manager handles the reservation)
                if (!embeddingModel.isPreempted()
                        && gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING)) {
                    log.info("Embedding subprocess stopped (not preempted) — releasing GPU reservation");
                    gpuResourceManager.releaseAllForService(DeviceRoutingConfig.SERVICE_EMBEDDING);
                }
                break;

            case SUBPROCESS_CRASHED:
                // Embedding subprocess crashed — release reservation unless preempted
                if (!embeddingModel.isPreempted()
                        && gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING)) {
                    log.info("Embedding subprocess crashed — releasing GPU reservation");
                    gpuResourceManager.releaseAllForService(DeviceRoutingConfig.SERVICE_EMBEDDING);
                }
                break;

            case SUBPROCESS_RESTART_SUCCESS:
                // Subprocess restarted — re-register reservation if not already held
                if (!gpuResourceManager.hasReservationForService(DeviceRoutingConfig.SERVICE_EMBEDDING)) {
                    log.info("Embedding subprocess restarted — registering GPU reservation");
                    registerGpuReservationIfRunning();
                }
                break;

            default:
                // Other events (PROGRESS, LOG, ERROR, etc.) — no reservation action needed
                break;
        }
    }

    // ==================== ManagedService Implementation ====================

    @Override
    public String getServiceType() {
        return DeviceRoutingConfig.SERVICE_EMBEDDING;
    }

    @Override
    public boolean suspend(String reason) {
        return embeddingModel.suspendForPreemption(reason);
    }

    @Override
    public boolean resume() {
        return embeddingModel.resumeFromPreemption();
    }

    @Override
    public boolean isRunning() {
        return embeddingModel.isInitialized();
    }

    @Override
    public boolean isSuspended() {
        return embeddingModel.isPreempted();
    }

    // ==================== GPU Reservation Helpers ====================

    /**
     * Register a GPU reservation for the embedding subprocess if it's currently running.
     * Determines the correct GPU device from the embedding's device routing config.
     */
    private void registerGpuReservationIfRunning() {
        if (!embeddingModel.isInitialized()) {
            log.debug("Embedding subprocess not yet initialized — skipping reservation registration");
            return;
        }

        // Determine which device the embedding is running on
        Integer cudaDevice = embeddingModel.getConfiguredCudaDevice();
        GpuDevice targetDevice;

        if (cudaDevice != null) {
            // Explicit device routing
            Optional<GpuDevice> device = gpuResourceManager.getDeviceByCudaRuntimeIndex(cudaDevice);
            if (device.isEmpty()) {
                log.warn("Embedding configured for CUDA device {} but no such device found in GpuResourceManager",
                        cudaDevice);
                return;
            }
            targetDevice = device.get();
        } else {
            // No explicit routing — assume device 0 (default CUDA behavior)
            Optional<GpuDevice> device = gpuResourceManager.getDeviceByCudaRuntimeIndex(0);
            if (device.isEmpty()) {
                log.warn("No CUDA device 0 found in GpuResourceManager — cannot register embedding reservation");
                return;
            }
            targetDevice = device.get();
        }

        long budget = gpuResourceManager.getMemoryBudget(DeviceRoutingConfig.SERVICE_EMBEDDING);
        try {
            gpuResourceManager.reserve(DeviceRoutingConfig.SERVICE_EMBEDDING, targetDevice, budget);
            log.info("Registered embedding GPU reservation: {}MB on {} (CUDA runtime {})",
                    budget / (1024 * 1024), targetDevice.name(), targetDevice.cudaRuntimeIndex());
        } catch (IllegalStateException e) {
            log.warn("Could not register embedding reservation (device may be over-committed): {}",
                    e.getMessage());
        }
    }
}
