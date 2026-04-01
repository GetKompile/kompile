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

package ai.kompile.staging.execution;

import ai.kompile.staging.web.dto.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory CRUD service for named prompt templates.
 * Templates use {{variable}} syntax for variable substitution.
 */
@Service
public class PromptTemplateService {

    private final ConcurrentHashMap<String, PromptTemplate> templates = new ConcurrentHashMap<>();
    private final ChatTemplateService chatTemplateService;

    public PromptTemplateService(ChatTemplateService chatTemplateService) {
        this.chatTemplateService = chatTemplateService;
    }

    public PromptTemplate create(PromptTemplate template) {
        if (template.getId() == null || template.getId().isBlank()) {
            template.setId(UUID.randomUUID().toString().substring(0, 8));
        }
        template.setCreatedAt(System.currentTimeMillis());
        // Auto-detect variables from the template text
        template.setVariables(chatTemplateService.extractVariables(template.getTemplate()));
        templates.put(template.getId(), template);
        return template;
    }

    public PromptTemplate getById(String id) {
        return templates.get(id);
    }

    public List<PromptTemplate> listAll() {
        return new ArrayList<>(templates.values());
    }

    public PromptTemplate update(String id, PromptTemplate updated) {
        PromptTemplate existing = templates.get(id);
        if (existing == null) return null;
        updated.setId(id);
        updated.setCreatedAt(existing.getCreatedAt());
        updated.setVariables(chatTemplateService.extractVariables(updated.getTemplate()));
        templates.put(id, updated);
        return updated;
    }

    public boolean delete(String id) {
        return templates.remove(id) != null;
    }

    /**
     * Apply a template by substituting variables and returning the resulting prompt.
     */
    public String apply(String id, Map<String, String> variables) {
        PromptTemplate template = templates.get(id);
        if (template == null) return null;
        return chatTemplateService.substituteVariables(template.getTemplate(), variables);
    }
}
