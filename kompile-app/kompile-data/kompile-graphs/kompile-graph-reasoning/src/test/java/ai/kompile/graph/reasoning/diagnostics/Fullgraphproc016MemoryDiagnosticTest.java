/*
 * Temporary, opt-in memory diagnostic for Fullgraphproc016.
 *
 * This class is intentionally test-only and is disabled unless
 * -Dkompile.fullgraphproc016.diag=true is supplied. The optional
 * -Dkompile.fullgraphproc016.trimProbe=true adds one Linux-only malloc_trim(0)
 * measurement after the trainer-close sample. It invokes the existing
 * MEBN/FOL, RotatE link-prediction, and Node2Vec tests in one ordered JVM so
 * JavaCPP/ND4J resource ownership can be measured at every method boundary.
 */
package ai.kompile.graph.reasoning.diagnostics;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.javacpp.Pointer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nd4j.autodiff.samediff.internal.memory.ArrayCacheMemoryMgr;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.framework.constant.ConstantCacheState;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bounded, ordered reproduction of the three native-memory-heavy test classes.
 *
 * <p>No GC request, production hook, or assertion about returning to zero is
 * performed here. Reusable allocator/pool retention is deliberately left intact
 * except for the explicitly opt-in one-shot trim probe.</p>
 */
@EnabledIfSystemProperty(named = "kompile.fullgraphproc016.diag", matches = "true")
class Fullgraphproc016MemoryDiagnosticTest {

    private static final String MEBN = "ai.kompile.graph.reasoning.e2e.MebnFolTypeE2ETest";
    private static final String LINK = "ai.kompile.graph.reasoning.embedding.learn.LinkPredictorTest";
    private static final String NODE2VEC = "ai.kompile.graph.reasoning.embedding.learn.Node2VecLearnerTest";

    private static final List<String> MEBN_METHODS = List.of(
            "typeHierarchyMembershipFromUnifiedGraph",
            "typeRegistryIsATransitiveAndAsymmetric",
            "typeHierarchySubtypeEntityInclusion",
            "relationalMTheoryBuilderFragmentStructure",
            "ssbNGroundingWithRelationalMTheoryYieldsBnNodes",
            "inferredFactMaterializationBinaryAndUnary",
            "recursiveQueryEngineTransitiveClosureThreeHop",
            "derivationTreeConstructionAndAccessors",
            "mebnWeightLearnerReducesSSE",
            "modelPersistenceRoundTripPreservesReasoningFidelity");

    private static final List<String> LINK_METHODS = List.of(
            "trueTailRanksAboveWrongTypeEntityInPredictTails",
            "trueHeadRanksAboveWrongTypeEntityInPredictHeads",
            "scoreTripleMatchesTrainedModel",
            "predictTailsTruncatesToTopK",
            "unknownEntityThrowsIllegalArgumentException",
            "unknownRelationThrowsIllegalArgumentException",
            "nullModelThrowsAtConstruction");

    private static final List<String> NODE2VEC_METHODS = List.of(
            "sameGraphAndConfigProduceIdenticalVectors",
            "intraClusterCosineExceedsInterClusterCosine",
            "everyEntityGetsCorrectDimensionVector",
            "deepWalkPathProducesFiniteVectors",
            "learnIntoWritesEmbeddingsBackIntoGraph",
            "learnIntoLayerPreservesPrimaryEmbeddingsAndProvidesReasoningView",
            "nonUniformPqProducesFiniteVectors",
            "sameDiffTrainerLossDecreasesOverEpochs",
            "sameDiffTrainerCloseIsIdempotentAndRejectsUseAfterClose",
            "tryWithResourcesClosesTrainerWhenTrainingFails",
            "node2VecReturnsJavaEmbeddingCopyAfterTrainerClose");

    private static final long IDLE_SETTLE_MILLIS = 5_000L;
    private static final String TRIM_PROBE_PROPERTY = "kompile.fullgraphproc016.trimProbe";
    private static final Path SMAPS_ROLLUP = Path.of("/proc/self/smaps_rollup");

