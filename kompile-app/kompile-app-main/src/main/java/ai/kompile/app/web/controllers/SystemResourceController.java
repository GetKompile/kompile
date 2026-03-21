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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.MemoryWatchdogService;
import ai.kompile.app.services.SystemResourceBroadcaster;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.lang.management.*;
import java.util.*;

/**
 * REST controller for real-time system resource monitoring.
 * Provides endpoints to monitor CPU, GPU, RAM, disk, threads, and process statistics.
 * Also supports WebSocket broadcasting for real-time updates via SystemResourceBroadcaster.
 */
@RestController
@RequestMapping("/api/system")
public class SystemResourceController {

    private static final Logger logger = LoggerFactory.getLogger(SystemResourceController.class);

    private final MemoryWatchdogService memoryWatchdogService;
    private final SystemResourceBroadcaster systemResourceBroadcaster;

    // Store previous CPU measurements for calculating usage
    private long previousCpuTime = 0;
    private long previousTimestamp = 0;

    @Autowired
    public SystemResourceController(
            @Autowired(required = false) MemoryWatchdogService memoryWatchdogService,
            @Autowired(required = false) SystemResourceBroadcaster systemResourceBroadcaster) {
        this.memoryWatchdogService = memoryWatchdogService;
        this.systemResourceBroadcaster = systemResourceBroadcaster;
    }

    /**
     * Get comprehensive system resource information in a single call.
     * Optimized for real-time dashboard updates.
     */
    @GetMapping("/resources")
    public ResponseEntity<Map<String, Object>> getSystemResources() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            long timestamp = System.currentTimeMillis();
            response.put("timestamp", timestamp);
            response.put("timestampIso", new Date(timestamp).toString());

            // 1. CPU Information
            response.put("cpu", getCpuInfo());

            // 2. Memory Information (JVM + System)
            response.put("memory", getMemoryInfo());

            // 3. ND4J/GPU Information
            response.put("nd4j", getNd4jInfo());

            // 4. Thread Information
            response.put("threads", getThreadInfo());

            // 5. Process Information
            response.put("process", getProcessInfo());

            // 6. Disk Information
            response.put("disk", getDiskInfo());

