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

package ai.kompile.metrics.binder;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Method;

/**
 * Low-level memory metrics for ND4J / JavaCPP / GPU.
 *
 * JVM heap/non-heap:
 * <ul>
 *   <li>{@code kompile.memory.jvm.heap.used}</li>
 *   <li>{@code kompile.memory.jvm.heap.max}</li>
 *   <li>{@code kompile.memory.jvm.nonheap.used}</li>
 * </ul>
 *
 * ND4J off-heap (JavaCPP managed):
 * <ul>
 *   <li>{@code kompile.memory.nd4j.bytes_used} – allocated off-heap bytes</li>
 *   <li>{@code kompile.memory.nd4j.bytes_max} – max off-heap bytes</li>
 *   <li>{@code kompile.memory.nd4j.physical_bytes} – physical RSS from JavaCPP</li>
 * </ul>
 *
 * GPU (CUDA only – gracefully skipped on CPU backend):
 * <ul>
 *   <li>{@code kompile.memory.gpu.free_bytes} – free device memory</li>
 *   <li>{@code kompile.memory.gpu.total_bytes} – total device memory</li>
 *   <li>{@code kompile.memory.gpu.used_bytes} – used device memory</li>
 *   <li>{@code kompile.memory.gpu.utilization} – 0.0..1.0 fraction used</li>
 * </ul>
 */
public class Nd4jMemoryMetrics {

    private static final Logger log = LoggerFactory.getLogger(Nd4jMemoryMetrics.class);

    private final MeterRegistry registry;
    private final MemoryMXBean memoryMXBean;

    // Reflective handles for ND4J / CUDA – null if unavailable
    private Method pointerGetMaxBytesMethod;
    private Method pointerGetBytesMethod;
    private Method pointerPhysicalBytesMethod;
    private Object nativeOpsInstance;
    private Method getDeviceFreeMemoryMethod;
    private Method getDeviceTotalMemoryMethod;
    private boolean gpuAvailable;

