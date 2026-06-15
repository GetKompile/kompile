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

package ai.kompile.app.services;

import ai.kompile.app.config.GpuDevice;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Central service tracking GPU memory reservations across all managed services.
 *
 * <p>Each service (embedding, VLM, ingest, etc.) registers a <b>reservation</b> — a declared
 * memory budget on a specific GPU device. The resource manager tracks these reservations
 * and answers queries like "is there room for service X on device Y?" or
 * "which services must be evicted to make room for service X?".</p>
 *
 * <p>Reservations are keyed by a {@code reservationId}, which can be a service type
 * (for singleton services like embedding) or a job-specific key (for services that
 * run multiple concurrent jobs like ingest). This allows multiple concurrent reservations
 * of the same service type without collision.</p>
 *
 * <p><b>This is a reservation-based system, not a real-time memory monitor.</b>
 * Services declare how much GPU memory they expect to use. The manager does not
 * poll nvidia-smi for live usage — it trusts the declared budgets. This is intentional:
 * real-time polling introduces race conditions and cannot prevent OOM because memory
 * allocation happens inside the subprocess, not in the parent JVM.</p>
 *
 * <h3>Multi-node extension</h3>
 * <p>GPU devices carry a {@code nodeId} field ("local" for the current machine).
 * Future multi-node support would add a {@code NodeRegistry} that discovers remote
 * nodes and their GPUs, populating this manager's device list via RPC.</p>
 */
@Service
public class GpuResourceManager {

    private static final Logger log = LoggerFactory.getLogger(GpuResourceManager.class);

    /**
     * A reservation of GPU memory by a service on a specific device.
     */
    public record GpuReservation(
            /** Unique reservation identifier (serviceType for singletons, or jobId-based for concurrent) */
            String reservationId,
            /** The service type holding this reservation (e.g., "embedding", "vlm") */
            String serviceType,
            /** The GPU device this reservation is on */
            GpuDevice device,
            /** Reserved memory in bytes */
            long reservedBytes,
            /** Priority — higher priority services can preempt lower ones */
            int priority,
            /** Timestamp when the reservation was created */
            long createdAtMillis
    ) {
        public long reservedMb() {
            return reservedBytes / (1024L * 1024L);
        }
    }

    /** Known GPU devices on this node (and potentially remote nodes) */
    private final List<GpuDevice> devices = new ArrayList<>();

    /** Active reservations keyed by reservationId (which may be serviceType or jobId) */
    private final Map<String, GpuReservation> activeReservations = new ConcurrentHashMap<>();

    /** Lock for reservation modifications that need atomicity */
    private final ReentrantReadWriteLock reservationLock = new ReentrantReadWriteLock();

    /** Default memory budgets per service type (in bytes). Configurable via setMemoryBudget(). */
    private final Map<String, Long> defaultBudgets = new ConcurrentHashMap<>();

    /** Default priorities per service type. Higher = more important. */
    private final Map<String, Integer> servicePriorities = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        discoverLocalGpus();

        // Set default priorities
        servicePriorities.put("embedding", 10);
        servicePriorities.put("ingest", 50);
        servicePriorities.put("vectorPopulation", 30);
        servicePriorities.put("modelInit", 20);
        servicePriorities.put("vlm", 100);

        // Set default memory budgets (conservative estimates)
        defaultBudgets.put("embedding", 5L * 1024 * 1024 * 1024);      // 5 GB
        defaultBudgets.put("vlm", 18L * 1024 * 1024 * 1024);           // 18 GB
        defaultBudgets.put("ingest", 2L * 1024 * 1024 * 1024);         // 2 GB
        defaultBudgets.put("vectorPopulation", 1L * 1024 * 1024 * 1024);// 1 GB
        defaultBudgets.put("modelInit", 2L * 1024 * 1024 * 1024);      // 2 GB

