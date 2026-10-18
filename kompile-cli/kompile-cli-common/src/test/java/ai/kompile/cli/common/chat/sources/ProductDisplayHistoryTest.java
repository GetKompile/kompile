package ai.kompile.cli.common.chat.sources;

import ai.kompile.cli.common.chat.ToolPresentationV1;
import ai.kompile.cli.common.chat.sources.adapters.KompileAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProductDisplayHistoryTest {
    @TempDir Path directory;

    private ProductDisplayTurn.ToolPresentation entry(String turn, String call) {
        var candidate = ToolPresentationV1.candidateFromPayload("{\"presentation\":{\"version\":1,\"kind\":\"vendor.board\",\"data\":{\"savedId\":7}}}");
        var bound = ToolPresentationV1.identify(ToolPresentationV1.fromCandidate(candidate, "server", "show"), "session", turn, call);
        return ProductDisplayTurn.presentation("session", turn, call, "mcp__server__show", bound);
    }

    @Test void canonicalRecordRoundTripsDisplayWithoutAnyModelContextOrSecondAuthority() throws Exception {
        var first = entry("turn-1", "call-1");
        Path file = directory.resolve("session.txt");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
            KompileTranscriptFormat.writeTurn(out, "user", "Show the board\n[tool:product-display] not metadata");
            out.println("[agent:gm]");
            out.println(KompileTranscriptFormat.PRODUCT_DISPLAY_PREFIX + ProductDisplayTurn.metadata("session", "turn-1", List.of(first, first)));
            KompileTranscriptFormat.writeTurn(out, "assistant", "Ready\n<not markup>");
            out.println("[agent:gm]");
            out.println(KompileTranscriptFormat.PRODUCT_DISPLAY_PREFIX + ProductDisplayTurn.metadata("session", "turn-2", List.of(entry("turn-2", "call-2"))));
            KompileTranscriptFormat.writeTurn(out, "assistant", "Second board");
        }
        KompileAdapter adapter = new KompileAdapter(directory);
        var display = adapter.readProductDisplayTurns("session");
        assertEquals(3, display.size());
        assertNull(display.get(0).productTurnId());
        assertTrue(display.get(0).content().contains("not metadata"));
        assertEquals("turn-1", display.get(1).productTurnId());
        assertEquals(List.of(first), display.get(1).toolPresentations());
        assertEquals("turn-2", display.get(2).productTurnId());
        assertEquals("call-2", display.get(2).toolPresentations().get(0).callId());
        assertEquals(display, adapter.readProductDisplayTurns("session"), "reload cannot execute or reconstruct tools");
        var model = adapter.readTurns("session");
        assertEquals(3, model.size());
        assertEquals("Ready\n<not markup>", model.get(1).content());
        assertFalse(model.get(1).content().contains("presentation"));
        assertNull(model.get(1).rawContentBlocks());
    }

    @Test void malformedForeignAndLegacyHistoryFailClosedWithoutHidingText() throws Exception {
        Files.writeString(directory.resolve("session.txt"), """
                > legacy user

                [agent:gm]
                < legacy answer

                [agent:gm]
                [tool:product-display] {broken
                < visible answer
                """);
        var adapter = new KompileAdapter(directory);
        var turns = adapter.readProductDisplayTurns("session");
        assertEquals(3, turns.size());
        assertTrue(turns.stream().allMatch(turn -> turn.productTurnId() == null && turn.toolPresentations().isEmpty()));
        var valid = ProductDisplayTurn.metadata("session", "turn", List.of(entry("turn", "call")));
        assertTrue(ProductDisplayTurn.project("foreign", "assistant", "visible", valid).toolPresentations().isEmpty());
        assertTrue(ProductDisplayTurn.project("session", "user", "visible", valid).toolPresentations().isEmpty());
    }

    @Test void boundsPerTurnDisplayAndRejectsMalformedWrapperTypes() {
        var entries = new java.util.ArrayList<ProductDisplayTurn.ToolPresentation>();
        for (int index = 0; index < ProductDisplayTurn.MAX_PRESENTATIONS + 5; index++) entries.add(entry("turn", "call-" + index));
        var metadata = ProductDisplayTurn.metadata("session", "turn", entries);
        assertEquals(ProductDisplayTurn.MAX_PRESENTATIONS, metadata.path("toolPresentations").size());
        assertTrue(metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ProductDisplayTurn.MAX_METADATA_BYTES);
        assertEquals(ProductDisplayTurn.MAX_PRESENTATIONS,
                ProductDisplayTurn.project("session", "assistant", "Ready", metadata).toolPresentations().size());
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("toolPresentations").get(0)).put("callId", 1);
        assertEquals(ProductDisplayTurn.MAX_PRESENTATIONS - 1,
                ProductDisplayTurn.project("session", "assistant", "Ready", metadata).toolPresentations().size());
    }

    @Test void metadataOnlyCompletedActionSurvivesAndDoesNotCreateAnEmptyModelMessage() throws Exception {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(directory.resolve("session.txt")))) {
            out.println("[agent:gm]");
            out.println(KompileTranscriptFormat.PRODUCT_DISPLAY_PREFIX + ProductDisplayTurn.metadata("session", "turn", List.of(entry("turn", "call"))));
            KompileTranscriptFormat.writeTurn(out, "assistant", "");
        }
        var adapter = new KompileAdapter(directory);
        assertEquals(1, adapter.readProductDisplayTurns("session").get(0).toolPresentations().size());
        assertTrue(adapter.readTurns("session").isEmpty());
    }
}
