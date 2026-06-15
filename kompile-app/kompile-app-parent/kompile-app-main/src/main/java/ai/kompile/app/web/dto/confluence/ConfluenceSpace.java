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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.dto.confluence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a Confluence space.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfluenceSpace {

    /**
     * The unique ID of the space.
     */
    private String id;

    /**
     * The space key (short identifier).
     */
    private String key;

    /**
     * The display name of the space.
     */
    private String name;

    /**
     * Description of the space.
     */
    private String description;

    /**
     * The type of space: global or personal.
     */
    private String type;

    /**
     * The status of the space.
     */
    private String status;

    /**
     * The ID of the space's homepage.
     */
    private String homepageId;

    /**
     * URL to the space icon.
     */
    private String iconUrl;
}
