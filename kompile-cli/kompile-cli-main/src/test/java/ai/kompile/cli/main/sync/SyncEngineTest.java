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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decision-table tests for the three-way sync engine and scanner safety rules. */
class SyncEngineTest {

    @TempDir
    Path tempDir;

    private static SyncEntry file(String component, String path, String content) {
        return SyncEntry.file(component, path, SyncSession.sha256(content.getBytes()), content.length(), null);
    }

    @Test
    void identicalContentOnBothSidesIsNoop() {
        Map<String, List<SyncEntry>> local = Map.of("skills",
                List.of(file("skills", "a.md", "same")));
        Map<String, List<SyncEntry>> remote = Map.of("skills",
                List.of(file("skills", "a.md", "same")));
        SyncPlan plan = new SyncEngine(s -> { }).plan(local, remote, new HashMap<>(), false);
        assertEquals(1, plan.count(SyncPlan.Action.NOOP));
        assertTrue(plan.isNoop());
    }

    @Test
    void localOnlyChangeCopiesToRemote() {
        Map<String, List<SyncEntry>> local = Map.of("memories",
                List.of(file("memories", "MEMORY.md", "local edit")));
        Map<String, List<SyncEntry>> remote = Map.of("memories",
                List.of(file("memories", "MEMORY.md", "original")));
        Map<String, String> baseline = Map.of("memories/MEMORY.md",
                SyncSession.sha256("original".getBytes()));
        SyncPlan plan = new SyncEngine(s -> { }).plan(local, remote, baseline, false);
        assertEquals(1, plan.count(SyncPlan.Action.COPY_TO_REMOTE));
    }

    @Test
    void remoteOnlyChangeCopiesToLocal() {
        Map<String, List<SyncEntry>> local = Map.of("roles",
                List.of(file("roles", "reviewer.md", "original")));
        Map<String, List<SyncEntry>> remote = Map.of("roles",
                List.of(file("roles", "reviewer.md", "remote edit")));
        Map<String, String> baseline = Map.of("roles/reviewer.md",
                SyncSession.sha256("original".getBytes()));
        SyncPlan plan = new SyncEngine(s -> { }).plan(local, remote, baseline, false);
        assertEquals(1, plan.count(SyncPlan.Action.COPY_TO_LOCAL));
    }

    @Test
    void independentEditsConflict() {
        Map<String, List<SyncEntry>> local = Map.of("skills",
                List.of(file("skills", "x/SKILL.md", "local edit")));
        Map<String, List<SyncEntry>> remote = Map.of("skills",
                List.of(file("skills", "x/SKILL.md", "remote edit")));
        SyncPlan plan = new SyncEngine(s -> { }).plan(local, remote, new HashMap<>(), false);
        assertEquals(1, plan.conflicts().size());
    }

    @Test
    void deletionVersusEditConflicts() {
        Map<String, List<SyncEntry>> local = Map.of("skills",
                List.of(file("skills", "old.md", "edited locally")));
        Map<String, List<SyncEntry>> remote = Map.of("skills",
                List.of(SyncEntry.deletion("skills", "old.md", null)));
        Map<String, String> baseline = Map.of("skills/old.md",
                SyncSession.sha256("original".getBytes()));
        SyncPlan plan = new SyncEngine(s -> { }).plan(local, remote, baseline, false);
        assertEquals(1, plan.conflicts().size());
    }

    @Test
    void identicalIndependentEditsAreNoop() {
        String converged = "both sides fixed it the same way";
        Map<String, List<SyncEntry>> local = Map.of("roles",
                List.of(file("roles", "a.md", converged)));
        Map<String, List<SyncEntry>> remote = Map.of("roles",
                List.of(file("roles", "a.md", converged)));
        Map<String, String> baseline = Map.of("roles/a.md",
                SyncSession.sha256("original".getBytes()));
        SyncPlan plan = new SyncEngine(s -> { }).plan(local, remote, baseline, false);
        assertTrue(plan.isNoop());
    }