    public Nd4jMemoryMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
    }

    @PostConstruct
    public void bindMetrics() {
        bindJvmMemory();
        bindNd4jOffHeap();
        bindGpuMemory();
    }

    private void bindJvmMemory() {
        Gauge.builder("kompile.memory.jvm.heap.used", memoryMXBean,
                m -> m.getHeapMemoryUsage().getUsed())
                .baseUnit("bytes").description("JVM heap memory used").register(registry);

        Gauge.builder("kompile.memory.jvm.heap.max", memoryMXBean,
                m -> {
                    long max = m.getHeapMemoryUsage().getMax();
                    return max == -1 ? m.getHeapMemoryUsage().getCommitted() : max;
                })
                .baseUnit("bytes").description("JVM heap memory max").register(registry);

        Gauge.builder("kompile.memory.jvm.heap.utilization", memoryMXBean,
                m -> {
                    MemoryUsage usage = m.getHeapMemoryUsage();
                    long max = usage.getMax() == -1 ? usage.getCommitted() : usage.getMax();
                    return max > 0 ? (double) usage.getUsed() / max : 0.0;
                })
                .description("JVM heap utilization (0.0-1.0)").register(registry);

        Gauge.builder("kompile.memory.jvm.nonheap.used", memoryMXBean,
                m -> m.getNonHeapMemoryUsage().getUsed())
                .baseUnit("bytes").description("JVM non-heap memory used").register(registry);
    }

    private void bindNd4jOffHeap() {
        try {
            Class<?> pointerClass = Class.forName("org.bytedeco.javacpp.Pointer");
            pointerGetMaxBytesMethod = pointerClass.getMethod("maxBytes");
            pointerGetBytesMethod = pointerClass.getMethod("totalBytes");
            pointerPhysicalBytesMethod = pointerClass.getMethod("physicalBytes");

            Gauge.builder("kompile.memory.nd4j.bytes_used", this, m -> invokeStaticLong(m.pointerGetBytesMethod))
                    .baseUnit("bytes").description("ND4J/JavaCPP off-heap bytes allocated").register(registry);

            Gauge.builder("kompile.memory.nd4j.bytes_max", this, m -> invokeStaticLong(m.pointerGetMaxBytesMethod))
                    .baseUnit("bytes").description("ND4J/JavaCPP off-heap max bytes").register(registry);

            Gauge.builder("kompile.memory.nd4j.physical_bytes", this, m -> invokeStaticLong(m.pointerPhysicalBytesMethod))
                    .baseUnit("bytes").description("JavaCPP physical (RSS) bytes").register(registry);

            log.info("ND4J off-heap memory metrics registered");
        } catch (ClassNotFoundException e) {
            log.debug("JavaCPP Pointer class not available – skipping ND4J off-heap metrics");
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            log.debug("JavaCPP native library not loaded – skipping ND4J off-heap metrics: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to register ND4J off-heap metrics: {}", e.getMessage());
        }
    }

    private void bindGpuMemory() {
        try {
            Class<?> nativeOpsClass = Class.forName("org.nd4j.nativeblas.NativeOpsHolder");
            Method getInstanceMethod = nativeOpsClass.getMethod("getInstance");
            Object holder = getInstanceMethod.invoke(null);
            Method getDeviceNativeOpsMethod = holder.getClass().getMethod("getDeviceNativeOps");
            nativeOpsInstance = getDeviceNativeOpsMethod.invoke(holder);

            getDeviceFreeMemoryMethod = nativeOpsInstance.getClass().getMethod("getDeviceFreeMemory", int.class);
            getDeviceTotalMemoryMethod = nativeOpsInstance.getClass().getMethod("getDeviceTotalMemory", int.class);

            // Test the call to verify GPU is actually available
            long totalMem = (long) getDeviceTotalMemoryMethod.invoke(nativeOpsInstance, 0);
            if (totalMem <= 0) {
                log.info("GPU reports 0 total memory – CPU-only backend, skipping GPU metrics");
                return;
            }

            gpuAvailable = true;
            Tags tags = Tags.of("device", "0");

            Gauge.builder("kompile.memory.gpu.free_bytes", this, m -> getGpuFreeMemory(0))
                    .tags(tags).baseUnit("bytes").description("GPU device free memory").register(registry);

            Gauge.builder("kompile.memory.gpu.total_bytes", this, m -> getGpuTotalMemory(0))
                    .tags(tags).baseUnit("bytes").description("GPU device total memory").register(registry);

            Gauge.builder("kompile.memory.gpu.used_bytes", this, m -> {
                long total = getGpuTotalMemory(0);
                long free = getGpuFreeMemory(0);
                return total - free;
            }).tags(tags).baseUnit("bytes").description("GPU device used memory").register(registry);

            Gauge.builder("kompile.memory.gpu.utilization", this, m -> {
                long total = getGpuTotalMemory(0);
                long free = getGpuFreeMemory(0);
                return total > 0 ? (double) (total - free) / total : 0.0;
            }).tags(tags).description("GPU memory utilization (0.0-1.0)").register(registry);

            log.info("GPU memory metrics registered for device 0 ({} MB total)",
                    totalMem / (1024 * 1024));
        } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError e) {
            log.debug("NativeOpsHolder not available – CPU backend, skipping GPU metrics");
        } catch (Exception e) {
            log.debug("GPU metrics unavailable: {}", e.getMessage());
        }
    }

    private long getGpuFreeMemory(int device) {
        if (!gpuAvailable) return 0;
        try {
            return (long) getDeviceFreeMemoryMethod.invoke(nativeOpsInstance, device);
        } catch (Exception e) {
            return 0;
        }
    }

    private long getGpuTotalMemory(int device) {
        if (!gpuAvailable) return 0;
        try {
            return (long) getDeviceTotalMemoryMethod.invoke(nativeOpsInstance, device);
        } catch (Exception e) {
            return 0;
        }
    }

    private static double invokeStaticLong(Method method) {
        if (method == null) return 0;
        try {
            return ((Number) method.invoke(null)).doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }
}