    @Test
    void runOrderedMemoryDiagnostic() throws Exception {
        System.out.printf(Locale.ROOT, "[FULLGRAPH-DIAG] start property=kompile.fullgraphproc016.diag trimProbe=%s%n",
                trimProbeEnabled());
        sample("baseline");

        runGroup("MebnFolTypeE2ETest", MEBN, MEBN_METHODS, null);
        runGroup("LinkPredictorTest", LINK, LINK_METHODS, "trainModel");
        runGroup("Node2VecLearnerTest", NODE2VEC, NODE2VEC_METHODS, null);

        sample("complete");
        System.out.println("[FULLGRAPH-DIAG] end");
    }

    private static boolean trimProbeEnabled() {
        return Boolean.parseBoolean(System.getProperty(TRIM_PROBE_PROPERTY, "false"));
    }

    private static void runTrimProbe() {
        Snapshot before = sample("trimProbe.before");
        TrimProbeResult trim = invokeMallocTrim();
        Snapshot after = sample("trimProbe.after");
        System.out.printf(Locale.ROOT,
                "[FULLGRAPH-DIAG] trimProbeResult=%s beforeSmapsRssKb=%d afterSmapsRssKb=%d "
                        + "deltaSmapsRssKb=%+d beforeSmapsAnonymousKb=%d afterSmapsAnonymousKb=%d "
                        + "deltaSmapsAnonymousKb=%+d beforeLiveArrayCount=%d afterLiveArrayCount=%d "
                        + "deltaLiveArrayCount=%+d%n",
                trim.describe(), before.smapsRssKb, after.smapsRssKb,
                after.smapsRssKb - before.smapsRssKb, before.smapsAnonymousKb,
                after.smapsAnonymousKb, after.smapsAnonymousKb - before.smapsAnonymousKb,
                before.liveArrayCount, after.liveArrayCount,
                after.liveArrayCount - before.liveArrayCount);
    }

    private static TrimProbeResult invokeMallocTrim() {
        try {
            if (!Platform.isLinux()) {
                return TrimProbeResult.unavailable("platform is not Linux: "
                        + System.getProperty("os.name", "unknown"));
            }
            LinuxLibC libc = Native.load(Platform.C_LIBRARY_NAME, LinuxLibC.class);
            return TrimProbeResult.invoked(libc.malloc_trim(0));
        } catch (LinkageError | RuntimeException failure) {
            return TrimProbeResult.unavailable(failure.getClass().getSimpleName() + ": "
                    + String.valueOf(failure.getMessage()));
        }
    }

    private static void runGroup(String label, String className, List<String> methods,
                                 String beforeAll) throws Exception {
        Class<?> type = Class.forName(className);
        Object instance = newInstance(type);
        if (beforeAll != null) {
            invoke(type, null, beforeAll);
            sample(label + ".@BeforeAll." + beforeAll);
        }
        for (String methodName : methods) {
            String phase = label + "." + methodName;
            Snapshot before = sample(phase + ".before");
            invoke(type, instance, methodName);
            Snapshot after = sample(phase + ".after");
            printDelta(phase, before, after);

            if (label.equals("Node2VecLearnerTest")
                    && methodName.equals("sameDiffTrainerCloseIsIdempotentAndRejectsUseAfterClose")) {
                sample(phase + ".afterTrainerClose");
                if (trimProbeEnabled()) {
                    runTrimProbe();
                }
                sampleAfterIdle(phase + ".idle5s");
            }
        }
    }

    private static Object newInstance(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void invoke(Class<?> type, Object instance, String methodName) throws Exception {
        Method method = type.getDeclaredMethod(methodName);
        method.setAccessible(true);
        try {
            method.invoke(instance);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof Exception exception) throw exception;
            throw new RuntimeException(cause);
        }
    }