    @Test
    void unsafePathsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SyncEntry.file("skills", "../escape.md", "h", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> SyncEntry.file("skills", "/absolute.md", "h", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> SyncEntry.file("skills", "a\\b.md", "h", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> SyncEntry.file("skills", "colon:path.md", "h", 1, null));
    }

    @Test
    void scannerSkipsTempFilesAndBlockedNames() throws IOException {
        Path home = tempDir.resolve("home2");
        Path skills = home.resolve("skills");
        Files.createDirectories(skills.resolve("good"));
        Files.writeString(skills.resolve("good/SKILL.md"), "real skill");
        Files.writeString(skills.resolve("notes.md.tmp"), "temp");
        Files.createDirectories(skills.resolve("cache"));
        Files.writeString(skills.resolve("cache/junk.bin"), "junk");
        var inventory = SyncInventoryScanner.scan(home, List.of("skills"));
        var entries = inventory.get("skills");
        assertEquals(1, entries.size());
        assertEquals("good/SKILL.md", entries.get(0).relativePath());
    }

    @Test
    void skillUnitsGroupByTopLevelDirectory() {
        assertEquals("skills:my-skill",
                SyncInventoryScanner.unitIdFor("skills", "my-skill/SKILL.md"));
        assertEquals("skills:my-skill",
                SyncInventoryScanner.unitIdFor("skills", "my-skill/references/x.md"));
        assertNull(SyncInventoryScanner.unitIdFor("memories", "MEMORY.md"));
    }

    @Test
    void catalogValidationAcceptsKnownAndRejectsUnknown() {
        assertEquals(SyncCatalog.COMPONENTS, SyncCatalog.validate(List.of("all")));
        assertEquals(List.of("skills", "memories"), SyncCatalog.validate(List.of("skills, memories")));
        assertThrows(IllegalArgumentException.class, () -> SyncCatalog.validate(List.of("models")));
        assertThrows(IllegalArgumentException.class, () -> SyncCatalog.validate(List.of("credentials")));
    }

    @Test
    void absenceAgainstBaselineIsWithheldUnlessOptIn() {
        // Locally absent (never materialized) + remotely unchanged from baseline:
        // WITHOUT opt-in this must NOT become DELETE_REMOTE.
        Map<String, List<SyncEntry>> local = Map.of("skills", List.of());
        Map<String, List<SyncEntry>> remote = Map.of("skills",
                List.of(file("skills", "kept.md", "baseline content")));
        Map<String, String> baseline = Map.of("skills/kept.md",
                SyncSession.sha256("baseline content".getBytes()));

        SyncPlan withheld = new SyncEngine(s -> { }).plan(local, remote, baseline, false);
        assertEquals(0, withheld.count(SyncPlan.Action.DELETE_REMOTE),
                "absence must not plan a deletion without opt-in");
        assertEquals(1, withheld.count(SyncPlan.Action.NOOP));
        assertFalse(withheld.notes().isEmpty(), "withheld deletion should be visible in notes");

        SyncPlan optedIn = new SyncEngine(s -> { }).plan(local, remote, baseline, true);
        assertEquals(1, optedIn.count(SyncPlan.Action.DELETE_REMOTE));
    }

    @Test
    void absenceOnBothSidesIsPlainNoop() {
        Map<String, List<SyncEntry>> local = Map.of("skills", List.of());
        Map<String, List<SyncEntry>> remote = Map.of("skills", List.of());
        Map<String, String> baseline = Map.of("skills/gone.md",
                SyncSession.sha256("old".getBytes()));
        SyncPlan plan = new SyncEngine(s -> { }).plan(local, remote, baseline, true);
        assertTrue(plan.isNoop(), "both-side absence is converged, not a deletion");
    }

    @Test
    void componentDirsFollowScopeLayout() {
        assertEquals("skills", SyncCatalog.componentDir("global", "skills"));
        assertEquals(".kompile/skills", SyncCatalog.componentDir("project", "skills"));
        assertEquals(".kompile/memory", SyncCatalog.componentDir("project", "memories"));
        assertEquals("system-prompts", SyncCatalog.componentDir("global", "prompts"));
    }
}
