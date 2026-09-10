/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.generation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphGenerationContextTest {

    @Test
    void nestedScopesRestoreWithoutLeakingGenerationToWorkerThreads() throws Exception {
        GraphGeneration.Ref outer = ref(42L, "outer");
        GraphGeneration.Ref inner = ref(42L, "inner");

        try (var ignored = GraphGenerationContext.open(outer)) {
            assertEquals("factsheet_42~gen~outer", GraphGenerationContext.resolve(42L, "factsheet_42"));
            assertEquals("factsheet_other", GraphGenerationContext.resolve(42L, "factsheet_other"));
            AtomicReference<String> child = new AtomicReference<>();
            Thread worker = new Thread(() -> child.set(
                    GraphGenerationContext.resolve(42L, "factsheet_42")));
            worker.start();
            worker.join();
            assertEquals("factsheet_42", child.get());
            try (var nested = GraphGenerationContext.open(inner)) {
                assertEquals("factsheet_42~gen~inner", GraphGenerationContext.resolve(42L, "factsheet_42"));
            }
            assertEquals("factsheet_42~gen~outer", GraphGenerationContext.resolve(42L, "factsheet_42"));
        }
        assertTrue(GraphGenerationContext.current().isEmpty());
    }

    @Test
    void explicitlyWrappedTaskUsesGenerationWithoutLeakingIntoReusedPoolThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            GraphGeneration.Ref generation = ref(42L, "pool");
            String scoped;
            try (var ignored = GraphGenerationContext.open(generation, "job-42")) {
                scoped = pool.submit(GraphGenerationContext.wrapCurrent(() ->
                        GraphGenerationContext.resolve(42L, "factsheet_42"))).get();
            }
            String after = pool.submit(() ->
                    GraphGenerationContext.resolve(42L, "factsheet_42")).get();

            assertEquals("factsheet_42~gen~pool", scoped);
            assertEquals("factsheet_42", after);
        } finally {
            pool.shutdownNow();
        }
    }

    private static GraphGeneration.Ref ref(long factSheetId, String generationId) {
        String logical = "factsheet_" + factSheetId;
        return new GraphGeneration.Ref(factSheetId, logical,
                logical + "~gen~" + generationId, generationId, logical, 0L);
    }
}