    private static Snapshot sample(String phase) {
        long poolUsed = 0L;
        long poolReserved = 0L;
        List<String> poolByDevice = new ArrayList<>();
        int deviceCount = 0;
        try {
            deviceCount = Nd4j.getAffinityManager().getNumberOfDevices();
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            for (int device = 0; device < deviceCount; device++) {
                long used = -1L;
                long reserved = -1L;
                try (LongPointer usedPtr = new LongPointer(1);
                     LongPointer reservedPtr = new LongPointer(1)) {
                    nativeOps.getMemoryPoolStats(device, usedPtr, reservedPtr);
                    used = Math.max(0L, usedPtr.get(0));
                    reserved = Math.max(0L, reservedPtr.get(0));
                } catch (Throwable ignored) {
                    // CPU/native backends may not expose allocator-pool statistics.
                }
                if (used >= 0L) poolUsed += used;
                if (reserved >= 0L) poolReserved += reserved;
                poolByDevice.add(device + ":" + used + "/" + reserved);
            }
        } catch (Throwable ignored) {
            poolByDevice.add("unavailable");
        }

        long hostAllocated = 0L;
        long hostCached = 0L;
        long deviceAllocated = 0L;
        long deviceCached = 0L;
        long workspaceTracked = 0L;
        try {
            var tracker = Nd4j.framework.memory().tracker();
            hostAllocated = tracker.getAllocatedHostAmount();
            hostCached = tracker.getCachedHostAmount();
            for (int device = 0; device < deviceCount; device++) {
                deviceAllocated += tracker.getAllocatedAmount(device);
                deviceCached += tracker.getCachedAmount(device);
                workspaceTracked += tracker.getWorkspaceAllocatedAmount(device);
            }
        } catch (Throwable ignored) {
            // Keep unavailable fields at zero; Pointer metrics remain authoritative.
        }

        long liveRefs = 0L;
        MetricValue deallocatorReferenceBytes = MetricValue.unavailable(
                "DeallocatorAccess exposes the live count but not reference-map bytes");
        long liveArrayCount = 0L;
        long lifecycleCurrentLive = 0L;
        long created = 0L;
        long destroyed = 0L;
        long nativeDeallocatorLive = 0L;
        long nativeDeallocatorBytes = 0L;
        try {
            var deallocator = Nd4j.framework.memory().deallocator();
            liveRefs = deallocator.getLiveReferenceCount();
            created = deallocator.getTotalAllocations();
            destroyed = deallocator.getTotalDeallocations();
        } catch (Throwable ignored) {
            // Keep unavailable fields at zero.
        }
        try {
            var lifecycle = Nd4j.framework.summary().getLifecycle();
            liveArrayCount = lifecycle.getLiveArrays();
            lifecycleCurrentLive = lifecycle.getLiveArrays();
        } catch (Throwable ignored) {
            // The framework summary is best-effort on minimal backends.
        }

        MetricValue opaqueArrayLeakCount = MetricValue.unavailable(
                "NativeOps public interface and installed ABI do not expose opaque NDArray leak count");
        MetricValue hostNativeAllocCounter = MetricValue.unavailable(
                "NativeOps public interface and installed ABI do not expose native allocation group counters");
        MetricValue nativeNdArrayCurrentLive = MetricValue.unavailable(
                "NativeOps public interface and installed ABI do not expose NDArray lifecycle JSON");
        MetricValue nativeDataBufferCurrentLive = MetricValue.unavailable(
                "NativeOps public interface and installed ABI do not expose DataBuffer lifecycle JSON");
        try {
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            nativeDeallocatorLive = nativeOps.getDeallocatorServiceLiveCount();
            nativeDeallocatorBytes = nativeOps.getDeallocatorServiceBytesInUse();
        } catch (Throwable ignored) {
            // Native deallocator counters are optional on older/native-minimal backends.
        }

        SmapsRollup smaps = readSmapsRollup();

        long currentWorkspace = 0L;
        long workspaceOffset = 0L;
        long workspaceCycle = 0L;
        long workspaceLast = 0L;
        long workspaceMax = 0L;
        boolean workspaceActive = false;
        try {
            MemoryWorkspace workspace = Nd4j.getMemoryManager().getCurrentWorkspace();
            if (workspace != null) {
                currentWorkspace = workspace.getCurrentSize();
                workspaceOffset = workspace.getCurrentOffset();
                workspaceCycle = workspace.getThisCycleAllocations();
                workspaceLast = workspace.getLastCycleAllocations();
                workspaceMax = workspace.getMaxCycleAllocations();
                workspaceActive = workspace.isScopeActive();
            }
        } catch (Throwable ignored) {
            // No current workspace is a valid state.
        }

        long arrayCacheBytes = 0L;
        int arrayCacheEntries = 0;
        long[] arrayCacheCounters = new long[6];
        boolean arrayCacheEnabled = false;
        try {
            arrayCacheEnabled = ArrayCacheMemoryMgr.isCacheEnabled();
            arrayCacheBytes = ArrayCacheMemoryMgr.getCurrentCacheSize().get();
            arrayCacheEntries = ArrayCacheMemoryMgr.getLruCacheValues().size();
            long[] counters = ArrayCacheMemoryMgr.getCacheCounters();
            System.arraycopy(counters, 0, arrayCacheCounters, 0,
                    Math.min(counters.length, arrayCacheCounters.length));
        } catch (Throwable ignored) {
            // Cache diagnostics are best-effort and must not change test behavior.
        }

        long constantCacheBytes = 0L;
        int constantBuffers = 0;
        int shapeBuffers = 0;
        int tadHelpers = 0;
        long shapeCacheBytes = 0L;
        long tadCacheBytes = 0L;
        try {
            ConstantCacheState constants = Nd4j.framework.constants().state();
            constantCacheBytes = constants.getTotalCacheBytes();
            constantBuffers = constants.getTotalConstantBuffers();
            shapeBuffers = constants.getTotalShapeBuffers();
            tadHelpers = constants.getTotalTadHelpers();
            shapeCacheBytes = constants.getShapeBufferCacheBytes();
            tadCacheBytes = constants.getTadHelperCacheBytes();
        } catch (Throwable ignored) {
            // Native constant/TAD caches are not available on every backend.
        }

        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();
        long liveArrayEstimate = created - destroyed;
        Snapshot result = new Snapshot(
                Pointer.totalBytes(), Pointer.physicalBytes(), Pointer.maxPhysicalBytes(),
                heapUsed, heapMax, liveRefs, deallocatorReferenceBytes, liveArrayCount,
                lifecycleCurrentLive, created, destroyed, liveArrayEstimate,
                opaqueArrayLeakCount, nativeDeallocatorLive, nativeDeallocatorBytes,
                nativeNdArrayCurrentLive, nativeDataBufferCurrentLive, hostNativeAllocCounter,
                hostAllocated, hostCached, deviceAllocated, deviceCached, workspaceTracked,
                currentWorkspace, workspaceOffset, workspaceCycle, workspaceLast, workspaceMax,
                workspaceActive, arrayCacheEnabled, arrayCacheBytes, arrayCacheEntries,
                arrayCacheCounters, constantCacheBytes, constantBuffers, shapeBuffers, tadHelpers,
                shapeCacheBytes, tadCacheBytes, poolUsed, poolReserved, deviceCount,
                String.join(";", poolByDevice), smaps.rssKb, smaps.privateDirtyKb, smaps.anonymousKb);

        System.out.printf(Locale.ROOT,
                "[FULLGRAPH-DIAG] phase=%s total=%d physical=%d maxPhysical=%d heap=%d/%d "
                        + "deallocatorRefCount=%d deallocatorRefBytes=%s liveArrayCount=%d lifecycleCurrentLive=%d "
                        + "created=%d destroyed=%d liveArrayEstimate=%d opaqueArrayLeakCount=%s "
                        + "nativeDeallocatorLive=%d nativeDeallocatorBytes=%d "
                        + "nativeNdArrayCurrentLive=%s nativeDataBufferCurrentLive=%s "
                        + "hostNativeAllocCounter=%s smapsRssKb=%d smapsPrivateDirtyKb=%d smapsAnonymousKb=%d "
                        + "hostAllocated=%d hostCached=%d deviceAllocated=%d deviceCached=%d "
                        + "workspaceTracked=%d currentWorkspace=%d workspaceOffset=%d "
                        + "workspaceCycle=%d workspaceLast=%d workspaceMax=%d workspaceActive=%s "
                        + "arrayCacheEnabled=%s arrayCacheBytes=%d arrayCacheEntries=%d "
                        + "arrayCacheHits=%d arrayCacheCapacityHits=%d arrayCacheMisses=%d "
                        + "arrayCacheReleases=%d constantCacheBytes=%d constantBuffers=%d "
                        + "shapeBuffers=%d tadHelpers=%d shapeCacheBytes=%d tadCacheBytes=%d "
                        + "poolUsed=%d poolReserved=%d deviceCount=%d poolByDevice=%s%n",
                phase, result.totalBytes, result.physicalBytes, result.maxPhysicalBytes,
                result.heapUsed, result.heapMax, result.liveRefs, result.deallocatorReferenceBytes.describe(),
                result.liveArrayCount, result.lifecycleCurrentLive,
                result.created, result.destroyed, result.liveArrayEstimate, result.opaqueArrayLeakCount.describe(),
                result.nativeDeallocatorLive, result.nativeDeallocatorBytes,
                result.nativeNdArrayCurrentLive.describe(), result.nativeDataBufferCurrentLive.describe(),
                result.hostNativeAllocCounter.describe(), result.smapsRssKb, result.smapsPrivateDirtyKb,
                result.smapsAnonymousKb,
                result.hostAllocated, result.hostCached,
                result.deviceAllocated, result.deviceCached, result.workspaceTracked,
                result.currentWorkspace, result.workspaceOffset, result.workspaceCycle,
                result.workspaceLast, result.workspaceMax, result.workspaceActive,
                result.arrayCacheEnabled, result.arrayCacheBytes, result.arrayCacheEntries,
                result.arrayCacheCounters[0], result.arrayCacheCounters[1],
                result.arrayCacheCounters[2], result.arrayCacheCounters[4],
                result.constantCacheBytes, result.constantBuffers, result.shapeBuffers,
                result.tadHelpers, result.shapeCacheBytes, result.tadCacheBytes,
                result.poolUsed, result.poolReserved, result.deviceCount, result.poolByDevice);
        return result;
    }

