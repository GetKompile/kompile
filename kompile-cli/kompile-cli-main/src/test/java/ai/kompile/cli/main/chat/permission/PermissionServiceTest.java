package ai.kompile.cli.main.chat.permission;

import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void jlineBridgeRoutesTheNextInputLineToABackgroundPermissionRequest() throws Exception {
        PermissionService service = new PermissionService();
        service.setUserOverride("knowledge_graph", PermissionService.PermissionLevel.ASK);
        CountDownLatch prompted = new CountDownLatch(1);
        AtomicReference<PermissionService.PermissionPrompt> seen = new AtomicReference<>();
        service.setPromptListener(prompt -> {
            seen.set(prompt);
            prompted.countDown();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PermissionService.PermissionResult> result = executor.submit(
                    () -> service.check(null, "knowledge_graph", "Knowledge graph operation"));

            assertTrue(prompted.await(2, TimeUnit.SECONDS));
            assertTrue(service.hasPendingPrompt());
            assertEquals("knowledge_graph", seen.get().permissionKey());
            assertTrue(service.submitPromptResponse("y"));
            assertEquals(PermissionService.PermissionResult.ASKED_AND_ALLOWED,
                    result.get(2, TimeUnit.SECONDS));
            assertFalse(service.hasPendingPrompt());
        } finally {
            service.cancelPendingPrompts();
            executor.shutdownNow();
        }
    }

    @Test
    void explicitNoIsRejectedByToolContext() {
        PermissionService service = new PermissionService();
        service.setUserOverride("graph_simulate", PermissionService.PermissionLevel.ASK);
        service.setPromptListener(prompt -> assertTrue(service.submitPromptResponse("n")));
        ToolContext context = new ToolContext("permission-test", null, service, tempDir, null);

        assertThrows(ToolExecutionException.class,
                () -> context.checkPermission("graph_simulate", "Graph simulation"));
    }

    @Test
    void allowForSessionAppliesToLaterChecksWithoutPromptingAgain() {
        PermissionService service = new PermissionService();
        service.setUserOverride("crawl_source", PermissionService.PermissionLevel.ASK);
        service.setPromptListener(prompt -> assertTrue(service.submitPromptResponse("a")));

        assertEquals(PermissionService.PermissionResult.ASKED_AND_ALLOWED,
                service.check(null, "crawl_source", "Crawl a source"));
        service.setPromptListener(prompt -> {
            throw new AssertionError("session permission should avoid a second prompt");
        });
        assertEquals(PermissionService.PermissionResult.ALLOWED,
                service.check(null, "crawl_source", "Crawl a source"));
    }

    @Test
    void allMcpToolKeysAreAllowedByDefault() {
        PermissionService service = new PermissionService();

        assertEquals(PermissionService.PermissionResult.ALLOWED,
                service.check(null, "crawl_discover", "Discover crawl capabilities"));
        assertEquals(PermissionService.PermissionResult.ALLOWED,
                service.check(null, "knowledge_status", "Check knowledge base status"));
        assertEquals(PermissionService.PermissionLevel.ALLOW,
                service.getEffectiveLevel(null, "knowledge_graph"));
        assertEquals(PermissionService.PermissionLevel.ALLOW,
                service.getEffectiveLevel(null, "external_directory"));
        assertEquals(PermissionService.PermissionLevel.ALLOW,
                service.getEffectiveLevel(null, "future_tool_key"));
    }

    @Test
    void allowAllBypassesExplicitRulesAndResetRestoresPermissiveDefault() {
        PermissionService service = new PermissionService();
        service.setUserOverride("future_tool_key", PermissionService.PermissionLevel.DENY);
        assertEquals(PermissionService.PermissionResult.DENIED,
                service.check(null, "future_tool_key", "Future tool"));

        service.allowAll();
        assertEquals(PermissionService.PermissionResult.ALLOWED,
                service.check(null, "future_tool_key", "Future tool"));

        service.resetSessionOverrides();
        assertEquals(PermissionService.PermissionLevel.ALLOW,
                service.getEffectiveLevel(null, "future_tool_key"));
    }

    @Test
    void toolContextAutoApproveFlagBypassesPermissionService() throws Exception {
        PermissionService service = new PermissionService();
        service.setUserOverride("knowledge_graph", PermissionService.PermissionLevel.DENY);
        ToolContext context = new ToolContext("permission-test", null, service, tempDir, null);
        context.setAutoApproveAll(true);

        context.checkPermission("knowledge_graph", "Knowledge graph operation");
    }
}
