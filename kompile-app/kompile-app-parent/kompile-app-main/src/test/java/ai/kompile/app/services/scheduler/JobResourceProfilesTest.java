package ai.kompile.app.services.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JobResourceProfiles")
class JobResourceProfilesTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void allRegisteredProfilesArePresent() {
        Map<String, JobResourceProfile> all = JobResourceProfiles.all();
        assertTrue(all.size() >= 8, "Expected at least 8 profiles, got " + all.size());
        assertNotNull(all.get("ingest"));
        assertNotNull(all.get("vectorPopulation"));
        assertNotNull(all.get("crawl"));
        assertNotNull(all.get("unifiedCrawl"));
        assertNotNull(all.get("training"));
        assertNotNull(all.get("vlm"));
        assertNotNull(all.get("modelInit"));
        assertNotNull(all.get("llm"));
    }

    @Test
    void getReturnsCorrectProfile() {
        JobResourceProfile ingest = JobResourceProfiles.get("ingest");
        assertNotNull(ingest);
        assertEquals("ingest", ingest.serviceType());
        assertTrue(ingest.requiresGpu());
        assertEquals(2 * GB, ingest.peakGpuMemoryBytes());
    }

    @Test
    void getReturnsNullForUnknownType() {
        assertNull(JobResourceProfiles.get("nonexistent-type"));
    }

    @Test
    void crawlIsCpuOnly() {
        JobResourceProfile crawl = JobResourceProfiles.CRAWL;
        assertFalse(crawl.requiresGpu());
        assertEquals(0, crawl.peakGpuMemoryBytes());
        assertTrue(crawl.concurrentAllowed());
    }

    @Test
    void unifiedCrawlHasPhaseBreakdown() {
        JobResourceProfile unified = JobResourceProfiles.UNIFIED_CRAWL;
        assertTrue(unified.hasPhaseBreakdown());
        assertEquals(8, unified.phaseProfiles().size());

        // CPU-only early phases
        assertFalse(unified.phaseRequiresGpu("LOADING"));
        assertFalse(unified.phaseRequiresGpu("CONVERTING"));
        assertFalse(unified.phaseRequiresGpu("ROUTING"));
        assertFalse(unified.phaseRequiresGpu("CHUNKING"));
        assertFalse(unified.phaseRequiresGpu("GRAPH_EXTRACTION"));

        // GPU tail phases
        assertTrue(unified.phaseRequiresGpu("ENTITY_RESOLUTION"));
        assertTrue(unified.phaseRequiresGpu("EDGE_COMPUTATION"));
        assertTrue(unified.phaseRequiresGpu("VECTOR_INDEXING"));
    }

    @Test
    void trainingConflictsWithVlm() {
        JobResourceProfile training = JobResourceProfiles.TRAINING;
        assertTrue(training.conflictsWith().contains("vlm"));
    }

    @Test
    void vlmConflictsWithTraining() {
        JobResourceProfile vlm = JobResourceProfiles.VLM;
        assertTrue(vlm.conflictsWith().contains("training"));
    }

    @Test
    void registerCustomAddsProfile() {
        String customType = "test-custom-" + System.nanoTime();
        JobResourceProfile custom = JobResourceProfile.cpuOnly(customType, "Custom", GB);
        JobResourceProfiles.registerCustom(custom);

        assertNotNull(JobResourceProfiles.get(customType));
        assertEquals("Custom", JobResourceProfiles.get(customType).displayName());
    }

    @Test
    void allReturnsDefensiveCopy() {
        Map<String, JobResourceProfile> all = JobResourceProfiles.all();
        assertThrows(UnsupportedOperationException.class, () -> all.put("hack", null));
    }
}