    private static void printDelta(String phase, Snapshot before, Snapshot after) {
        System.out.printf(Locale.ROOT,
                "[FULLGRAPH-DIAG] delta=%s total=%+d physical=%+d heap=%+d deallocatorRefCount=%+d "
                        + "deallocatorRefBytes=%s liveArrayCount=%+d lifecycleCurrentLive=%+d "
                        + "liveArrayEstimate=%+d opaqueArrayLeakCount=%s nativeDeallocatorLive=%+d "
                        + "nativeDeallocatorBytes=%+d nativeNdArrayCurrentLive=%s "
                        + "nativeDataBufferCurrentLive=%s hostNativeAllocCounter=%s "
                        + "smapsRssKb=%+d smapsPrivateDirtyKb=%+d smapsAnonymousKb=%+d "
                        + "hostAllocated=%+d hostCached=%+d "
                        + "deviceAllocated=%+d deviceCached=%+d workspaceTracked=%+d "
                        + "currentWorkspace=%+d arrayCacheBytes=%+d constantCacheBytes=%+d "
                        + "poolUsed=%+d poolReserved=%+d%n",
                phase, after.totalBytes - before.totalBytes,
                after.physicalBytes - before.physicalBytes,
                after.heapUsed - before.heapUsed, after.liveRefs - before.liveRefs,
                MetricValue.delta(before.deallocatorReferenceBytes, after.deallocatorReferenceBytes),
                after.liveArrayCount - before.liveArrayCount,
                after.lifecycleCurrentLive - before.lifecycleCurrentLive,
                after.liveArrayEstimate - before.liveArrayEstimate,
                MetricValue.delta(before.opaqueArrayLeakCount, after.opaqueArrayLeakCount),
                after.nativeDeallocatorLive - before.nativeDeallocatorLive,
                after.nativeDeallocatorBytes - before.nativeDeallocatorBytes,
                MetricValue.delta(before.nativeNdArrayCurrentLive, after.nativeNdArrayCurrentLive),
                MetricValue.delta(before.nativeDataBufferCurrentLive, after.nativeDataBufferCurrentLive),
                MetricValue.delta(before.hostNativeAllocCounter, after.hostNativeAllocCounter),
                after.smapsRssKb - before.smapsRssKb,
                after.smapsPrivateDirtyKb - before.smapsPrivateDirtyKb,
                after.smapsAnonymousKb - before.smapsAnonymousKb,
                after.hostAllocated - before.hostAllocated, after.hostCached - before.hostCached,
                after.deviceAllocated - before.deviceAllocated, after.deviceCached - before.deviceCached,
                after.workspaceTracked - before.workspaceTracked,
                after.currentWorkspace - before.currentWorkspace,
                after.arrayCacheBytes - before.arrayCacheBytes,
                after.constantCacheBytes - before.constantCacheBytes,
                after.poolUsed - before.poolUsed, after.poolReserved - before.poolReserved);
    }

