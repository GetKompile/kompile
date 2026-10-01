/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The initialize brief: the status line it builds for each resolver outcome, when it
 * asks for a session-start refresh, and that it falls back to the guidance alone.
 */
@TemporaryUserHome
class CodeIndexSessionBriefTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-01T12:30:00Z");
    private static final String REINDEX =
            "index metadata unreadable - run local_code_index action=index from the repository root.";

    @TempDir Path repo;

    private static Path home() {
        return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    }

    private static String uniqueId() {
        return "session-brief-test-" + System.nanoTime();
    }

    private static ProjectIdResolver.Resolution resolved(String id, String source) {
        return new ProjectIdResolver.Resolution(id, source, true);
    }

    private static ObjectNode metadata(String rootPath) {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("rootPath", rootPath);
        metadata.put("filesProcessed", 42);
        metadata.put("indexedAt", "2026-10-01T10:00:00.123Z");
        return metadata;
    }

    private static void writeMetadata(String id, String content) throws IOException {
        Path dir = LocalCodeIndexer.getIndexDir(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("metadata.json"), content);
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("resolver was not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void composeServesTheGuidanceAloneWithoutALine() {
        assertEquals(CodeIndexSessionBrief.GUIDANCE, CodeIndexSessionBrief.compose(null));
        assertEquals(CodeIndexSessionBrief.GUIDANCE, CodeIndexSessionBrief.compose("  "));
        assertEquals(CodeIndexSessionBrief.GUIDANCE + "\n\nCode index: line.",
                CodeIndexSessionBrief.compose("Code index: line."));
    }

    @Test
    void composeCapsTheWholeText() {
        String composed = CodeIndexSessionBrief.compose("x".repeat(CodeIndexSessionBrief.MAX_CHARS));

        assertEquals(CodeIndexSessionBrief.MAX_CHARS, composed.length());
        assertTrue(composed.startsWith(CodeIndexSessionBrief.GUIDANCE + "\n\nxxx"));
        assertTrue(composed.endsWith("x..."));
    }

    @Test
    void describeReportsAnIndexedProject() {
        String id = uniqueId();

        assertEquals("Code index: project_id=" + id + " (resolved via project-manifest), root " + repo
                        + ", 42 files, last indexed 2026-10-01T10:00:00Z (2h ago).",
                CodeIndexSessionBrief.describe(repo, home(), resolved(id, "project-manifest"),
                        metadata(repo.toString()), NOW));
    }

    @Test
    void describeDropsARootThatWouldOverflowTheCap() {
        String id = uniqueId();

        assertEquals("Code index: project_id=" + id + " (resolved via registration), 42 files, "
                        + "last indexed 2026-10-01T10:00:00Z (2h ago).",
                CodeIndexSessionBrief.describe(repo, home(), resolved(id, "registration"),
                        metadata("/" + "r".repeat(CodeIndexSessionBrief.MAX_CHARS)), NOW));
    }

    @Test
    void describeReportsAnUnfinishedUpdate() throws IOException {
        String id = uniqueId();
        Path dir = LocalCodeIndexer.getIndexDir(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("update.pending"), "");

        String line = CodeIndexSessionBrief.describe(repo, home(), resolved(id, "index-root"),
                metadata(repo.toString()), NOW);

        assertTrue(line.endsWith("(2h ago), health update pending (in progress or interrupted)."), line);
    }

    @Test
    void describeWithoutMetadataNamesTheNextStep() {
        assertEquals("Code index: project_id=p (resolved via registration); " + REINDEX,
                CodeIndexSessionBrief.describe(repo, home(), resolved("p", "registration"), null, NOW));
        assertEquals("Code index: project_id=p (resolved via registration-unindexed): "
                        + CodeIndexSessionBrief.NO_INDEX + ".",
                CodeIndexSessionBrief.describe(repo, home(), resolved("p", "registration-unindexed"), null, NOW));
        assertEquals("Code index: " + CodeIndexSessionBrief.NO_INDEX + ".",
                CodeIndexSessionBrief.describe(repo, home(), resolved("repo", "cwd-name"), null, NOW));
    }

    @Test
    void describeNeverPointsAtIndexingHomeOrAnAncestor() {
        Path home = home();

        assertNull(CodeIndexSessionBrief.describe(home, home, resolved("home", "cwd-name"), null, NOW));
        assertNull(CodeIndexSessionBrief.describe(home, home, resolved("home", "registration"), null, NOW));
        assertNull(CodeIndexSessionBrief.describe(home.getParent(), home, resolved("tmp", "cwd-name"), null, NOW));
        assertNull(CodeIndexSessionBrief.describe(repo, home, null, null, NOW));
    }

    @Test
    void refreshableOnlyForAnExistingIndexBelowHome() {
        Path home = home();
        JsonNode atRepo = metadata(repo.toString());
        for (String source : List.of("project-manifest", "registration", "index-root")) {
            assertTrue(CodeIndexSessionBrief.refreshable(repo, home, resolved("p", source), atRepo), source);
        }

        assertFalse(CodeIndexSessionBrief.refreshable(repo, home, null, atRepo));
        assertFalse(CodeIndexSessionBrief.refreshable(repo, home, resolved("p", "registration"), null));
        assertFalse(CodeIndexSessionBrief.refreshable(repo, home, resolved("p", "registration-unindexed"), atRepo));
        assertFalse(CodeIndexSessionBrief.refreshable(repo, home, resolved("p", "cwd-name"), atRepo));
        assertFalse(CodeIndexSessionBrief.refreshable(home, home, resolved("p", "registration"), atRepo));
        assertFalse(CodeIndexSessionBrief.refreshable(repo, home, resolved("p", "registration"),
                metadata(home.toString())));
        assertFalse(CodeIndexSessionBrief.refreshable(repo, home, resolved("p", "registration"),
                metadata(home.getParent().toString())));
        assertFalse(CodeIndexSessionBrief.refreshable(repo, home, resolved("p", "registration"), metadata(" ")));
        assertFalse(CodeIndexSessionBrief.refreshable(repo, home, resolved("p", "registration"),
                metadata(repo.resolve("missing").toString())));
    }

    @Test
    void ageRoundsDownToTheLargestUnit() {
        assertEquals("just now", CodeIndexSessionBrief.age(NOW.minusSeconds(30), NOW));
        assertEquals("5m ago", CodeIndexSessionBrief.age(NOW.minus(Duration.ofMinutes(5)), NOW));
        assertEquals("2h ago", CodeIndexSessionBrief.age(NOW.minus(Duration.ofHours(2)), NOW));
        assertEquals("47h ago", CodeIndexSessionBrief.age(NOW.minus(Duration.ofHours(47)), NOW));
        assertEquals("2d ago", CodeIndexSessionBrief.age(NOW.minus(Duration.ofHours(48)), NOW));
        assertEquals("3d ago", CodeIndexSessionBrief.age(NOW.minus(Duration.ofHours(72)), NOW));
    }

    @Test
    void startResolvesTheWorkingDirectoryAndRefreshesAnIndexedProject() throws Exception {
        String id = uniqueId();
        writeMetadata(id, MAPPER.writeValueAsString(metadata(repo.toString())));
        AtomicReference<Path> resolvedDir = new AtomicReference<>();
        List<String> refreshed = new CopyOnWriteArrayList<>();

        CodeIndexSessionBrief brief = CodeIndexSessionBrief.start(repo, dir -> {
            resolvedDir.set(dir);
            return resolved(id, "project-manifest");
        }, refreshed::add, 5_000);
        brief.settled().get(10, TimeUnit.SECONDS);

        assertEquals(repo.toAbsolutePath().normalize(), resolvedDir.get());
        assertEquals(List.of(id), refreshed);
        String instructions = brief.instructions();
        assertTrue(instructions.startsWith(CodeIndexSessionBrief.GUIDANCE + "\n\nCode index: project_id=" + id
                + " (resolved via project-manifest), root " + repo
                + ", 42 files, last indexed 2026-10-01T10:00:00Z ("), instructions);
        assertTrue(instructions.endsWith(")."), instructions);
    }

    @Test
    void tornOrNonObjectMetadataAsksForReindexAndIsNotRefreshed() throws Exception {
        for (String content : List.of("", "[]", "{\"rootPath\":")) {
            String id = uniqueId();
            writeMetadata(id, content);
            List<String> refreshed = new CopyOnWriteArrayList<>();

            CodeIndexSessionBrief brief = CodeIndexSessionBrief.start(repo,
                    dir -> resolved(id, "registration"), refreshed::add, 5_000);
            brief.settled().get(10, TimeUnit.SECONDS);

            assertEquals(List.of(), refreshed, "metadata '" + content + "'");
            assertEquals(CodeIndexSessionBrief.GUIDANCE + "\n\nCode index: project_id=" + id
                    + " (resolved via registration); " + REINDEX, brief.instructions(), "metadata '" + content + "'");
        }
    }

    @Test
    void aFailingRefreshRequestKeepsTheStatusLine() throws Exception {
        String id = uniqueId();
        writeMetadata(id, MAPPER.writeValueAsString(metadata(repo.toString())));

        CodeIndexSessionBrief brief = CodeIndexSessionBrief.start(repo, dir -> resolved(id, "index-root"),
                projectId -> { throw new IllegalStateException("refresh unavailable"); }, 5_000);
        brief.settled().get(10, TimeUnit.SECONDS);

        String instructions = brief.instructions();
        assertTrue(instructions.startsWith(CodeIndexSessionBrief.GUIDANCE + "\n\nCode index: project_id=" + id
                + " (resolved via index-root), root " + repo), instructions);
    }

    @Test
    void aSlowResolutionServesTheGuidanceAloneWithinTheBudget() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> refreshed = new CopyOnWriteArrayList<>();
        CodeIndexSessionBrief brief = CodeIndexSessionBrief.start(repo, dir -> {
            awaitRelease(release);
            return resolved("repo", "cwd-name");
        }, refreshed::add, 50);

        try {
            assertEquals(CodeIndexSessionBrief.GUIDANCE, brief.instructions());
        } finally {
            release.countDown();
        }
        brief.settled().get(10, TimeUnit.SECONDS);

        assertEquals(CodeIndexSessionBrief.GUIDANCE + "\n\nCode index: " + CodeIndexSessionBrief.NO_INDEX + ".",
                brief.instructions());
        assertTrue(refreshed.isEmpty(), "a directory without an index is never refreshed");
    }

    @Test
    void aFailingResolverServesTheGuidanceAlone() throws Exception {
        List<String> refreshed = new CopyOnWriteArrayList<>();

        CodeIndexSessionBrief brief = CodeIndexSessionBrief.start(repo,
                dir -> { throw new IllegalStateException("resolver offline"); }, refreshed::add, 5_000);
        brief.settled().get(10, TimeUnit.SECONDS);

        assertEquals(CodeIndexSessionBrief.GUIDANCE, brief.instructions());
        assertTrue(refreshed.isEmpty());
    }
}
