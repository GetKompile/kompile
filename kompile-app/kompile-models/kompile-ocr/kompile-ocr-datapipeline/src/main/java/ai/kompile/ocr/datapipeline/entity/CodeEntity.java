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
 * Represents a code block extracted from a document.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeEntity extends DocumentEntity {

    /**
     * The code content.
     */
    private String code;

    /**
     * Programming language.
     */
    private String language;

    /**
     * Summary or description of what the code does.
     */
    private String summary;

    /**
     * Filename if specified.
     */
    private String filename;

    /**
     * Line number range in original document.
     */
    private String lineRange;

    @Override
    public Document toSearchDocument() {
        String searchText = buildSearchText();
        Document doc = new Document(searchText);
        doc.getMetadata().putAll(getBaseMetadata());
        doc.getMetadata().put("language", language != null ? language : "unknown");
        doc.getMetadata().put("full_code", code);
        if (filename != null) {
            doc.getMetadata().put("filename", filename);
        }
        return doc;
    }

    @Override
    public Document toFullDocument() {
        Document doc = new Document(code);
        doc.getMetadata().putAll(getBaseMetadata());
        doc.getMetadata().put("language", language != null ? language : "unknown");
        return doc;
    }

    @Override
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("```");
        if (language != null) {
            sb.append(language);
        }
        sb.append("\n");
        sb.append(code);
        if (!code.endsWith("\n")) {
            sb.append("\n");
        }
        sb.append("```");
        return sb.toString();
    }

    private String buildSearchText() {
        StringBuilder sb = new StringBuilder();

        if (summary != null && !summary.isEmpty()) {
            sb.append(summary);
        } else {
            sb.append("Code");
            if (language != null) {
                sb.append(" (").append(language).append(")");
            }
            if (filename != null) {
                sb.append(" in ").append(filename);
            }
        }

        return sb.toString();
    }

    /**
     * Gets the number of lines in the code.
     */
    public int getLineCount() {
        if (code == null) return 0;
        return (int) code.lines().count();
    }
}
