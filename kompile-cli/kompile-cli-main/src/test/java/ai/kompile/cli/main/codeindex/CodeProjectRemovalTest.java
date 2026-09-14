package ai.kompile.cli.main.codeindex;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectLifecycleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Comparator;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CodeProjectRemovalTest {
    @TempDir Path root;

    @Test void removalWaitsForWriterAndQueuedWorkCannotResurrectIndex() throws Exception {
        String id = "removal-race-" + UUID.randomUUID();
        Path index = LocalCodeIndexer.getIndexDir(id);
        Files.writeString(root.resolve("Race.java"), "class Race {}\n");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            new LocalCodeIndexer().index(root, id, "*.java", null, quiet);
            LocalCodeKGraphPublisher.publish(root, id, "*.java", null);
            java.util.concurrent.Future<Integer> removal;
            var started = new java.util.concurrent.CountDownLatch(1);
            try (var lock = IndexLockManager.acquireWriteLock(id, index)) {
                removal = executor.submit(() -> {
                    started.countDown();
                    return LocalCodeKGraphPublisher.remove(root, id);
                });
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> removal.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
                assertTrue(Files.isRegularFile(index.resolve("index.db")));
            }
            assertEquals(1, removal.get(10, java.util.concurrent.TimeUnit.SECONDS));
            BackgroundIndexService service = BackgroundIndexService.getInstance();
            var staleJob = service.submitIndexJob(root, id, "*.java", null, true);
            assertTrue(service.awaitJob(staleJob, 10_000));
            assertEquals(BackgroundIndexService.JobStatus.FAILED, staleJob.status());
            assertTrue(staleJob.error().contains("removed"));
            assertFalse(Files.exists(index.resolve("index.db")));
            assertFalse(service.isWatching(id));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            if (Files.exists(index)) try (var paths = Files.walk(index)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @Test void removesLiveProjectionPreservesSnapshotsAndRequiresExplicitRestoration() throws Exception {
        String id = "removal-" + UUID.randomUUID();
        Path index = LocalCodeIndexer.getIndexDir(id);
        Path source = root.resolve("Example.java");
        Files.writeString(source, "class Example {}\n");
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            indexer.index(root, id, "*.java", null, quiet);
            var projected = LocalCodeKGraphPublisher.publish(root, id, "*.java", null);
            UnifiedGraph graph = UnifiedGraph.load(projected.graphPath());
            assertTrue(graph.entities().stream().anyMatch(e -> id.equals(e.attributes().get("codeProjectId"))));
            graph.addEntity(GraphEntity.builder("document").type("DOCUMENT").label("Keep document")
                    .attribute("codeProjectId", id).build()); // matching project id alone is NOT ownership
            graph.addEntity(GraphEntity.builder("other").type("CLASS").label("Other project")
                    .attribute("codeProjectId", "other").attribute("_kompileProjectionOwner", "local-code-index").build());
            graph.addRelation("keep", "document", "other", "DESCRIBES", 1.0);
            graph.saveCompact(projected.graphPath());
            Path snapshot = projected.graphPath().getParent().resolve("before-removal.kgraph");
            Files.copy(projected.graphPath(), snapshot);
            byte[] saved = Files.readAllBytes(snapshot);
            Path second = Files.createDirectories(root.resolve("data/crawls/second")).resolve("graph.kgraph");
            graph.saveCompact(second);

            assertEquals(2, LocalCodeKGraphPublisher.remove(root, id));
            assertFalse(Files.exists(index.resolve("index.db")));
            assertTrue(LocalCodeIndexer.isRemoved(id));
            assertTrue(Files.isRegularFile(index.resolve("project.lock")));
            assertTrue(Files.isRegularFile(source));
            assertArrayEquals(saved, Files.readAllBytes(snapshot));
            for (Path live : new Path[]{projected.graphPath(), second}) {
                UnifiedGraph remaining = UnifiedGraph.load(live);
                assertFalse(remaining.entities().stream().anyMatch(e -> id.equals(e.attributes().get("codeProjectId"))
                        && "local-code-index".equals(e.attributes().get("_kompileProjectionOwner"))));
                assertTrue(remaining.entities().stream().anyMatch(e -> "document".equals(e.id())));
                assertTrue(remaining.relations().stream().anyMatch(e -> "keep".equals(e.id())));
                assertEquals(true, remaining.meta().get("learning.reasoningStale"));
            }
            assertEquals(KompileProjectLifecycleState.ARCHIVED,
                    new KompileProjectStore().load(root).getCodingProjects().get(0).getLifecycle());
            assertThrows(Exception.class, () -> new LocalCodeIndexer().index(root, id, "*.java", null, true, quiet));
            assertThrows(Exception.class, () -> LocalCodeKGraphPublisher.publish(root, id, null, null));
            assertEquals(0, LocalCodeKGraphPublisher.remove(root, id));
            LocalCodeKGraphPublisher.prepareExplicitIndex(root, id);
            assertFalse(LocalCodeIndexer.isRemoved(id));
            indexer.index(root, id, "*.java", null, quiet);
            var restored = LocalCodeKGraphPublisher.publish(root, id, "*.java", null);
            assertEquals(projected.graphPath(), restored.graphPath());
            assertTrue(UnifiedGraph.load(restored.graphPath()).entities().stream().anyMatch(e -> "Example".equals(e.label())));
        } finally {
            if (Files.exists(index)) try (var paths = Files.walk(index)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