    private static void sampleAfterIdle(String phase) {
        try {
            Thread.sleep(IDLE_SETTLE_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        sample(phase);
    }

    private static SmapsRollup readSmapsRollup() {
        try {
            SmapsRollup result = parseSmapsRollup(Files.readAllLines(SMAPS_ROLLUP));
            if (result.unavailableReason() != null) {
                reportSmapsUnavailable(result.unavailableReason());
            }
            return result;
        } catch (Exception failure) {
            String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            reportSmapsUnavailable(reason);
            return SmapsRollup.unavailable(reason);
        }
    }

    static SmapsRollup parseSmapsRollup(List<String> lines) {
        long rssKb = -1L;
        long privateDirtyKb = -1L;
        long anonymousKb = -1L;
        List<String> unavailable = new ArrayList<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            if (!key.equals("Rss") && !key.equals("Private_Dirty") && !key.equals("Anonymous")) {
                continue;
            }
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                unavailable.add(key + " has no numeric value");
                continue;
            }
            String[] fields = value.split("\\s+");
            try {
                long parsed = Long.parseLong(fields[0]);
                switch (key) {
                    case "Rss" -> rssKb = parsed;
                    case "Private_Dirty" -> privateDirtyKb = parsed;
                    case "Anonymous" -> anonymousKb = parsed;
                    default -> { }
                }
            } catch (NumberFormatException failure) {
                unavailable.add(key + " has non-numeric value '" + fields[0] + "'");
            }
        }
        if (rssKb < 0L) unavailable.add("Rss unavailable");
        if (privateDirtyKb < 0L) unavailable.add("Private_Dirty unavailable");
        if (anonymousKb < 0L) unavailable.add("Anonymous unavailable");
        String reason = unavailable.isEmpty() ? null : String.join("; ", unavailable);
        return new SmapsRollup(rssKb, privateDirtyKb, anonymousKb, reason);
    }

