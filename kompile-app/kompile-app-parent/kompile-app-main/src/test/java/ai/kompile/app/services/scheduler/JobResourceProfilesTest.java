package ai.kompile.app.services.scheduler;

import ai.kompile.crawl.graph.CrawlPipelineStepRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        assertEquals(16, unified.phaseProfiles().size());
        // Dispatch places a job by its first phase, so a crawl waiting for its slot holds no GPU
        assertEquals("QUEUED", unified.phaseProfiles().get(0).phaseName());

        // CPU-only phases
        assertFalse(unified.phaseRequiresGpu("QUEUED"));
        assertFalse(unified.phaseRequiresGpu("LOADING"));
        assertFalse(unified.phaseRequiresGpu("DISCOVERING"));
        assertFalse(unified.phaseRequiresGpu("CONVERTING"));
        assertFalse(unified.phaseRequiresGpu("PREPROCESSING"));
        assertFalse(unified.phaseRequiresGpu("ROUTING"));
        assertFalse(unified.phaseRequiresGpu("GRAPH_PREP"));
        assertFalse(unified.phaseRequiresGpu("CHUNKING"));
        assertFalse(unified.phaseRequiresGpu("GRAPH_EXTRACTION"));
        assertFalse(unified.phaseRequiresGpu("SURFACING"));
        assertFalse(unified.phaseRequiresGpu("ENTITY_PARTITIONS"));
        assertFalse(unified.phaseRequiresGpu("ENRICHMENT"));
        assertFalse(unified.phaseRequiresGpu("LEARNING"));

        // GPU tail phases
        assertTrue(unified.phaseRequiresGpu("ENTITY_RESOLUTION"));
        assertTrue(unified.phaseRequiresGpu("EDGE_COMPUTATION"));
        assertTrue(unified.phaseRequiresGpu("VECTOR_INDEXING"));
    }

    /**
     * A phase with no profile silently inherits the job-level answer — {@code requiresGpu=true} and
     * the 5 GB peak — so "declared CPU-only" and "never declared" are indistinguishable through
     * {@link JobResourceProfile#phaseRequiresGpu} alone. Asserting the budget too is what separates
     * them: a phase the pipeline runs but the profile forgot would reserve GPU it never touches.
     */
    @Test
    void everyUnifiedCrawlPhaseIsDeclaredNotInherited() {
        JobResourceProfile unified = JobResourceProfiles.UNIFIED_CRAWL;

        assertEquals(0, unified.gpuMemoryForPhase("GRAPH_EXTRACTION"));
        assertEquals(0, unified.gpuMemoryForPhase("ENTITY_PARTITIONS"));
        assertEquals(5 * GB, unified.gpuMemoryForPhase("VECTOR_INDEXING"));

        // Exactly the phases a crawl resolves to: every pipeline step, plus QUEUED and LEARNING
        Set<String> workPhases = new HashSet<>(CrawlPipelineStepRegistry.NON_STEP_WORK_PHASES);
        CrawlPipelineStepRegistry.all().forEach(step -> workPhases.add(step.id()));
        List<String> declared = unified.phaseProfiles().stream()
                .map(JobResourceProfile.PhaseResourceProfile::phaseName).toList();
        assertEquals(workPhases, new HashSet<>(declared));
        assertEquals(workPhases.size(), declared.size(), "a phase is declared twice: " + declared);

        // The fallback itself, so the assertions above are known to be reading real entries.
        assertEquals(unified.peakGpuMemoryBytes(), unified.gpuMemoryForPhase("NO_SUCH_PHASE"));
        assertTrue(unified.phaseRequiresGpu("NO_SUCH_PHASE"));
    }

    /**
     * A crawl's reported phase reaches the scheduler through {@link CrawlPipelineStepRegistry#workPhase}:
     * vector indexing's EMBEDDING and INDEXING get its GPU budget, a decomposed extraction pass gets its
     * step's (none), and an end marker is never looked up. Each resolved name must be declared: the job
     * default equals VECTOR_INDEXING's budget, so an alias left unresolved would still pass the budget checks.
     */
    @Test
    void reportedUnifiedCrawlPhasesResolveToDeclaredBudgets() {
        JobResourceProfile unified = JobResourceProfiles.UNIFIED_CRAWL;
        List<String> declared = unified.phaseProfiles().stream()
                .map(JobResourceProfile.PhaseResourceProfile::phaseName).toList();

        for (String phase : List.of("EMBEDDING", "INDEXING")) {
            String resolved = CrawlPipelineStepRegistry.workPhase(phase);
            assertTrue(declared.contains(resolved), phase + " resolves to undeclared " + resolved);
            assertTrue(unified.phaseRequiresGpu(resolved), phase);
            assertEquals(5 * GB, unified.gpuMemoryForPhase(resolved), phase);
        }
        for (String phase : List.of("GRAPH_EXTRACTION_ENTITIES", "GRAPH_EXTRACTION_RELATIONS",
                "ENTITY_PARTITIONS_ENTITIES", "ENTITY_PARTITIONS_RELATIONS")) {
            String resolved = CrawlPipelineStepRegistry.workPhase(phase);
            assertTrue(declared.contains(resolved), phase + " resolves to undeclared " + resolved);
            assertFalse(unified.phaseRequiresGpu(resolved), phase);
            assertEquals(0, unified.gpuMemoryForPhase(resolved), phase);
        }
        for (String phase : CrawlPipelineStepRegistry.NON_WORK_PHASES) {
            assertNull(CrawlPipelineStepRegistry.workPhase(phase), phase);
        }
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
