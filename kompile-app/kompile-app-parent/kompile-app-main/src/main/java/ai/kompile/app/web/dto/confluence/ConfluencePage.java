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

import java.util.List;
import java.util.Map;

/**
 * Represents a Confluence page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfluencePage {

    /**
     * The unique ID of the page.
     */
    private String id;

    /**
     * The title of the page.
     */
    private String title;

    /**
     * The key of the space this page belongs to.
     */
    private String spaceKey;

    /**
     * The name of the space this page belongs to.
     */
    private String spaceName;

    /**
     * The type of content: page, blogpost, or attachment.
     */
    private String type;

    /**
     * The status of the page.
     */
    private String status;

    /**
     * When the page was created.
     */
    private String createdDate;

    /**
     * When the page was last modified.
     */
    private String modifiedDate;

    /**
     * Who created the page.
     */
    private String createdBy;

    /**
     * Who last modified the page.
     */
    private String lastModifiedBy;

    /**
     * The web URL to view this page.
     */
    private String webUrl;

    /**
     * The version number of the page.
     */
    private Integer version;

    /**
     * The ancestor pages (breadcrumb path).
     */
    private List<Map<String, String>> ancestors;

    /**
     * Information about child content.
     */
    private Map<String, Object> children;

    /**
     * Whether this page has children.
     */
    private Boolean hasChildren;

    /**
     * The body content of the page (when fetched with expand=body.storage).
     */
    private String bodyContent;
}
