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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.skill.SkillService;
import ai.kompile.app.services.skill.SkillService.SkillDefinition;
import ai.kompile.app.services.skill.SkillService.SkillsSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for managing kompile skills.
 * Skills are markdown-based prompt templates invoked as /skillname in the CLI chat
 * and injected into agent system prompts via skills.md.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET /api/skills} — list all skills (with optional filters)</li>
 *   <li>{@code GET /api/skills/{name}} — get a skill by name</li>
 *   <li>{@code POST /api/skills} — create a new skill</li>
 *   <li>{@code PUT /api/skills/{name}} — update an existing skill</li>
 *   <li>{@code DELETE /api/skills/{name}} — delete a custom skill</li>
 *   <li>{@code POST /api/skills/{name}/expand} — expand template with args</li>
 *   <li>{@code GET /api/skills/meta/summary} — get skills summary</li>
 *   <li>{@code GET /api/skills/meta/categories} — get category list</li>
 *   <li>{@code GET /api/skills/meta/markdown} — get generated skills.md</li>
 *   <li>{@code POST /api/skills/meta/refresh} — reload from disk</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    // ── List / Search ────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<SkillDefinition>> listSkills(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String query) {
        List<SkillDefinition> result;
        if (query != null && !query.isBlank()) {
            result = skillService.search(query);
        } else if (category != null && !category.isBlank()) {
            result = skillService.listByCategory(category);
        } else {
            result = skillService.listAll();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> getSkill(@PathVariable String name) {
        SkillDefinition skill = skillService.getByName(name);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(skill);
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> createSkill(@RequestBody SkillDefinition skill) {
        try {
            SkillDefinition created = skillService.create(skill);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> updateSkill(@PathVariable String name,
                                         @RequestBody SkillDefinition updates) {
        try {
            SkillDefinition updated = skillService.update(name, updates);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> deleteSkill(@PathVariable String name) {
        try {
            skillService.delete(name);
            return ResponseEntity.ok(Map.of("message", "Skill deleted: " + name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Template Expansion ───────────────────────────────────────────────────

    @PostMapping("/{name}/expand")
    public ResponseEntity<?> expandTemplate(@PathVariable String name,
                                            @RequestBody Map<String, String> body) {
        try {
            String args = body.getOrDefault("args", "");
            String expanded = skillService.expandTemplate(name, args);
            return ResponseEntity.ok(Map.of("name", name, "expanded", expanded));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Meta Endpoints ───────────────────────────────────────────────────────

    @GetMapping("/meta/summary")
    public ResponseEntity<SkillsSummary> getSummary() {
        return ResponseEntity.ok(skillService.getSummary());
    }

    @GetMapping("/meta/categories")
    public ResponseEntity<Map<String, List<SkillDefinition>>> getByCategory() {
        List<SkillDefinition> all = skillService.listAll();
        Map<String, List<SkillDefinition>> grouped = all.stream()
                .collect(Collectors.groupingBy(
                        s -> s.category != null ? s.category : "general"));
        return ResponseEntity.ok(grouped);
    }

    @GetMapping("/meta/markdown")
    public ResponseEntity<?> getSkillsMarkdown(
            @RequestParam(defaultValue = "false") boolean compact) {
        String content = compact
                ? skillService.generateCompactListing()
                : skillService.generateSkillsMarkdown();
        if (content == null) {
            return ResponseEntity.ok(Map.of("content", "", "skillCount", 0));
        }
        return ResponseEntity.ok(Map.of(
                "content", content,
                "skillCount", skillService.listAll().size()));
    }

    @PostMapping("/meta/refresh")
    public ResponseEntity<?> refresh() {
        int count = skillService.refresh();
        return ResponseEntity.ok(Map.of(
                "message", "Skills reloaded from disk",
                "skillCount", count));
    }
}
