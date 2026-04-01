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

package ai.kompile.ocr.datapipeline.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Represents a list (ordered or unordered) extracted from a document.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListEntity extends DocumentEntity {

    /**
     * List items.
     */
    private List<String> items;

    /**
     * Whether this is an ordered (numbered) list.
     */
    private boolean ordered;

    /**
     * List title/heading if present.
     */
    private String title;

    @Override
    public Document toSearchDocument() {
        String searchText = buildSearchText();
        Document doc = new Document(searchText);
        doc.getMetadata().putAll(getBaseMetadata());
        doc.getMetadata().put("ordered", ordered);
        doc.getMetadata().put("item_count", items != null ? items.size() : 0);
        return doc;
    }

    @Override
    public Document toFullDocument() {
        return toSearchDocument();
    }

    @Override
    public String toMarkdown() {
        if (items == null || items.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append("**").append(title).append("**\n\n");
        }

        for (int i = 0; i < items.size(); i++) {
            if (ordered) {
                sb.append(i + 1).append(". ");
            } else {
                sb.append("- ");
            }
            sb.append(items.get(i)).append("\n");
        }

        return sb.toString();
    }

    private String buildSearchText() {
        StringBuilder sb = new StringBuilder();

        if (title != null) {
            sb.append(title).append(": ");
        }

        if (items != null) {
            sb.append(String.join(", ", items));
        }

        return sb.toString();
    }
}
