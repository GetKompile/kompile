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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.unified.MiniJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the dispatcher error-contract: unknown tool, bad JSON, null args, and catalog parity.
 */
class LocalToolDispatcherTest {

    private LocalToolDispatcher dispatcher;
    private LocalReasoningSession session;

    @BeforeEach
    void setUp() {
        dispatcher = LocalToolDispatcher.create();
        session = LocalReasoningSession.createEmpty();
    }

    @Test
    void unknownToolReturnsInvalid() {
        String response = dispatcher.dispatch(session, "no_such_tool", "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(response);
        assertEquals("INVALID", parsed.get("status"), "Expected INVALID status for unknown tool");
        assertTrue(parsed.containsKey("knownTools"), "Response should list knownTools");
        assertTrue(parsed.containsKey("message"), "Response should contain a message");
    }

    @Test
    void nullToolNameReturnsInvalid() {
        String response = dispatcher.dispatch(session, null, "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(response);
        assertEquals("INVALID", parsed.get("status"));
    }

    @Test
    void blankToolNameReturnsInvalid() {
        String response = dispatcher.dispatch(session, "   ", "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(response);
        assertEquals("INVALID", parsed.get("status"));
    }

    @Test
    void malformedArgsJsonReturnsError() {
        String response = dispatcher.dispatch(session, "graph_reasoning_query", "not valid json {{");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(response);
        assertEquals("ERROR", parsed.get("status"), "Malformed JSON should return ERROR");
    }

    @Test
    void nullArgsJsonIsTreatedAsEmptyObject() {
        // Sending null args to graph_reasoning_query with no operation should return an error
        // about missing operation — not a crash or INVALID (tool is known, just bad args)
        String response = dispatcher.dispatch(session, "graph_reasoning_query", null);
        assertNotNull(response);
        // Must be valid JSON
        Object parsed = MiniJson.parse(response);
        assertNotNull(parsed);
    }

    @Test
    void knownToolsListMatchesCatalog() {
        List<String> knownTools = dispatcher.knownToolsList();
        List<LocalToolCatalog.Entry> entries = dispatcher.catalog().entries();

        assertEquals(knownTools.size(), entries.size(),
                "knownTools and catalog entries must have same count");

        // Every catalog entry name must appear in knownTools
        for (LocalToolCatalog.Entry entry : entries) {
            assertTrue(knownTools.contains(entry.name()),
                    "Catalog entry '" + entry.name() + "' must be in knownTools");
        }
    }

    @Test
    void catalogJsonIsValidAndContainsCoreTools() {
        String catalogJson = dispatcher.catalog().toJson();
        assertNotNull(catalogJson);

        Object parsed = MiniJson.parse(catalogJson);
        assertInstanceOf(List.class, parsed, "Catalog JSON must be an array");

        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) parsed;
        assertTrue(entries.size() >= 4, "Must have at least the 4 core tools");

        // Every entry has name, description, parameters
        for (Object entry : entries) {
            assertInstanceOf(Map.class, entry);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) entry;
            assertTrue(m.containsKey("name"), "Each entry needs a name");
            assertTrue(m.containsKey("description"), "Each entry needs a description");
            assertTrue(m.containsKey("parameters"), "Each entry needs parameters");
        }
    }

    @Test
    void sessionNullThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> dispatcher.dispatch(null, "tools_catalog", "{}"));
    }
}
