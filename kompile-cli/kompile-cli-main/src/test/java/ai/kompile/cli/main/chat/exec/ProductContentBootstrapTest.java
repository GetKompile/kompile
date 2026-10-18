package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.main.chat.tools.ProductManagedWorkTracker;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProductContentBootstrapTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private ProductChatPolicy policy(boolean content, boolean knowledge) throws Exception {
        Files.writeString(root.resolve("gm.md"), "Frozen role");
        Files.writeString(root.resolve("mcp.json"), "{\"mcpServers\":{\"elthoria\":{\"command\":\"never-started\"}}}");
        var value = mapper.createObjectNode().put("schemaVersion", "kompile.product-chat-policy.v1")
                .put("id", "elthoria-play").put("title", "Content test").put("role", "gm")
                .put("roleFile", "gm.md").put("mcpConfigFile", "mcp.json").put("assetsDirectory", "assets")
                .put("idleTimeoutSeconds", 10).put("turnTimeoutSeconds", 10);
        value.putArray("nativeTools").add("pipeline").add("knowledge_status");
        value.putArray("mcpServers").add("elthoria");
        var reads = value.putObject("readCapabilities");
        if (knowledge) reads.put("knowledge", "mcp__elthoria__elthoria_knowledge_workspace");
        if (content) value.put("nativeActionPolicy", "elthoria.content.v1");
        Files.writeString(root.resolve("policy.json"), value.toString());
        return ProductChatPolicy.load(root.resolve("policy.json"), root);
    }
    private ToolRegistry registry(ProductChatPolicy policy) {
        var tools = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        tools.freezeProductPolicy(policy, policy.nativeTools());
        return tools;
    }
    private String metadata(Path workspace) throws Exception {
        Path sources = Files.createDirectories(workspace.resolve("data/input_documents/elthoria"));
        Path crawl = workspace.resolve("data/crawls/content-test-knowledge");
        return mapper.createObjectNode().put("mode", "folder-local").put("workspace", workspace.toString())
                .put("knowledge_base", "content-test-knowledge").put("sources_directory", sources.toString())
                .put("crawl_directory", crawl.toString()).put("graph_path", crawl.resolve("graph.kgraph").toString()).toString();
    }
    @Test void bindsOnlyNamedMetadataWithoutAModelOrUserTurn() throws Exception {
        var policy = policy(true, true); var tools = registry(policy); var calls = new AtomicInteger();
        String scope = metadata(root);
        ProductSessionRuntime.bindContentScope(policy, tools, name -> {
            assertEquals("knowledge", name); calls.incrementAndGet(); return ToolResult.success(scope);
        }, mapper);
        assertEquals(1, calls.get());
        assertEquals(0, tools.productContentSession().pendingApprovals().size());
        assertThrows(IllegalStateException.class, () -> tools.productContentSession().bindKnowledgeScope(mapper.readTree(scope)));
    }
    @Test void missingCapabilityCannotSubstituteAnotherRead() throws Exception {
        var policy = policy(true, false);
        assertThrows(IllegalStateException.class, () -> ProductSessionRuntime.bindContentScope(policy, registry(policy),
                name -> { fail("No substituted tool"); return null; }, mapper));
    }
    @Test void foreignMetadataAndErrorCannotFreezeScope() throws Exception {
        var policy = policy(true, true); String foreign = metadata(Files.createDirectory(root.resolve("foreign")));
        assertThrows(SecurityException.class, () -> ProductSessionRuntime.bindContentScope(policy, registry(policy), name -> ToolResult.success(foreign), mapper));
        assertThrows(IllegalStateException.class, () -> ProductSessionRuntime.bindContentScope(policy, registry(policy), name -> ToolResult.error("unavailable"), mapper));
    }
    @Test void oldProductDoesNotAcquireKnowledgeOrManagedWork() throws Exception {
        var policy = policy(false, false); var tools = registry(policy);
        ProductSessionRuntime.bindContentScope(policy, tools, name -> { fail("Legacy bootstrap unchanged"); return null; }, mapper);
        assertNull(tools.productContentSession());
        assertTrue(HeadlessAgentRunner.drainProductManagedWork(tools, Duration.ZERO));
    }
    @Test void bindingFailureClosesActualRuntimeInputOnceWithoutStartingReaderOrTurn() throws Exception {
        var policy = policy(true, true); var tools = registry(policy);
        String wire = mapper.createObjectNode().put("version", 1).put("action", "session_open")
                .put("requestId", "open").put("sessionId", "test-session").put("policyDigest", policy.digest()) + "\ninvalid-later-frame\n";
        AtomicInteger closes = new AtomicInteger(), readerReads = new AtomicInteger();
        var input = new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read() {
                if (Thread.currentThread().getName().equals("product-session-input")) readerReads.incrementAndGet();
                return super.read();
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        var runtime = ProductSessionRuntime.open(input, policy);
        var events = new ArrayList<HeadlessRunEvent>();
        assertThrows(IllegalStateException.class, () -> runtime.run(tools,
                prompt -> { fail("No model/user turn during failed bootstrap"); return ""; },
                name -> ToolResult.error("knowledge metadata unavailable"), events::add,
                signal -> fail("No turn signal"), () -> fail("No active turn")));
        runtime.close();
        assertEquals(1, closes.get()); assertEquals(0, readerReads.get());
        assertFalse(events.stream().anyMatch(e -> e.type() == HeadlessRunEvent.Type.SESSION_READY
                || e.type() == HeadlessRunEvent.Type.TURN_STARTED));
        var admission = ToolRegistry.class.getDeclaredField("productAdmission"); admission.setAccessible(true);
        assertFalse(admission.getBoolean(tools));
    }

    @Test void resistantWorkerCannotProduceConfirmedCleanup() throws Exception {
        var complete = registry(policy(true, true));
        complete.productContentSession().tracker().register(ProductManagedWorkTracker.PIPELINE, "done", () -> fail("Already stopped"), CompletableFuture.completedFuture(null));
        assertTrue(HeadlessAgentRunner.drainProductManagedWork(complete, Duration.ofMillis(100)));
        var resistant = registry(policy(true, true)); var cancelled = new CountDownLatch(1); var stopped = new CompletableFuture<Void>();
        resistant.productContentSession().tracker().register(ProductManagedWorkTracker.CRAWL, "running", cancelled::countDown, stopped);
        try {
            assertFalse(HeadlessAgentRunner.drainProductManagedWork(resistant, Duration.ofMillis(20)));
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            assertTrue(resistant.hasProductUnknownOutcome());
            assertFalse(resistant.productContentSession().tracker().cleanupConfirmed());
        } finally { stopped.complete(null); }
    }
}
