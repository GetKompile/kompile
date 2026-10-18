package ai.kompile.app.services.agent;

import ai.kompile.cli.common.ProductChatPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

/** Cleanup receipt conjunction only: no process, server, model or lease is started. */
class ProductManagedCleanupReceiptTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private ProductChatPolicy policy(boolean content) throws Exception {
        Files.writeString(root.resolve("gm.md"), "Frozen role");
        Files.writeString(root.resolve("mcp.json"), "{\"mcpServers\":{\"elthoria\":{\"command\":\"not-started\"}}}");
        ObjectNode value = mapper.createObjectNode().put("schemaVersion", "kompile.product-chat-policy.v1")
                .put("id", "elthoria-play").put("title", "Cleanup test").put("role", "gm")
                .put("roleFile", "gm.md").put("mcpConfigFile", "mcp.json").put("assetsDirectory", "assets")
                .put("idleTimeoutSeconds", 10).put("turnTimeoutSeconds", 10);
        value.putArray("nativeTools"); value.putArray("mcpServers").add("elthoria");
        value.putObject("readCapabilities").put("knowledge", "mcp__elthoria__elthoria_knowledge_workspace");
        if (content) value.put("nativeActionPolicy", "elthoria.content.v1");
        Files.writeString(root.resolve("policy.json"), value.toString());
        return ProductChatPolicy.load(root.resolve("policy.json"), root);
    }
    private boolean receipt(boolean content, boolean confirmed, Object nativeProof) throws Exception {
        var worker = new KompileCliSessionWorker(mapper, policy(content), "session",
                () -> List.of("never-started"), (args, cwd) -> { throw new AssertionError("No process may start"); });
        Field status = KompileCliSessionWorker.class.getDeclaredField("status"); status.setAccessible(true);
        Field ack = KompileCliSessionWorker.class.getDeclaredField("closeAcknowledged"); ack.setAccessible(true);
        Field pending = KompileCliSessionWorker.class.getDeclaredField("cleanupReceipt"); pending.setAccessible(true);
        Method accept = KompileCliSessionWorker.class.getDeclaredMethod("acceptEvent", com.fasterxml.jackson.databind.JsonNode.class); accept.setAccessible(true);
        try {
            status.set(worker, "closing"); ack.set(worker, true);
            ObjectNode event = mapper.createObjectNode().put("seq", 1).put("type", "session_cleanup");
            ObjectNode data = event.putObject("data").put("confirmed", confirmed);
            if (nativeProof != null) data.set("managedWorkCleanupConfirmed", mapper.valueToTree(nativeProof));
            accept.invoke(worker, event);
            return Boolean.TRUE.equals(((CompletableFuture<?>) pending.get(worker)).getNow(null));
        } finally { status.set(worker, "idle"); worker.close(); }
    }
    @Test void missingFalseOrMalformedNativeProofCannotConfirmContentCleanup() throws Exception {
        assertFalse(receipt(true, true, null));
        assertFalse(receipt(true, true, false));
        assertFalse(receipt(true, true, "true"));
    }
    @Test void bothNativeAndMcpProofsAreRequired() throws Exception {
        assertTrue(receipt(true, true, true));
        assertFalse(receipt(true, false, true));
    }
    @Test void oldProductsRetainTheirOriginalReceiptContract() throws Exception {
        assertTrue(receipt(false, true, null));
        assertFalse(receipt(false, false, null));
    }
}
