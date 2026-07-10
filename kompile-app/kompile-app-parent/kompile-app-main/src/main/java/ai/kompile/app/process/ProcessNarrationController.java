/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.app.process;

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * On-demand LLM narration of mined process suggestions (the automatic pass runs after every
 * mining run; this re-runs it, e.g. after switching chat models). The response always carries a
 * coherent narrative — the deterministic template when no model is configured or the LLM output
 * failed the grounding gate.
 */
@RestController
@RequestMapping("/api/process/narration")
public class ProcessNarrationController {

    private final LlmProcessNarrationService narrationService;
    private final ProcessSuggestionStore suggestionStore;

    public ProcessNarrationController(LlmProcessNarrationService narrationService,
                                      ProcessSuggestionStore suggestionStore) {
        this.narrationService = narrationService;
        this.suggestionStore = suggestionStore;
    }

    /** Re-narrate every pending mined suggestion of a fact sheet. */
    @PostMapping("/fact-sheet/{factSheetId}")
    public ResponseEntity<Map<String, Object>> narrateFactSheet(@PathVariable long factSheetId) {
        int upgraded = narrationService.narrateFactSheet(factSheetId);
        return ResponseEntity.ok(Map.of("factSheetId", factSheetId, "upgraded", upgraded));
    }

    /** Re-narrate one stored suggestion; returns it with its (possibly upgraded) narrative. */
    @PostMapping("/suggestions/{id}")
    public ResponseEntity<ProcessSuggestion> narrateSuggestion(@PathVariable String id) {
        Optional<ProcessSuggestion> suggestion = suggestionStore.get(id);
        if (suggestion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        narrationService.narrateSuggestion(suggestion.get());
        return ResponseEntity.ok(suggestionStore.get(id).orElse(suggestion.get()));
    }
}
