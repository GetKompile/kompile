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
package ai.kompile.app.prompts.service;

import ai.kompile.app.prompts.domain.SystemPromptEntity;
import ai.kompile.app.prompts.repository.SystemPromptRepository;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.facts.domain.FactSheet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for managing system prompts with versioning support.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemPromptService {

    private final SystemPromptRepository promptRepository;
    private final FactSheetService factSheetService;
    private final ObjectMapper objectMapper;

    // ==================== CRUD Operations ====================

    /**
     * Create a new prompt for the active fact sheet.
     */
    @Transactional
    public SystemPromptEntity createPrompt(SystemPromptEntity prompt) {
        Long factSheetId = getActiveFactSheetId();
        return createPromptForFactSheet(prompt, factSheetId);
    }

    /**
     * Create a new prompt for a specific fact sheet.
     */
    @Transactional
    public SystemPromptEntity createPromptForFactSheet(SystemPromptEntity prompt, Long factSheetId) {
        prompt.setId(UUID.randomUUID().toString());
        prompt.setFactSheetId(factSheetId);

        // Assign next version number
        int nextVersion = promptRepository.findMaxVersionByFactSheetId(factSheetId).orElse(0) + 1;
        prompt.setVersion(nextVersion);

        // If this is the first prompt, make it active
        if (nextVersion == 1) {
            prompt.setIsActive(true);
        }

        log.info("Creating new prompt '{}' version {} for fact sheet {}",
                prompt.getName(), nextVersion, factSheetId);

        return promptRepository.save(prompt);
    }

    /**
     * Get a prompt by ID.
     */
    @Transactional(readOnly = true)
    public Optional<SystemPromptEntity> getPromptById(String id) {
        return promptRepository.findById(id);
    }

    /**
     * Get all prompts for the active fact sheet.
     */
    @Transactional(readOnly = true)
    public List<SystemPromptEntity> getPromptsForActiveFactSheet() {
        Long factSheetId = getActiveFactSheetId();
        return promptRepository.findByFactSheetIdOrderByCreatedAtDesc(factSheetId);
    }

    /**
     * Get all prompts for a specific fact sheet.
     */
    @Transactional(readOnly = true)
    public List<SystemPromptEntity> getPromptsForFactSheet(Long factSheetId) {
        return promptRepository.findByFactSheetIdOrderByCreatedAtDesc(factSheetId);
    }

    /**
     * Get the active prompt for the active fact sheet.
     */
    @Transactional(readOnly = true)
    public Optional<SystemPromptEntity> getActivePrompt() {
        Long factSheetId = getActiveFactSheetId();
        return promptRepository.findByFactSheetIdAndIsActiveTrue(factSheetId);
    }

    /**
     * Get the active prompt for a specific fact sheet.
     */
    @Transactional(readOnly = true)
    public Optional<SystemPromptEntity> getActivePromptForFactSheet(Long factSheetId) {
        return promptRepository.findByFactSheetIdAndIsActiveTrue(factSheetId);
    }

    /**
     * Update an existing prompt (creates a new version).
     */
    @Transactional
    public SystemPromptEntity updatePrompt(String id, SystemPromptEntity updates) {
        SystemPromptEntity existing = promptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + id));

        // Update mutable fields
        existing.setName(updates.getName());
        existing.setDescription(updates.getDescription());
        existing.setContent(updates.getContent());
        existing.setVariablesJson(updates.getVariablesJson());
        existing.setTagsJson(updates.getTagsJson());
        existing.setChangeNotes(updates.getChangeNotes());

        return promptRepository.save(existing);
    }

    /**
     * Delete a prompt by ID.
     */
    @Transactional
    public void deletePrompt(String id) {
        SystemPromptEntity prompt = promptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + id));

        // If deleting the active prompt, activate the most recent remaining one
        if (Boolean.TRUE.equals(prompt.getIsActive())) {
            promptRepository.delete(prompt);

            List<SystemPromptEntity> remaining =
                    promptRepository.findByFactSheetIdOrderByVersionDesc(prompt.getFactSheetId());
            if (!remaining.isEmpty()) {
                activatePrompt(remaining.get(0).getId());
            }
        } else {
            promptRepository.delete(prompt);
        }

        log.info("Deleted prompt {} (version {})", id, prompt.getVersion());
    }

    // ==================== Versioning Operations ====================

    /**
     * Create a new version of an existing prompt.
     */
    @Transactional
    public SystemPromptEntity createNewVersion(String parentId, String changeNotes) {
        SystemPromptEntity parent = promptRepository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent prompt not found: " + parentId));

        int nextVersion = promptRepository.findMaxVersionByFactSheetId(parent.getFactSheetId()).orElse(0) + 1;

        SystemPromptEntity newVersion = SystemPromptEntity.builder()
                .id(UUID.randomUUID().toString())
                .name(parent.getName())
                .description(parent.getDescription())
                .content(parent.getContent())
                .factSheetId(parent.getFactSheetId())
                .version(nextVersion)
                .parentVersionId(parentId)
                .isActive(false)
                .variablesJson(parent.getVariablesJson())
                .tagsJson(parent.getTagsJson())
                .changeNotes(changeNotes)
                .createdBy(parent.getCreatedBy())
                .build();

        log.info("Created new version {} of prompt '{}' from parent version {}",
                nextVersion, parent.getName(), parent.getVersion());

        return promptRepository.save(newVersion);
    }

    /**
     * Create a new version with updated content.
     */
    @Transactional
    public SystemPromptEntity createNewVersionWithContent(String parentId, String newContent, String changeNotes) {
        SystemPromptEntity newVersion = createNewVersion(parentId, changeNotes);
        newVersion.setContent(newContent);
        return promptRepository.save(newVersion);
    }

    /**
     * Get version history for a prompt (all versions with the same name).
     */
    @Transactional(readOnly = true)
    public List<SystemPromptEntity> getVersionHistory(String promptId) {
        SystemPromptEntity prompt = promptRepository.findById(promptId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId));

        return promptRepository.findVersionHistory(prompt.getFactSheetId(), prompt.getName());
    }

    // ==================== Activation Operations ====================

    /**
     * Activate a specific prompt (deactivates all others for the same fact sheet).
     */
    @Transactional
    public SystemPromptEntity activatePrompt(String promptId) {
        SystemPromptEntity prompt = promptRepository.findById(promptId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId));

        // Deactivate all prompts for this fact sheet
        promptRepository.deactivateAllForFactSheet(prompt.getFactSheetId());

        // Activate the specified prompt
        promptRepository.activatePrompt(promptId);

        log.info("Activated prompt {} (version {}) for fact sheet {}",
                promptId, prompt.getVersion(), prompt.getFactSheetId());

        // Return the updated entity
        return promptRepository.findById(promptId).orElse(prompt);
    }

    // ==================== Search Operations ====================

    /**
     * Search prompts by name.
     */
    @Transactional(readOnly = true)
    public List<SystemPromptEntity> searchByName(String searchTerm) {
        Long factSheetId = getActiveFactSheetId();
        return promptRepository.searchByName(factSheetId, searchTerm);
    }

    /**
     * Find prompts by tag.
     */
    @Transactional(readOnly = true)
    public List<SystemPromptEntity> findByTag(String tag) {
        Long factSheetId = getActiveFactSheetId();
        return promptRepository.findByTag(factSheetId, tag);
    }

    // ==================== Variable Management ====================

    /**
     * Extract variables from prompt content.
     * Looks for patterns like {{variable_name}} or ${variable_name}.
     */
    public List<String> extractVariables(String content) {
        Set<String> variables = new LinkedHashSet<>();

        // Match {{variable}} pattern
        java.util.regex.Pattern pattern1 = java.util.regex.Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");
        java.util.regex.Matcher matcher1 = pattern1.matcher(content);
        while (matcher1.find()) {
            variables.add(matcher1.group(1));
        }

        // Match ${variable} pattern
        java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");
        java.util.regex.Matcher matcher2 = pattern2.matcher(content);
        while (matcher2.find()) {
            variables.add(matcher2.group(1));
        }

        return new ArrayList<>(variables);
    }

    /**
     * Get variables JSON as a List of Maps.
     */
    public List<Map<String, Object>> getVariablesAsList(SystemPromptEntity prompt) {
        if (prompt.getVariablesJson() == null || prompt.getVariablesJson().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(prompt.getVariablesJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse variables JSON for prompt {}: {}", prompt.getId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Set variables from a List of Maps.
     */
    public void setVariablesFromList(SystemPromptEntity prompt, List<Map<String, Object>> variables) {
        try {
            prompt.setVariablesJson(objectMapper.writeValueAsString(variables));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize variables for prompt {}: {}", prompt.getId(), e.getMessage());
        }
    }

    // ==================== Tag Management ====================

    /**
     * Get tags as a List.
     */
    public List<String> getTagsAsList(SystemPromptEntity prompt) {
        if (prompt.getTagsJson() == null || prompt.getTagsJson().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(prompt.getTagsJson(),
                    new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tags JSON for prompt {}: {}", prompt.getId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Set tags from a List.
     */
    public void setTagsFromList(SystemPromptEntity prompt, List<String> tags) {
        try {
            prompt.setTagsJson(objectMapper.writeValueAsString(tags));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tags for prompt {}: {}", prompt.getId(), e.getMessage());
        }
    }

    // ==================== Statistics ====================

    /**
     * Get prompt count for the active fact sheet.
     */
    @Transactional(readOnly = true)
    public long getPromptCount() {
        Long factSheetId = getActiveFactSheetId();
        return promptRepository.countByFactSheetId(factSheetId);
    }

    // ==================== Helper Methods ====================

    private Long getActiveFactSheetId() {
        FactSheet activeSheet = factSheetService.getActiveSheet();
        return activeSheet != null ? activeSheet.getId() : null;
    }
}
