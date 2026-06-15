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
 * Represents a mathematical formula/equation extracted from a document.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormulaEntity extends DocumentEntity {

    /**
     * LaTeX representation of the formula.
     */
    private String latex;

    /**
     * MathML representation (if available).
     */
    private String mathml;

    /**
     * Plain text representation.
     */
    private String plainText;

    /**
     * Natural language description of the formula.
     */
    private String description;

    /**
     * Whether this is an inline or display formula.
     */
    private boolean inline;

    /**
     * Equation number/reference (e.g., "(1)").
     */
    private String equationNumber;

    @Override
    public Document toSearchDocument() {
        String searchText = buildSearchText();
        Document doc = new Document(searchText);
        doc.getMetadata().putAll(getBaseMetadata());
        doc.getMetadata().put("latex", latex != null ? latex : "");
        doc.getMetadata().put("inline", inline);
        if (equationNumber != null) {
            doc.getMetadata().put("equation_number", equationNumber);
        }
        return doc;
    }

    @Override
    public Document toFullDocument() {
        return toSearchDocument();
    }

    @Override
    public String toMarkdown() {
        if (latex == null) {
            return plainText != null ? plainText : "";
        }

        if (inline) {
            return "$" + latex + "$";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("$$\n").append(latex).append("\n$$");
            if (equationNumber != null) {
                sb.append(" ").append(equationNumber);
            }
            return sb.toString();
        }
    }

    private String buildSearchText() {
        StringBuilder sb = new StringBuilder();

        if (description != null && !description.isEmpty()) {
            sb.append(description);
        } else if (plainText != null && !plainText.isEmpty()) {
            sb.append(plainText);
        } else if (latex != null) {
            sb.append("Formula: ").append(latex);
        }

        if (equationNumber != null) {
            sb.append(" ").append(equationNumber);
        }

        return sb.toString();
    }
}
