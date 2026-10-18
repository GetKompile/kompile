package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ai.kompile.cli.main.chat.testing.TemporaryUserHome
class ToolEvidenceProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String BODY = "literal source evidence, not a success judgment\n".repeat(500);
    private static final String RECEIPT = "[Exact duplicate tool output:";
    private enum Wire { CHAT, RESPONSES, ANTHROPIC, PI }

    @Test
    void allProtocolsKeepOriginalAndNewestEightResultsWithoutMutatingHistory() {
        for (Wire wire : Wire.values()) {
            ArrayNode input = repeated(wire, 12, BODY);
            ArrayNode original = input.deepCopy();
            ToolEvidenceProjection projection = new ToolEvidenceProjection();
            ArrayNode result = projection.project(input);
            assertEquals(original, input, wire.toString());
            assertEquals(input.size(), result.size());
            assertEquals(output(input, wire, 0), output(result, wire, 0));
            for (int i = 1; i < 4; i++) {
                assertTrue(output(result, wire, i).startsWith(RECEIPT), wire + " " + i);
                assertTrue(output(result, wire, i).contains("earlier call \"call-0\""));
                assertTrue(output(result, wire, i).contains("This call still executed"));
                assertEquals(envelope(input, wire, i), envelope(result, wire, i), "only result body may change");
                assertEquals(input.get(i * 2), result.get(i * 2), "call and arguments remain exact");
            }
            for (int i = 4; i < 12; i++) assertEquals(output(input, wire, i), output(result, wire, i));
            assertTrue(result.toString().length() < input.toString().length());
            assertEquals(result, projection.project(input), "stable projection of original history");
            assertEquals(result, projection.project(result), "idempotent on already projected input");
        }
    }

    @Test
    void batchingDefersSmallSavingsAndRetainsCommittedDecisionsBelowNextBatch() {
        ToolEvidenceProjection projection = new ToolEvidenceProjection(1, 3, Long.MAX_VALUE);
        ArrayNode input = repeated(Wire.CHAT, 4, BODY);
        assertEquals(input, projection.project(input), "only two older duplicates: defer");
        pair(input, Wire.CHAT, "call-4", "read", "{}", BODY);
        ArrayNode committed = projection.project(input);
        assertTrue(output(committed, Wire.CHAT, 1).startsWith(RECEIPT));
        assertTrue(output(committed, Wire.CHAT, 3).startsWith(RECEIPT));
        pair(input, Wire.CHAT, "call-5", "read", "{}", BODY);
        ArrayNode next = projection.project(input);
        for (int i = 0; i < 8; i++) assertEquals(committed.get(i), next.get(i), "committed prefix is byte-stable");
        assertEquals(BODY, output(next, Wire.CHAT, 4), "one new duplicate does not churn prefix");
        projection.clear();
        ArrayNode shortHistory = repeated(Wire.CHAT, 4, BODY);
        assertEquals(shortHistory, projection.project(shortHistory), "new history has no inherited decisions");
    }

    @Test
    void aSingleLargeSavingCanCommitWithoutWaitingForCountBatch() {
        ArrayNode input = repeated(Wire.RESPONSES, 3, BODY);
        assertTrue(output(new ToolEvidenceProjection(1, 8, 16_384).project(input), Wire.RESPONSES, 1)
                .startsWith(RECEIPT));
    }

    @Test
    void committedDecisionCannotBypassMissingOrChangedOriginal() {
        ToolEvidenceProjection projection = new ToolEvidenceProjection(1, 1, 1);
        ArrayNode input = repeated(Wire.RESPONSES, 4, BODY);
        assertTrue(output(projection.project(input), Wire.RESPONSES, 1).startsWith(RECEIPT));
        ((ObjectNode) input.get(1)).put("output", "changed earlier evidence");
        ArrayNode changed = projection.project(input);
        assertEquals(BODY, output(changed, Wire.RESPONSES, 1), "next original must now remain inline");
        assertTrue(output(changed, Wire.RESPONSES, 2).contains("earlier call \"call-1\""));
        input.remove(1);
        input.remove(0);
        assertEquals(BODY, projection.project(input).get(1).path("output").asText(), "removed original is not retained by a hash");
    }

    @Test
    void matchingRequiresExactToolArgumentsAndOutputNotJustAHashOrToolName() {
        for (Wire wire : Wire.values()) {
            for (int difference = 0; difference < 3; difference++) {
                ArrayNode input = mapper.createArrayNode();
                pair(input, wire, "original", "read", "{}", BODY);
                pair(input, wire, "different", difference == 0 ? "grep" : "read",
                        difference == 1 ? "{\"file_path\":\"other\"}" : "{}",
                        difference == 2 ? BODY + "changed" : BODY);
                pair(input, wire, "frontier", "read", "{}", "current");
                assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input), wire + " " + difference);
            }
        }
    }

    @Test
    void matchingDoesNotNormalizeWhitespaceInTextualArguments() {
        ArrayNode input = mapper.createArrayNode();
        pair(input, Wire.RESPONSES, "a", "read", "{}", BODY);
        pair(input, Wire.RESPONSES, "b", "read", "{ }", BODY);
        pair(input, Wire.RESPONSES, "c", "read", "{}", "latest");
        assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input));
    }

    @Test
    void uniqueResultsUserInstructionsAssistantTextAndOpaqueItemsRemainVerbatim() {
        ArrayNode input = repeated(Wire.RESPONSES, 12, BODY);
        input.insert(0, mapper.createObjectNode().put("type", "reasoning").put("encrypted_content", "ciphertext"));
        input.addObject().put("type", "compaction").put("encrypted_content", "opaque checkpoint");
        input.addObject().put("role", "assistant").put("content", BODY);
        input.addObject().put("role", "user").put("content", BODY);
        ArrayNode result = new ToolEvidenceProjection().project(input);
        assertEquals(input.get(0), result.get(0));
        for (int i = input.size() - 3; i < input.size(); i++) assertEquals(input.get(i), result.get(i));
        assertEquals(BODY, result.get(2).path("output").asText());
    }

    @Test
    void duplicateAndMissingCallIdsAndResultsFailConservatively() {
        for (int corruption = 0; corruption < 4; corruption++) {
            ArrayNode input = repeated(Wire.RESPONSES, 3, BODY);
            if (corruption == 0) ((ObjectNode) input.get(2)).put("call_id", "call-0");
            if (corruption == 1) ((ObjectNode) input.get(2)).remove("name");
            if (corruption == 2) input.insert(4, input.get(3).deepCopy());
            if (corruption == 3) ((ObjectNode) input.get(3)).put("call_id", "unknown");
            assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input), "corruption " + corruption);
        }
    }

    @Test
    void modelMustHaveActedOnResultAndNewestFrontierIsAlwaysProtected() {
        ArrayNode input = mapper.createArrayNode();
        // Parallel call results, with no subsequent assistant action on either.
        ObjectNode calls = input.addObject().put("role", "assistant");
        ArrayNode toolCalls = calls.putArray("tool_calls");
        for (int i = 0; i < 3; i++) {
            ObjectNode call = toolCalls.addObject().put("id", "p" + i).put("type", "function");
            call.putObject("function").put("name", "read").put("arguments", "{}");
        }
        for (int i = 0; i < 3; i++) input.addObject().put("role", "tool").put("tool_call_id", "p" + i).put("content", BODY);
        assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input));
        input.addObject().put("role", "assistant").put("content", "observed all results");
        ArrayNode result = new ToolEvidenceProjection(1, 1, 1).project(input);
        assertTrue(result.get(2).path("content").asText().startsWith(RECEIPT));
        assertEquals(input.get(3), result.get(3), "newest result remains intact even after a model action");
    }

    @Test
    void errorsSecurityEvidenceSmallAndNontextualBodiesRemainUntouched() {
        for (String body : List.of("small", BODY + "\nBUILD FAILED", BODY + "\nError: failure",
                BODY + "\nCVE-2026-12345", BODY + "\n-----BEGIN PRIVATE KEY-----")) {
            ArrayNode input = repeated(Wire.RESPONSES, 4, body);
            assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input));
        }
        for (Wire wire : Wire.values()) {
            ArrayNode input = repeated(wire, 4, BODY);
            for (int i = 0; i < 4; i++) {
                ObjectNode error = wire == Wire.PI ? (ObjectNode) input.get(i * 2 + 1) : resultNode(input, wire, i);
                error.put(wire == Wire.PI ? "isError" : "is_error", true);
            }
            assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input), wire.toString());
            input = repeated(wire, 4, BODY);
            for (int i = 0; i < 4; i++) {
                ObjectNode node = resultNode(input, wire, i);
                node.putArray(wire == Wire.RESPONSES ? "output" : wire == Wire.PI ? "text" : "content")
                        .addObject().put("type", "image").put("data", BODY);
            }
            assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input));
        }
    }

    @Test
    void mixedPiAndAnthropicPayloadsStayStructuredAndCountTowardFrontier() {
        ArrayNode pi = repeated(Wire.PI, 3, BODY);
        ((ArrayNode) pi.get(3).path("content")).addObject().put("type", "image").put("data", "image");
        assertEquals(pi, new ToolEvidenceProjection(1, 1, 1).project(pi));
        ArrayNode anthropic = repeated(Wire.ANTHROPIC, 3, BODY);
        resultNode(anthropic, Wire.ANTHROPIC, 1).putArray("content").addObject().put("type", "text").put("text", BODY);
        assertEquals(anthropic, new ToolEvidenceProjection(1, 1, 1).project(anthropic));
    }

    @Test
    void opaqueCheckpointIsAnEvidenceBoundaryEvenForPreviouslyCommittedDuplicates() {
        ToolEvidenceProjection projection = new ToolEvidenceProjection(1, 1, 1);
        ArrayNode input = repeated(Wire.RESPONSES, 3, BODY);
        assertTrue(output(projection.project(input), Wire.RESPONSES, 1).startsWith(RECEIPT));
        int barrier = input.size();
        input.addObject().put("type", "compaction").put("encrypted_content", "opaque checkpoint");
        for (int i = 0; i < 3; i++) pair(input, Wire.RESPONSES, "after-" + i, "read", "{}", BODY);
        ArrayNode result = projection.project(input);
        for (int i = 0; i <= barrier; i++) assertEquals(input.get(i), result.get(i));
        assertEquals(input.get(barrier + 2), result.get(barrier + 2), "first post-checkpoint original stays inline");
        assertTrue(result.get(barrier + 4).path("output").asText().contains("earlier call \"after-0\""));
    }

    @Test
    void malformedDuplicateCallDeclarationCannotAuthorizeProjection() {
        for (boolean malformedFirst : List.of(false, true)) {
            ArrayNode input = repeated(Wire.RESPONSES, 3, BODY);
            ObjectNode invalid = mapper.createObjectNode().put("type", "function_call").put("call_id", "call-1");
            input.insert(malformedFirst ? 0 : 4, invalid);
            assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input));
        }
    }

    @Test
    void signedAnthropicCheckpointCannotBorrowPreCheckpointEvidence() {
        ArrayNode input = repeated(Wire.ANTHROPIC, 3, BODY);
        input.addObject().put("role", "assistant").putArray("content").addObject()
                .put("type", "compaction").put("content", "summary").put("signature", "opaque signature");
        pair(input, Wire.ANTHROPIC, "after", "read", "{}", BODY);
        pair(input, Wire.ANTHROPIC, "latest", "read", "{}", BODY);
        assertEquals(input, new ToolEvidenceProjection(1, 1, 1).project(input));
    }

    @Test
    void unsupportedCustomResultsStillOccupyTheProtectedFrontier() {
        ArrayNode input = repeated(Wire.RESPONSES, 3, BODY);
        input.addObject().put("type", "custom_tool_call_output").put("call_id", "custom").put("output", "opaque result");
        input.addObject().put("role", "assistant").put("content", "acted on all results");
        ArrayNode result = new ToolEvidenceProjection(2, 1, 1).project(input);
        assertTrue(output(result, Wire.RESPONSES, 1).startsWith(RECEIPT));
        assertEquals(input.get(5), result.get(5), "latest function result stays protected alongside custom output");
        assertEquals(input.get(6), result.get(6));
    }

    @Test
    void decisionMemoryIsBoundedAndContainsOnlyHashes() throws Exception {
        ToolEvidenceProjection projection = new ToolEvidenceProjection(1, 1, 1);
        projection.project(repeated(Wire.CHAT, 4_110, "e".repeat(1_100)));
        var field = ToolEvidenceProjection.class.getDeclaredField("decisions");
        field.setAccessible(true);
        var decisions = (java.util.Map<?, ?>) field.get(projection);
        assertEquals(4_096, decisions.size());
        for (Object key : decisions.keySet()) assertTrue(key.toString().matches("[0-9a-f]{64}"));
    }

    @Test
    void capacityCannotChangeAnIdenticalCountAndStreamProjection() {
        ArrayNode input = repeated(Wire.RESPONSES, 4_106, "e".repeat(1_100));
        ToolEvidenceProjection projection = new ToolEvidenceProjection();
        ArrayNode counted = projection.project(input);
        assertTrue(output(counted, Wire.RESPONSES, 1).startsWith(RECEIPT));
        assertEquals("e".repeat(1_100), output(counted, Wire.RESPONSES, 4_097), "capacity leaves new candidates inline");
        assertTrue(counted.equals(projection.project(input)), "same history must yield identical streamed input at capacity");
        assertTrue(counted.equals(projection.project(input)), "active commitments cannot be evicted");
    }

    @Test
    void argumentTypesMustMatchTheProtocolAndEachOther() {
        for (Wire wire : Wire.values()) {
            ArrayNode input = repeated(wire, 3, BODY);
            ObjectNode call = (ObjectNode) input.get(2);
            switch (wire) {
                case CHAT -> ((ObjectNode) call.path("tool_calls").get(0).path("function")).putObject("arguments");
                case RESPONSES -> call.putObject("arguments");
                case ANTHROPIC -> ((ObjectNode) call.path("content").get(0)).put("input", "{}");
                case PI -> ((ObjectNode) call.path("content").get(0)).put("arguments", "{}");
            }
            assertTrue(input.equals(new ToolEvidenceProjection(1, 1, 1).project(input)), wire.toString());
        }
        ArrayNode mixed = mapper.createArrayNode();
        pair(mixed, Wire.CHAT, "text-args", "read", "{}", BODY);
        pair(mixed, Wire.ANTHROPIC, "object-args", "read", "{}", BODY);
        pair(mixed, Wire.ANTHROPIC, "frontier", "read", "{}", "latest");
        assertEquals(mixed, new ToolEvidenceProjection(1, 1, 1).project(mixed));
    }

    @Test
    void errorKnowledgeCannotBeEvictedIntoEligibilityAtCapacity() {
        ToolEvidenceProjection projection = new ToolEvidenceProjection(1, 1, 1);
        ArrayNode input = repeated(Wire.CHAT, 3, BODY);
        projection.protectError("call-1");
        assertEquals(input, projection.project(input));
        for (int i = 0; i < 4_097; i++) projection.protectError("other-" + i);
        assertEquals(input, projection.project(input), "overflow fails open to full inline evidence");
        projection.clear();
        assertTrue(output(projection.project(input), Wire.CHAT, 1).startsWith(RECEIPT));
    }

    @Test
    void uniqueArchivedOutputsRetireAcrossProtocolsOnlyWithCurrentExactProof() throws Exception {
        for (Wire wire : Wire.values()) {
            var store = new ai.kompile.cli.main.chat.tools.ToolResultStore("archive-" + wire);
            ArrayNode input = mapper.createArrayNode();
            for (int i = 0; i < 12; i++) {
                String body = "unique result " + i + "\n" + BODY;
                pair(input, wire, "call-" + i, "read", "{}", body);
                assertNotNull(store.save("read", "call-" + i, "{}", body, false));
            }
            ArrayNode original = input.deepCopy();
            ToolEvidenceProjection projection = new ToolEvidenceProjection();
            ArrayNode result = projection.project(input, store);
            for (int i = 0; i < 4; i++) {
                assertTrue(output(result, wire, i).startsWith("[Archived historical result:"), wire + " " + i);
                assertEquals(envelope(input, wire, i), envelope(result, wire, i));
            }
            for (int i = 4; i < 12; i++) assertEquals(output(input, wire, i), output(result, wire, i));
            assertEquals(original, input);
            assertEquals(result, projection.project(input, store));
            java.nio.file.Files.delete(store.getResultFile("call-0"));
            ArrayNode missing = projection.project(input, store);
            assertEquals(output(input, wire, 0), output(missing, wire, 0), "stale decisions cannot hide missing evidence");
        }
    }

    @Test
    void archiveModeNeverLeavesDuplicatePointersToRetiredInlineOriginals() {
        var store = new ai.kompile.cli.main.chat.tools.ToolResultStore("archive-repeated");
        ArrayNode input = repeated(Wire.RESPONSES, 12, BODY);
        for (int i = 0; i < 12; i++) assertNotNull(store.save("read", "call-" + i, "{}", BODY, false));
        ArrayNode result = new ToolEvidenceProjection().project(input, store);
        for (int i = 0; i < 4; i++) {
            assertTrue(output(result, Wire.RESPONSES, i).startsWith("[Archived historical result:"));
            assertFalse(output(result, Wire.RESPONSES, i).contains("earlier call"));
        }
    }

    @Test
    void projectedReceiptsAreNotTreatedAsOriginalEvidence() {
        ArrayNode input = repeated(Wire.RESPONSES, 4, BODY);
        ToolEvidenceProjection projection = new ToolEvidenceProjection(1, 1, 1);
        ArrayNode projected = projection.project(input);
        ((ObjectNode) projected.get(1)).put("output", "original was removed");
        assertEquals(projected, projection.project(projected), "receipts are not raw originals");
    }

    private ArrayNode repeated(Wire wire, int count, String body) {
        ArrayNode input = mapper.createArrayNode();
        for (int i = 0; i < count; i++) pair(input, wire, "call-" + i, "read", "{}", body);
        return input;
    }

    private void pair(ArrayNode input, Wire wire, String id, String name, String args, String body) {
        ObjectNode call = input.addObject();
        ObjectNode result = mapper.createObjectNode();
        switch (wire) {
            case CHAT -> {
                call.put("role", "assistant").put("reasoning_content", "unchanged reasoning");
                call.putArray("tool_calls").addObject().put("id", id).put("type", "function")
                        .putObject("function").put("name", name).put("arguments", args);
                result.put("role", "tool").put("tool_call_id", id).put("content", body);
            }
            case RESPONSES -> {
                call.put("type", "function_call").put("call_id", id).put("name", name).put("arguments", args);
                result.put("type", "function_call_output").put("call_id", id).put("output", body);
            }
            case ANTHROPIC -> {
                call.put("role", "assistant").putArray("content").addObject()
                        .put("type", "tool_use").put("id", id).put("name", name).set("input", parse(args));
                result.put("role", "user").putArray("content").addObject()
                        .put("type", "tool_result").put("tool_use_id", id).put("content", body);
            }
            case PI -> {
                call.put("role", "assistant").putArray("content").addObject()
                        .put("type", "toolCall").put("id", id).put("name", name).set("arguments", parse(args));
                result.put("role", "toolResult").put("toolCallId", id).put("toolName", name)
                        .put("isError", false).put("timestamp", 123L).putArray("content")
                        .addObject().put("type", "text").put("text", body);
            }
        }
        result.put("sentinel", "metadata must remain");
        input.add(result);
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private ObjectNode resultNode(ArrayNode input, Wire wire, int pair) {
        ObjectNode node = (ObjectNode) input.get(pair * 2 + 1);
        return wire == Wire.ANTHROPIC || wire == Wire.PI ? (ObjectNode) node.path("content").get(0) : node;
    }

    private String output(ArrayNode input, Wire wire, int pair) {
        return resultNode(input, wire, pair).path(wire == Wire.RESPONSES ? "output" : wire == Wire.PI ? "text" : "content").asText();
    }

    private ObjectNode envelope(ArrayNode input, Wire wire, int pair) {
        ObjectNode copy = ((ObjectNode) input.get(pair * 2 + 1)).deepCopy();
        ObjectNode node = wire == Wire.ANTHROPIC || wire == Wire.PI ? (ObjectNode) copy.path("content").get(0) : copy;
        node.remove(wire == Wire.RESPONSES ? "output" : wire == Wire.PI ? "text" : "content");
        return copy;
    }
}
