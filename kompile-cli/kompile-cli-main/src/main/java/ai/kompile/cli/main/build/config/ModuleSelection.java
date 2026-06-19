/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.build.config;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable resolved set of modules for a build.
 * Built from a preset, then category overrides, then individual include/exclude.
 */
public final class ModuleSelection {

    private final Set<String> moduleIds;

    private ModuleSelection(Set<String> moduleIds) {
        this.moduleIds = Collections.unmodifiableSet(new LinkedHashSet<>(moduleIds));
    }

    /**
     * Check if a specific module ID is selected.
     */
    public boolean has(String moduleId) {
        return moduleIds.contains(moduleId);
    }

    /**
     * Check if any of the given module IDs (within a category) are selected.
     */
    public boolean hasAny(ModuleCatalog.Category category, String... ids) {
        for (String id : ids) {
            if (moduleIds.contains(id)) return true;
        }
        return false;
    }

    /**
     * Check if any module from a category is selected.
     */
    public boolean hasCategory(ModuleCatalog.Category category) {
        return ModuleCatalog.getByCategory(category).stream()
                .anyMatch(m -> moduleIds.contains(m.getId()));
    }

    /**
     * Get all selected module IDs.
     */
    public Set<String> getAll() {
        return moduleIds;
    }

    /**
     * Get all selected modules in a given category.
     */
    public List<ModuleCatalog.ModuleEntry> getByCategory(ModuleCatalog.Category category) {
        return ModuleCatalog.getByCategory(category).stream()
                .filter(m -> moduleIds.contains(m.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Get all selected module entries.
     */
    public List<ModuleCatalog.ModuleEntry> getAllEntries() {
        return moduleIds.stream()
                .map(ModuleCatalog::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Create a builder starting from a preset.
     */
    public static Builder fromPreset(BuildPreset preset) {
        return new Builder(preset.getDefaultModules());
    }

    /**
     * Create a builder starting from an empty set.
     */
    public static Builder empty() {
        return new Builder(Collections.emptySet());
    }

    public static final class Builder {
        private final Set<String> moduleIds;
        private final Set<String> explicitlyExcluded;

        private Builder(Set<String> initial) {
            this.moduleIds = new LinkedHashSet<>(initial);
            this.explicitlyExcluded = new LinkedHashSet<>();
        }

        /**
         * Override a category: replace all modules in that category with the given module IDs.
         * If moduleIds is null, leaves the category unchanged. If empty, removes all modules of that category.
         */
        public Builder overrideCategory(ModuleCatalog.Category category, List<String> newModuleIds) {
            if (newModuleIds == null) return this;
            // Remove all existing modules in this category
            Set<String> categoryIds = ModuleCatalog.getByCategory(category).stream()
                    .map(ModuleCatalog.ModuleEntry::getId)
                    .collect(Collectors.toSet());
            moduleIds.removeAll(categoryIds);
            // Add the new ones
            for (String id : newModuleIds) {
                if (ModuleCatalog.get(id) == null) {
                    throw new IllegalArgumentException("Unknown module ID: " + id);
                }
                moduleIds.add(id);
            }
            return this;
        }

        /**
         * Include additional modules.
         */
        public Builder include(Collection<String> ids) {
            if (ids == null) return this;
            for (String id : ids) {
                if (ModuleCatalog.get(id) == null) {
                    throw new IllegalArgumentException("Unknown module ID for --include: " + id);
                }
                moduleIds.add(id);
            }
            return this;
        }

        /**
         * Exclude specific modules.
         */
        public Builder exclude(Collection<String> ids) {
            if (ids == null) return this;
            explicitlyExcluded.addAll(ids);
            moduleIds.removeAll(ids);
            return this;
        }

        /**
         * Include a single module.
         */
        public Builder include(String id) {
            if (ModuleCatalog.get(id) == null) {
                throw new IllegalArgumentException("Unknown module ID: " + id);
            }
            moduleIds.add(id);
            return this;
        }

        /**
         * Exclude a single module.
         */
        public Builder exclude(String id) {
            explicitlyExcluded.add(id);
            moduleIds.remove(id);
            return this;
        }

        public ModuleSelection build() {
            // Infer process-discovery when process-engine + knowledge-graph + crawl-graph are all present
            // and process-discovery has not been explicitly excluded by the caller
            if (moduleIds.contains("process-engine")
                    && moduleIds.contains("knowledge-graph")
                    && moduleIds.contains("crawl-graph")
                    && !explicitlyExcluded.contains("process-discovery")) {
                moduleIds.add("process-discovery");
            }
            return new ModuleSelection(moduleIds);
        }
    }
}
