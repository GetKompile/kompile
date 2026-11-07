/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.incidents.IncidentStore;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory recall runs on the turn thread before the model is called, so its cost
 * is dead time after the user presses Enter.
 */
@TemporaryUserHome
class ChatMemoryTranscriptSearchTest {
    @TempDir Path project;

    /** Counts transcript searches so a skipped search is observable. */
    private static final class CountingMemory extends ChatMemory {
        int searches;

        CountingMemory(Path workDir) {
            super(null, "current-session", true, workDir);
        }

        @Override
        public String searchTranscripts(String query) {
            searches++;
            return super.searchTranscripts(query);
        }
    }

    private static void seedTranscript(String sessionId, String user, String assistant) throws Exception {
        ChatHistory history = new ChatHistory(sessionId);
        history.open("local", "kompile", false);
        history.logUserMessage(user);
        history.logAgentResponse("kompile", assistant, 10);
        history.close();
    }

    @Test void transcriptSearchIsSkippedWhenLeadingSectionsFillTheBudget() throws Exception {
        seedTranscript("filled-budget-session", "dispatcher stall investigation", "found the dispatcher stall");
        // MEMORY.md alone is selected to its own budget and can no longer fill the
        // envelope; a full incident section beside it does.
        ObjectNode incident = JsonUtils.standardMapper().createObjectNode().put("title", "Dispatcher stall")
                .put("symptom", "Turns hang").put("invariant", "i".repeat(3000));
        new IncidentStore(project).mutate("add", null, 0, incident, null, "seed");
        Path dir = project.resolve(".kompile/memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MEMORY.md"), "m".repeat(10000));

        CountingMemory memory = new CountingMemory(project);
        String context = memory.buildMemoryContext("dispatcher stall");

        assertNotNull(context);
        assertFalse(context.contains("[Previous conversations]"), context);
        assertEquals(0, memory.searches, "a search whose output is truncated away must not run");
    }

    @Test void transcriptsAreStillRecalledWhenTheBudgetHasRoom() throws Exception {
        seedTranscript("roomy-budget-session", "dispatcher stall investigation", "found the dispatcher stall");

        CountingMemory memory = new CountingMemory(project);
        String context = memory.buildMemoryContext("dispatcher stall");

        assertEquals(1, memory.searches);
        assertNotNull(context);
        assertTrue(context.contains("[Previous conversations]"), context);
        assertTrue(context.contains("dispatcher stall investigation"), context);
    }

    @Test void longQueriesKeepOnlyTheLongestKeywords() {
        Set<String> keywords = IntStream.range(0, 200)
                .mapToObj(i -> "kw" + "x".repeat(i % 50) + i)
                .collect(Collectors.toSet());

        List<String> kept = ChatMemory.boundedKeywords(keywords);

        assertEquals(ChatMemory.MAX_QUERY_KEYWORDS, kept.size());
        Set<String> keptWords = Set.copyOf(kept);
        int shortestKept = keptWords.stream().mapToInt(String::length).min().orElseThrow();
        int longestDropped = keywords.stream().filter(k -> !keptWords.contains(k))
                .mapToInt(String::length).max().orElseThrow();
        assertTrue(shortestKept >= longestDropped);
    }

    @Test void wholeWordCheckMatchesRegexWordBoundaries() {
        String[] texts = {"stall", "a stall here", "stalls only", "prestall", "x_stall", "stall_y",
                "stall9", "(stall)", "stalls then stall.", "", "sta", "über-stall", "éstall"};
        for (String text : texts) {
            int first = text.indexOf("stall");
            boolean expected = Pattern.compile("\\bstall\\b").matcher(text).find();
            boolean actual = first >= 0 && ChatMemory.containsWord(text, "stall", first);
            assertEquals(expected, actual, text);
        }
    }
}
