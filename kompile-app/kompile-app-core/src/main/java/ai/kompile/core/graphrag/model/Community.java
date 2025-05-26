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

package ai.kompile.core.graphrag.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Represents a detected community or cluster of related entities within the graph.
 */
@Data
public class Community {
    /**
     * A unique identifier for the community.
     */
    private String id;

    /**
     * A list of entity IDs that belong to this community.
     */
    private List<String> entities;

    /**
     * A generated summary or report describing the overall theme of the community.
     */
    private String summary;

    /**
     * Additional metadata associated with the community.
     */
    private Map<String, Object> metadata;
}
