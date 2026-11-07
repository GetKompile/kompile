/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Regression tests for the textless-final-message handling that backs
 * {@link OpencodeServeManager#prompt}: a turn can end without text because the
 * provider failed mid-loop (the failure rides the assistant message's
 * {@code info.error}) or because the turn used its steps on tool bookkeeping
 * only — the manager must surface the real failure, reconcile the turn's
 * earlier assistant steps, or fail with shape diagnostics instead of a bare
 * "no assistant text".
 */
class OpencodeServeManagerTextlessTurnTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractAssistantTextIgnoresErrorEnvelopeMessages() throws Exception {
        String body = """
                {"info":{"id":"msg_1","role":"assistant","error":{"name":"APIError",
                 "data":{"message":"rate limit exceeded (429)"}}},
                 "parts":[{"type":"text","text":""}]}
                """;
        String text = manager().extractAssistantText(body);
        // No text parts survived: the manager must treat this as blank and
        // route through describeError instead of returning the envelope.
        assertEquals("", text);
    }

    @Test
    void describeErrorReadsTheNamedErrorEnvelope() throws Exception {
        JsonNode apiError = objectMapper.readTree(
                "{\"error\":{\"name\":\"APIError\",\"data\":{\"message\":\"rate limit exceeded (429)\"}}}");
        assertEquals("APIError: rate limit exceeded (429)",
                OpencodeServeManager.describeError(apiError));
        assertEquals("AuthError", OpencodeServeManager.describeError(objectMapper.readTree(
                "{\"error\":{\"name\":\"AuthError\",\"data\":{}}}")));
        assertEquals("unknown failure", OpencodeServeManager.describeError(objectMapper.readTree(
                "{\"error\":{\"data\":{}}}")));
        assertNull(OpencodeServeManager.describeError(objectMapper.readTree("{}")));
        // name-only envelope (aborted turns) and bare message (older shape)
        assertEquals("MessageAbortedError", OpencodeServeManager.describeError(objectMapper.readTree(
                "{\"error\":{\"name\":\"MessageAbortedError\"}}")));
        assertEquals("mined message", OpencodeServeManager.describeError(objectMapper.readTree(
                "{\"error\":{\"message\":\"mined message\"}}")));
    }

    @Test
    void extractAssistantTextByParentJoinsTheTurnsAssistantSteps() throws Exception {
        String listing = """
                [
                  {"info":{"id":"msg_user","role":"user","parentID":"ses_1","time":{"created":1}},
                   "parts":[{"type":"text","text":"extract entities"}]},
                  {"info":{"id":"msg_stale","role":"assistant","parentID":"msg_other_user",
                   "time":{"created":2}},"parts":[{"type":"text","text":"stale earlier turn"}]},
                  {"info":{"id":"msg_a","role":"assistant","parentID":"msg_user",
                   "time":{"created":3}},"parts":[{"type":"text","text":"first thing"}]},
                  {"info":{"id":"msg_broken","role":"assistant","parentID":"msg_user",
                   "time":{"created":4},"error":{"name":"APIError"}},
                   "parts":[{"type":"text","text":"died with its error"}]},
                  {"info":{"id":"msg_b","role":"assistant","parentID":"msg_user",
                   "time":{"created":5}},"parts":[{"type":"text","text":"final answer"}]}
                ]
                """;
        String joined = OpencodeServeManager.extractAssistantTextByParent(
                objectMapper, listing, "msg_user");
        assertEquals("first thing\nfinal answer", joined);
    }

    @Test
    void blankTurnDiagnosticsSummarizeTheMessageShape() throws Exception {
        JsonNode root = objectMapper.readTree("""
                {"info":{"id":"msg_1","role":"assistant","parentID":"msg_user",
                  "finish":"tool-calls","tokens":{"input":13939,"output":4,"reasoning":18}},
                 "parts":[{"type":"step-start"},{"type":"tool","callID":"c1"},{"type":"step-finish"}]}
                """);
        JsonNode info = root.path("info");
        String diagnostic = OpencodeServeManager.blankTurnDiagnostics(root, info);
        assertTrue(diagnostic.contains("finish=tool-calls"), diagnostic);
        assertTrue(diagnostic.contains("tool×1"), diagnostic);
        assertTrue(diagnostic.contains("tokens in=13939 out=4 reasoning=18"), diagnostic);
        assertFalse(diagnostic.contains("null"));
    }

    private OpencodeServeManager manager() {
        return new OpencodeServeManager(objectMapper, null);
    }
}
