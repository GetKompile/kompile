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

package ai.kompile.app.sync.convert;

import ai.kompile.app.facts.domain.Note;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Converts between Kompile Note content and Obsidian Flavored Markdown (OFM)
 * with YAML frontmatter. Handles reading and writing frontmatter blocks that
 * carry Kompile sync metadata (note ID, fact sheet ID, tags, timestamps).
 */
@Component
public class ObsidianFrontmatterConverter {

    /**
     * Produce OFM file content: YAML frontmatter block + Markdown body.
     */
    public String toObsidianFormat(Note note, String markdownBody) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        if (note.getId() != null) {
            frontmatter.put("kompile_note_id", note.getId());
        }
        if (note.getFactSheetId() != null) {
            frontmatter.put("kompile_fact_sheet", note.getFactSheetId());
        }
        if (note.getTags() != null && !note.getTags().isBlank()) {
            frontmatter.put("tags", Arrays.asList(note.getTags().split("\\s*,\\s*")));
        }
        if (note.getCreatedAt() != null) {
            frontmatter.put("created", note.getCreatedAt().toString());
        }
        if (note.getUpdatedAt() != null) {
            frontmatter.put("updated", note.getUpdatedAt().toString());
        }

        DumperOptions dOpts = new DumperOptions();
        dOpts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dOpts.setPrettyFlow(true);
        Yaml yaml = new Yaml(dOpts);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append(yaml.dump(frontmatter));
        sb.append("---\n");
        if (markdownBody != null && !markdownBody.isBlank()) {
            sb.append("\n").append(markdownBody);
        }
        return sb.toString();
    }

    /**
     * Parse OFM file content. Strips YAML frontmatter, returns the Markdown body
     * and extracted metadata.
     */
    public ParsedObsidianNote fromObsidianFormat(String ofmContent) {
        if (ofmContent == null || ofmContent.isBlank()) {
            return new ParsedObsidianNote(null, null, "", null, null, null);
        }

        String body;
        Map<String, Object> frontmatter = Collections.emptyMap();

        if (ofmContent.startsWith("---")) {
            int endIndex = ofmContent.indexOf("\n---", 3);
            if (endIndex != -1) {
                String yamlBlock = ofmContent.substring(3, endIndex).trim();
                body = ofmContent.substring(endIndex + 4).trim();
                try {
                    LoaderOptions loaderOptions = new LoaderOptions();
                    Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
                    Object parsed = yaml.load(yamlBlock);
                    if (parsed instanceof Map<?, ?> map) {
                        frontmatter = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            frontmatter.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                } catch (Exception e) {
                    // Failed to parse YAML - treat entire content as body
                    body = ofmContent;
                }
            } else {
                body = ofmContent;
            }
        } else {
            body = ofmContent;
        }

        // Extract title from first H1 heading in body, or from frontmatter
        String title = null;
        if (frontmatter.containsKey("title")) {
            title = String.valueOf(frontmatter.get("title"));
        } else if (body.startsWith("# ")) {
            int nlIdx = body.indexOf('\n');
            title = (nlIdx > 0 ? body.substring(2, nlIdx) : body.substring(2)).trim();
        }

        // Extract tags
        String tags = null;
        Object tagsObj = frontmatter.get("tags");
        if (tagsObj instanceof List<?> tagList) {
            tags = String.join(",", tagList.stream().map(String::valueOf).toList());
        } else if (tagsObj instanceof String s) {
            tags = s;
        }

        // Extract Kompile note ID
        Long kompileNoteId = null;
        Object noteIdObj = frontmatter.get("kompile_note_id");
        if (noteIdObj instanceof Number num) {
            kompileNoteId = num.longValue();
        }

        // Extract updated timestamp
        Instant updatedAt = null;
        Object updatedObj = frontmatter.get("updated");
        if (updatedObj instanceof String s) {
            try {
                updatedAt = Instant.parse(s);
            } catch (Exception ignored) {}
        }

        return new ParsedObsidianNote(title, tags, body, kompileNoteId, updatedAt, frontmatter);
    }

    /**
     * Sanitize a note title for use as a filename (remove invalid chars).
     */
    public String sanitizeFileName(String title) {
        if (title == null || title.isBlank()) return "Untitled";
        return title.replaceAll("[/\\\\:*?\"<>|]", "_")
                    .replaceAll("\\s+", " ")
                    .trim();
    }

    /**
     * Result of parsing an Obsidian .md file.
     */
    public record ParsedObsidianNote(
        String title,
        String tags,
        String body,
        Long kompileNoteId,
        Instant updatedAt,
        Map<String, Object> rawFrontmatter
    ) {}
}
