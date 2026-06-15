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

/**
 * Represents a heading/title extracted from a document.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HeadingEntity extends DocumentEntity {

    /**
     * Heading text.
     */
    private String text;

    /**
     * Heading level (1-6).
     */
    private int level;

    /**
     * Section number (e.g., "1.2.3").
     */
    private String sectionNumber;

    @Override
    public Document toSearchDocument() {
        Document doc = new Document(text);
        doc.getMetadata().putAll(getBaseMetadata());
        doc.getMetadata().put("heading_level", level);
        if (sectionNumber != null) {
            doc.getMetadata().put("section_number", sectionNumber);
        }
        return doc;
    }

    @Override
    public Document toFullDocument() {
        return toSearchDocument();
    }

    @Override
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(level, 6); i++) {
            sb.append("#");
        }
        sb.append(" ");
        if (sectionNumber != null) {
            sb.append(sectionNumber).append(" ");
        }
        sb.append(text);
        return sb.toString();
    }
}
