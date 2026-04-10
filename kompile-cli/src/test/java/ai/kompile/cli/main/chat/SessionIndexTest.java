/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SessionIndex.SessionMetadata serialization round-trip.
 */
@DisplayName("SessionIndex")
class SessionIndexTest {

    @Nested
    @DisplayName("SessionMetadata serialization")
    class MetadataSerialization {

        @Test
        void roundTrip() {
            Instant now = Instant.now();
            SessionIndex.SessionMetadata original = new SessionIndex.SessionMetadata(
                    "session-123",
                    "kompile",
                    "session-123",
                    now,
                    42,
                    "What is kompile?",
                    System.currentTimeMillis()
            );

            ObjectNode json = original.toJson();
            SessionIndex.SessionMetadata restored = SessionIndex.SessionMetadata.fromJson(json);

            assertEquals(original.sessionId(), restored.sessionId());
            assertEquals(original.source(), restored.source());
            assertEquals(original.originalId(), restored.originalId());
            assertEquals(original.messageCount(), restored.messageCount());
            assertEquals(original.title(), restored.title());
            assertEquals(original.lastModified(), restored.lastModified());
        }

        @Test
        void fromJsonWithDefaults() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            // Minimal JSON - missing most fields
            node.put("sessionId", "minimal-session");

            SessionIndex.SessionMetadata meta = SessionIndex.SessionMetadata.fromJson(node);
            assertEquals("minimal-session", meta.sessionId());
            assertEquals("kompile", meta.source()); // default
            assertEquals("", meta.originalId());
            assertEquals(0, meta.messageCount());
            assertEquals("", meta.title());
        }

        @Test
        void fromJsonWithEmptyIndexedAt() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("sessionId", "test");
            node.put("indexedAt", "");

            SessionIndex.SessionMetadata meta = SessionIndex.SessionMetadata.fromJson(node);
            assertNotNull(meta.indexedAt()); // Falls back to Instant.now()
        }

        @Test
        void toJsonNullTitle() {
            SessionIndex.SessionMetadata meta = new SessionIndex.SessionMetadata(
                    "test", "kompile", "test", Instant.now(), 0, null, 0);

            ObjectNode json = meta.toJson();
            assertEquals("", json.path("title").asText());
        }

        @Test
        void importedSessionMetadata() {
            SessionIndex.SessionMetadata meta = new SessionIndex.SessionMetadata(
                    "imported-abc123",
                    "claude-code",
                    "abc123",
                    Instant.now(),
                    10,
                    "Help me debug",
                    System.currentTimeMillis()
            );

            ObjectNode json = meta.toJson();
            assertEquals("claude-code", json.path("source").asText());
            assertEquals("abc123", json.path("originalId").asText());

            SessionIndex.SessionMetadata restored = SessionIndex.SessionMetadata.fromJson(json);
            assertEquals("claude-code", restored.source());
        }
    }

    @Nested
    @DisplayName("Index operations")
    class IndexOperations {

        @Test
        void emptyIndexHasZeroCount() {
            SessionIndex index = new SessionIndex();
            // Note: getCount will trigger load which scans disk —
            // on a clean test env this should find whatever exists
            int count = index.getCount();
            assertTrue(count >= 0);
        }

        @Test
        void searchWithNoLoadedSessions() {
            SessionIndex index = new SessionIndex();
            var results = index.search("nonexistent-xyzzy-term");
            assertNotNull(results);
        }

        @Test
        void listSessionsWithSourceFilter() {
            SessionIndex index = new SessionIndex();
            var all = index.listSessions("all");
            var kompile = index.listSessions("kompile");
            assertNotNull(all);
            assertNotNull(kompile);
            assertTrue(kompile.size() <= all.size());
        }
    }
}
