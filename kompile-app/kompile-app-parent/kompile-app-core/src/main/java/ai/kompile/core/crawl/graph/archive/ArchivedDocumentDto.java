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

package ai.kompile.core.crawl.graph.archive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jackson-friendly, line-serializable form of a Spring AI {@link Document}, used when archiving a
 * pipeline step's chunked documents to disk.
 *
 * <p>Captures only the durable fields (id, text, metadata). The ephemeral content formatter and any
 * computed embedding vector are intentionally dropped (mirrors the
 * {@code @JsonIgnoreProperties({"contentFormatter","embedding"})} contract of
 * {@code RetrievedDoc}). Null metadata values are filtered on the way out and widened numeric values
 * are normalized on the way back in, so a {@code Document} survives a JSON round-trip with its
 * vector-store-relevant semantics intact.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchivedDocumentDto {

    private String id;
    private String text;
    private Map<String, Object> metadata;

    /** Convert a Spring AI {@link Document} to its archivable DTO, dropping null metadata values. */
    public static ArchivedDocumentDto of(Document doc) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (doc.getMetadata() != null) {
            for (Map.Entry<String, Object> e : doc.getMetadata().entrySet()) {
                if (e.getValue() != null) {
                    meta.put(e.getKey(), e.getValue());
                }
            }
        }
        return new ArchivedDocumentDto(doc.getId(), doc.getText(), meta);
    }

    /**
     * Rebuild a Spring AI {@link Document}. Jackson widens JSON integers to {@code Long}; narrow them
     * back to {@code Integer} where they fit, matching how vector stores expect simple metadata types.
     */
    public Document toDocument() {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, Object> e : metadata.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    v = l.intValue();
                }
                meta.put(e.getKey(), v);
            }
        }
        return new Document(id != null ? id : "", text != null ? text : "", meta);
    }
}
