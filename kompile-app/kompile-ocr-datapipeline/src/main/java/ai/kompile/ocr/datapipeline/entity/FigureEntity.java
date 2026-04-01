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
 * Represents a figure/image extracted from a document.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FigureEntity extends DocumentEntity {

    /**
     * Figure caption text.
     */
    private String caption;

    /**
     * Alternative text description.
     */
    private String altText;

    /**
     * Type of figure.
     */
    private FigureType figureType;

    /**
     * Reference/ID in the document (e.g., "Figure 1").
     */
    private String reference;

    /**
     * Base64 encoded image data (optional).
     */
    private String imageData;

    /**
     * Image format (png, jpg, etc.).
     */
    private String imageFormat;

    @Override
    public Document toSearchDocument() {
        String searchText = buildSearchText();
        Document doc = new Document(searchText);
        doc.getMetadata().putAll(getBaseMetadata());
        doc.getMetadata().put("figure_type", figureType != null ? figureType.name() : "unknown");
        if (reference != null) {
            doc.getMetadata().put("reference", reference);
        }
        return doc;
    }

    @Override
    public Document toFullDocument() {
        return toSearchDocument();  // Same for figures
    }

    @Override
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("![");
        if (altText != null) {
            sb.append(altText);
        } else if (caption != null) {
            sb.append(caption);
        }
        sb.append("]");

        // If we have image data, use data URL
        if (imageData != null && imageFormat != null) {
            sb.append("(data:image/").append(imageFormat).append(";base64,").append(imageData).append(")");
        } else {
            sb.append("()");
        }

        if (caption != null) {
            sb.append("\n\n*").append(caption).append("*");
        }

        return sb.toString();
    }

    private String buildSearchText() {
        StringBuilder sb = new StringBuilder();

        if (reference != null) {
            sb.append(reference).append(": ");
        }

        if (caption != null && !caption.isEmpty()) {
            sb.append(caption);
        } else if (altText != null && !altText.isEmpty()) {
            sb.append(altText);
        } else {
            sb.append("Figure");
            if (figureType != null) {
                sb.append(" (").append(figureType.name().toLowerCase()).append(")");
            }
        }

        return sb.toString();
    }

    /**
     * Types of figures.
     */
    public enum FigureType {
        CHART,
        DIAGRAM,
        PHOTO,
        SCREENSHOT,
        ILLUSTRATION,
        GRAPH,
        MAP,
        LOGO,
        UNKNOWN
    }
}
