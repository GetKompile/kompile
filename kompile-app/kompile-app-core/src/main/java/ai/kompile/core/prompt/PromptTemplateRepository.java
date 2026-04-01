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

package ai.kompile.core.prompt;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persisting and retrieving prompt templates.
 */
public interface PromptTemplateRepository {

    /**
     * Saves a prompt template.
     *
     * @param template The template to save
     * @return The saved template with any generated fields populated
     */
    PromptTemplate save(PromptTemplate template);

    /**
     * Finds a template by its unique ID.
     *
     * @param id The template ID
     * @return Optional containing the template if found
     */
    Optional<PromptTemplate> findById(String id);

    /**
     * Finds a template by its unique name.
     *
     * @param name The template name
     * @return Optional containing the template if found
     */
    Optional<PromptTemplate> findByName(String name);

    /**
     * Returns all templates.
     *
     * @return List of all templates
     */
    List<PromptTemplate> findAll();

    /**
     * Finds templates by category.
     *
     * @param category The category to filter by
     * @return List of templates in the category
     */
    List<PromptTemplate> findByCategory(String category);

    /**
     * Finds templates matching a search query.
     * Searches name, description, and tags.
     *
     * @param query The search query
     * @return List of matching templates
     */
    List<PromptTemplate> search(String query);

    /**
     * Finds templates by tag.
     *
     * @param tag The tag to search for
     * @return List of templates with the tag
     */
    List<PromptTemplate> findByTag(String tag);

    /**
     * Finds enabled templates only.
     *
     * @return List of enabled templates
     */
    List<PromptTemplate> findEnabled();

    /**
     * Deletes a template by its ID.
     *
     * @param id The template ID
     * @return true if deleted, false if not found
     */
    boolean deleteById(String id);

    /**
     * Deletes a template by its name.
     *
     * @param name The template name
     * @return true if deleted, false if not found
     */
    boolean deleteByName(String name);

    /**
     * Checks if a template exists by name.
     *
     * @param name The template name
     * @return true if exists
     */
    boolean existsByName(String name);

    /**
     * Returns the count of all templates.
     *
     * @return Total number of templates
     */
    long count();

    /**
     * Reloads templates from the underlying storage.
     */
    void refresh();
}
