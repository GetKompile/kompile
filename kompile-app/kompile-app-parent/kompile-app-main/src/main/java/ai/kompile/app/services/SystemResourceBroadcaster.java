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

import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service that broadcasts system resource metrics via WebSocket at regular intervals.
 * Provides real-time CPU, memory, thread, and ND4J resource information to connected clients.
 */
@Service
public class SystemResourceBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(SystemResourceBroadcaster.class);

    public static final String TOPIC_SYSTEM_RESOURCES = "/topic/system/resources";

    private final SimpMessagingTemplate messagingTemplate;

    @Value("${kompile.system.broadcast.enabled:true}")
    private boolean broadcastEnabled;

    @Value("${kompile.system.broadcast.interval-ms:2000}")
    private long broadcastIntervalMs;

    // Track connected subscribers
    private final AtomicInteger subscriberCount = new AtomicInteger(0);
    private final AtomicBoolean broadcasting = new AtomicBoolean(false);

    // Store previous CPU measurements for calculating usage
    private volatile long previousCpuTime = 0;
    private volatile long previousTimestamp = 0;

    @Autowired
    public SystemResourceBroadcaster(@Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    public void init() {
        logger.info("SystemResourceBroadcaster initialized - broadcast enabled: {}, interval: {}ms",
                broadcastEnabled, broadcastIntervalMs);
    }

    @PreDestroy
    public void shutdown() {
        broadcasting.set(false);
        logger.info("SystemResourceBroadcaster shutting down");
    }

    /**
     * Scheduled method that broadcasts system resources at fixed intervals.
     * Only broadcasts when there are active subscribers and broadcasting is enabled.
     */
    @Scheduled(fixedRateString = "${kompile.system.broadcast.interval-ms:2000}")
    public void broadcastSystemResources() {
        if (!broadcastEnabled || messagingTemplate == null) {
            return;
        }

        // Only broadcast if there are subscribers or if broadcasting is explicitly enabled
        if (!broadcasting.get() && subscriberCount.get() == 0) {
            return;
        }

        try {
            Map<String, Object> resources = collectSystemResources();
            messagingTemplate.convertAndSend(TOPIC_SYSTEM_RESOURCES, resources);
        } catch (Exception e) {
            logger.debug("Error broadcasting system resources: {}", e.getMessage());
        }
    }

    /**
     * Enable broadcasting (called when a client subscribes).
     */
    public void enableBroadcasting() {
        int count = subscriberCount.incrementAndGet();
        broadcasting.set(true);
        logger.debug("Broadcasting enabled, subscriber count: {}", count);
    }

    /**
     * Disable broadcasting (called when a client unsubscribes).
     */
    public void disableBroadcasting() {
        int count = subscriberCount.decrementAndGet();
        if (count <= 0) {
            subscriberCount.set(0);
            broadcasting.set(false);
            logger.debug("Broadcasting disabled, no more subscribers");
        } else {
            logger.debug("Subscriber disconnected, remaining: {}", count);
        }
    }

    /**
     * Force start broadcasting regardless of subscriber count.
     */
    public void startBroadcasting() {
        broadcasting.set(true);
        logger.debug("Broadcasting force-started");
    }

    /**
     * Stop broadcasting.
     */
    public void stopBroadcasting() {
        broadcasting.set(false);
        logger.debug("Broadcasting stopped");
    }

    /**
     * Check if broadcasting is currently active.
     */
    public boolean isBroadcasting() {
        return broadcasting.get();
    }

    /**
     * Get current subscriber count.
     */
    public int getSubscriberCount() {
        return subscriberCount.get();
    }

    /**
     * Collect all system resources into a single map for broadcasting.
     */
    public Map<String, Object> collectSystemResources() {
        Map<String, Object> response = new LinkedHashMap<>();

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

        // 7. System Properties (minimal for broadcasting)
        response.put("system", getSystemInfoMinimal());

        response.put("status", "success");

        return response;
    }

    private Map<String, Object> getCpuInfo() {
        Map<String, Object> cpu = new LinkedHashMap<>();

        try {
            OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();

            cpu.put("availableProcessors", osMxBean.getAvailableProcessors());
            cpu.put("arch", osMxBean.getArch());

            // System load average (Unix-like systems)
            double loadAverage = osMxBean.getSystemLoadAverage();
            cpu.put("systemLoadAverage", loadAverage >= 0 ? loadAverage : "N/A");

            // Try to get more detailed CPU info using com.sun.management
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOsMxBean) {
                // CPU usage
                double cpuLoad = sunOsMxBean.getCpuLoad();
                double processCpuLoad = sunOsMxBean.getProcessCpuLoad();

                cpu.put("systemCpuLoad", cpuLoad >= 0 ? Math.round(cpuLoad * 100 * 100.0) / 100.0 : "N/A");
                cpu.put("processCpuLoad", processCpuLoad >= 0 ? Math.round(processCpuLoad * 100 * 100.0) / 100.0 : "N/A");
                cpu.put("processCpuTime", sunOsMxBean.getProcessCpuTime() / 1_000_000_000.0);

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
        }

        return cpu;
    }

    private Map<String, Object> getMemoryInfo() {
        Map<String, Object> memory = new LinkedHashMap<>();

        try {
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
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOsMxBean) {
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

            // Memory Pool Details (minimal for broadcasting)
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
        }

        return memory;
    }

    private Map<String, Object> getNd4jInfo() {
        Map<String, Object> nd4j = new LinkedHashMap<>();

        try {
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

            // Device info
            int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
            int currentDevice = Nd4j.getAffinityManager().getDeviceForCurrentThread();
            nd4j.put("numberOfDevices", numDevices);
            nd4j.put("currentDevice", currentDevice);

            // Device memory details
            List<Map<String, Object>> deviceMemoryList = new ArrayList<>();
            for (int i = 0; i < numDevices; i++) {
                Map<String, Object> deviceMem = new LinkedHashMap<>();
                deviceMem.put("deviceId", i);
                deviceMem.put("current", i == currentDevice);

                try {
                    String deviceName = nativeOps.getDeviceName(i);
                    deviceMem.put("name", deviceName != null && !deviceName.isEmpty() ?
                            deviceName : (isGpuBackend ? "GPU " + i : "CPU " + i));

                    long totalMemory = nativeOps.getDeviceTotalMemory(i);
                    long freeMemory = nativeOps.getDeviceFreeMemory(i);

                    if (totalMemory > 0) {
                        deviceMem.put("totalMemoryMB", totalMemory / (1024 * 1024));
                        deviceMem.put("freeMemoryMB", freeMemory / (1024 * 1024));
                        deviceMem.put("usedMemoryMB", (totalMemory - freeMemory) / (1024 * 1024));
                        deviceMem.put("usagePercent",
                                Math.round(((totalMemory - freeMemory) * 100.0 / totalMemory) * 100.0) / 100.0);
                    }

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

            // Current device free memory
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
            nd4j.put("environment", environment);

            // Cache statistics
            Map<String, Object> cache = new LinkedHashMap<>();

            Map<String, Object> shapeCache = new LinkedHashMap<>();
            shapeCache.put("entries", nativeOps.getShapeCachedEntries());
            shapeCache.put("bytesMB", String.format("%.2f", nativeOps.getShapeCachedBytes() / (1024.0 * 1024.0)));
            cache.put("shape", shapeCache);

            Map<String, Object> tadCache = new LinkedHashMap<>();
            tadCache.put("entries", nativeOps.getTADCachedEntries());
            tadCache.put("bytesMB", String.format("%.2f", nativeOps.getTADCachedBytes() / (1024.0 * 1024.0)));
            cache.put("tad", tadCache);

            nd4j.put("cache", cache);

        } catch (Exception e) {
            logger.debug("Error getting ND4J info: {}", e.getMessage());
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

            // Top CPU consuming threads
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
        }

        return threads;
    }

    private Map<String, Object> getProcessInfo() {
        Map<String, Object> process = new LinkedHashMap<>();

        try {
            ProcessHandle currentProcess = ProcessHandle.current();
            ProcessHandle.Info info = currentProcess.info();

            process.put("pid", currentProcess.pid());

            info.user().ifPresent(user -> process.put("user", user));
            info.startInstant().ifPresent(start -> {
                process.put("startTime", start.toString());
                process.put("uptimeSeconds", java.time.Duration.between(start, java.time.Instant.now()).getSeconds());
            });

            // JVM uptime
            RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
            process.put("jvmUptimeMs", runtimeMxBean.getUptime());
            process.put("vmName", runtimeMxBean.getVmName());
            process.put("vmVersion", runtimeMxBean.getVmVersion());

            // Class loading
            ClassLoadingMXBean classLoadingMxBean = ManagementFactory.getClassLoadingMXBean();
            Map<String, Object> classLoading = new LinkedHashMap<>();
            classLoading.put("loadedClassCount", classLoadingMxBean.getLoadedClassCount());
            classLoading.put("totalLoadedClassCount", classLoadingMxBean.getTotalLoadedClassCount());
            process.put("classLoading", classLoading);

            // GC info
            List<Map<String, Object>> gcInfoList = new ArrayList<>();
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                Map<String, Object> gcInfo = new LinkedHashMap<>();
                gcInfo.put("name", gcBean.getName());
                gcInfo.put("collectionCount", gcBean.getCollectionCount());
                gcInfo.put("collectionTimeMs", gcBean.getCollectionTime());
                gcInfoList.add(gcInfo);
            }
            process.put("garbageCollectors", gcInfoList);

        } catch (Exception e) {
            logger.debug("Error getting process info: {}", e.getMessage());
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
        }

        return disk;
    }

    private Map<String, Object> getSystemInfoMinimal() {
        Map<String, Object> system = new LinkedHashMap<>();

        try {
            system.put("osName", System.getProperty("os.name"));
            system.put("osArch", System.getProperty("os.arch"));
            system.put("osVersion", System.getProperty("os.version"));
            system.put("javaVersion", System.getProperty("java.version"));
            system.put("javaVendor", System.getProperty("java.vendor"));

        } catch (Exception e) {
            logger.debug("Error getting system info: {}", e.getMessage());
        }

        return system;
    }
}
