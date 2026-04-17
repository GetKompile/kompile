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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChatMemory — keyword extraction and utility methods.
 * Tests the static/pure-function aspects that don't require MCP connectivity.
 */
@DisplayName("ChatMemory")
class ChatMemoryTest {

    @Nested
    @DisplayName("Keyword extraction")
    class KeywordExtraction {

        @Test
        void extractsWordsOfThreeOrMoreChars() {
            Set<String> keywords = ChatMemory.extractKeywords("How do I fix the build error?");
            assertTrue(keywords.contains("fix"));
            assertTrue(keywords.contains("build"));
            assertTrue(keywords.contains("error"));
            assertFalse(keywords.contains("do")); // too short
        }

        @Test
        void filtersStopWords() {
            Set<String> keywords = ChatMemory.extractKeywords("what is the best way to handle this");
            assertFalse(keywords.contains("what"));
            assertFalse(keywords.contains("the"));
            assertFalse(keywords.contains("this"));
            assertFalse(keywords.contains("with"));
            assertTrue(keywords.contains("best"));
            assertTrue(keywords.contains("way"));
            assertTrue(keywords.contains("handle"));
        }

        @Test
        void lowercasesAllKeywords() {
            Set<String> keywords = ChatMemory.extractKeywords("Java Spring Maven");
            assertTrue(keywords.contains("java"));
            assertTrue(keywords.contains("spring"));
            assertTrue(keywords.contains("maven"));
        }

        @Test
        void splitsPunctuation() {
            // The regex splits on whitespace, commas, semicolons, periods, etc.
            // Colons are NOT in the split regex, so "error:" stays as one token
            Set<String> keywords = ChatMemory.extractKeywords("NullPointerException at line 42");
            assertTrue(keywords.contains("nullpointerexception"));
            assertTrue(keywords.contains("line"));
            assertFalse(keywords.contains("at")); // too short
        }

        @Test
        void emptyQueryReturnsEmptySet() {
            Set<String> keywords = ChatMemory.extractKeywords("");
            assertTrue(keywords.isEmpty());
        }

        @Test
        void onlyShortWordsReturnsEmpty() {
            Set<String> keywords = ChatMemory.extractKeywords("I am on it");
            assertTrue(keywords.isEmpty());
        }

        @Test
        void onlyStopWordsReturnsEmpty() {
            Set<String> keywords = ChatMemory.extractKeywords("what are they about");
            assertTrue(keywords.isEmpty());
        }

        @Test
        void deduplicatesKeywords() {
            Set<String> keywords = ChatMemory.extractKeywords("error error error");
            assertEquals(1, keywords.size());
            assertTrue(keywords.contains("error"));
        }

        @Test
        void handlesSpecialCharacters() {
            Set<String> keywords = ChatMemory.extractKeywords("What's the [best] {approach}?");
            assertTrue(keywords.contains("best"));
            assertTrue(keywords.contains("approach"));
        }
    }

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        void disabledMemoryReturnsNullPersistentContent() {
            ChatMemory memory = new ChatMemory(null, "session-1", false);
            assertNull(memory.getPersistentMemoryContent());
        }

        @Test
        void disabledMemoryReturnsNullContext() {
            ChatMemory memory = new ChatMemory(null, "session-1", false);
            assertNull(memory.buildMemoryContext("test query"));
        }

        @Test
        void enableDisableToggle() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            assertTrue(memory.isEnabled());
            memory.setEnabled(false);
            assertFalse(memory.isEnabled());
        }

        @Test
        void transcriptSearchToggle() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            assertTrue(memory.isTranscriptSearchEnabled());
            memory.setTranscriptSearchEnabled(false);
            assertFalse(memory.isTranscriptSearchEnabled());
        }

        @Test
        void ragSearchDisabledWithoutMcpClient() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            assertFalse(memory.isRagSearchEnabled());
        }

        @Test
        void persistentMemoryToggle() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            assertTrue(memory.isPersistentMemoryEnabled());
            memory.setPersistentMemoryEnabled(false);
            assertFalse(memory.isPersistentMemoryEnabled());
        }

        @Test
        void disabledPersistentMemoryReturnsNull() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            memory.setPersistentMemoryEnabled(false);
            assertNull(memory.getPersistentMemoryContent());
        }
    }

    @Nested
    @DisplayName("Search with no data")
    class SearchNoData {

        @Test
        void searchTranscriptsReturnsNullForEmptyKeywords() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            assertNull(memory.searchTranscripts("the a")); // all stop words / short
        }

        @Test
        void buildMemoryContextReturnsNullWithNoSources() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            memory.setTranscriptSearchEnabled(false);
            memory.setRagSearchEnabled(false);
            assertNull(memory.buildMemoryContext("test"));
        }

        @Test
        void searchReturnsNoResultsMessage() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            String result = memory.search("nonexistent query xyzzy");
            assertTrue(result.contains("No results found"));
        }
    }

    @Nested
    @DisplayName("Status reporting")
    class StatusReporting {

        @Test
        void statusShowsEnabledState() {
            ChatMemory memory = new ChatMemory(null, "session-1", true);
            String status = memory.getStatus();
            assertTrue(status.contains("enabled"));
            assertTrue(status.contains("Transcript search"));
            assertTrue(status.contains("RAG search"));
        }

        @Test
        void statusShowsDisabledState() {
            ChatMemory memory = new ChatMemory(null, "session-1", false);
            String status = memory.getStatus();
            assertTrue(status.contains("disabled"));
        }
    }
}
