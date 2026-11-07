package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Detect stale host output masking the extracted core during downstream tests. */
class StructuralCoreClasspathTest {
    @Test
    void structuralClassesHaveOneDefinitionOwnedByCore() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        for (String name : List.of("BlendedCodeSearch", "CloneDetector", "CoChangeAnalyzer", "CodeGraphIdentity",
                "CodeGraphReasoningConfig", "CodeIndexDiagnostics", "CodeRelevanceRanker", "CodeTokenizer",
                "ComplexityClassifier", "GitCommandOutput", "GitSignals", "ImpactAnalyzer", "IndexAutoRefresher",
                "IndexDatabase", "IndexFileStore", "IndexFileWatcher", "IndexHealthScorer", "IndexLockManager",
                "IndexMaintenance", "IntentClassifier", "LocalCodeIndexer", "LocalRelationExtractor", "PageRankComputer",
                "PathPatterns", "ProjectIdResolver", "SignatureExtractor", "SpathParser", "SpathResolver",
                "SplanPlanParser", "UnusedExportDetector", "../chat/tools/SearchExclusions")) {
            String fqn = name.startsWith("../") ? "ai.kompile.cli.main.chat.tools.SearchExclusions"
                    : "ai.kompile.cli.main.codeindex." + name;
            var definitions = Collections.list(loader.getResources(fqn.replace('.', '/') + ".class"));
            assertEquals(1, definitions.size(), () -> "Duplicate/missing core class " + fqn + ": " + definitions);
            Class<?> type = Class.forName(fqn, false, loader);
            assertTrue(type.getProtectionDomain().getCodeSource().getLocation().toString()
                    .contains("kompile-code-index-core"), () -> "Core class is still host-owned: " + fqn);
        }
    }

    @Test
    void lifecycleAndGraphPublicationRemainHostOwned() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        var hostLocation = Class.forName("ai.kompile.cli.main.MainCommand", false, loader)
                .getProtectionDomain().getCodeSource().getLocation();
        for (String name : List.of("BackgroundIndexService", "FileContextService", "LocalCodeKGraphPublisher",
                "CodeIndexCommand", "CodeIndexGraphCommand", "CodeIndexSessionBrief", "CodeSearchEngine")) {
            Class<?> type = Class.forName("ai.kompile.cli.main.codeindex." + name, false, loader);
            assertEquals(hostLocation, type.getProtectionDomain().getCodeSource().getLocation(), name);
        }
    }
}
