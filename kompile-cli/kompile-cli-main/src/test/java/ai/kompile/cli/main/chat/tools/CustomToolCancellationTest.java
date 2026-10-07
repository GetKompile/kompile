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
    void customBashCannotBypassTheShellMandate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolContext context = new ToolContext("custom-shell", null, new PermissionService(),
                Path.of(".").toAbsolutePath(), null);
        context.setAutoApproveAll(true);
        for (String command : new String[]{"cat < input.txt", "cd project && sed -n 1p input.txt"}) {
            CustomToolDefinition definition = CustomToolDefinition.builder()
                    .name("shell_fixture").description("Shell fixture").parameters(mapper.createObjectNode())
                    .execute(ExecuteConfig.builder().type("bash").command(command).build())
                    .timeoutSeconds(5).build();
            ToolResult result = new CustomToolBridge(definition).execute(mapper.createObjectNode(), context);
            assertTrue(result.isError());
            assertTrue(result.getOutput().contains("blocked by the kompile tool mandate"), result.getOutput());
        }
        CustomToolDefinition allowed = CustomToolDefinition.builder()
                .name("shell_fixture").description("Shell fixture").parameters(mapper.createObjectNode())
                .execute(ExecuteConfig.builder().type("bash").command("printf MATCH").build())
                .timeoutSeconds(5).build();
        org.junit.jupiter.api.Assertions.assertFalse(
                new CustomToolBridge(allowed).execute(mapper.createObjectNode(), context).isError());
    }

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
