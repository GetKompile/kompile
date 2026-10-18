package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ProductContentSessionTest.Fixture;
import ai.kompile.cli.main.chat.tools.ProductManagedWorkTracker;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real PipelineTool/store/lifecycle, only the native model call is a model-free fake. */
class ProductContentPipelineIntegrationTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void pinnedCreateAndRunInvokeRealDelegateAndNativeHostBoundaryExactlyOnce() throws Exception {
        Fixture f = new Fixture(root);
        AtomicInteger hostCalls = new AtomicInteger();
        PipelineTool real = new PipelineTool(mapper, Runnable::run, (workspace, definition, input, progress, chunks) -> {
            hostCalls.incrementAndGet();
            assertEquals(root.toRealPath(), workspace);
            assertEquals("CHAT_MODEL", definition.getProcessor().get("type"));
            assertEquals("codex", definition.getProcessor().get("provider"));
            assertEquals("exact-test-model", definition.getProcessor().get("modelId"));
            assertEquals("pdf", definition.getProcessor().get("operation"));
            assertEquals(f.original.toString(), input.get("filePath"));
            return "{\"schemaVersion\":1,\"candidates\":[]}";
        });
        ToolRegistry registry = ToolRegistry.createProduct(() -> new ToolRegistry(mapper));
        registry.register(real); f.bind(registry);
        var tool = registry.get("pipeline"); var context = f.context(registry); var session = registry.productContentSession();
        assertFalse(tool.execute(f.validate(), context).isError());
        String createApproval = required(tool.execute(f.create(), context)); assertEquals(0, hostCalls.get());
        session.resolveApproval(createApproval, true);
        ToolResult created = tool.execute(f.create(), context); assertFalse(created.isError(), created.getOutput());
        assertSame(created, tool.execute(f.create(), context));
        String runApproval = required(tool.execute(f.run(), context)); assertEquals(0, hostCalls.get());
        session.resolveApproval(runApproval, true); assertEquals(0, hostCalls.get());
        ToolResult run = tool.execute(f.run(), context); assertFalse(run.isError(), run.getOutput());
        assertEquals(1, hostCalls.get(), run.getOutput()); assertSame(run, tool.execute(f.run(), context));
        assertEquals(1, hostCalls.get());
        String runId = mapper.readTree(run.getOutput()).path("runId").asText();
        assertFalse(runId.isBlank()); assertTrue(session.tracker().owns(ProductManagedWorkTracker.PIPELINE, runId));
        assertFalse(tool.execute(mapper.createObjectNode().put("action", "status").put("runId", runId), context).isError());
        assertTrue(tool.execute(mapper.createObjectNode().put("action", "status").put("runId", "other-session-run"), context).isError());
        var test = f.create().put("action", "test"); test.set("input", f.plan.get("pipelineInput"));
        String testApproval = required(tool.execute(test, context)); session.resolveApproval(testApproval, true);
        ToolResult tested = tool.execute(test, context); assertFalse(tested.isError(), tested.getOutput());
        assertEquals(2, hostCalls.get()); assertSame(tested, tool.execute(test, context)); assertEquals(2, hostCalls.get());
        assertTrue(session.tracker().owns(ProductManagedWorkTracker.PIPELINE, mapper.readTree(tested.getOutput()).path("runId").asText()));
        assertTrue(session.tracker().cancelAndAwait(Duration.ofSeconds(1)), "Only the real host worker stop signal proves cleanup");
    }

    private String required(ToolResult result) throws Exception {
        assertTrue(result.isError(), result.getOutput());
        assertEquals(Boolean.TRUE, result.getMetadata().get("productApprovalRequired"), result.getOutput());
        return mapper.readTree(result.getOutput()).path("approvalId").asText();
    }
}
