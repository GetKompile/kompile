package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.custom.CustomToolBridge;
import ai.kompile.cli.main.chat.tools.custom.CustomToolDefinition;
import ai.kompile.cli.main.chat.tools.custom.ExecuteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomToolCancellationTest {

    @Test
    void inheritedCancellationAbortsInFlightHttpTool() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            requestStarted.countDown();
            try {
                releaseResponse.await(10, TimeUnit.SECONDS);
                byte[] body = "late".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ObjectMapper mapper = new ObjectMapper();
        CustomToolDefinition definition = CustomToolDefinition.builder()
                .name("slow_http")
                .description("Slow cancellable request")
                .parameters(mapper.createObjectNode())
                .execute(ExecuteConfig.builder()
                        .type("http")
                        .url("http://127.0.0.1:" + server.getAddress().getPort() + "/slow")
                        .method("GET")
                        .build())
                .timeoutSeconds(30)
                .build();
        PermissionService permissions = new PermissionService();
        ToolContext context = new ToolContext(
                "custom-http-cancel", null, permissions,
                Path.of(".").toAbsolutePath(), null);
        context.setAutoApproveAll(true);
        AtomicBoolean parentCancelled = new AtomicBoolean(false);
        context.linkAbortCheck(parentCancelled::get);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> result = executor.submit(() ->
                    new CustomToolBridge(definition).execute(
                            mapper.createObjectNode(), context));
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            parentCancelled.set(true);

            ToolResult cancelled = result.get(5, TimeUnit.SECONDS);
            assertTrue(cancelled.isError());
            assertTrue(cancelled.getOutput().toLowerCase().contains("aborted"));
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }
}