            // 7. System Properties
            response.put("system", getSystemInfo());

            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting system resources", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get CPU-specific information including usage percentage.
     */
    @GetMapping("/cpu")
    public ResponseEntity<Map<String, Object>> getCpuEndpoint() {
        try {
            return ResponseEntity.ok(getCpuInfo());
        } catch (Exception e) {
            logger.error("Error getting CPU info", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get memory information (JVM heap, non-heap, and system memory).
     */
    @GetMapping("/memory")
    public ResponseEntity<Map<String, Object>> getMemoryEndpoint() {
        try {
            return ResponseEntity.ok(getMemoryInfo());
        } catch (Exception e) {
            logger.error("Error getting memory info", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get ND4J backend and device information.
     */
    @GetMapping("/nd4j")
    public ResponseEntity<Map<String, Object>> getNd4jEndpoint() {
        try {
            return ResponseEntity.ok(getNd4jInfo());
        } catch (Exception e) {
            logger.error("Error getting ND4J info", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get list of all available compute devices using ND4J backend-agnostic APIs.
     * Uses NativeOps for device enumeration which works across CPU/CUDA/Aurora backends.
     */
    @GetMapping("/devices")
    public ResponseEntity<Map<String, Object>> getDevices() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            List<Map<String, Object>> devices = new ArrayList<>();

            // Get NativeOps for backend-agnostic device information
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
            int currentDevice = Nd4j.getAffinityManager().getDeviceForCurrentThread();
            String backend = Nd4j.getBackend().getClass().getSimpleName();

            // Determine if this is a GPU backend
            boolean isGpuBackend = backend.toLowerCase().contains("cuda") ||
                                   backend.toLowerCase().contains("gpu") ||
                                   backend.toLowerCase().contains("aurora");

            for (int i = 0; i < numDevices; i++) {
                Map<String, Object> device = new LinkedHashMap<>();
                device.put("id", i);
                device.put("backend", backend);
                device.put("current", i == currentDevice);
                device.put("available", true);

                // Use NativeOps for backend-agnostic device information
                try {
                    // Get device name (works for both CPU and GPU backends)
                    String deviceName = nativeOps.getDeviceName(i);
                    device.put("name", deviceName != null && !deviceName.isEmpty() ?
                               deviceName : (isGpuBackend ? "GPU Device " + i : "CPU Device " + i));

                    // Get device memory info (backend-agnostic)
                    long totalMemory = nativeOps.getDeviceTotalMemory(i);
                    long freeMemory = nativeOps.getDeviceFreeMemory(i);

                    if (totalMemory > 0) {
                        device.put("totalMemoryMB", totalMemory / (1024 * 1024));
                        device.put("freeMemoryMB", freeMemory / (1024 * 1024));
                        device.put("usedMemoryMB", (totalMemory - freeMemory) / (1024 * 1024));
                        device.put("memoryUsagePercent",
                                   Math.round(((totalMemory - freeMemory) * 100.0 / totalMemory) * 100.0) / 100.0);
                    }

                    // Get device compute capability (major.minor version)
                    int major = nativeOps.getDeviceMajor(i);
                    int minor = nativeOps.getDeviceMinor(i);
                    if (major > 0 || minor > 0) {
                        device.put("computeCapability", major + "." + minor);
                    }

                    // Determine device type based on backend and available info
                    if (isGpuBackend) {
                        device.put("type", "GPU");
                    } else {
                        device.put("type", "CPU");
                        device.put("cores", Runtime.getRuntime().availableProcessors());
                    }

                } catch (Exception ex) {
                    logger.debug("Could not get detailed info for device {}: {}", i, ex.getMessage());
                    // Fallback for basic info
                    device.put("type", isGpuBackend ? "GPU" : "CPU");
                    device.put("name", isGpuBackend ? "GPU Device " + i : "CPU Device " + i);
                    if (!isGpuBackend) {
                        device.put("cores", Runtime.getRuntime().availableProcessors());
                    }
                }

                devices.add(device);
            }

            // Add system CPU info as a separate entry if using GPU backend
            if (isGpuBackend) {
                Map<String, Object> cpuDevice = new LinkedHashMap<>();
                cpuDevice.put("id", -1);
                cpuDevice.put("type", "CPU");
                cpuDevice.put("name", System.getProperty("os.arch") + " Host CPU");
                cpuDevice.put("cores", Runtime.getRuntime().availableProcessors());
                cpuDevice.put("backend", "Host");
                cpuDevice.put("available", true);
                cpuDevice.put("current", false);
                devices.add(0, cpuDevice);
            }

            response.put("devices", devices);
            response.put("deviceCount", devices.size());
            response.put("nd4jDeviceCount", numDevices);
            response.put("currentDevice", currentDevice);
            response.put("backend", backend);
            response.put("isGpuBackend", isGpuBackend);
            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting devices", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get thread information including thread states breakdown.
     */
    @GetMapping("/threads")
    public ResponseEntity<Map<String, Object>> getThreadsEndpoint() {
        try {
            return ResponseEntity.ok(getThreadInfo());
        } catch (Exception e) {
            logger.error("Error getting thread info", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get detailed thread dump.
     */
    @GetMapping(value = "/threads/dump", produces = "text/plain")
    public ResponseEntity<String> getThreadDump() {
        try {
            StringBuilder dump = new StringBuilder();
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

            for (ThreadInfo threadInfo : threadMXBean.dumpAllThreads(true, true)) {
                dump.append(threadInfo.toString());
                dump.append("\n");
            }

            return ResponseEntity.ok(dump.toString());

        } catch (Exception e) {
            logger.error("Error getting thread dump", e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    /**
     * Get process information.
     */
    @GetMapping("/process")
    public ResponseEntity<Map<String, Object>> getProcessEndpoint() {
        try {
            return ResponseEntity.ok(getProcessInfo());
        } catch (Exception e) {
            logger.error("Error getting process info", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Trigger garbage collection (use with caution).
     */
    @PostMapping("/gc")
    public ResponseEntity<Map<String, Object>> triggerGc() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            Runtime runtime = Runtime.getRuntime();
            long beforeFree = runtime.freeMemory();
            long beforeTotal = runtime.totalMemory();

            System.gc();

            // Small delay to let GC complete
            Thread.sleep(100);

            long afterFree = runtime.freeMemory();
            long afterTotal = runtime.totalMemory();

            response.put("beforeFreeMemoryMB", beforeFree / (1024 * 1024));
            response.put("afterFreeMemoryMB", afterFree / (1024 * 1024));
            response.put("freedMemoryMB", (afterFree - beforeFree) / (1024 * 1024));
            response.put("status", "success");
            response.put("message", "Garbage collection triggered");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error triggering GC", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> getCpuInfo() {
        Map<String, Object> cpu = new LinkedHashMap<>();

        try {
            OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();

            cpu.put("availableProcessors", osMxBean.getAvailableProcessors());
            cpu.put("arch", osMxBean.getArch());
            cpu.put("name", osMxBean.getName());
            cpu.put("version", osMxBean.getVersion());

            // System load average (Unix-like systems)
            double loadAverage = osMxBean.getSystemLoadAverage();
            cpu.put("systemLoadAverage", loadAverage >= 0 ? loadAverage : "N/A");

            // Try to get more detailed CPU info using com.sun.management
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsMxBean =
                        (com.sun.management.OperatingSystemMXBean) osMxBean;

                // CPU usage
                double cpuLoad = sunOsMxBean.getCpuLoad();
                double processCpuLoad = sunOsMxBean.getProcessCpuLoad();

                cpu.put("systemCpuLoad", cpuLoad >= 0 ? Math.round(cpuLoad * 100 * 100.0) / 100.0 : "N/A");
                cpu.put("processCpuLoad", processCpuLoad >= 0 ? Math.round(processCpuLoad * 100 * 100.0) / 100.0 : "N/A");
                cpu.put("processCpuTime", sunOsMxBean.getProcessCpuTime() / 1_000_000_000.0); // Convert to seconds

                // Calculate CPU usage since last call
                long currentCpuTime = sunOsMxBean.getProcessCpuTime();
                long currentTimestamp = System.nanoTime();

                if (previousCpuTime > 0 && previousTimestamp > 0) {
                    long cpuTimeDiff = currentCpuTime - previousCpuTime;
                    long timeDiff = currentTimestamp - previousTimestamp;

                    if (timeDiff > 0) {
                        double cpuUsage = (cpuTimeDiff * 100.0) / timeDiff / osMxBean.getAvailableProcessors();
                        cpu.put("recentCpuUsage", Math.round(cpuUsage * 100.0) / 100.0);
                    }
                }

                previousCpuTime = currentCpuTime;
                previousTimestamp = currentTimestamp;
            }

        } catch (Exception e) {
            logger.debug("Error getting detailed CPU info: {}", e.getMessage());
            cpu.put("error", e.getMessage());
        }

        return cpu;
    }

    private Map<String, Object> getMemoryInfo() {
        Map<String, Object> memory = new LinkedHashMap<>();

        try {
            // JVM Memory
            Runtime runtime = Runtime.getRuntime();
            MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();

            // Heap Memory
            Map<String, Object> heap = new LinkedHashMap<>();
            MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
            heap.put("usedMB", heapUsage.getUsed() / (1024 * 1024));
            heap.put("committedMB", heapUsage.getCommitted() / (1024 * 1024));
            heap.put("maxMB", heapUsage.getMax() / (1024 * 1024));
            heap.put("usagePercent", Math.round((heapUsage.getUsed() * 100.0 / heapUsage.getMax()) * 100.0) / 100.0);
            memory.put("heap", heap);

            // Non-Heap Memory
            Map<String, Object> nonHeap = new LinkedHashMap<>();
            MemoryUsage nonHeapUsage = memoryMxBean.getNonHeapMemoryUsage();
            nonHeap.put("usedMB", nonHeapUsage.getUsed() / (1024 * 1024));
            nonHeap.put("committedMB", nonHeapUsage.getCommitted() / (1024 * 1024));
            if (nonHeapUsage.getMax() > 0) {
                nonHeap.put("maxMB", nonHeapUsage.getMax() / (1024 * 1024));
            }
            memory.put("nonHeap", nonHeap);

            // Runtime Memory (simple view)
            Map<String, Object> jvmTotal = new LinkedHashMap<>();
            jvmTotal.put("totalMB", runtime.totalMemory() / (1024 * 1024));
            jvmTotal.put("freeMB", runtime.freeMemory() / (1024 * 1024));
            jvmTotal.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
            jvmTotal.put("maxMB", runtime.maxMemory() / (1024 * 1024));
            memory.put("jvm", jvmTotal);

            // System Memory (if available)
            OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsMxBean =
                        (com.sun.management.OperatingSystemMXBean) osMxBean;

                Map<String, Object> system = new LinkedHashMap<>();
                long totalPhysical = sunOsMxBean.getTotalMemorySize();
                long freePhysical = sunOsMxBean.getFreeMemorySize();

                system.put("totalMB", totalPhysical / (1024 * 1024));
                system.put("freeMB", freePhysical / (1024 * 1024));
                system.put("usedMB", (totalPhysical - freePhysical) / (1024 * 1024));
                system.put("usagePercent", Math.round(((totalPhysical - freePhysical) * 100.0 / totalPhysical) * 100.0) / 100.0);

                // Swap
                long totalSwap = sunOsMxBean.getTotalSwapSpaceSize();
                long freeSwap = sunOsMxBean.getFreeSwapSpaceSize();
                if (totalSwap > 0) {
                    system.put("swapTotalMB", totalSwap / (1024 * 1024));
                    system.put("swapFreeMB", freeSwap / (1024 * 1024));
                    system.put("swapUsedMB", (totalSwap - freeSwap) / (1024 * 1024));
                }

                memory.put("system", system);
            }

            // Memory Pool Details
            List<Map<String, Object>> pools = new ArrayList<>();
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                Map<String, Object> poolInfo = new LinkedHashMap<>();
                poolInfo.put("name", pool.getName());
                poolInfo.put("type", pool.getType().toString());
                MemoryUsage usage = pool.getUsage();
                if (usage != null) {
                    poolInfo.put("usedMB", usage.getUsed() / (1024 * 1024));
                    poolInfo.put("committedMB", usage.getCommitted() / (1024 * 1024));
                    if (usage.getMax() > 0) {
                        poolInfo.put("maxMB", usage.getMax() / (1024 * 1024));
                    }
                }
                pools.add(poolInfo);
            }
            memory.put("pools", pools);

        } catch (Exception e) {
            logger.debug("Error getting memory info: {}", e.getMessage());
            memory.put("error", e.getMessage());
        }

        return memory;
    }

    private Map<String, Object> getNd4jInfo() {
        Map<String, Object> nd4j = new LinkedHashMap<>();

        try {
            // Get NativeOps for backend-agnostic device information
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();

            // Backend info
            String backendName = Nd4j.getBackend().getClass().getSimpleName();
            nd4j.put("backend", backendName);
            nd4j.put("dataType", Nd4j.dataType().toString());

            // Determine backend type
            boolean isGpuBackend = backendName.toLowerCase().contains("cuda") ||
                                   backendName.toLowerCase().contains("gpu") ||
                                   backendName.toLowerCase().contains("aurora");
            nd4j.put("isGpuBackend", isGpuBackend);

            // Device info using backend-agnostic AffinityManager
            int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
            int currentDevice = Nd4j.getAffinityManager().getDeviceForCurrentThread();
            nd4j.put("numberOfDevices", numDevices);
            nd4j.put("currentDevice", currentDevice);

            // Device memory details using NativeOps (backend-agnostic)
            List<Map<String, Object>> deviceMemoryList = new ArrayList<>();
            for (int i = 0; i < numDevices; i++) {
                Map<String, Object> deviceMem = new LinkedHashMap<>();
                deviceMem.put("deviceId", i);
                deviceMem.put("current", i == currentDevice);

                try {
                    // Get device name via NativeOps
                    String deviceName = nativeOps.getDeviceName(i);
                    deviceMem.put("name", deviceName != null && !deviceName.isEmpty() ?
                                  deviceName : (isGpuBackend ? "GPU " + i : "CPU " + i));

                    // Get device memory via NativeOps
                    long totalMemory = nativeOps.getDeviceTotalMemory(i);
                    long freeMemory = nativeOps.getDeviceFreeMemory(i);

                    if (totalMemory > 0) {
                        deviceMem.put("totalMemoryMB", totalMemory / (1024 * 1024));
                        deviceMem.put("freeMemoryMB", freeMemory / (1024 * 1024));
                        deviceMem.put("usedMemoryMB", (totalMemory - freeMemory) / (1024 * 1024));
                        deviceMem.put("usagePercent",
                                      Math.round(((totalMemory - freeMemory) * 100.0 / totalMemory) * 100.0) / 100.0);
                    }

                    // Get device compute capability
                    int major = nativeOps.getDeviceMajor(i);
                    int minor = nativeOps.getDeviceMinor(i);
                    if (major > 0 || minor > 0) {
                        deviceMem.put("computeCapability", major + "." + minor);
                    }

                } catch (Exception ex) {
                    deviceMem.put("error", ex.getMessage());
                }

                deviceMemoryList.add(deviceMem);
            }
            nd4j.put("devices", deviceMemoryList);

            // Current device default free memory
            try {
                long defaultFreeMemory = nativeOps.getDeviceFreeMemoryDefault();
                nd4j.put("currentDeviceFreeMemoryMB", defaultFreeMemory / (1024 * 1024));
            } catch (Exception e) {
                // Not available
            }

            // Environment flags
            Map<String, Object> environment = new LinkedHashMap<>();
            environment.put("debug", Nd4j.getEnvironment().isDebug());
            environment.put("verbose", Nd4j.getEnvironment().isVerbose());
            environment.put("profiling", Nd4j.getEnvironment().isProfiling());
            environment.put("maxThreads", Nd4j.getEnvironment().maxThreads());
            environment.put("maxMasterThreads", Nd4j.getEnvironment().maxMasterThreads());
            nd4j.put("environment", environment);

            // Cache statistics
            Map<String, Object> cache = new LinkedHashMap<>();

            // Shape cache
            Map<String, Object> shapeCache = new LinkedHashMap<>();
            shapeCache.put("entries", nativeOps.getShapeCachedEntries());
            shapeCache.put("bytesMB", String.format("%.2f", nativeOps.getShapeCachedBytes() / (1024.0 * 1024.0)));
            shapeCache.put("peakEntries", nativeOps.getShapePeakCachedEntries());
            shapeCache.put("peakBytesMB", String.format("%.2f", nativeOps.getShapePeakCachedBytes() / (1024.0 * 1024.0)));
            cache.put("shape", shapeCache);

            // TAD cache
            Map<String, Object> tadCache = new LinkedHashMap<>();
            tadCache.put("entries", nativeOps.getTADCachedEntries());
            tadCache.put("bytesMB", String.format("%.2f", nativeOps.getTADCachedBytes() / (1024.0 * 1024.0)));
            tadCache.put("peakEntries", nativeOps.getTADPeakCachedEntries());
            tadCache.put("peakBytesMB", String.format("%.2f", nativeOps.getTADPeakCachedBytes() / (1024.0 * 1024.0)));
            cache.put("tad", tadCache);

            nd4j.put("cache", cache);

            // Workspace info
            try {
                MemoryWorkspace ws = Nd4j.getMemoryManager().getCurrentWorkspace();
                if (ws != null) {
                    Map<String, Object> workspace = new LinkedHashMap<>();
                    workspace.put("id", ws.getId());
                    workspace.put("currentSizeMB", ws.getCurrentSize() / (1024 * 1024));
                    workspace.put("generation", ws.getGenerationId());
                    nd4j.put("currentWorkspace", workspace);
                }
            } catch (Exception e) {
                // No workspace
            }

        } catch (Exception e) {
            logger.debug("Error getting ND4J info: {}", e.getMessage());
            nd4j.put("error", e.getMessage());
        }

        return nd4j;
    }

    private Map<String, Object> getThreadInfo() {
        Map<String, Object> threads = new LinkedHashMap<>();

        try {
            ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();

            threads.put("threadCount", threadMxBean.getThreadCount());
            threads.put("peakThreadCount", threadMxBean.getPeakThreadCount());
            threads.put("daemonThreadCount", threadMxBean.getDaemonThreadCount());
            threads.put("totalStartedThreadCount", threadMxBean.getTotalStartedThreadCount());

            // Thread state breakdown
            Map<Thread.State, Integer> stateCount = new EnumMap<>(Thread.State.class);
            for (Thread.State state : Thread.State.values()) {
                stateCount.put(state, 0);
            }

            ThreadInfo[] threadInfos = threadMxBean.getThreadInfo(threadMxBean.getAllThreadIds());
            for (ThreadInfo info : threadInfos) {
                if (info != null) {
                    stateCount.merge(info.getThreadState(), 1, Integer::sum);
                }
            }

            Map<String, Integer> states = new LinkedHashMap<>();
            for (Thread.State state : Thread.State.values()) {
                states.put(state.name(), stateCount.get(state));
            }
            threads.put("states", states);

            // Deadlock detection
            long[] deadlockedThreads = threadMxBean.findDeadlockedThreads();
            threads.put("deadlockedThreads", deadlockedThreads != null ? deadlockedThreads.length : 0);

            // Top CPU consuming threads (if CPU time monitoring is supported)
            if (threadMxBean.isThreadCpuTimeSupported() && threadMxBean.isThreadCpuTimeEnabled()) {
                List<Map<String, Object>> topThreads = new ArrayList<>();
                long[] threadIds = threadMxBean.getAllThreadIds();
                List<long[]> threadTimes = new ArrayList<>();

                for (long threadId : threadIds) {
                    long cpuTime = threadMxBean.getThreadCpuTime(threadId);
                    if (cpuTime > 0) {
                        threadTimes.add(new long[]{threadId, cpuTime});
                    }
                }

                // Sort by CPU time descending
                threadTimes.sort((a, b) -> Long.compare(b[1], a[1]));

                // Take top 10
                for (int i = 0; i < Math.min(10, threadTimes.size()); i++) {
                    long threadId = threadTimes.get(i)[0];
                    long cpuTime = threadTimes.get(i)[1];
                    ThreadInfo info = threadMxBean.getThreadInfo(threadId);
                    if (info != null) {
                        Map<String, Object> threadData = new LinkedHashMap<>();
                        threadData.put("id", threadId);
                        threadData.put("name", info.getThreadName());
                        threadData.put("state", info.getThreadState().name());
                        threadData.put("cpuTimeMs", cpuTime / 1_000_000);
                        topThreads.add(threadData);
                    }
                }
                threads.put("topCpuThreads", topThreads);
            }

        } catch (Exception e) {
            logger.debug("Error getting thread info: {}", e.getMessage());
            threads.put("error", e.getMessage());
        }

        return threads;
    }

    private Map<String, Object> getProcessInfo() {
        Map<String, Object> process = new LinkedHashMap<>();

        try {
            ProcessHandle currentProcess = ProcessHandle.current();
            ProcessHandle.Info info = currentProcess.info();

            process.put("pid", currentProcess.pid());

            info.command().ifPresent(cmd -> process.put("command", cmd));
            info.arguments().ifPresent(args -> process.put("arguments", args));
            info.user().ifPresent(user -> process.put("user", user));
            info.startInstant().ifPresent(start -> {
                process.put("startTime", start.toString());
                process.put("uptimeSeconds", java.time.Duration.between(start, java.time.Instant.now()).getSeconds());
            });

            info.totalCpuDuration().ifPresent(duration ->
                    process.put("totalCpuSeconds", duration.getSeconds()));

            // JVM uptime
            RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
            process.put("jvmUptimeMs", runtimeMxBean.getUptime());
            process.put("jvmStartTime", new Date(runtimeMxBean.getStartTime()).toString());
            process.put("vmName", runtimeMxBean.getVmName());
            process.put("vmVendor", runtimeMxBean.getVmVendor());
            process.put("vmVersion", runtimeMxBean.getVmVersion());

            // JVM arguments
            process.put("inputArguments", runtimeMxBean.getInputArguments());

            // Class loading
            ClassLoadingMXBean classLoadingMxBean = ManagementFactory.getClassLoadingMXBean();
            Map<String, Object> classLoading = new LinkedHashMap<>();
            classLoading.put("loadedClassCount", classLoadingMxBean.getLoadedClassCount());
            classLoading.put("totalLoadedClassCount", classLoadingMxBean.getTotalLoadedClassCount());
            classLoading.put("unloadedClassCount", classLoadingMxBean.getUnloadedClassCount());
            process.put("classLoading", classLoading);

            // Compilation info
            CompilationMXBean compilationMxBean = ManagementFactory.getCompilationMXBean();
            if (compilationMxBean != null) {
                Map<String, Object> compilation = new LinkedHashMap<>();
                compilation.put("name", compilationMxBean.getName());
                if (compilationMxBean.isCompilationTimeMonitoringSupported()) {
                    compilation.put("totalCompilationTimeMs", compilationMxBean.getTotalCompilationTime());
                }
                process.put("compilation", compilation);
            }

            // GC info
            List<Map<String, Object>> gcInfoList = new ArrayList<>();
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                Map<String, Object> gcInfo = new LinkedHashMap<>();
                gcInfo.put("name", gcBean.getName());
                gcInfo.put("collectionCount", gcBean.getCollectionCount());
                gcInfo.put("collectionTimeMs", gcBean.getCollectionTime());
                gcInfo.put("memoryPoolNames", gcBean.getMemoryPoolNames());
                gcInfoList.add(gcInfo);
            }
            process.put("garbageCollectors", gcInfoList);

        } catch (Exception e) {
            logger.debug("Error getting process info: {}", e.getMessage());
            process.put("error", e.getMessage());
        }

        return process;
    }

    private Map<String, Object> getDiskInfo() {
        Map<String, Object> disk = new LinkedHashMap<>();

        try {
            File[] roots = File.listRoots();
            List<Map<String, Object>> partitions = new ArrayList<>();

            for (File root : roots) {
                Map<String, Object> partition = new LinkedHashMap<>();
                partition.put("path", root.getAbsolutePath());
                partition.put("totalGB", String.format("%.2f", root.getTotalSpace() / (1024.0 * 1024.0 * 1024.0)));
                partition.put("freeGB", String.format("%.2f", root.getFreeSpace() / (1024.0 * 1024.0 * 1024.0)));
                partition.put("usableGB", String.format("%.2f", root.getUsableSpace() / (1024.0 * 1024.0 * 1024.0)));

                if (root.getTotalSpace() > 0) {
                    double usagePercent = ((root.getTotalSpace() - root.getFreeSpace()) * 100.0) / root.getTotalSpace();
                    partition.put("usagePercent", Math.round(usagePercent * 100.0) / 100.0);
                }

                partitions.add(partition);
            }

            disk.put("partitions", partitions);

            // Current working directory info
            File cwd = new File(".");
            disk.put("currentDirectory", cwd.getAbsolutePath());
            disk.put("currentDirectoryFreeGB", String.format("%.2f", cwd.getFreeSpace() / (1024.0 * 1024.0 * 1024.0)));

        } catch (Exception e) {
            logger.debug("Error getting disk info: {}", e.getMessage());
            disk.put("error", e.getMessage());
        }

        return disk;
    }

    private Map<String, Object> getSystemInfo() {
        Map<String, Object> system = new LinkedHashMap<>();

        try {
            system.put("osName", System.getProperty("os.name"));
            system.put("osArch", System.getProperty("os.arch"));
            system.put("osVersion", System.getProperty("os.version"));
            system.put("javaVersion", System.getProperty("java.version"));
            system.put("javaVendor", System.getProperty("java.vendor"));
            system.put("javaHome", System.getProperty("java.home"));
            system.put("userDir", System.getProperty("user.dir"));
            system.put("userHome", System.getProperty("user.home"));
            system.put("userName", System.getProperty("user.name"));
            system.put("tempDir", System.getProperty("java.io.tmpdir"));
            system.put("fileEncoding", System.getProperty("file.encoding"));

            // Environment variables (selected safe ones)
            Map<String, String> safeEnvVars = new LinkedHashMap<>();
            String[] safeVars = {"PATH", "JAVA_HOME", "MAVEN_HOME", "CUDA_HOME", "LD_LIBRARY_PATH"};
            for (String var : safeVars) {
                String value = System.getenv(var);
                if (value != null) {
                    safeEnvVars.put(var, value);
                }
            }
            system.put("environmentVariables", safeEnvVars);

        } catch (Exception e) {
            logger.debug("Error getting system info: {}", e.getMessage());
            system.put("error", e.getMessage());
        }

        return system;
    }

    // ==================== Memory Watchdog Endpoints ====================

    /**
     * Get memory watchdog status and configuration.
     * Returns current memory pressure state, running jobs, and thresholds.
     */
    @GetMapping("/memory-watchdog")
    public ResponseEntity<Map<String, Object>> getMemoryWatchdogStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (memoryWatchdogService == null) {
                response.put("status", "unavailable");
                response.put("message", "Memory watchdog service is not available");
                return ResponseEntity.ok(response);
            }

            MemoryWatchdogService.WatchdogStatus watchdogStatus = memoryWatchdogService.getStatus();

            response.put("enabled", watchdogStatus.enabled());
            response.put("memoryPressureDetected", watchdogStatus.memoryPressureDetected());
            response.put("currentMemoryUsagePercent", Math.round(watchdogStatus.currentMemoryUsagePercent() * 100.0) / 100.0);
            response.put("memoryThresholdPercent", watchdogStatus.memoryThresholdPercent());
            response.put("memoryCriticalPercent", watchdogStatus.memoryCriticalPercent());
            response.put("runningJobCount", watchdogStatus.runningJobCount());
            response.put("jobsMarkedForStopCount", watchdogStatus.jobsMarkedForStopCount());
            response.put("checkIntervalMs", watchdogStatus.checkIntervalMs());
            response.put("consecutiveHighMemoryChecks", watchdogStatus.consecutiveHighMemoryChecks());

            if (watchdogStatus.lastMemoryPressureTime() != null) {
                response.put("lastMemoryPressureTime", watchdogStatus.lastMemoryPressureTime().toString());
            }

            // Include running jobs details
            Map<String, MemoryWatchdogService.JobInfo> runningJobs = memoryWatchdogService.getRunningJobs();
            List<Map<String, Object>> jobsList = new ArrayList<>();
            for (MemoryWatchdogService.JobInfo job : runningJobs.values()) {
                Map<String, Object> jobInfo = new LinkedHashMap<>();
                jobInfo.put("taskId", job.taskId());
                jobInfo.put("fileName", job.fileName());
                jobInfo.put("startTime", job.startTime().toString());
                jobInfo.put("markedForStop", memoryWatchdogService.shouldJobStop(job.taskId()));
                jobsList.add(jobInfo);
            }
            response.put("runningJobs", jobsList);

            // Include jobs marked for stopping
            response.put("jobsMarkedForStop", memoryWatchdogService.getJobsMarkedForStop());

            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting memory watchdog status", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Enable or disable the memory watchdog.
     */
    @PostMapping("/memory-watchdog/enabled")
    public ResponseEntity<Map<String, Object>> setMemoryWatchdogEnabled(@RequestParam boolean enabled) {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (memoryWatchdogService == null) {
                response.put("status", "unavailable");
                response.put("message", "Memory watchdog service is not available");
                return ResponseEntity.ok(response);
            }

            memoryWatchdogService.setWatchdogEnabled(enabled);

            response.put("enabled", memoryWatchdogService.isWatchdogEnabled());
            response.put("status", "success");
            response.put("message", "Memory watchdog " + (enabled ? "enabled" : "disabled"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error setting memory watchdog enabled state", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Update the memory watchdog check interval.
     */
    @PostMapping("/memory-watchdog/check-interval")
    public ResponseEntity<Map<String, Object>> setMemoryWatchdogCheckInterval(@RequestParam long intervalMs) {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (memoryWatchdogService == null) {
                response.put("status", "unavailable");
                response.put("message", "Memory watchdog service is not available");
                return ResponseEntity.ok(response);
            }

            memoryWatchdogService.setCheckIntervalMs(intervalMs);

            response.put("checkIntervalMs", memoryWatchdogService.getStatus().checkIntervalMs());
            response.put("status", "success");
            response.put("message", "Check interval updated to " + intervalMs + "ms");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error setting memory watchdog check interval", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== WebSocket Broadcast Control Endpoints ====================

    /**
     * Get the status of the WebSocket system resource broadcaster.
     */
    @GetMapping("/broadcast")
    public ResponseEntity<Map<String, Object>> getBroadcastStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (systemResourceBroadcaster == null) {
                response.put("status", "unavailable");
                response.put("message", "System resource broadcaster is not available");
                return ResponseEntity.ok(response);
            }

            response.put("broadcasting", systemResourceBroadcaster.isBroadcasting());
            response.put("subscriberCount", systemResourceBroadcaster.getSubscriberCount());
            response.put("topic", SystemResourceBroadcaster.TOPIC_SYSTEM_RESOURCES);
            response.put("status", "success");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting broadcast status", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Start WebSocket broadcasting of system resources.
     * This is automatically managed when clients subscribe/unsubscribe,
     * but can be manually triggered if needed.
     */
    @PostMapping("/broadcast/start")
    public ResponseEntity<Map<String, Object>> startBroadcast() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (systemResourceBroadcaster == null) {
                response.put("status", "unavailable");
                response.put("message", "System resource broadcaster is not available");
                return ResponseEntity.ok(response);
            }

            systemResourceBroadcaster.startBroadcasting();

            response.put("broadcasting", systemResourceBroadcaster.isBroadcasting());
            response.put("status", "success");
            response.put("message", "Broadcasting started. Subscribe to " + SystemResourceBroadcaster.TOPIC_SYSTEM_RESOURCES);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error starting broadcast", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Stop WebSocket broadcasting of system resources.
     */
    @PostMapping("/broadcast/stop")
    public ResponseEntity<Map<String, Object>> stopBroadcast() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (systemResourceBroadcaster == null) {
                response.put("status", "unavailable");
                response.put("message", "System resource broadcaster is not available");
                return ResponseEntity.ok(response);
            }

            systemResourceBroadcaster.stopBroadcasting();

            response.put("broadcasting", systemResourceBroadcaster.isBroadcasting());
            response.put("status", "success");
            response.put("message", "Broadcasting stopped");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error stopping broadcast", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Notify that a client has subscribed to system resources.
     * This enables broadcasting if it was disabled.
     */
    @PostMapping("/broadcast/subscribe")
    public ResponseEntity<Map<String, Object>> subscribeToResources() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (systemResourceBroadcaster == null) {
                response.put("status", "unavailable");
                response.put("message", "System resource broadcaster is not available");
                return ResponseEntity.ok(response);
            }

            systemResourceBroadcaster.enableBroadcasting();

            response.put("broadcasting", systemResourceBroadcaster.isBroadcasting());
            response.put("subscriberCount", systemResourceBroadcaster.getSubscriberCount());
            response.put("topic", SystemResourceBroadcaster.TOPIC_SYSTEM_RESOURCES);
            response.put("status", "success");
            response.put("message", "Subscribed to system resources broadcast");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error subscribing to broadcast", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Notify that a client has unsubscribed from system resources.
     * This disables broadcasting if no more subscribers.
     */
    @PostMapping("/broadcast/unsubscribe")
    public ResponseEntity<Map<String, Object>> unsubscribeFromResources() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (systemResourceBroadcaster == null) {
                response.put("status", "unavailable");
                response.put("message", "System resource broadcaster is not available");
                return ResponseEntity.ok(response);
            }

            systemResourceBroadcaster.disableBroadcasting();

            response.put("broadcasting", systemResourceBroadcaster.isBroadcasting());
            response.put("subscriberCount", systemResourceBroadcaster.getSubscriberCount());
            response.put("status", "success");
            response.put("message", "Unsubscribed from system resources broadcast");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error unsubscribing from broadcast", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
