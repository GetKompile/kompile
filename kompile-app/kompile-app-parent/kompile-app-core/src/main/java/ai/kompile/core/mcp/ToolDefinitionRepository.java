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

package ai.kompile.core.mcp;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persisting and retrieving tool definitions.
 * Implementations can use different storage backends (file system, database, etc.).
 */
public interface ToolDefinitionRepository {

    /**
     * Saves a tool definition. If a tool with the same ID exists, it will be updated.
     *
     * @param definition the tool definition to save
     * @return the saved tool definition (with any generated fields like ID populated)
     */
    EnhancedToolDefinition save(EnhancedToolDefinition definition);

    /**
     * Finds a tool definition by its ID.
     *
     * @param id the tool definition ID
     * @return an Optional containing the tool if found
     */
    Optional<EnhancedToolDefinition> findById(String id);

    /**
     * Finds a tool definition by its name.
     *
     * @param name the tool name
     * @return an Optional containing the tool if found
     */
    Optional<EnhancedToolDefinition> findByName(String name);

    /**
     * Returns all tool definitions.
     *
     * @return list of all tool definitions
     */
    List<EnhancedToolDefinition> findAll();

    /**
     * Returns all enabled tool definitions.
     *
     * @return list of enabled tool definitions
     */
    List<EnhancedToolDefinition> findAllEnabled();

    /**
     * Finds tool definitions by category.
     *
     * @param category the category to filter by
     * @return list of tool definitions in the category
     */
    List<EnhancedToolDefinition> findByCategory(String category);

    /**
     * Finds tool definitions by source.
     *
     * @param source the source to filter by
     * @return list of tool definitions from the source
     */
    List<EnhancedToolDefinition> findBySource(EnhancedToolDefinition.ToolSource source);

    /**
     * Finds tool definitions by tag.
     *
     * @param tag the tag to search for
     * @return list of tool definitions with the tag
     */
    List<EnhancedToolDefinition> findByTag(String tag);

    /**
     * Searches tool definitions by query string (searches name, description, tags).
     *
     * @param query the search query
     * @return list of matching tool definitions
     */
    List<EnhancedToolDefinition> search(String query);

    /**
     * Deletes a tool definition by ID.
     *
     * @param id the tool definition ID
     * @return true if deleted, false if not found
     */
    boolean deleteById(String id);

    /**
     * Deletes a tool definition by name.
     *
     * @param name the tool name
     * @return true if deleted, false if not found
     */
    boolean deleteByName(String name);

    /**
     * Checks if a tool with the given name exists.
     *
     * @param name the tool name
     * @return true if exists
     */
    boolean existsByName(String name);

    /**
     * Returns the count of all tool definitions.
     *
     * @return count of tool definitions
     */
    long count();

    /**
     * Deletes all tool definitions.
     */
    void deleteAll();

    /**
     * Reloads all tool definitions from storage.
     * Useful after external modifications.
     */
    void refresh();
}
