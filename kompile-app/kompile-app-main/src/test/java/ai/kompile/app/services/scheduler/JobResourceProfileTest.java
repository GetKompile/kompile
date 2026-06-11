package ai.kompile.app.services.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JobResourceProfile")
class JobResourceProfileTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        void cpuOnlyCreatesProfileWithNoGpu() {
            JobResourceProfile profile = JobResourceProfile.cpuOnly("crawl", "Web Crawl", 2 * GB);

            assertEquals("crawl", profile.serviceType());
            assertEquals("Web Crawl", profile.displayName());
            assertFalse(profile.requiresGpu());
            assertEquals(0, profile.peakGpuMemoryBytes());
            assertEquals(2 * GB, profile.estimatedHeapBytes());
            assertTrue(profile.concurrentAllowed());
            assertEquals(4, profile.maxConcurrent());
            assertTrue(profile.phaseProfiles().isEmpty());
            assertTrue(profile.conflictsWith().isEmpty());
            assertTrue(profile.batchableWith().isEmpty());
        }

        @Test
        void gpuRequiredCreatesProfileWithGpu() {
            JobResourceProfile profile = JobResourceProfile.gpuRequired(
                    "ingest", "Ingest", 2 * GB, 4 * GB);

            assertEquals("ingest", profile.serviceType());
            assertTrue(profile.requiresGpu());
            assertEquals(2 * GB, profile.peakGpuMemoryBytes());
            assertEquals(4 * GB, profile.estimatedHeapBytes());
            assertFalse(profile.concurrentAllowed());
            assertEquals(1, profile.maxConcurrent());
        }

        @Test
        void gpuRequiredWithConcurrencyOverride() {
            JobResourceProfile profile = JobResourceProfile.gpuRequired(
                    "ingest", "Ingest", 2 * GB, 4 * GB, true, 4);

            assertTrue(profile.requiresGpu());
            assertTrue(profile.concurrentAllowed());
            assertEquals(4, profile.maxConcurrent());
        }
    }

    @Nested
    @DisplayName("Phase breakdown")
    class PhaseBreakdown {

        @Test
        void hasPhaseBreakdownFalseForEmptyPhases() {
            JobResourceProfile profile = JobResourceProfile.cpuOnly("test", "Test", GB);
            assertFalse(profile.hasPhaseBreakdown());
        }

        @Test
        void hasPhaseBreakdownFalseForNullPhases() {
            JobResourceProfile profile = new JobResourceProfile(
                    "test", "Test", false, 0, GB,
                    true, 4, null, List.of(), List.of());
            assertFalse(profile.hasPhaseBreakdown());
        }

        @Test
        void hasPhaseBreakdownTrueWhenPhasesPresent() {
            JobResourceProfile profile = new JobResourceProfile(
                    "test", "Test", true, 5 * GB, GB,
                    false, 1,
                    List.of(
                            new JobResourceProfile.PhaseResourceProfile("LOADING", false, 0, 60, true),
                            new JobResourceProfile.PhaseResourceProfile("INDEXING", true, 5 * GB, 60, false)
                    ),
                    List.of(), List.of());
            assertTrue(profile.hasPhaseBreakdown());
        }

        @Test
        void gpuMemoryForPhaseReturnsPhaseSpecificValue() {
            JobResourceProfile profile = new JobResourceProfile(
                    "test", "Test", true, 10 * GB, GB,
                    false, 1,
                    List.of(
                            new JobResourceProfile.PhaseResourceProfile("LOADING", false, 0, 60, true),
                            new JobResourceProfile.PhaseResourceProfile("INDEXING", true, 5 * GB, 60, false)
                    ),
                    List.of(), List.of());

            assertEquals(0, profile.gpuMemoryForPhase("LOADING"));
            assertEquals(5 * GB, profile.gpuMemoryForPhase("INDEXING"));
        }

        @Test
        void gpuMemoryForPhaseFallsToPeakForUnknownPhase() {
            JobResourceProfile profile = new JobResourceProfile(
                    "test", "Test", true, 10 * GB, GB,
                    false, 1,
                    List.of(new JobResourceProfile.PhaseResourceProfile("INDEXING", true, 5 * GB, 60, false)),
                    List.of(), List.of());

            assertEquals(10 * GB, profile.gpuMemoryForPhase("UNKNOWN_PHASE"));
        }

        @Test
        void phaseRequiresGpuReturnsPhaseSpecificValue() {
            JobResourceProfile profile = new JobResourceProfile(
                    "test", "Test", true, 5 * GB, GB,
                    false, 1,
                    List.of(
                            new JobResourceProfile.PhaseResourceProfile("LOADING", false, 0, 60, true),
                            new JobResourceProfile.PhaseResourceProfile("INDEXING", true, 5 * GB, 60, false)
                    ),
                    List.of(), List.of());

            assertFalse(profile.phaseRequiresGpu("LOADING"));
            assertTrue(profile.phaseRequiresGpu("INDEXING"));
        }

        @Test
        void phaseRequiresGpuFallsToProfileDefault() {
            JobResourceProfile profile = JobResourceProfile.gpuRequired("test", "Test", GB, GB);
            assertTrue(profile.phaseRequiresGpu("ANY_PHASE"));

            JobResourceProfile cpuProfile = JobResourceProfile.cpuOnly("test2", "Test2", GB);
            assertFalse(cpuProfile.phaseRequiresGpu("ANY_PHASE"));
        }
    }
}
