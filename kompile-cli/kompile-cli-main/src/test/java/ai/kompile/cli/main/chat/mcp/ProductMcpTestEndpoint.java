package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory MCP resource exercising the real McpTool adapter, without a child or provider. */
public final class ProductMcpTestEndpoint implements AutoCloseable {
    @FunctionalInterface public interface Call { ToolResult run() throws Exception; }
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final CliTool tool;

    public ProductMcpTestEndpoint(Call call) {
        var schema = new ObjectMapper().createObjectNode().put("type", "object");
        var info = new McpBundleToolLoader.RemoteTool("get_play_connection", "Fixture read", schema);
        var endpoint = new McpBundleToolLoader.McpEndpoint() {
            public void initialize() { }
            public List<McpBundleToolLoader.RemoteTool> listTools() { return List.of(info); }
            public McpBundleToolLoader.RemoteCallResult callTool(String name, JsonNode params) throws Exception {
                if (!open.get()) throw new IllegalStateException("Fixture endpoint closed");
                calls.incrementAndGet();
                ToolResult result = call.run();
                return new McpBundleToolLoader.RemoteCallResult(result.getOutput(), result.isError());
            }
            public void close() { ProductMcpTestEndpoint.this.close(); }
        };
        tool = new McpBundleToolLoader.McpTool("elthoria", info, endpoint);
    }

    public CliTool tool() { return tool; }
    public boolean isOpen() { return open.get(); }
    public int calls() { return calls.get(); }
    public int closes() { return closes.get(); }
    @Override public void close() {
        if (open.compareAndSet(true, false)) closes.incrementAndGet();
    }
}
