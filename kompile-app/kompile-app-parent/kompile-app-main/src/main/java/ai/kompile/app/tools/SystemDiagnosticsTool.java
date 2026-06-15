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

package ai.kompile.app.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.*;
import java.util.*;

/**
 * MCP Tool for system diagnostics and monitoring.
 * Exposes functionality to inspect system resources, memory, CPU, and JVM status.
 */
@Component
public class SystemDiagnosticsTool {

    private static final Logger logger = LoggerFactory.getLogger(SystemDiagnosticsTool.class);

    private final Runtime runtime = Runtime.getRuntime();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

    // Input records for tools
    public record GetSystemResourcesInput(Boolean includeDetails) {}
    public record GetMemoryStatusInput(Boolean includePoolDetails) {}
    public record GetCpuStatusInput() {}
    public record GetDiskStatusInput(String path) {}
    public record GetThreadStatusInput(Boolean includeStackTraces) {}
    public record TriggerGarbageCollectionInput() {}
    public record GetJvmInfoInput() {}

    /**
     * Gets comprehensive system resource information.
     */
    @Tool(name = "get_system_resources",
            description = "Gets comprehensive system resource information including CPU, memory, disk, and thread statistics. Set includeDetails=true for extended information.")
    public Map<String, Object> getSystemResources(GetSystemResourcesInput input) {
        logger.info("Getting system resources, includeDetails: {}", input.includeDetails());

        try {
            boolean includeDetails = input.includeDetails() != null && input.includeDetails();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("timestamp", new Date().toString());

            // CPU info
            Map<String, Object> cpu = new LinkedHashMap<>();
            cpu.put("availableProcessors", runtime.availableProcessors());
            cpu.put("systemLoadAverage", osMXBean.getSystemLoadAverage());
            cpu.put("arch", osMXBean.getArch());
            result.put("cpu", cpu);

            // Memory info
            Map<String, Object> memory = new LinkedHashMap<>();
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            memory.put("heapUsed", formatBytes(heapUsage.getUsed()));
            memory.put("heapMax", formatBytes(heapUsage.getMax()));
            memory.put("heapUsedBytes", heapUsage.getUsed());
            memory.put("heapMaxBytes", heapUsage.getMax());
            memory.put("heapUsagePercent", Math.round((double) heapUsage.getUsed() / heapUsage.getMax() * 100));

            MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
            memory.put("nonHeapUsed", formatBytes(nonHeapUsage.getUsed()));
            memory.put("nonHeapUsedBytes", nonHeapUsage.getUsed());

            memory.put("freeMemory", formatBytes(runtime.freeMemory()));
            memory.put("totalMemory", formatBytes(runtime.totalMemory()));
            memory.put("maxMemory", formatBytes(runtime.maxMemory()));
            result.put("memory", memory);

            // Thread info
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            Map<String, Object> threads = new LinkedHashMap<>();
            threads.put("threadCount", threadMXBean.getThreadCount());
            threads.put("peakThreadCount", threadMXBean.getPeakThreadCount());
            threads.put("daemonThreadCount", threadMXBean.getDaemonThreadCount());
            threads.put("totalStartedThreadCount", threadMXBean.getTotalStartedThreadCount());
            result.put("threads", threads);

            // Disk info (current working directory)
            File cwd = new File(".");
            Map<String, Object> disk = new LinkedHashMap<>();
            disk.put("path", cwd.getAbsolutePath());
            disk.put("totalSpace", formatBytes(cwd.getTotalSpace()));
            disk.put("freeSpace", formatBytes(cwd.getFreeSpace()));
            disk.put("usableSpace", formatBytes(cwd.getUsableSpace()));
            disk.put("usagePercent", Math.round((double) (cwd.getTotalSpace() - cwd.getFreeSpace()) / cwd.getTotalSpace() * 100));
            result.put("disk", disk);

            // JVM info
            if (includeDetails) {
                Map<String, Object> jvm = new LinkedHashMap<>();
                jvm.put("vmName", runtimeMXBean.getVmName());
                jvm.put("vmVersion", runtimeMXBean.getVmVersion());
                jvm.put("vmVendor", runtimeMXBean.getVmVendor());
                jvm.put("uptime", formatDuration(runtimeMXBean.getUptime()));
                jvm.put("uptimeMs", runtimeMXBean.getUptime());
                jvm.put("startTime", new Date(runtimeMXBean.getStartTime()).toString());
                jvm.put("javaVersion", System.getProperty("java.version"));
                result.put("jvm", jvm);

                // OS info
                Map<String, Object> os = new LinkedHashMap<>();
                os.put("name", osMXBean.getName());
                os.put("version", osMXBean.getVersion());
                os.put("arch", osMXBean.getArch());
                result.put("operatingSystem", os);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting system resources: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get system resources: " + e.getMessage());
        }
    }

    /**
     * Gets detailed memory status.
     */
    @Tool(name = "get_memory_status",
            description = "Gets detailed JVM memory status including heap, non-heap, and memory pool information. Set includePoolDetails=true for individual memory pool statistics.")
    public Map<String, Object> getMemoryStatus(GetMemoryStatusInput input) {
        logger.info("Getting memory status, includePoolDetails: {}", input.includePoolDetails());

        try {
            boolean includePoolDetails = input.includePoolDetails() != null && input.includePoolDetails();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("timestamp", new Date().toString());

            // Heap memory
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            Map<String, Object> heap = new LinkedHashMap<>();
            heap.put("init", formatBytes(heapUsage.getInit()));
            heap.put("used", formatBytes(heapUsage.getUsed()));
            heap.put("committed", formatBytes(heapUsage.getCommitted()));
            heap.put("max", formatBytes(heapUsage.getMax()));
            heap.put("usedBytes", heapUsage.getUsed());
            heap.put("maxBytes", heapUsage.getMax());
            heap.put("usagePercent", heapUsage.getMax() > 0 ?
                    Math.round((double) heapUsage.getUsed() / heapUsage.getMax() * 100) : 0);
            result.put("heap", heap);

            // Non-heap memory
            MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
            Map<String, Object> nonHeap = new LinkedHashMap<>();
            nonHeap.put("init", formatBytes(nonHeapUsage.getInit()));
            nonHeap.put("used", formatBytes(nonHeapUsage.getUsed()));
            nonHeap.put("committed", formatBytes(nonHeapUsage.getCommitted()));
            nonHeap.put("usedBytes", nonHeapUsage.getUsed());
            result.put("nonHeap", nonHeap);

            // Memory pools
            if (includePoolDetails) {
                List<Map<String, Object>> pools = new ArrayList<>();
                for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    Map<String, Object> poolInfo = new LinkedHashMap<>();
                    poolInfo.put("name", pool.getName());
                    poolInfo.put("type", pool.getType().toString());

                    MemoryUsage usage = pool.getUsage();
                    if (usage != null) {
                        poolInfo.put("used", formatBytes(usage.getUsed()));
                        poolInfo.put("max", usage.getMax() > 0 ? formatBytes(usage.getMax()) : "unlimited");
                        poolInfo.put("usedBytes", usage.getUsed());
                    }
                    pools.add(poolInfo);
                }
                result.put("memoryPools", pools);
            }

            // GC info
            List<Map<String, Object>> gcInfo = new ArrayList<>();
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                Map<String, Object> gcBean = new LinkedHashMap<>();
                gcBean.put("name", gc.getName());
                gcBean.put("collectionCount", gc.getCollectionCount());
                gcBean.put("collectionTime", gc.getCollectionTime() + "ms");
                gcInfo.add(gcBean);
            }
            result.put("garbageCollectors", gcInfo);

            // Memory warnings
            List<String> warnings = new ArrayList<>();
            if (heapUsage.getMax() > 0 && heapUsage.getUsed() > heapUsage.getMax() * 0.9) {
                warnings.add("Heap memory usage is above 90%");
            }
            if (heapUsage.getMax() > 0 && heapUsage.getUsed() > heapUsage.getMax() * 0.8) {
                warnings.add("Heap memory usage is above 80%");
            }
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting memory status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get memory status: " + e.getMessage());
        }
    }

    /**
     * Gets CPU status and load information.
     */
    @Tool(name = "get_cpu_status",
            description = "Gets CPU status including processor count, system load average, and process CPU information.")
    public Map<String, Object> getCpuStatus(GetCpuStatusInput input) {
        logger.info("Getting CPU status");

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("timestamp", new Date().toString());

            result.put("availableProcessors", runtime.availableProcessors());
            result.put("systemLoadAverage", osMXBean.getSystemLoadAverage());
            result.put("arch", osMXBean.getArch());

            // Calculate load per processor
            double loadAvg = osMXBean.getSystemLoadAverage();
            if (loadAvg >= 0) {
                result.put("loadPerProcessor", Math.round(loadAvg / runtime.availableProcessors() * 100) / 100.0);
            }

            // Compilation info
            CompilationMXBean compilationMXBean = ManagementFactory.getCompilationMXBean();
            if (compilationMXBean != null) {
                Map<String, Object> compilation = new LinkedHashMap<>();
                compilation.put("name", compilationMXBean.getName());
                if (compilationMXBean.isCompilationTimeMonitoringSupported()) {
                    compilation.put("totalCompilationTime", compilationMXBean.getTotalCompilationTime() + "ms");
                }
                result.put("jitCompiler", compilation);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting CPU status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get CPU status: " + e.getMessage());
        }
    }

    /**
     * Gets disk space information for a given path.
     */
    @Tool(name = "get_disk_status",
            description = "Gets disk space information for the specified path or current working directory if not specified.")
    public Map<String, Object> getDiskStatus(GetDiskStatusInput input) {
        logger.info("Getting disk status for path: {}", input.path());

        try {
            String targetPath = input.path() != null ? input.path() : ".";
            File file = new File(targetPath);

            if (!file.exists()) {
                return Map.of("status", "error", "error", "Path does not exist: " + targetPath);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("path", file.getAbsolutePath());
            result.put("totalSpace", formatBytes(file.getTotalSpace()));
            result.put("freeSpace", formatBytes(file.getFreeSpace()));
            result.put("usableSpace", formatBytes(file.getUsableSpace()));
            result.put("totalSpaceBytes", file.getTotalSpace());
            result.put("freeSpaceBytes", file.getFreeSpace());
            result.put("usableSpaceBytes", file.getUsableSpace());

            long used = file.getTotalSpace() - file.getFreeSpace();
            result.put("usedSpace", formatBytes(used));
            result.put("usedSpaceBytes", used);

            if (file.getTotalSpace() > 0) {
                result.put("usagePercent", Math.round((double) used / file.getTotalSpace() * 100));
            }

            // Check for low disk space warning
            if (file.getTotalSpace() > 0 && (double) file.getFreeSpace() / file.getTotalSpace() < 0.1) {
                result.put("warning", "Less than 10% disk space remaining");
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting disk status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get disk status: " + e.getMessage());
        }
    }

    /**
     * Gets thread status and information.
     */
    @Tool(name = "get_thread_status",
            description = "Gets thread status including thread count, thread states distribution, and optional stack traces for debugging.")
    public Map<String, Object> getThreadStatus(GetThreadStatusInput input) {
        logger.info("Getting thread status, includeStackTraces: {}", input.includeStackTraces());

        try {
            boolean includeStackTraces = input.includeStackTraces() != null && input.includeStackTraces();
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("timestamp", new Date().toString());

            result.put("threadCount", threadMXBean.getThreadCount());
            result.put("peakThreadCount", threadMXBean.getPeakThreadCount());
            result.put("daemonThreadCount", threadMXBean.getDaemonThreadCount());
            result.put("totalStartedThreadCount", threadMXBean.getTotalStartedThreadCount());

            // Thread state distribution
            Map<Thread.State, Integer> stateCount = new EnumMap<>(Thread.State.class);
            for (Thread.State state : Thread.State.values()) {
                stateCount.put(state, 0);
            }

            ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds());
            for (ThreadInfo info : threadInfos) {
                if (info != null) {
                    stateCount.merge(info.getThreadState(), 1, Integer::sum);
                }
            }

            Map<String, Integer> states = new LinkedHashMap<>();
            for (Map.Entry<Thread.State, Integer> entry : stateCount.entrySet()) {
                states.put(entry.getKey().name(), entry.getValue());
            }
            result.put("threadStateDistribution", states);

            // Check for deadlocks
            long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
            if (deadlockedThreads != null && deadlockedThreads.length > 0) {
                result.put("deadlockDetected", true);
                result.put("deadlockedThreadCount", deadlockedThreads.length);
            } else {
                result.put("deadlockDetected", false);
            }

            // Include stack traces if requested (limited to top 10 threads)
            if (includeStackTraces) {
                List<Map<String, Object>> threadDetails = new ArrayList<>();
                int count = 0;
                for (ThreadInfo info : threadInfos) {
                    if (info != null && count < 10) {
                        Map<String, Object> threadDetail = new LinkedHashMap<>();
                        threadDetail.put("name", info.getThreadName());
                        threadDetail.put("id", info.getThreadId());
                        threadDetail.put("state", info.getThreadState().name());

                        StackTraceElement[] stackTrace = info.getStackTrace();
                        if (stackTrace.length > 0) {
                            List<String> trace = new ArrayList<>();
                            for (int i = 0; i < Math.min(5, stackTrace.length); i++) {
                                trace.add(stackTrace[i].toString());
                            }
                            threadDetail.put("stackTrace", trace);
                        }

                        threadDetails.add(threadDetail);
                        count++;
                    }
                }
                result.put("threadDetails", threadDetails);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error getting thread status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get thread status: " + e.getMessage());
        }
    }

    /**
     * Triggers garbage collection.
     */
    @Tool(name = "trigger_garbage_collection",
            description = "Suggests JVM garbage collection and reports memory before and after. Note: GC timing is not guaranteed as it's only a suggestion to the JVM.")
    public Map<String, Object> triggerGarbageCollection(TriggerGarbageCollectionInput input) {
        logger.info("Triggering garbage collection");

        try {
            // Memory before GC
            long usedBefore = memoryMXBean.getHeapMemoryUsage().getUsed();

            // Suggest GC
            System.gc();

            // Small delay to allow GC to run
            Thread.sleep(100);

            // Memory after GC
            long usedAfter = memoryMXBean.getHeapMemoryUsage().getUsed();
            long freed = usedBefore - usedAfter;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Garbage collection suggested to JVM");
            result.put("heapUsedBefore", formatBytes(usedBefore));
            result.put("heapUsedAfter", formatBytes(usedAfter));
            result.put("approximateFreed", formatBytes(Math.max(0, freed)));
            result.put("heapUsedBeforeBytes", usedBefore);
            result.put("heapUsedAfterBytes", usedAfter);
            result.put("freedBytes", Math.max(0, freed));

            return result;

        } catch (Exception e) {
            logger.error("Error triggering GC: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to trigger GC: " + e.getMessage());
        }
    }

    /**
     * Gets JVM information.
     */
    @Tool(name = "get_jvm_info",
            description = "Gets detailed JVM information including version, vendor, uptime, and runtime configuration.")
    public Map<String, Object> getJvmInfo(GetJvmInfoInput input) {
        logger.info("Getting JVM info");

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");

            // VM info
            result.put("vmName", runtimeMXBean.getVmName());
            result.put("vmVersion", runtimeMXBean.getVmVersion());
            result.put("vmVendor", runtimeMXBean.getVmVendor());
            result.put("specName", runtimeMXBean.getSpecName());
            result.put("specVersion", runtimeMXBean.getSpecVersion());

            // Uptime
            result.put("uptime", formatDuration(runtimeMXBean.getUptime()));
            result.put("uptimeMs", runtimeMXBean.getUptime());
            result.put("startTime", new Date(runtimeMXBean.getStartTime()).toString());

            // Java info
            result.put("javaVersion", System.getProperty("java.version"));
            result.put("javaVendor", System.getProperty("java.vendor"));
            result.put("javaHome", System.getProperty("java.home"));

            // Input arguments (filtered for security)
            List<String> safeArgs = new ArrayList<>();
            for (String arg : runtimeMXBean.getInputArguments()) {
                // Skip arguments that might contain sensitive info
                if (!arg.contains("password") && !arg.contains("secret") && !arg.contains("key=")) {
                    safeArgs.add(arg);
                }
            }
            result.put("jvmArguments", safeArgs);

            return result;

        } catch (Exception e) {
            logger.error("Error getting JVM info: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get JVM info: " + e.getMessage());
        }
    }

    // Helper methods
    private String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
