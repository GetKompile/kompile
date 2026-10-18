package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class ToolOutputBudgetTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reservationsSharePressureButNeverStarveAndResetByTimeNotCompaction() {
        AtomicLong clock = new AtomicLong(100);
        ToolOutputBudget budget = new ToolOutputBudget(
                new ToolOutputBudget.Settings(true, 8000, 6000, 2048, 10000, 1000), clock::get);
        try (var first = budget.reserve("owner", true); var parallel = budget.reserve("owner", true)) {
            assertEquals(6000, first.limit());
            assertEquals(4000, parallel.limit());
            first.complete(6000);
            parallel.complete(4000);
        }
        try (var floor = budget.reserve("owner", true)) {
            assertEquals(2048, floor.limit());
            floor.complete(2048);
        }
        try (var isolated = budget.reserve("other", true)) { assertEquals(6000, isolated.limit()); }
        clock.addAndGet(1000);
        try (var reset = budget.reserve("owner", true)) { assertEquals(6000, reset.limit()); }
    }

    @Test
    void smallResponsesRefundReservationAndDisabledPolicyRetainsLegacyLimits() {
        ToolOutputBudget budget = new ToolOutputBudget(ToolOutputBudget.Settings.defaults(), () -> 1);
        for (int i = 0; i < 100; i++) {
            try (var call = budget.reserve("owner", false)) {
                assertEquals(8000, call.limit());
                call.complete(1);
                call.complete(16000); // Idempotent settlement.
            }
        }
        ToolOutputBudget disabled = new ToolOutputBudget(ToolOutputBudget.Settings.legacy(), () -> 1);
        try (var page = disabled.reserve("owner", true)) { assertEquals(16000, page.limit()); }
    }

    @Test
    void projectOverridesOnlySuppliedKeysAndInvalidFileKeepsPriorLayer() throws Exception {
        Path global = Path.of(System.getProperty("user.home"), ".kompile/config");
        Path project = directory.resolve("config");
        Files.createDirectories(global);
        Files.createDirectories(project);
        Files.writeString(global.resolve("tool-output-budget.json"), "{\"inlineChars\":10000,\"fetchChars\":5000}");
        Path config = project.resolve("tool-output-budget.json");
        Files.writeString(config, "{\"fetchChars\":3000}");
        ToolOutputBudget value = ToolOutputBudget.load(directory, () -> 1);
        try (var inline = value.reserve("owner", false); var page = value.reserve("other", true)) {
            assertEquals(10000, inline.limit());
            assertEquals(3000, page.limit());
        }
        for (String bad : new String[]{"[]", "{\"fetchChars\":0}", "{\"enabled\":\"false\"}", "{"}) {
            Files.writeString(config, bad);
            try (var page = ToolOutputBudget.load(directory, () -> 1).reserve("owner", true)) {
                assertEquals(5000, page.limit());
            }
        }
        Files.writeString(config, "{\"enabled\":false}");
        try (var page = ToolOutputBudget.load(directory, () -> 1).reserve("owner", true)) {
            assertEquals(16000, page.limit());
        }
    }

    @Test
    void deliveryAndFetchShareAllowanceAndPreserveFullErrors() throws Exception {
        AtomicLong clock = new AtomicLong(1);
        var settings = new ToolOutputBudget.Settings(true, 8000, 6000, 2048, 10000, 1000);
        var cache = new ToolResultReferenceCache(16000, 60000, settings, clock::get);
        ToolResult ordinary = cache.prepareForDelivery("owner", "grep", ToolResult.success("x".repeat(7000)));
        assertEquals(7000, ordinary.getOutput().length());
        ToolContext context = context("owner");
        String id = cache.store("read", "y".repeat(30000), Map.of());
        ToolResult page = new FetchResultTool(cache).execute(mapper.createObjectNode().put("result_id", id), context);
        assertTrue(page.getOutput().length() <= 3000);
        assertTrue((boolean) page.getMetadata().get("truncated"));
        assertSame(page, cache.prepareForDelivery("owner", "fetch_result", page));
        try (var next = cache.reserveFetch("owner")) {
            assertEquals(2048, next.limit(), "fetch must be charged once, not bypass aggregate pressure");
        }
        String diagnostic = "compile failed\n" + "z".repeat(12000);
        ToolResult bounded = cache.prepareForDelivery("owner", "bash", ToolResult.error(diagnostic));
        assertTrue(bounded.isError());
        assertTrue(bounded.getOutput().length() <= 2048);
        assertTrue(bounded.getOutput().contains("Tool failed"));
        assertEquals(diagnostic, cache.get((String) bounded.getMetadata().get("result_id")).orElseThrow().output());
        clock.addAndGet(1000);
        try (var fresh = cache.reserveFetch("owner")) { assertEquals(6000, fresh.limit()); }
    }

    @Test
    void longUnicodeLineIsRecoverableAcrossShrinkingPagesAndDurableReload() throws Exception {
        Path config = directory.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("tool-output-budget.json"),
                "{\"fetchChars\":6000,\"windowChars\":16384}");
        var cache = new ToolResultReferenceCache(directory);
        String text = "x".repeat(5742) + "😀" + "z".repeat(20000) + "TAIL";
        ToolResult stored = cache.storeAndSummarize("owner", "read", "read", text, Map.of());
        String handle = (String) stored.getMetadata().get("result_id");
        var restarted = new ToolResultReferenceCache(directory);
        var args = mapper.createObjectNode().put("result_id", handle).put("limit", 1).put("offset", 1);
        StringBuilder recovered = new StringBuilder();
        boolean shrank = false;
        for (int i = 0; i < 30; i++) {
            ToolResult page = new FetchResultTool(restarted).execute(args, context("owner"));
            assertFalse(page.isError(), page.getOutput());
            assertTrue(page.getOutput().length() <= 6000);
            String body = page.getOutput().substring(0, page.getOutput().indexOf('\n'));
            assertFalse(!body.isEmpty() && Character.isHighSurrogate(body.charAt(body.length() - 1)));
            recovered.append(body);
            if (!Boolean.TRUE.equals(page.getMetadata().get("truncated"))) break;
            if (body.length() < 3000) shrank = true;
            args.put("offset", ((Number) page.getMetadata().get("nextOffset")).intValue());
            args.put("character_offset", ((Number) page.getMetadata().get("nextCharacterOffset")).intValue());
        }
        assertTrue(shrank);
        assertEquals(text, recovered.toString());
    }

    @Test
    void pressuredBatchDefersSuffixInsteadOfMultiplyingMinimumSliceAllowance() throws Exception {
        var cache = new ToolResultReferenceCache(16000, 60000,
                new ToolOutputBudget.Settings(true, 2048, 2048, 2048, 64000, 300000), () -> 1);
        String id = cache.store("read", "match " + "x".repeat(20000) + "TAIL", Map.of());
        var args = mapper.createObjectNode();
        var requests = args.putArray("requests");
        for (int i = 0; i < 20; i++) requests.addObject().put("result_id", id).put("pattern", "match");
        ToolResult result = new FetchResultBatchTool(cache).execute(args, context("owner"));
        assertTrue(result.getOutput().length() <= 2048, result.getOutput());
        assertEquals(3, result.getMetadata().get("succeeded"));
        assertEquals(17, result.getMetadata().get("deferred"));
        assertEquals(4, result.getMetadata().get("nextRequestIndex"));
        assertTrue(result.getOutput().contains("were NOT read"));
        assertTrue(result.getOutput().contains("character_offset="));
        assertTrue(cache.get(id).orElseThrow().output().endsWith("TAIL"));
    }

    @Test
    void filteredLongLineKeepsMatchingIndexWhilePageBudgetShrinks() throws Exception {
        var cache = new ToolResultReferenceCache(16000, 60000,
                new ToolOutputBudget.Settings(true, 8000, 6000, 2048, 10000, 300000), () -> 1);
        String matched = "needle " + "x".repeat(18000) + "TAIL";
        String id = cache.store("read", "skip\n" + matched + "\nskip", Map.of());
        var args = mapper.createObjectNode().put("result_id", id).put("pattern", "needle").put("offset", 1);
        StringBuilder recovered = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            ToolResult page = new FetchResultTool(cache).execute(args, context("owner"));
            assertTrue(page.getOutput().startsWith("2: "));
            String line = page.getOutput().substring(3, page.getOutput().indexOf('\n'));
            recovered.append(line);
            if (!Boolean.TRUE.equals(page.getMetadata().get("truncated"))) break;
            assertEquals(1, page.getMetadata().get("nextOffset"));
            args.put("character_offset", ((Number) page.getMetadata().get("nextCharacterOffset")).intValue());
        }
        assertEquals(matched, recovered.toString());
    }

    @Test
    @ResourceLock("user.dir")
    void productionCacheConstructorsLoadConfiguredPagesAndPreserveOptOut() throws Exception {
        Path global = Path.of(System.getProperty("user.home"), ".kompile/config");
        Files.createDirectories(global);
        Path file = global.resolve("tool-output-budget.json");
        String prior = Files.exists(file) ? Files.readString(file) : null;
        String priorDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", directory.toString());
            Files.writeString(file, "{\"fetchChars\":3000,\"inlineChars\":4000}");
            // The no-arg path used by socket construction is configured, not the legacy test constructor.
            var cache = new ToolResultReferenceCache();
            String id = cache.store("read", "x".repeat(12000), Map.of());
            ToolResult page = new FetchResultTool(cache).execute(
                    mapper.createObjectNode().put("result_id", id), context("owner"));
            assertTrue(page.getOutput().length() <= 3000);
            ToolResult delivered = cache.prepareForDelivery("owner", "read", ToolResult.success("x".repeat(5000)));
            assertTrue(Boolean.TRUE.equals(delivered.getMetadata().get("cached")));
            assertTrue(delivered.getOutput().length() <= 4000);
            Path project = directory.resolve("config");
            Files.createDirectories(project);
            Files.writeString(project.resolve("tool-output-budget.json"), "{\"enabled\":false}");
            cache.configureOutputBudget(directory); // Socket resolves client working directory before tools are registered.
            ToolResult uncapped = new FetchResultTool(cache).execute(
                    mapper.createObjectNode().put("result_id", id), context("owner"));
            assertTrue(uncapped.getOutput().length() > 3000);
            assertTrue(uncapped.getOutput().length() <= 16000);
        } finally {
            if (priorDirectory == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", priorDirectory);
            if (prior == null) Files.deleteIfExists(file);
            else Files.writeString(file, prior);
        }
    }

    private ToolContext context(String session) {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        return new ToolContext(session, null, permissions, directory, new ToolRegistry(mapper));
    }
}
