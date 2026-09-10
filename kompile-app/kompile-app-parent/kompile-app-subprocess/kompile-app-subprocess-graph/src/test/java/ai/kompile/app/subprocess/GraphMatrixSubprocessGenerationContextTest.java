/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.subprocess;

import ai.kompile.knowledgegraph.generation.GraphGenerationCoordinator;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMatrixSubprocessGenerationContextTest {

    @TempDir
    Path tempDir;

    @Test
    void subprocessContextOwnsOneEnabledGenerationCoordinator() throws Exception {
        Map<String, String> previous = setProperties(Map.of(
                "kompile.data.dir", tempDir.toString(),
                "kompile.vectorstore.anserini.index-path", tempDir.resolve("vectors").toString(),
                "anserini.indexPath", tempDir.resolve("keyword").toString(),
                "kompile.subprocess.mode", "false",
                "kompile.vectorstore.anserini.enabled", "false",
                "kompile.vectorstore.anserini.persistence-enabled", "false",
                "kompile.graph.generations.subprocess-authority", "true",
                "kompile.graph.generations.enabled", "true",
                "kompile.graph.eager-rehydration-enabled", "false"));
        AnnotationConfigApplicationContext context = null;
        try {
            Method createContext = GraphMatrixSubprocessMain.class.getDeclaredMethod("createContext");
            createContext.setAccessible(true);
            context = (AnnotationConfigApplicationContext) createContext.invoke(null);

            assertEquals(1, context.getBeansOfType(GraphGenerationCoordinator.class).size());
            assertTrue(context.getBean(KnowledgeGraphService.class).supportsGraphGenerations());
        } finally {
            if (context != null) context.close();
            restoreProperties(previous);
        }
    }

    @Test
    void nonAuthoritativeSubprocessStillServesOrdinaryGraphOperations() throws Exception {
        Map<String, String> previous = setProperties(Map.of(
                "kompile.data.dir", tempDir.resolve("reader").toString(),
                "kompile.vectorstore.anserini.index-path", tempDir.resolve("reader-vectors").toString(),
                "anserini.indexPath", tempDir.resolve("reader-keyword").toString(),
                "kompile.subprocess.mode", "false",
                "kompile.vectorstore.anserini.enabled", "false",
                "kompile.vectorstore.anserini.persistence-enabled", "false",
                "kompile.graph.generations.subprocess-authority", "false",
                "kompile.graph.generations.enabled", "false",
                "kompile.graph.eager-rehydration-enabled", "false"));
        AnnotationConfigApplicationContext context = null;
        try {
            Method createContext = GraphMatrixSubprocessMain.class.getDeclaredMethod("createContext");
            createContext.setAccessible(true);
            context = (AnnotationConfigApplicationContext) createContext.invoke(null);

            assertTrue(context.getBeansOfType(GraphGenerationCoordinator.class).isEmpty());
            assertTrue(context.getBean(KnowledgeGraphService.class).getNode("missing").isEmpty());
        } finally {
            if (context != null) context.close();
            restoreProperties(previous);
        }
    }

    private static Map<String, String> setProperties(Map<String, String> values) {
        Map<String, String> previous = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        return previous;
    }

    private static void restoreProperties(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }
}
