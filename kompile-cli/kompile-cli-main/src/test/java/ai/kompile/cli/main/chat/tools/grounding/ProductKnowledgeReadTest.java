package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ProductManagedWorkTracker;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the real local reader branch, rather than assuming an injected KB prevents bootstrap. */
@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ProductKnowledgeReadTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @TempDir Path root;

    @Test void explicitMissingKbCannotBootstrapCreateJobsOrInvokeModels() throws Exception {
        AtomicInteger models = new AtomicInteger();
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper, (project, file, pipeline, text) -> {
            models.incrementAndGet(); throw new AssertionError("A read must not enter a model pipeline");
        });
        ToolContext context = productContext();
        Map<String, List<Byte>> before = snapshot();
        String jobs = LocalCrawlJobRegistry.retainedJobs(root, mapper).toString();
        ToolResult search = backend.search("rule", null, "product-kb", 5, context);
        ToolResult status = backend.status("product-kb", context);
        for (ToolResult result : List.of(search, status)) {
            assertTrue(result.isError(), result.getOutput());
            assertEquals("NOT_READY", result.getMetadata().get("status"));
            assertEquals("NATIVE_KNOWLEDGE_NOT_READY", result.getMetadata().get("code"));
        }
        assertTrue(backend.search("rule", null, null, 5, context).isError(), "Omission must not enter default bootstrap");
        assertTrue(backend.status(null, context).isError());
        assertEquals(0, models.get()); assertEquals(before, snapshot());
        assertEquals(jobs, LocalCrawlJobRegistry.retainedJobs(root, mapper).toString());
        assertFalse(Files.exists(root.resolve("data"))); assertFalse(Files.exists(root.resolve("kompile.project.json")));
    }

    @Test void realProductReadsUseOnlyTheDirectCorpusAndNeverWriteVectorsOrDefaults() throws Exception {
        Path selected = writeCorpus("product-kb", "rule player-safe passage");
        writeCorpus("other-kb", "rule private sibling");
        AtomicInteger models = new AtomicInteger();
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(mapper, (project, file, pipeline, text) -> {
            models.incrementAndGet(); throw new AssertionError("No model execution during a product read");
        });
        Map<String, List<Byte>> before = snapshot(); ToolContext context = productContext();
        ToolResult search = backend.search("rule", null, "product-kb", 5, context);
        assertFalse(search.isError(), search.getOutput());
        assertEquals("lexical-only", search.getMetadata().get("retrievalMode"));
        assertTrue(search.getOutput().contains("player-safe passage")); assertFalse(search.getOutput().contains("private sibling"));
        ToolResult status = backend.status("product-kb", context); assertFalse(status.isError(), status.getOutput());
        assertEquals(1, mapper.readTree(status.getOutput()).path("knowledge_base_count").asInt());
        assertFalse(status.getOutput().contains("other-kb")); assertEquals(0, models.get());
        assertEquals(before, snapshot()); assertFalse(Files.exists(selected.resolve("semantic-index.jsonl")));
        assertFalse(Files.exists(root.resolve("kompile.project.json")));
    }

    @Test void productLexicalPathNeverOpensEncoderAndDoesNotMaskCorruptCorpus() throws Exception {
        Path selected = writeCorpus("product-kb", "rule public passage"); AtomicInteger encoders = new AtomicInteger();
        LocalProjectRagSearch rag = new LocalProjectRagSearch(mapper, (project, json) -> {
            encoders.incrementAndGet(); throw new AssertionError("Product lexical reads cannot open an encoder");
        });
        ToolResult result = rag.searchLexical(root, List.of(selected), "rule", null, 5);
        assertFalse(result.isError(), result.getOutput()); assertEquals(0, encoders.get());
        Files.writeString(selected.resolve("chunks.jsonl"), "");
        assertTrue(rag.searchLexical(root, List.of(selected), "rule", null, 5).isError(), "Missing rows are not empty ready results");
        Files.writeString(selected.resolve("chunks.jsonl"), "not-json\n");
        ToolResult failed = rag.searchLexical(root, List.of(selected), "rule", null, 5);
        assertTrue(failed.isError(), "Failed reads must not synthesize empty ready results");
        assertEquals(0, encoders.get()); assertFalse(Files.exists(selected.resolve("semantic-index.jsonl")));
        Files.writeString(selected.resolve("crawl-result.json"), "not-json");
        assertTrue(new LocalProjectCrawlBackend(mapper).status("product-kb", productContext()).isError());
    }

    @Test void productGraphCannotBootstrapOrFallThroughToNumericAliasWhenDirectArchiveIsMissing() throws Exception {
        Path alias = Files.createDirectories(root.resolve("data/crawls/kb-product-kb"));
        new UnifiedGraph().addEntity("foreign", "RULE", "foreign alias rule").save(alias.resolve("graph.kgraph"));
        LocalProjectGraphBackend backend = new LocalProjectGraphBackend(mapper);
        ToolContext context = productContext(); Map<String, List<Byte>> before = snapshot();
        var selected = mapper.createObjectNode().put("operation", "SEARCH").put("question", "rule").put("knowledgeBase", "product-kb");
        Exception missing = assertThrows(Exception.class, () -> backend.reasoningQuery(selected, context));
        assertTrue(missing.getMessage().contains("NATIVE_KNOWLEDGE_NOT_READY"), missing.getMessage());
        var omitted = mapper.createObjectNode().put("operation", "SEARCH").put("question", "rule");
        assertThrows(Exception.class, () -> backend.reasoningQuery(omitted, context));
        ToolResult graphSearch = backend.executeOfflineTool("graph_search", selected.put("query", "rule"), context);
        assertTrue(graphSearch.isError(), graphSearch.getOutput());
        assertFalse(graphSearch.getOutput().contains("foreign alias rule")); assertEquals(before, snapshot());
        assertFalse(Files.exists(root.resolve("data/crawls/product-kb")));
        assertFalse(Files.exists(root.resolve("kompile.project.json")));
    }

    @Test void ordinaryRagStillAttemptsItsEncoderAndRetainsLexicalFallback() throws Exception {
        Path selected = writeCorpus("product-kb", "rule public passage"); AtomicInteger encoders = new AtomicInteger();
        LocalProjectRagSearch rag = new LocalProjectRagSearch(mapper, (project, json) -> {
            encoders.incrementAndGet(); throw new IllegalStateException("fixture encoder unavailable");
        });
        ToolResult result = rag.search(root, List.of(selected), "rule", null, 5);
        assertFalse(result.isError(), result.getOutput()); assertEquals(1, encoders.get());
        assertEquals("lexical-fallback", result.getMetadata().get("retrievalMode"));
    }

    private ToolContext productContext() {
        ToolContext context = new ToolContext("scoped-read-test", AgentConfig.builder("gm").build(),
                new PermissionService(), root, new ToolRegistry(mapper));
        context.linkProductManagedWork(new ProductManagedWorkTracker());
        return context;
    }
    private Path writeCorpus(String kb, String text) throws Exception {
        Path directory = Files.createDirectories(root.resolve("data/crawls").resolve(kb));
        mapper.writeValue(directory.resolve("crawl-result.json").toFile(), Map.of("status", "COMPLETED", "documentCount", 1, "chunkCount", 1));
        Files.writeString(directory.resolve("chunks.jsonl"), mapper.createObjectNode().put("documentId", "doc")
                .put("chunkId", "chunk").put("text", text).toString() + "\n");
        return directory;
    }
    private Map<String, List<Byte>> snapshot() throws Exception {
        Map<String, List<Byte>> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                List<Byte> bytes = new ArrayList<>(); for (byte value : Files.readAllBytes(path)) bytes.add(value);
                files.put(root.relativize(path).toString(), bytes);
            }
        }
        return files;
    }
}
