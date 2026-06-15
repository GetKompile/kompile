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

package ai.kompile.core.graphrag.prompts;

import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import java.util.List;

/**
 * A service for generating dynamic prompts for various tasks within the Graph RAG system.
 * This allows for easy customization and tuning of the prompts used to interact with the Language Model.
 */
public interface PromptEngine {

    /**
     * Generates a prompt for extracting entities and relationships from a text.
     *
     * @param text The text to extract the graph from.
     * @return The generated prompt.
     */
    String forGraphExtraction(String text);

    /**
     * Generates a prompt for summarizing an entity.
     *
     * @param entity The entity to summarize.
     * @param textUnits The list of text units associated with the entity.
     * @return The generated prompt.
     */
    String forEntitySummarization(Entity entity, List<String> textUnits);

    /**
     * Generates a prompt for summarizing a community.
     *
     * @param community The community to summarize.
     * @param entities The list of entities in the community.
     * @return The generated prompt.
     */
    String forCommunitySummarization(Community community, List<Entity> entities);
}

