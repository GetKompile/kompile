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

package ai.kompile.app.services.subprocess;

import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * Analyzes system memory to determine restart strategy for OOM recovery.
 *
 * This component queries system RAM and calculates whether heap can be increased
 * or if thread/batch size reduction is needed instead.
 *
 * Key responsibilities:
 * - Query system RAM (total, used, available)
 * - Calculate if heap increase is feasible based on available RAM
 * - Distinguish between OOM killer (exit 137) and Java OOM
 * - Provide clear reasoning when heap increase isn't possible
 * - Recommend thread adjustments (OMP, BLAS, etc.) when heap can't increase
 */
@Component
public class SystemMemoryAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(SystemMemoryAnalyzer.class);

    /**
     * Result of memory analysis for restart decision.
     */
    public record MemoryStatus(
            long totalSystemRamBytes,
            long usedSystemRamBytes,
            long availableSystemRamBytes,
            long currentHeapBytes,
            long recommendedHeapBytes,
            long currentOffHeapBytes,
            long recommendedOffHeapBytes,
            boolean canIncreaseHeap,
            boolean shouldReduceOffHeap,
            String reason,
            ThreadAdjustment threadAdjustment
    ) {
        /**
         * Get a formatted summary string for logging/display.
         */
        public String getSummary() {
            return String.format(
                    "System RAM: %s total, %s used, %s free. Heap: %s -> %s (%s). Off-heap: %s -> %s (%s). %s",
                    formatBytes(totalSystemRamBytes),
                    formatBytes(usedSystemRamBytes),
                    formatBytes(availableSystemRamBytes),
                    formatBytes(currentHeapBytes),
                    formatBytes(recommendedHeapBytes),
                    canIncreaseHeap ? "INCREASED" : "UNCHANGED",
                    formatBytes(currentOffHeapBytes),
                    formatBytes(recommendedOffHeapBytes),
                    shouldReduceOffHeap ? "REDUCED" : (recommendedOffHeapBytes > currentOffHeapBytes ? "INCREASED" : "UNCHANGED"),
                    threadAdjustment != null ? threadAdjustment.adjustmentReason() : "No thread adjustment"
            );
        }
    }

    /**
     * Thread adjustment recommendations when heap cannot be increased.
     */
    public record ThreadAdjustment(
            int currentOmpThreads,
            int recommendedOmpThreads,
            int currentOpenBlasThreads,
            int recommendedOpenBlasThreads,
            int currentMaxThreads,
            int recommendedMaxThreads,
            int currentBatchSize,
            int recommendedBatchSize,
            String adjustmentReason
    ) {
        /**
         * Check if any adjustments are recommended.
         */
        public boolean hasAdjustments() {
            return currentOmpThreads != recommendedOmpThreads
                    || currentOpenBlasThreads != recommendedOpenBlasThreads
                    || currentMaxThreads != recommendedMaxThreads
                    || currentBatchSize != recommendedBatchSize;
        }
    }

    /**
     * Analyze system memory and determine restart strategy.
     *
     * @param currentHeapBytes      Current subprocess heap size in bytes
     * @param currentOffHeapBytes   Current off-heap (JavaCPP) memory in bytes
     * @param heapIncreaseFactor    Desired increase factor (e.g., 1.25 for 25% more)
     * @param safetyMargin          Percentage of system RAM to keep free (e.g., 0.15)
     * @param wasOomKilled          True if exit code was 137 (OOM killer)
     * @param currentOmpThreads     Current OMP_NUM_THREADS setting
     * @param currentBlasThreads    Current OPENBLAS_NUM_THREADS setting
     * @param currentMaxThreads     Current max embedding threads setting
     * @param currentBatchSize      Current batch size setting
     * @return Memory status with recommendations
     */
    public MemoryStatus analyzeForRestart(
            long currentHeapBytes,
            long currentOffHeapBytes,
            double heapIncreaseFactor,
            double safetyMargin,
            boolean wasOomKilled,
            int currentOmpThreads,
            int currentBlasThreads,
            int currentMaxThreads,
            int currentBatchSize) {

        // Get system memory via OperatingSystemMXBean
        long totalRam = getTotalSystemMemory();
        long freeRam = getFreeSystemMemory();
        long usedRam = totalRam - freeRam;

        // Total current memory footprint (heap + off-heap)
        long currentTotalMemory = currentHeapBytes + currentOffHeapBytes;

        long desiredHeapBytes = (long) (currentHeapBytes * heapIncreaseFactor);
        long heapIncrease = desiredHeapBytes - currentHeapBytes;

        // Calculate safe available RAM (total - safety margin)
        long safeAvailable = (long) (totalRam * (1.0 - safetyMargin)) - usedRam + currentTotalMemory;

        boolean canIncrease;
        boolean shouldReduceOffHeap = false;
        long recommendedOffHeap = currentOffHeapBytes;
        String reason;

        if (wasOomKilled) {
            // OOM killer = system RAM was exhausted - need to REDUCE memory, not increase
            // First strategy: reduce off-heap significantly (it's often the culprit with ND4J)
            shouldReduceOffHeap = true;
            canIncrease = false;

            // Reduce off-heap by 50% on OOM kill
            recommendedOffHeap = Math.max(512L * 1024 * 1024, currentOffHeapBytes / 2); // Min 512MB

            reason = String.format(
                    "OOM killer terminated process - REDUCING memory footprint. " +
                            "System RAM: %s total, %s used, %s free. " +
                            "Current footprint: heap=%s + off-heap=%s = %s total. " +
                            "Reducing off-heap from %s to %s. Will also reduce threads and batch size.",
                    formatBytes(totalRam), formatBytes(usedRam), formatBytes(freeRam),
                    formatBytes(currentHeapBytes), formatBytes(currentOffHeapBytes), formatBytes(currentTotalMemory),
                    formatBytes(currentOffHeapBytes), formatBytes(recommendedOffHeap));

        } else {
            // Java OOM (not system OOM killer) - might have room to grow heap
            // But need to consider total footprint including off-heap
            long desiredTotalMemory = desiredHeapBytes + currentOffHeapBytes;

            if (safeAvailable >= desiredTotalMemory) {
                canIncrease = true;
                reason = String.format(
                        "CAN increase heap: Java OOM but system has RAM. " +
                                "System RAM: %s total, %s available (after %d%% safety margin). " +
                                "Increasing heap from %s to %s. Off-heap unchanged at %s.",
                        formatBytes(totalRam), formatBytes(safeAvailable), (int) (safetyMargin * 100),
                        formatBytes(currentHeapBytes), formatBytes(desiredHeapBytes),
                        formatBytes(currentOffHeapBytes));
            } else {
                // Not enough RAM - reduce off-heap instead of increasing heap
                canIncrease = false;
                shouldReduceOffHeap = true;
                recommendedOffHeap = Math.max(512L * 1024 * 1024, currentOffHeapBytes / 2);

                reason = String.format(
                        "CANNOT increase heap: Insufficient system RAM. " +
                                "System RAM: %s total, %s safe-available (after %d%% margin). " +
                                "Current footprint: heap=%s + off-heap=%s. " +
                                "Reducing off-heap from %s to %s. Will also reduce threads and batch size.",
                        formatBytes(totalRam), formatBytes(safeAvailable), (int) (safetyMargin * 100),
                        formatBytes(currentHeapBytes), formatBytes(currentOffHeapBytes),
                        formatBytes(currentOffHeapBytes), formatBytes(recommendedOffHeap));
            }
        }

        // Calculate thread adjustment (always reduce on OOM to lower memory footprint)
        ThreadAdjustment threadAdj = calculateThreadAdjustment(
                canIncrease, currentOmpThreads, currentBlasThreads, currentMaxThreads, currentBatchSize);

        long recommendedHeap = canIncrease ? desiredHeapBytes : currentHeapBytes;

        logger.info("Memory analysis complete: canIncreaseHeap={}, shouldReduceOffHeap={}, reason={}",
                canIncrease, shouldReduceOffHeap, reason);

        return new MemoryStatus(
                totalRam, usedRam, freeRam,
                currentHeapBytes, recommendedHeap,
                currentOffHeapBytes, recommendedOffHeap,
                canIncrease, shouldReduceOffHeap,
                reason, threadAdj
        );
    }

    /**
     * Quick check if heap increase is possible without full analysis.
     */
    public boolean canIncreaseHeap(long currentHeapBytes, double heapIncreaseFactor, double safetyMargin) {
        long totalRam = getTotalSystemMemory();
        long freeRam = getFreeSystemMemory();
        long desiredHeapBytes = (long) (currentHeapBytes * heapIncreaseFactor);
        long safeAvailable = (long) (totalRam * (1.0 - safetyMargin)) - (totalRam - freeRam) + currentHeapBytes;
        return safeAvailable >= desiredHeapBytes;
    }

    /**
     * Calculate thread adjustments for memory reduction.
     */
    private ThreadAdjustment calculateThreadAdjustment(
            boolean heapWillIncrease,
            int currentOmp,
            int currentBlas,
            int currentMax,
            int currentBatch) {

        int recommendedOmp, recommendedBlas, recommendedMax, recommendedBatch;
        String reason;

        if (heapWillIncrease) {
            // Heap is increasing, keep threads same
            recommendedOmp = currentOmp;
            recommendedBlas = currentBlas;
            recommendedMax = currentMax;
            recommendedBatch = currentBatch;
            reason = "Heap increased - maintaining current thread settings";
        } else {
            // Cannot increase heap - reduce threads to lower memory footprint
            // Halve all thread counts, minimum 1
            recommendedOmp = Math.max(1, currentOmp / 2);
            recommendedBlas = Math.max(1, currentBlas / 2);
            recommendedMax = Math.max(1, currentMax / 2);
            recommendedBatch = Math.max(1, currentBatch / 2);

            reason = String.format(
                    "Heap cannot increase - reducing threads: OMP %d->%d, BLAS %d->%d, Max %d->%d, Batch %d->%d",
                    currentOmp, recommendedOmp, currentBlas, recommendedBlas,
                    currentMax, recommendedMax, currentBatch, recommendedBatch);
        }

        return new ThreadAdjustment(
                currentOmp, recommendedOmp,
                currentBlas, recommendedBlas,
                currentMax, recommendedMax,
                currentBatch, recommendedBatch,
                reason
        );
    }

    /**
     * Get total physical memory of the system.
     */
    public long getTotalSystemMemory() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                return sunOsBean.getTotalMemorySize();
            }
        } catch (Exception e) {
            logger.warn("Failed to get total system memory via MXBean: {}", e.getMessage());
        }

        // Fallback: use Runtime (less accurate but always available)
        return Runtime.getRuntime().maxMemory() * 4; // Rough estimate
    }

    /**
     * Get free/available physical memory of the system.
     */
    public long getFreeSystemMemory() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                return sunOsBean.getFreeMemorySize();
            }
        } catch (Exception e) {
            logger.warn("Failed to get free system memory via MXBean: {}", e.getMessage());
        }

        // Fallback: use Runtime free memory
        return Runtime.getRuntime().freeMemory();
    }

    /**
     * Get current system memory usage as a percentage.
     */
    public double getSystemMemoryUsagePercent() {
        long total = getTotalSystemMemory();
        long free = getFreeSystemMemory();
        if (total <= 0) {
            return 0.0;
        }
        return ((total - free) * 100.0) / total;
    }

    /**
     * Get a snapshot of current system memory state.
     */
    public SystemMemorySnapshot getMemorySnapshot() {
        long total = getTotalSystemMemory();
        long free = getFreeSystemMemory();
        long used = total - free;
        double usagePercent = total > 0 ? (used * 100.0 / total) : 0.0;

        return new SystemMemorySnapshot(total, used, free, usagePercent);
    }

    /**
     * Snapshot of system memory at a point in time.
     */
    public record SystemMemorySnapshot(
            long totalBytes,
            long usedBytes,
            long freeBytes,
            double usagePercent
    ) {
        public String getSummary() {
            return String.format("%s total, %s used (%.1f%%), %s free",
                    formatBytes(totalBytes), formatBytes(usedBytes), usagePercent, formatBytes(freeBytes));
        }
    }

    /**
     * Format bytes as human-readable string (e.g., "4.5 GB").
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        if (bytes < 1024L * 1024 * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    /**
     * Parse a memory size string (e.g., "4g", "512m") to bytes.
     */
    public static Long parseMemoryToBytes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String s = value.trim().toLowerCase();
        s = s.replace("_", "").replace(",", "");

        // Remove -Xmx prefix if present
        if (s.startsWith("-xmx")) {
            s = s.substring(4);
        }

        long multiplier = 1;
        if (s.endsWith("tb") || s.endsWith("t")) {
            multiplier = 1024L * 1024 * 1024 * 1024;
            s = s.replaceAll("[tb]+$", "");
        } else if (s.endsWith("gb") || s.endsWith("g")) {
            multiplier = 1024L * 1024 * 1024;
            s = s.replaceAll("[gb]+$", "");
        } else if (s.endsWith("mb") || s.endsWith("m")) {
            multiplier = 1024L * 1024;
            s = s.replaceAll("[mb]+$", "");
        } else if (s.endsWith("kb") || s.endsWith("k")) {
            multiplier = 1024L;
            s = s.replaceAll("[kb]+$", "");
        } else if (s.endsWith("b")) {
            s = s.substring(0, s.length() - 1);
        }

        try {
            long amount = Long.parseLong(s.trim());
            return amount * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Convert bytes to a heap size string (e.g., "4g").
     */
    public static String bytesToHeapSize(long bytes) {
        if (bytes < 1024L * 1024 * 1024) {
            // Less than 1GB, use MB
            long mb = bytes / (1024 * 1024);
            return mb + "m";
        } else {
            // 1GB or more, use GB (rounded down)
            long gb = bytes / (1024L * 1024 * 1024);
            return gb + "g";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GPU/CUDA DEVICE MEMORY METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Check if the current ND4J backend is a GPU backend (CUDA, Aurora, etc.).
     */
    public boolean isGpuBackend() {
        try {
            String backendName = Nd4j.getBackend().getClass().getSimpleName().toLowerCase();
            return backendName.contains("cuda") || backendName.contains("gpu") || backendName.contains("aurora");
        } catch (Exception e) {
            logger.debug("Could not determine backend type: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the number of GPU devices available.
     */
    public int getGpuDeviceCount() {
        try {
            return Nd4j.getAffinityManager().getNumberOfDevices();
        } catch (Exception e) {
            logger.debug("Could not get device count: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Get the current/active GPU device ID.
     */
    public int getCurrentGpuDevice() {
        try {
            return Nd4j.getAffinityManager().getDeviceForCurrentThread();
        } catch (Exception e) {
            logger.debug("Could not get current device: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Get total memory for a specific GPU device in bytes.
     */
    public long getGpuTotalMemory(int deviceId) {
        try {
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            return nativeOps.getDeviceTotalMemory(deviceId);
        } catch (Exception e) {
            logger.debug("Could not get total memory for device {}: {}", deviceId, e.getMessage());
            return 0;
        }
    }

    /**
     * Get free memory for a specific GPU device in bytes.
     */
    public long getGpuFreeMemory(int deviceId) {
        try {
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            return nativeOps.getDeviceFreeMemory(deviceId);
        } catch (Exception e) {
            logger.debug("Could not get free memory for device {}: {}", deviceId, e.getMessage());
            return 0;
        }
    }

    /**
     * Get the name of a GPU device.
     */
    public String getGpuDeviceName(int deviceId) {
        try {
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            String name = nativeOps.getDeviceName(deviceId);
            return name != null && !name.isEmpty() ? name : "GPU Device " + deviceId;
        } catch (Exception e) {
            logger.debug("Could not get device name for device {}: {}", deviceId, e.getMessage());
            return "GPU Device " + deviceId;
        }
    }

    /**
     * Get GPU memory snapshot for the current device.
     */
    public GpuMemorySnapshot getGpuMemorySnapshot() {
        if (!isGpuBackend()) {
            return new GpuMemorySnapshot(false, -1, null, 0, 0, 0, 0);
        }

        int deviceId = getCurrentGpuDevice();
        long total = getGpuTotalMemory(deviceId);
        long free = getGpuFreeMemory(deviceId);
        long used = total - free;
        double usagePercent = total > 0 ? (used * 100.0 / total) : 0.0;
        String deviceName = getGpuDeviceName(deviceId);

        return new GpuMemorySnapshot(true, deviceId, deviceName, total, used, free, usagePercent);
    }

    /**
     * Snapshot of GPU memory at a point in time.
     */
    public record GpuMemorySnapshot(
            boolean isGpuBackend,
            int deviceId,
            String deviceName,
            long totalBytes,
            long usedBytes,
            long freeBytes,
            double usagePercent
    ) {
        public String getSummary() {
            if (!isGpuBackend) {
                return "CPU backend (no GPU)";
            }
            return String.format("GPU %d (%s): %s total, %s used (%.1f%%), %s free",
                    deviceId,
                    deviceName != null ? deviceName : "Unknown",
                    formatBytes(totalBytes),
                    formatBytes(usedBytes),
                    usagePercent,
                    formatBytes(freeBytes));
        }
    }

    /**
     * Get recommended batch size based on GPU memory constraints.
     * GPU batch sizes are typically more conservative than CPU due to VRAM constraints.
     *
     * @param freeVramBytes Free GPU memory in bytes
     * @return Recommended embedding batch size for GPU operations
     */
    public int getGpuRecommendedBatchSize(long freeVramBytes) {
        long freeVramMB = freeVramBytes / (1024 * 1024);

        if (freeVramMB < 1024) {
            // < 1GB free VRAM - very constrained
            return 1;
        } else if (freeVramMB < 2048) {
            // 1-2GB free VRAM
            return 2;
        } else if (freeVramMB < 4096) {
            // 2-4GB free VRAM
            return 4;
        } else if (freeVramMB < 8192) {
            // 4-8GB free VRAM
            return 8;
        } else if (freeVramMB < 16384) {
            // 8-16GB free VRAM
            return 16;
        } else {
            // > 16GB free VRAM
            return 32;
        }
    }

    /**
     * Get the effective recommended batch size considering both system RAM and GPU VRAM.
     * When using CUDA, GPU memory is often the limiting factor.
     *
     * @param currentBatchSize Current batch size setting
     * @return Recommended batch size, potentially reduced if GPU is constrained
     */
    public int getEffectiveRecommendedBatchSize(int currentBatchSize) {
        if (!isGpuBackend()) {
            // CPU mode - use current setting
            return currentBatchSize;
        }

        GpuMemorySnapshot gpuSnapshot = getGpuMemorySnapshot();
        if (!gpuSnapshot.isGpuBackend() || gpuSnapshot.freeBytes() <= 0) {
            return currentBatchSize;
        }

        int gpuRecommended = getGpuRecommendedBatchSize(gpuSnapshot.freeBytes());

        // Use the minimum of current setting and GPU recommendation
        int effective = Math.min(currentBatchSize, gpuRecommended);

        if (effective < currentBatchSize) {
            logger.info("Reducing batch size from {} to {} due to GPU VRAM constraints ({} free)",
                    currentBatchSize, effective, formatBytes(gpuSnapshot.freeBytes()));
        }

        return effective;
    }

    /**
     * Check if GPU memory is critically low (< 1GB free).
     */
    public boolean isGpuMemoryCritical() {
        if (!isGpuBackend()) {
            return false;
        }

        GpuMemorySnapshot snapshot = getGpuMemorySnapshot();
        // Less than 1GB free is critical
        return snapshot.freeBytes() > 0 && snapshot.freeBytes() < 1024L * 1024 * 1024;
    }

    /**
     * Check if GPU memory is low (< 2GB free).
     */
    public boolean isGpuMemoryLow() {
        if (!isGpuBackend()) {
            return false;
        }

        GpuMemorySnapshot snapshot = getGpuMemorySnapshot();
        // Less than 2GB free is low
        return snapshot.freeBytes() > 0 && snapshot.freeBytes() < 2L * 1024 * 1024 * 1024;
    }

    /**
     * Get combined memory status including both system RAM and GPU VRAM.
     */
    public CombinedMemoryStatus getCombinedMemoryStatus() {
        SystemMemorySnapshot systemSnapshot = getMemorySnapshot();
        GpuMemorySnapshot gpuSnapshot = getGpuMemorySnapshot();

        boolean isGpuConstrained = false;
        String constraintReason;

        if (!gpuSnapshot.isGpuBackend()) {
            constraintReason = "Using CPU backend - recommendations based on system RAM";
        } else if (gpuSnapshot.freeBytes() <= 0) {
            constraintReason = "GPU memory info unavailable - using system RAM recommendations";
        } else {
            // Compare GPU vs system memory constraints
            // GPU is typically the constraint when embedding batch sizes are involved
            double gpuUsagePercent = gpuSnapshot.usagePercent();
            double systemUsagePercent = systemSnapshot.usagePercent();

            // Consider GPU constrained if it has less effective headroom
            // or if free VRAM is less than 4GB (typical model footprint)
            long freeVramGB = gpuSnapshot.freeBytes() / (1024L * 1024 * 1024);
            if (freeVramGB < 4 || gpuUsagePercent > systemUsagePercent + 10) {
                isGpuConstrained = true;
                constraintReason = String.format(
                        "GPU VRAM (%.1fGB free) is the limiting factor",
                        gpuSnapshot.freeBytes() / (1024.0 * 1024 * 1024));
            } else {
                constraintReason = String.format(
                        "System RAM (%.1fGB free) is the limiting factor",
                        systemSnapshot.freeBytes() / (1024.0 * 1024 * 1024));
            }
        }

        return new CombinedMemoryStatus(systemSnapshot, gpuSnapshot, isGpuConstrained, constraintReason);
    }

    /**
     * Combined memory status including both system and GPU memory.
     */
    public record CombinedMemoryStatus(
            SystemMemorySnapshot systemMemory,
            GpuMemorySnapshot gpuMemory,
            boolean isGpuConstrained,
            String constraintReason
    ) {
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("System: ").append(systemMemory.getSummary());
            if (gpuMemory.isGpuBackend()) {
                sb.append(" | GPU: ").append(gpuMemory.getSummary());
                sb.append(" | Constraint: ").append(constraintReason);
            }
            return sb.toString();
        }
    }
}
