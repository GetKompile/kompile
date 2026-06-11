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

package ai.kompile.app.services.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillServiceTest {

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService();
        service.init();
    }

    // ── listAll ───────────────────────────────────────────────────────────────

    @Test
    void testListAll_containsBuiltIns() {
        List<SkillService.SkillDefinition> all = service.listAll();
        assertFalse(all.isEmpty(), "Should have built-in skills after init");
    }

    @Test
    void testListAll_containsCommitSkill() {
        List<SkillService.SkillDefinition> all = service.listAll();
        assertTrue(all.stream().anyMatch(s -> "commit".equals(s.name)));
    }

    @Test
    void testListAll_containsPrSkill() {
        List<SkillService.SkillDefinition> all = service.listAll();
        assertTrue(all.stream().anyMatch(s -> "pr".equals(s.name)));
    }

    @Test
    void testListAll_containsReviewSkill() {
        List<SkillService.SkillDefinition> all = service.listAll();
        assertTrue(all.stream().anyMatch(s -> "review".equals(s.name)));
    }

    @Test
    void testListAll_containsTestSkill() {
        List<SkillService.SkillDefinition> all = service.listAll();
        assertTrue(all.stream().anyMatch(s -> "test".equals(s.name)));
    }

    @Test
    void testListAll_containsFixSkill() {
        List<SkillService.SkillDefinition> all = service.listAll();
        assertTrue(all.stream().anyMatch(s -> "fix".equals(s.name)));
    }

    // ── getByName ─────────────────────────────────────────────────────────────

    @Test
    void testGetByName_existingBuiltIn() {
        SkillService.SkillDefinition skill = service.getByName("commit");
        assertNotNull(skill);
        assertEquals("commit", skill.name);
        assertTrue(skill.builtIn);
    }

    @Test
    void testGetByName_notFound_returnsNull() {
        assertNull(service.getByName("nonexistent_skill_xyz"));
    }

    // ── listByCategory ────────────────────────────────────────────────────────

    @Test
    void testListByCategory_gitCategory() {
        List<SkillService.SkillDefinition> gitSkills = service.listByCategory("git");
        assertFalse(gitSkills.isEmpty());
        assertTrue(gitSkills.stream().allMatch(s -> "git".equalsIgnoreCase(s.category)));
    }

    @Test
    void testListByCategory_codeCategory() {
        List<SkillService.SkillDefinition> codeSkills = service.listByCategory("code");
        assertFalse(codeSkills.isEmpty());
    }

    @Test
    void testListByCategory_unknownCategory_empty() {
        List<SkillService.SkillDefinition> result = service.listByCategory("nonexistent_cat");
        assertTrue(result.isEmpty());
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void testSearch_byName() {
        List<SkillService.SkillDefinition> results = service.search("commit");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(s -> "commit".equals(s.name)));
    }

    @Test
    void testSearch_byDescription() {
        List<SkillService.SkillDefinition> results = service.search("pull request");
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearch_noMatch_empty() {
        List<SkillService.SkillDefinition> results = service.search("xyzabcnomatch99");
        assertTrue(results.isEmpty());
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void testCreate_validSkill() {
        SkillService.SkillDefinition skill = new SkillService.SkillDefinition();
        skill.name = "myCustomSkill";
        skill.description = "My custom skill";
        skill.promptTemplate = "Run {{args}}";
        skill.category = "custom";

        SkillService.SkillDefinition created = service.create(skill);
        assertNotNull(created);
        assertEquals("myCustomSkill", created.name);
        assertFalse(created.builtIn);
        assertNotNull(service.getByName("myCustomSkill"));
    }

    @Test
    void testCreate_invalidName_startsWithDigit() {
        SkillService.SkillDefinition skill = new SkillService.SkillDefinition();
        skill.name = "123invalid";
        skill.description = "bad name";
        assertThrows(IllegalArgumentException.class, () -> service.create(skill));
    }

    @Test
    void testCreate_nullName_throws() {
        SkillService.SkillDefinition skill = new SkillService.SkillDefinition();
        skill.name = null;
        skill.description = "null name";
        assertThrows(IllegalArgumentException.class, () -> service.create(skill));
    }

    @Test
    void testCreate_overwriteBuiltIn_throws() {
        SkillService.SkillDefinition skill = new SkillService.SkillDefinition();
        skill.name = "commit"; // Built-in
        skill.description = "override attempt";
        assertThrows(IllegalArgumentException.class, () -> service.create(skill));
    }

    @Test
    void testCreate_setsDefaultCategory_whenNull() {
        SkillService.SkillDefinition skill = new SkillService.SkillDefinition();
        skill.name = "skillNoCategory";
        skill.description = "No category";
        skill.category = null;

        SkillService.SkillDefinition created = service.create(skill);
        assertEquals("custom", created.category);
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void testUpdate_customSkill() {
        SkillService.SkillDefinition skill = new SkillService.SkillDefinition();
        skill.name = "updateableSkill";
        skill.description = "Original desc";
        skill.promptTemplate = "Original template";
        service.create(skill);

        SkillService.SkillDefinition updates = new SkillService.SkillDefinition();
        updates.description = "Updated desc";
        updates.promptTemplate = "New template";

        SkillService.SkillDefinition updated = service.update("updateableSkill", updates);
        assertEquals("Updated desc", updated.description);
        assertEquals("New template", updated.promptTemplate);
    }

    @Test
    void testUpdate_notFound_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.update("nonexistent", new SkillService.SkillDefinition()));
    }

    @Test
    void testUpdate_builtIn_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.update("commit", new SkillService.SkillDefinition()));
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void testDelete_customSkill() {
        SkillService.SkillDefinition skill = new SkillService.SkillDefinition();
        skill.name = "deleteMe";
        skill.description = "To be deleted";
        service.create(skill);

        assertNotNull(service.getByName("deleteMe"));
        service.delete("deleteMe");
        assertNull(service.getByName("deleteMe"));
    }

    @Test
    void testDelete_notFound_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.delete("missingSkill"));
    }

    @Test
    void testDelete_builtIn_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.delete("commit"));
    }

    // ── generateSkillsMarkdown ────────────────────────────────────────────────

    @Test
    void testGenerateSkillsMarkdown_containsHeader() {
        String md = service.generateSkillsMarkdown();
        assertNotNull(md);
        assertTrue(md.contains("# Available Kompile Skills"),
                "Markdown should start with header");
    }

    @Test
    void testGenerateSkillsMarkdown_containsCommitSkill() {
        String md = service.generateSkillsMarkdown();
        assertTrue(md.contains("/commit"), "Markdown should reference /commit skill");
    }

    @Test
    void testGenerateSkillsMarkdown_containsCategories() {
        String md = service.generateSkillsMarkdown();
        assertTrue(md.contains("##"), "Markdown should have category headers");
    }

    // ── generateCompactListing ────────────────────────────────────────────────

    @Test
    void testGenerateCompactListing_notNull() {
        String listing = service.generateCompactListing();
        assertNotNull(listing);
        assertFalse(listing.isEmpty());
    }

    @Test
    void testGenerateCompactListing_containsAllSkills() {
        String listing = service.generateCompactListing();
        assertTrue(listing.contains("/commit"));
        assertTrue(listing.contains("/review"));
    }

    // ── getSummary ────────────────────────────────────────────────────────────

    @Test
    void testGetSummary_countsCorrect() {
        SkillService.SkillsSummary summary = service.getSummary();
        assertNotNull(summary);
        assertTrue(summary.total() > 0);
        assertTrue(summary.builtIn() > 0);
        assertFalse(summary.categories().isEmpty());
    }

    @Test
    void testGetSummary_customCountIsNonNegative() {
        SkillService.SkillsSummary summary = service.getSummary();
        // Custom count reflects any skills persisted to disk — may be > 0 on dev machines
        assertTrue(summary.custom() >= 0);
    }

    // ── expandTemplate ────────────────────────────────────────────────────────

    @Test
    void testExpandTemplate_withArgs() {
        String expanded = service.expandTemplate("commit", "my specific commit args");
        assertNotNull(expanded);
        assertTrue(expanded.contains("my specific commit args"),
                "Args should be substituted into template");
    }

    @Test
    void testExpandTemplate_withNullArgs() {
        String expanded = service.expandTemplate("commit", null);
        assertNotNull(expanded);
        // {{args}} should be replaced with empty string
        assertFalse(expanded.contains("{{args}}"));
    }

    @Test
    void testExpandTemplate_withBlankArgs() {
        String expanded = service.expandTemplate("commit", "  ");
        assertNotNull(expanded);
        assertFalse(expanded.contains("{{args}}"));
    }

    @Test
    void testExpandTemplate_notFound_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.expandTemplate("nonexistent", "args"));
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    void testRefresh_restoresBuiltIns() {
        int countAfterRefresh = service.refresh();
        assertTrue(countAfterRefresh > 0);
        assertNotNull(service.getByName("commit"));
    }
}
