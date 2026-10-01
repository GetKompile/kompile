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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CrawlPipelineStepRegistry#workPhase} resolves the phase a crawl reports to the work phase the
 * crawl scheduler looks up; a name the scheduler doesn't declare inherits the crawl profile's job-wide GPU
 * default. The source scans read every phase the crawl code can set and fail when one doesn't resolve.
 * JobResourceProfilesTest (kompile-app-main) checks that UNIFIED_CRAWL declares every work phase.
 */
class CrawlPipelineStepRegistryTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /**
     * A call that sets a crawl job's current phase, with the phase it passes: the setter itself, and the
     * helpers whose phase parameter ends up in it. Declarations ({@code void updateProgress(}) are skipped.
     */
    private static final Pattern PHASE_ARGUMENT = Pattern.compile(
            "(?<!void |boolean )\\b(getCurrentPhase\\(\\)\\.set|updateProgress|waitForMemoryCapacity"
                    + "|emitDecomposedPassProgress)\\(\\s*(?:job\\s*,\\s*)?(\"[^\"]*\"|[\\w.]+)");

    private record PhaseArgument(String file, int line, String call, String argument) {
        boolean isLiteral() {
            return argument.startsWith("\"");
        }

        String literal() {
            return argument.substring(1, argument.length() - 1);
        }
    }

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    @Test
    void workPhase_stepsAndNonStepWorkPhases_resolveToThemselves() {
        for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
            assertEquals(d.id(), CrawlPipelineStepRegistry.workPhase(d.id()));
        }
        assertEquals("QUEUED", CrawlPipelineStepRegistry.workPhase("QUEUED"));
        assertEquals("LEARNING", CrawlPipelineStepRegistry.workPhase("LEARNING"));
    }

    @Test
    void workPhase_vectorIndexingAliases_resolveToVectorIndexing() {
        assertEquals("VECTOR_INDEXING", CrawlPipelineStepRegistry.workPhase("EMBEDDING"));
        assertEquals("VECTOR_INDEXING", CrawlPipelineStepRegistry.workPhase("INDEXING"));
    }

    @Test
    void workPhase_decomposedPasses_resolveToTheirStep() {
        assertEquals("GRAPH_EXTRACTION", CrawlPipelineStepRegistry.workPhase("GRAPH_EXTRACTION_ENTITIES"));
        assertEquals("GRAPH_EXTRACTION", CrawlPipelineStepRegistry.workPhase("GRAPH_EXTRACTION_RELATIONS"));
        assertEquals("ENTITY_PARTITIONS", CrawlPipelineStepRegistry.workPhase("ENTITY_PARTITIONS_ENTITIES"));
        assertEquals("ENTITY_PARTITIONS", CrawlPipelineStepRegistry.workPhase("ENTITY_PARTITIONS_RELATIONS"));
    }

    @Test
    void workPhase_nonWorkPhasesAndNull_resolveToNull() {
        for (String phase : List.of("COMPLETED", "FAILED", "CANCELLED", "PARTITION_COMPLETE", "PENDING_EMBEDDING",
                "SCHEMA_UNIFICATION_FAILED", "GRAPH_EXTRACTION_PREVIEW")) {
            assertNull(CrawlPipelineStepRegistry.workPhase(phase), phase);
        }
        assertNull(CrawlPipelineStepRegistry.workPhase(null));
    }

    /** An unknown phase is left to the scheduler profile's job-wide fallback; the source scans catch it. */
    @Test
    void workPhase_unknownPhase_isReturnedUnchanged() {
        assertEquals("NOT_A_PHASE", CrawlPipelineStepRegistry.workPhase("NOT_A_PHASE"));
        assertEquals("NOT_A_STEP_ENTITIES", CrawlPipelineStepRegistry.workPhase("NOT_A_STEP_ENTITIES"));
        assertFalse(CrawlPipelineStepRegistry.isWorkPhase("NOT_A_PHASE"));
    }

    @Test
    void isWorkPhase_onlyForResolvedNames() {
        for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
            assertTrue(CrawlPipelineStepRegistry.isWorkPhase(d.id()), d.id());
        }
        assertTrue(CrawlPipelineStepRegistry.isWorkPhase("QUEUED"));
        assertTrue(CrawlPipelineStepRegistry.isWorkPhase("LEARNING"));
        for (String phase : CrawlPipelineStepRegistry.NON_WORK_PHASES) {
            assertFalse(CrawlPipelineStepRegistry.isWorkPhase(phase), phase);
        }
        assertFalse(CrawlPipelineStepRegistry.isWorkPhase("EMBEDDING"));
        assertFalse(CrawlPipelineStepRegistry.isWorkPhase(null));
    }

    /** The tracker keeps one row per decomposed pass, so only the aliases are shared with it. */
    @Test
    void canonicalStepId_isTheTrackersStepNormalization() {
        PipelineStepTracker tracker = new PipelineStepTracker();
        for (String phase : List.of("EMBEDDING", "INDEXING", "VECTOR_INDEXING", "GRAPH_EXTRACTION",
                "GRAPH_EXTRACTION_ENTITIES", "QUEUED", "COMPLETED")) {
            assertEquals(tracker.normalizeStepId(phase), CrawlPipelineStepRegistry.canonicalStepId(phase), phase);
        }
        assertEquals("GRAPH_EXTRACTION_ENTITIES",
                CrawlPipelineStepRegistry.canonicalStepId("GRAPH_EXTRACTION_ENTITIES"));
        assertNull(CrawlPipelineStepRegistry.canonicalStepId(null));
    }

    // -----------------------------------------------------------------------
    // Every phase the crawl code sets
    // -----------------------------------------------------------------------

    @Test
    void everyPhaseLiteralTheCrawlSets_resolvesToAWorkPhase() throws IOException {
        Set<String> literals = new TreeSet<>();
        List<String> unresolved = new ArrayList<>();
        for (PhaseArgument argument : phaseArguments()) {
            if (!argument.isLiteral()) {
                continue;
            }
            String phase = argument.literal();
            literals.add(phase);
            if (!CrawlPipelineStepRegistry.NON_WORK_PHASES.contains(phase)
                    && !CrawlPipelineStepRegistry.isWorkPhase(CrawlPipelineStepRegistry.workPhase(phase))) {
                unresolved.add(phase + " at " + argument.file() + ":" + argument.line());
            }
        }
        // Guards the scan itself: it still sees the phases every crawl reports
        assertTrue(literals.containsAll(Set.of("QUEUED", "LOADING", "EMBEDDING", "INDEXING", "GRAPH_EXTRACTION",
                "COMPLETED")), "scan found only " + literals);
        assertEquals(List.of(), unresolved, "the crawl sets phases CrawlPipelineStepRegistry.workPhase doesn't"
                + " resolve; map them there and declare any new work phase in JobResourceProfiles.UNIFIED_CRAWL");
    }

    /**
     * Each computed phase is built from phases checked here: the helpers' phase parameter (their callers pass
     * literals), the decomposed passes' scope phase and the partition step's ID. A new computed phase has to
     * be checked the same way before it joins this list.
     */
    @Test
    void everyComputedPhaseTheCrawlSets_isAccountedFor() throws IOException {
        Map<String, Integer> computed = new TreeMap<>();
        for (PhaseArgument argument : phaseArguments()) {
            if (!argument.isLiteral()) {
                computed.merge(argument.file() + " " + argument.call() + "(" + argument.argument() + ")",
                        1, Integer::sum);
            }
        }
        assertEquals(new TreeMap<>(Map.of(
                "EntityPartitionCrawlStep.java getCurrentPhase().set(STEP_ID)", 1,
                "GraphExtractionOrchestrator.java emitDecomposedPassProgress(scopePhase)", 6,
                "GraphExtractionOrchestrator.java getCurrentPhase().set(phase)", 2,
                "UnifiedCrawlGraphServiceImpl.java getCurrentPhase().set(phase)", 1,
                "UnifiedCrawlGraphServiceImpl.java updateProgress(phase)", 1,
                "VectorIndexingHelper.java getCurrentPhase().set(phase)", 1)), computed);
    }

    /** A decomposed pass's scope phase is its step (GRAPH_EXTRACTION or ENTITY_PARTITIONS) plus a suffix. */
    @Test
    void decomposedPassPhases_resolveToTheirStep() throws IOException {
        String orchestrator = Files.readString(
                MAIN_SOURCES.resolve("ai/kompile/crawl/graph/GraphExtractionOrchestrator.java"));
        Matcher suffix = Pattern.compile("\\bphase \\+ \"(_[A-Z][A-Z_]*)\"").matcher(orchestrator);
        int passes = 0;
        while (suffix.find()) {
            passes++;
            for (String step : List.of("GRAPH_EXTRACTION", EntityPartitionCrawlStep.STEP_ID)) {
                assertEquals(step, CrawlPipelineStepRegistry.workPhase(step + suffix.group(1)),
                        step + suffix.group(1));
            }
        }
        assertTrue(passes > 0, "no decomposed pass phases found in GraphExtractionOrchestrator");
    }

    @Test
    void jobDefaultAndPartitionStepPhases_areWorkPhases() {
        assertEquals("QUEUED",
                CrawlPipelineStepRegistry.workPhase(UnifiedCrawlJob.builder().build().getCurrentPhase().get()));
        assertTrue(CrawlPipelineStepRegistry.isWorkPhase(EntityPartitionCrawlStep.STEP_ID));
    }

    private static List<PhaseArgument> phaseArguments() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES), "no sources at " + MAIN_SOURCES.toAbsolutePath());
        List<PhaseArgument> arguments = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                Matcher m = PHASE_ARGUMENT.matcher(source);
                while (m.find()) {
                    int line = 1 + (int) source.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                    arguments.add(new PhaseArgument(file.getFileName().toString(), line, m.group(1), m.group(2)));
                }
            }
        }
        return arguments;
    }
}
