package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.ProductChatSessionService;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.adapters.KompileAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductTranscriptControllerTest {
    @Test void readsOnlyTheBoundConversationWithoutFolderRegistration() throws Exception {
        ProductChatSessionService products = mock(ProductChatSessionService.class);
        KompileAdapter transcripts = mock(KompileAdapter.class);
        when(products.sessionId()).thenReturn("owned-chat");
        when(transcripts.readProductDisplayTurns("owned-chat")).thenReturn(List.of(
                new ai.kompile.cli.common.chat.sources.ProductDisplayTurn("USER", "hello", null, List.of())));
        var query = new LinkedMultiValueMap<String, String>();
        query.add("sessionId", "owned-chat"); query.add("workingDirectory", "/owned");
        var result = new ProductTranscriptController(products, transcripts).transcript(query);
        verify(products).validateIdentity("owned-chat", "/owned", null);
        assertEquals("owned-chat", result.sessionId());
        assertEquals("hello", result.turns().get(0).content());
        assertFalse(result.displayHistoryLimited());
    }
    @Test void displayUsesValidatedIdentityAndDeduplicatesCanonicalCallsWithoutRawResults() throws Exception {
        var candidate = ai.kompile.cli.common.chat.ToolPresentationV1.candidateFromPayload(
                "{\"presentation\":{\"version\":1,\"kind\":\"vendor.board\",\"data\":{\"savedId\":7}}}");
        var sourced = ai.kompile.cli.common.chat.ToolPresentationV1.fromCandidate(candidate, "server", "show");
        var bound = ai.kompile.cli.common.chat.ToolPresentationV1.identify(sourced, "owned", "turn", "call");
        var entry = ai.kompile.cli.common.chat.sources.ProductDisplayTurn.presentation("owned", "turn", "call", "mcp__server__show", bound);
        var turn = new ai.kompile.cli.common.chat.sources.ProductDisplayTurn("assistant", "Ready", "turn", List.of(entry));
        var result = ProductTranscriptController.displayProduct("owned", List.of(turn, turn));
        assertEquals(1, result.turns().stream().mapToInt(item -> item.toolPresentations().size()).sum());
        assertEquals(bound, result.turns().get(1).toolPresentations().get(0).presentation());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var wire = mapper.valueToTree(result);
        assertEquals(java.util.Set.of("sessionId", "turns", "displayHistoryLimited"),
                mapper.convertValue(wire, java.util.Map.class).keySet());
        assertFalse(wire.toString().contains("rawInput"));
        assertFalse(wire.toString().contains("rawContentBlocks"));
        assertTrue(ProductTranscriptController.displayProduct("foreign", List.of(turn)).turns().get(0).toolPresentations().isEmpty());
        var legacy = mapper.valueToTree(new ProductTranscriptController.DisplayTurn("assistant", ""));
        assertTrue(legacy.has("content")); assertFalse(legacy.has("toolPresentations")); assertFalse(legacy.has("productTurnId"));
    }

    @Test void rejectsForeignOrDuplicateIdentitiesBeforeReadingStorage() {
        ProductChatSessionService products = mock(ProductChatSessionService.class);
        KompileAdapter transcripts = mock(KompileAdapter.class);
        var controller = new ProductTranscriptController(products, transcripts);
        var query = new LinkedMultiValueMap<String, String>();
        query.add("sessionId", "other"); query.add("workingDirectory", "/owned");
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST))
                .when(products).validateIdentity("other", "/owned", null);
        assertThrows(ResponseStatusException.class, () -> controller.transcript(query));
        query.add("sessionId", "owned-chat");
        assertThrows(ResponseStatusException.class, () -> controller.transcript(query));
        verifyNoInteractions(transcripts);
    }
    @Test void boundsDisplayOnlyAndExplicitlyMarksOmittedHistory() {
        var source = new ArrayList<ChatTurn>();
        for (int index = 0; index < 201; index++) source.add(new ChatTurn("USER", "turn-" + index, null, null));
        var result = ProductTranscriptController.display("owned", source);
        assertTrue(result.displayHistoryLimited());
        assertEquals(201, source.size());
        assertTrue(result.turns().get(0).content().contains("canonical transcript"));
        assertEquals("turn-200", result.turns().get(result.turns().size() - 1).content());
        var longTurn = new ChatTurn("ASSISTANT", "x".repeat(100000), null, null);
        var bounded = ProductTranscriptController.display("owned", List.of(longTurn));
        assertTrue(bounded.displayHistoryLimited());
        assertEquals(100000, longTurn.content().length());
        assertTrue(bounded.turns().get(1).content().length() < 33000);
    }
}