        log.info("GpuResourceManager initialized with {} GPU device(s)", devices.size());
        for (GpuDevice device : devices) {
            log.info("  {}", device);
        }
    }

    /**
     * Initialize without GPU discovery (for testing).
     * Sets up default budgets and priorities only.
     */
    public void initForTesting() {
        // Set default priorities
        servicePriorities.put("embedding", 10);
        servicePriorities.put("ingest", 50);
        servicePriorities.put("vectorPopulation", 30);
        servicePriorities.put("modelInit", 20);
        servicePriorities.put("vlm", 100);

        // Set default memory budgets
        defaultBudgets.put("embedding", 5L * 1024 * 1024 * 1024);
        defaultBudgets.put("vlm", 18L * 1024 * 1024 * 1024);
        defaultBudgets.put("ingest", 2L * 1024 * 1024 * 1024);
        defaultBudgets.put("vectorPopulation", 1L * 1024 * 1024 * 1024);
        defaultBudgets.put("modelInit", 2L * 1024 * 1024 * 1024);
    }

    // ==================== Device Management ====================

    /**
     * Get all known GPU devices.
     */
    public List<GpuDevice> getDevices() {
        return Collections.unmodifiableList(devices);
    }

    /**
     * Get a GPU device by its nvidia-smi index.
     */
    public Optional<GpuDevice> getDeviceByNvidiaSmiIndex(int index) {
        return devices.stream().filter(d -> d.nvidiaSmiIndex() == index).findFirst();
    }

    /**
     * Get a GPU device by its CUDA runtime index.
     */
    public Optional<GpuDevice> getDeviceByCudaRuntimeIndex(int index) {
        return devices.stream().filter(d -> d.cudaRuntimeIndex() == index).findFirst();
    }

    /**
     * Get the device with the most total memory (useful for VLM placement).
     */
    public Optional<GpuDevice> getLargestDevice() {
        return devices.stream().max(Comparator.comparingLong(GpuDevice::totalMemoryBytes));
    }

    /**
     * Get the device with the most available (unreserved) memory.
     */
    public Optional<GpuDevice> getDeviceWithMostAvailableMemory() {
        return devices.stream().max(Comparator.comparingLong(this::getAvailableMemory));
    }

    /**
     * Register a GPU device manually (for multi-node extension or testing).
     */
    public void registerDevice(GpuDevice device) {
        devices.add(device);
        log.info("Registered GPU device: {}", device);
    }

    // ==================== Reservation Management ====================

    /**
     * Create a reservation for a service on a specific device.
     * The reservation is keyed by {@code serviceType} — use this for singleton services
     * (e.g., embedding) where only one reservation per service type exists.
     *
     * @param serviceType the service requesting the reservation (e.g., "vlm", "embedding")
     * @param device the GPU device to reserve on
     * @param memoryBytes how many bytes to reserve
     * @return true if the reservation was created
     * @throws IllegalStateException if there is not enough available memory
     */
    public boolean reserve(String serviceType, GpuDevice device, long memoryBytes) {
        return reserveWithId(serviceType, serviceType, device, memoryBytes);
    }

    /**
     * Create a reservation with a specific reservation ID.
     * Use this for services that may have multiple concurrent reservations
     * (e.g., ingest jobs, vector population jobs).
     *
     * @param reservationId unique identifier for this reservation
     * @param serviceType the service type (for priority lookup)
     * @param device the GPU device to reserve on
     * @param memoryBytes how many bytes to reserve
     * @return true if the reservation was created
     * @throws IllegalStateException if there is not enough available memory
     */
    public boolean reserveWithId(String reservationId, String serviceType,
                                  GpuDevice device, long memoryBytes) {
        reservationLock.writeLock().lock();
        try {
            long available = getAvailableMemory(device);
            if (memoryBytes > available) {
                throw new IllegalStateException(String.format(
                        "Cannot reserve %dMB for '%s' (id='%s') on %s — only %dMB available " +
                                "(total %dMB, reserved %dMB)",
                        memoryBytes / (1024 * 1024), serviceType, reservationId, device.name(),
                        available / (1024 * 1024), device.totalMemoryBytes() / (1024 * 1024),
                        getReservedMemory(device) / (1024 * 1024)));
            }

            int priority = servicePriorities.getOrDefault(serviceType, 0);
            GpuReservation reservation = new GpuReservation(
                    reservationId, serviceType, device, memoryBytes, priority,
                    System.currentTimeMillis());
            activeReservations.put(reservationId, reservation);

            log.info("Reserved {}MB on {} for '{}' (id='{}', priority={}, available after: {}MB)",
                    memoryBytes / (1024 * 1024), device.name(), serviceType, reservationId,
                    priority, getAvailableMemory(device) / (1024 * 1024));
            return true;
        } finally {
            reservationLock.writeLock().unlock();
        }
    }

    /**
     * Release a reservation by its reservation ID.
     * For singleton services, this is the same as the service type.
     * For job-level reservations, this is the reservation ID used when calling reserveWithId().
     */
    public void release(String reservationId) {
        reservationLock.writeLock().lock();
        try {
            GpuReservation removed = activeReservations.remove(reservationId);
            if (removed != null) {
                log.info("Released reservation '{}' for '{}': {}MB on {} (held for {}ms)",
                        reservationId, removed.serviceType(), removed.reservedMb(),
                        removed.device().name(),
                        System.currentTimeMillis() - removed.createdAtMillis());
            }
        } finally {
            reservationLock.writeLock().unlock();
        }
    }

    /**
     * Release all reservations for a given service type.
     * Used during eviction when a service needs to release ALL its reservations.
     *
     * @param serviceType the service type to release
     * @return the number of reservations released
     */
    public int releaseAllForService(String serviceType) {
        reservationLock.writeLock().lock();
        try {
            List<String> toRemove = activeReservations.entrySet().stream()
                    .filter(e -> e.getValue().serviceType().equals(serviceType))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            for (String id : toRemove) {
                GpuReservation removed = activeReservations.remove(id);
                if (removed != null) {
                    log.info("Released reservation '{}' for '{}': {}MB on {} (held for {}ms)",
                            id, removed.serviceType(), removed.reservedMb(),
                            removed.device().name(),
                            System.currentTimeMillis() - removed.createdAtMillis());
                }
            }
            return toRemove.size();
        } finally {
            reservationLock.writeLock().unlock();
        }
    }

    /**
     * Get a reservation by its reservation ID.
     */
    public Optional<GpuReservation> getReservation(String reservationId) {
        return Optional.ofNullable(activeReservations.get(reservationId));
    }

    /**
     * Get all active reservations.
     */
    public Map<String, GpuReservation> getActiveReservations() {
        return Collections.unmodifiableMap(activeReservations);
    }

    /**
     * Check if a reservation exists by its ID.
     */
    public boolean hasReservation(String reservationId) {
        return activeReservations.containsKey(reservationId);
    }

    /**
     * Check if any reservation exists for a given service type.
     */
    public boolean hasReservationForService(String serviceType) {
        return activeReservations.values().stream()
                .anyMatch(r -> r.serviceType().equals(serviceType));
    }

    /**
     * Get all reservations for a given service type.
     */
    public List<GpuReservation> getReservationsForService(String serviceType) {
        return activeReservations.values().stream()
                .filter(r -> r.serviceType().equals(serviceType))
                .toList();
    }

    /**
     * Count how many active reservations exist for a service type.
     */
    public int getReservationCount(String serviceType) {
        return (int) activeReservations.values().stream()
                .filter(r -> r.serviceType().equals(serviceType))
                .count();
    }

    // ==================== Capacity Planning ====================

    /**
     * Get the total reserved memory on a device across all services.
     */
    public long getReservedMemory(GpuDevice device) {
        return activeReservations.values().stream()
                .filter(r -> r.device().nvidiaSmiIndex() == device.nvidiaSmiIndex()
                        && r.device().nodeId().equals(device.nodeId()))
                .mapToLong(GpuReservation::reservedBytes)
                .sum();
    }

    /**
     * Get the available (unreserved) memory on a device.
     */
    public long getAvailableMemory(GpuDevice device) {
        return device.totalMemoryBytes() - getReservedMemory(device);
    }

    /**
     * Check if a service can fit on a device without evicting anything.
     */
    public boolean canFit(String serviceType, GpuDevice device) {
        long budget = getMemoryBudget(serviceType);
        return budget <= getAvailableMemory(device);
    }

    /**
     * Find which service types on a device would need to be evicted to make room
     * for the requesting service. Returns distinct service types sorted by
     * priority (lowest first).
     *
     * <p>Only returns services with strictly lower priority than the requester.
     * If evicting all lower-priority services still doesn't free enough memory,
     * returns an empty list (eviction is not possible).</p>
     *
     * @param serviceType the service requesting space
     * @param device the target device
     * @return list of service types to evict (lowest priority first), or empty if not possible
     */
    public List<String> findEvictionCandidates(String serviceType, GpuDevice device) {
        long needed = getMemoryBudget(serviceType);
        long available = getAvailableMemory(device);

        if (needed <= available) {
            return Collections.emptyList(); // No eviction needed
        }

        int requesterPriority = servicePriorities.getOrDefault(serviceType, 0);
        long deficit = needed - available;

        // Get all reservations on this device with strictly lower priority,
        // grouped by service type to avoid double-counting
        List<GpuReservation> candidates = activeReservations.values().stream()
                .filter(r -> r.device().nvidiaSmiIndex() == device.nvidiaSmiIndex()
                        && r.device().nodeId().equals(device.nodeId())
                        && r.priority() < requesterPriority)
                .sorted(Comparator.comparingInt(GpuReservation::priority))
                .toList();

        // Greedily select distinct service types until we have enough memory
        List<String> toEvict = new ArrayList<>();
        Set<String> seenServiceTypes = new HashSet<>();
        long freed = 0;
        for (GpuReservation candidate : candidates) {
            if (seenServiceTypes.add(candidate.serviceType())) {
                toEvict.add(candidate.serviceType());
            }
            freed += candidate.reservedBytes();
            if (freed >= deficit) {
                return toEvict;
            }
        }

        // Even evicting all lower-priority services is not enough
        if (freed < deficit) {
            log.warn("Cannot fit '{}' on {} even after evicting all lower-priority services. " +
                            "Need {}MB, can free {}MB, available {}MB",
                    serviceType, device.name(), needed / (1024 * 1024),
                    freed / (1024 * 1024), available / (1024 * 1024));
            return Collections.emptyList();
        }

        return toEvict;
    }

    /**
     * Find the best device for a service, considering current reservations.
     * Prefers devices where the service can fit without eviction.
     * Falls back to devices requiring the fewest evictions.
     *
     * @param serviceType the service to place
     * @return the best device, or empty if no device can accommodate the service
     */
    public Optional<GpuDevice> findBestDevice(String serviceType) {
        long budget = getMemoryBudget(serviceType);

        // First: find devices where the service fits without eviction
        Optional<GpuDevice> fitWithout = devices.stream()
                .filter(d -> getAvailableMemory(d) >= budget)
                .max(Comparator.comparingLong(this::getAvailableMemory));

        if (fitWithout.isPresent()) {
            return fitWithout;
        }

        // Second: find devices where eviction can make room
        return devices.stream()
                .filter(d -> !findEvictionCandidates(serviceType, d).isEmpty())
                .min(Comparator.comparingInt(d -> findEvictionCandidates(serviceType, d).size()));
    }

    // ==================== Budget Configuration ====================

    /**
     * Get the memory budget for a service type.
     */
    public long getMemoryBudget(String serviceType) {
        return defaultBudgets.getOrDefault(serviceType, 2L * 1024 * 1024 * 1024);
    }

    /**
     * Set the memory budget for a service type.
     */
    public void setMemoryBudget(String serviceType, long bytes) {
        defaultBudgets.put(serviceType, bytes);
        log.info("Set memory budget for '{}': {}MB", serviceType, bytes / (1024 * 1024));
    }

    /**
     * Get the priority for a service type.
     */
    public int getServicePriority(String serviceType) {
        return servicePriorities.getOrDefault(serviceType, 0);
    }

    /**
     * Set the priority for a service type.
     */
    public void setServicePriority(String serviceType, int priority) {
        servicePriorities.put(serviceType, priority);
        log.info("Set priority for '{}': {}", serviceType, priority);
    }

    // ==================== Status ====================

    /**
     * Get a status summary for monitoring.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("deviceCount", devices.size());

        List<Map<String, Object>> deviceStatuses = new ArrayList<>();
        for (GpuDevice device : devices) {
            Map<String, Object> ds = new LinkedHashMap<>();
            ds.put("name", device.name());
            ds.put("nvidiaSmiIndex", device.nvidiaSmiIndex());
            ds.put("cudaRuntimeIndex", device.cudaRuntimeIndex());
            ds.put("totalMemoryMb", device.totalMemoryMb());
            ds.put("reservedMemoryMb", getReservedMemory(device) / (1024 * 1024));
            ds.put("availableMemoryMb", getAvailableMemory(device) / (1024 * 1024));
            ds.put("nodeId", device.nodeId());

            List<Map<String, Object>> reservations = new ArrayList<>();
            for (GpuReservation r : activeReservations.values()) {
                if (r.device().nvidiaSmiIndex() == device.nvidiaSmiIndex()
                        && r.device().nodeId().equals(device.nodeId())) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("reservationId", r.reservationId());
                    rm.put("serviceType", r.serviceType());
                    rm.put("reservedMb", r.reservedMb());
                    rm.put("priority", r.priority());
                    rm.put("heldForMs", System.currentTimeMillis() - r.createdAtMillis());
                    reservations.add(rm);
                }
            }
            ds.put("reservations", reservations);
            deviceStatuses.add(ds);
        }
        status.put("devices", deviceStatuses);
        status.put("totalReservations", activeReservations.size());

        return status;
    }

    // ==================== Shutdown ====================

    /**
     * Log final state and release all reservations on shutdown.
     * This is a safety net — normally the ModelLifecycleManager releases
     * all reservations in its SmartLifecycle.stop() before this runs.
     */
    @PreDestroy
    public void shutdown() {
        log.info("=== GpuResourceManager shutting down ===");

        if (!activeReservations.isEmpty()) {
            log.warn("Releasing {} dangling GPU reservation(s) during shutdown:", activeReservations.size());
            for (GpuReservation r : activeReservations.values()) {
                log.warn("  Dangling reservation: id='{}', service='{}', device='{}', {}MB, held for {}ms",
                        r.reservationId(), r.serviceType(), r.device().name(), r.reservedMb(),
                        System.currentTimeMillis() - r.createdAtMillis());
            }
            activeReservations.clear();
        } else {
            log.info("No active GPU reservations at shutdown — clean state");
        }

        log.info("=== GpuResourceManager shutdown complete ===");
    }

    // ==================== GPU Discovery ====================

    /**
     * Discover local GPUs by running nvidia-smi and auto-detect CUDA runtime index mapping.
     *
     * <p>CUDA's default device enumeration (when {@code CUDA_DEVICE_ORDER} is unset) orders
     * GPUs by compute capability (highest first), then by PCI bus ID (lowest first) within
     * the same capability tier. This differs from nvidia-smi's ordering (always by PCI bus ID).</p>
     *
     * <p>We query nvidia-smi for PCI bus IDs and compute capabilities, then apply CUDA's
     * ordering algorithm to compute the correct CUDA runtime index for each device.</p>
     */
    private void discoverLocalGpus() {
        try {
            // Check if CUDA_DEVICE_ORDER forces PCI_BUS_ID ordering
            String cudaDeviceOrder = System.getenv("CUDA_DEVICE_ORDER");
            boolean pciOrdering = "PCI_BUS_ID".equalsIgnoreCase(cudaDeviceOrder);

            ProcessBuilder pb = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=index,name,memory.total,pci.bus_id,compute_cap",
                    "--format=csv,noheader,nounits"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try {

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line.trim());
                }
            }

            boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                log.warn("nvidia-smi timed out after 30s, GPU status unavailable");
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("nvidia-smi exited with code {} — no GPU devices registered", exitCode);
                return;
            }

            // Parse CSV output: "0, NVIDIA GeForce RTX 4090, 24564, 00000000:0E:00.0, 8.9"
            record GpuInfo(int smiIndex, String name, long totalMemoryMb, String pciBusId,
                           double computeCapability) {}

            List<GpuInfo> gpuInfos = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",\\s*");
                if (parts.length < 5) {
                    // Fallback: try without compute_cap (older nvidia-smi versions)
                    if (parts.length >= 3) {
                        int smiIndex = Integer.parseInt(parts[0].trim());
                        String name = parts[1].trim();
                        long totalMemoryMb = Long.parseLong(parts[2].trim());
                        gpuInfos.add(new GpuInfo(smiIndex, name, totalMemoryMb, "", 0.0));
                    } else {
                        log.warn("Unexpected nvidia-smi output line: {}", line);
                    }
                    continue;
                }

                int smiIndex = Integer.parseInt(parts[0].trim());
                String name = parts[1].trim();
                long totalMemoryMb = Long.parseLong(parts[2].trim());
                String pciBusId = parts[3].trim();
                double computeCapability;
                try {
                    computeCapability = Double.parseDouble(parts[4].trim());
                } catch (NumberFormatException e) {
                    computeCapability = 0.0;
                }

                gpuInfos.add(new GpuInfo(smiIndex, name, totalMemoryMb, pciBusId, computeCapability));
            }

            if (gpuInfos.isEmpty()) {
                log.info("nvidia-smi returned no GPUs");
                return;
            }

            // Compute CUDA runtime index mapping
            List<GpuInfo> cudaOrder;
            if (pciOrdering) {
                cudaOrder = new ArrayList<>(gpuInfos);
                cudaOrder.sort(Comparator.comparing(GpuInfo::pciBusId));
                log.info("CUDA_DEVICE_ORDER=PCI_BUS_ID — CUDA indices match nvidia-smi order");
            } else {
                cudaOrder = new ArrayList<>(gpuInfos);
                cudaOrder.sort(Comparator.comparingDouble(GpuInfo::computeCapability).reversed()
                        .thenComparing(GpuInfo::pciBusId));
                log.info("CUDA default device ordering (by compute capability DESC, PCI bus ID ASC)");
            }

            // Build nvidia-smi index → CUDA runtime index mapping
            Map<Integer, Integer> smiToCuda = new HashMap<>();
            for (int cudaIdx = 0; cudaIdx < cudaOrder.size(); cudaIdx++) {
                smiToCuda.put(cudaOrder.get(cudaIdx).smiIndex(), cudaIdx);
            }

            // Create GpuDevice entries
            for (GpuInfo info : gpuInfos) {
                long totalMemoryBytes = info.totalMemoryMb() * 1024L * 1024L;
                int cudaRuntimeIndex = smiToCuda.getOrDefault(info.smiIndex(), info.smiIndex());

                GpuDevice device = GpuDevice.local(info.smiIndex(), cudaRuntimeIndex, info.name(), totalMemoryBytes);
                devices.add(device);

                if (cudaRuntimeIndex != info.smiIndex()) {
                    log.info("  GPU '{}': nvidia-smi={} -> CUDA runtime={} (CC={}, PCI={})",
                            info.name(), info.smiIndex(), cudaRuntimeIndex,
                            info.computeCapability(), info.pciBusId());
                } else {
                    log.info("  GPU '{}': nvidia-smi={} = CUDA runtime={} (CC={}, PCI={})",
                            info.name(), info.smiIndex(), cudaRuntimeIndex,
                            info.computeCapability(), info.pciBusId());
                }
            }

            log.info("Discovered {} GPU device(s) via nvidia-smi", devices.size());

            } finally {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            log.warn("Failed to discover GPUs via nvidia-smi: {}. No GPU devices registered.", e.getMessage());
        }
    }

    /**
     * Override the CUDA runtime index for a device (for systems where nvidia-smi
     * index differs from CUDA runtime index).
     *
     * @param nvidiaSmiIndex the nvidia-smi device index
     * @param cudaRuntimeIndex the correct CUDA runtime index
     */
    public void setCudaRuntimeIndex(int nvidiaSmiIndex, int cudaRuntimeIndex) {
        for (int i = 0; i < devices.size(); i++) {
            GpuDevice d = devices.get(i);
            if (d.nvidiaSmiIndex() == nvidiaSmiIndex) {
                devices.set(i, new GpuDevice(
                        d.nvidiaSmiIndex(), cudaRuntimeIndex, d.name(),
                        d.totalMemoryBytes(), d.nodeId()));
                log.info("Updated CUDA runtime index for GPU {}: smi={} -> cuda={}",
                        d.name(), nvidiaSmiIndex, cudaRuntimeIndex);
                return;
            }
        }
        log.warn("No GPU device with nvidia-smi index {} found", nvidiaSmiIndex);
    }
}