    private static void reportSmapsUnavailable(String reason) {
        System.out.printf(Locale.ROOT, "[FULLGRAPH-DIAG] smaps_rollup=UNAVAILABLE(%s)%n", reason);
    }

    private record MetricValue(Long value, String unavailableReason) {
        private static MetricValue unavailable(String reason) {
            return new MetricValue(null, reason);
        }

        private String describe() {
            return value != null ? Long.toString(value) : "UNAVAILABLE(" + unavailableReason + ")";
        }

        private static String delta(MetricValue before, MetricValue after) {
            if (before.value == null || after.value == null) return after.describe();
            return String.format(Locale.ROOT, "%+d", after.value - before.value);
        }
    }

    private interface LinuxLibC extends Library {
        int malloc_trim(int pad);
    }

    private record TrimProbeResult(Integer returnCode, String unavailableReason) {
        private static TrimProbeResult invoked(int returnCode) {
            return new TrimProbeResult(returnCode, null);
        }

        private static TrimProbeResult unavailable(String reason) {
            return new TrimProbeResult(null, reason);
        }

        private String describe() {
            return returnCode != null
                    ? "malloc_trim(0) return=" + returnCode
                    : "UNAVAILABLE(" + unavailableReason + ")";
        }
    }

    private record Snapshot(
            long totalBytes,
            long physicalBytes,
            long maxPhysicalBytes,
            long heapUsed,
            long heapMax,
            long liveRefs,
            MetricValue deallocatorReferenceBytes,
            long liveArrayCount,
            long lifecycleCurrentLive,
            long created,
            long destroyed,
            long liveArrayEstimate,
            MetricValue opaqueArrayLeakCount,
            long nativeDeallocatorLive,
            long nativeDeallocatorBytes,
            MetricValue nativeNdArrayCurrentLive,
            MetricValue nativeDataBufferCurrentLive,
            MetricValue hostNativeAllocCounter,
            long hostAllocated,
            long hostCached,
            long deviceAllocated,
            long deviceCached,
            long workspaceTracked,
            long currentWorkspace,
            long workspaceOffset,
            long workspaceCycle,
            long workspaceLast,
            long workspaceMax,
            boolean workspaceActive,
            boolean arrayCacheEnabled,
            long arrayCacheBytes,
            int arrayCacheEntries,
            long[] arrayCacheCounters,
            long constantCacheBytes,
            int constantBuffers,
            int shapeBuffers,
            int tadHelpers,
            long shapeCacheBytes,
            long tadCacheBytes,
            long poolUsed,
            long poolReserved,
            int deviceCount,
            String poolByDevice,
            long smapsRssKb,
            long smapsPrivateDirtyKb,
            long smapsAnonymousKb) {
    }

    record SmapsRollup(long rssKb, long privateDirtyKb, long anonymousKb, String unavailableReason) {
        private static SmapsRollup unavailable(String reason) {
            return new SmapsRollup(-1L, -1L, -1L, reason);
        }
    }
}
