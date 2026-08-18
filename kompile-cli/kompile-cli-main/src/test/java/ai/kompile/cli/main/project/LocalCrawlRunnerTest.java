/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCrawlRunnerTest {
    @Test
    void graphContextRoundTripsTheFolderKnowledgeBaseHandoff() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        LocalCrawlRunner.GraphContext expected = new LocalCrawlRunner.GraphContext(
                "finance-kb", "Finance", null, "project-1",
                List.of(new LocalProjectGraphBackend.CodeProjectSource(
                        Path.of("/workspace/project"), "code-project-1", "Project",
                        List.of("**/*.java"), List.of("target/**"))));

        LocalCrawlRunner.GraphContext actual = mapper.treeToValue(
                mapper.valueToTree(expected), LocalCrawlRunner.GraphContext.class);

        assertEquals(expected, actual);
        assertEquals("finance-kb", actual.knowledgeBaseId());
        assertEquals(Path.of("/workspace/project"), actual.codeProjects().get(0).root());
    }

    @Test
    void nativeImageRegistersTheLocalCrawlWireTypes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String resource = "/META-INF/native-image/ai.kompile/kompile-cli/reflect-config.json";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "missing native-image reflection configuration");
            JsonNode config = mapper.readTree(input);
            Set<String> registered = new HashSet<>();
            config.forEach(entry -> registered.add(entry.path("name").asText()));

            Set<String> required = Set.of(
                    "ai.kompile.cli.main.project.LocalCrawlRunner$GraphContext",
                    "ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend$CodeProjectSource",
                    "ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend$GraphUpdate",
                    "ai.kompile.cli.main.project.ProjectCrawlCommand$LocalCrawlFailure",
                    "ai.kompile.core.crawl.graph.GraphExtractionConfig",
                    "ai.kompile.core.crawl.graph.ProcessingRouteConfig",
                    "ai.kompile.core.crawl.graph.ProcessingRouteConfig$ProcessingBackend",
                    "ai.kompile.core.crawl.graph.UnifiedCrawlRequest$RuntimeConfig",
                    "ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition",
                    "ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition$ServingConfig");
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(registered);
            assertTrue(missing.isEmpty(), () -> "Missing native reflection metadata: " + missing);
        }
    }
}
