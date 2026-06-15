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

package ai.kompile.loader.tika;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TikaLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(TikaLoaderImpl.class);
    private final Tika tika = new Tika();

    @Override
    public String getName() {
        return "Apache Tika Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        // Tika supports many file types, so it could be a fallback or check extensions it's good at.
        // For simplicity, let's say it supports if it's a file and not explicitly handled by others.
        // A more robust check might involve trying to detect type with Tika itself.
        return sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.FILE;
    }

    /**
     * Parse a single CSV line respecting RFC 4180 quoting rules.
     */
    public static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /**
     * Extract ATX-style markdown headings from text content.
     */
    public static List<Map<String, String>> extractMarkdownHeadings(String markdown) {
        List<Map<String, String>> headings = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            Matcher m = ATX_HEADING.matcher(line.trim());
            if (m.matches()) {
                String text = m.group(2).replaceAll("\\s+#+\\s*$", "").trim();
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("text", text);
                entry.put("level", String.valueOf(m.group(1).length()));
                headings.add(entry);
            }
        }
        return headings;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            throw new IllegalArgumentException("TikaLoader currently only supports FILE sources.");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + sourceDescriptor.getPathOrUrl());
        }

        String content;
        try (InputStream stream = new FileInputStream(file)) {
            content = tika.parseToString(stream);
        } catch (TikaException | IOException e) {
            // Handle corrupted or invalid files gracefully
            String errorMessage = e.getMessage();
            logger.warn("Unable to parse file '{}' with Tika: {}. The file may be corrupted or in an unsupported format.",
                       file.getName(), errorMessage);

            // Return an error document so the caller knows what happened
            Document errorDoc = new Document("[Error: Unable to parse file. The file may be corrupted, truncated, or in an unsupported format.]");
            errorDoc.getMetadata().put("source", file.getAbsolutePath());
            errorDoc.getMetadata().put("fileName", file.getName());
            errorDoc.getMetadata().put("fileSize", file.length());
            errorDoc.getMetadata().put("lastModified", file.lastModified());
            errorDoc.getMetadata().put("loader", getName());
            errorDoc.getMetadata().put("parseError", true);
            errorDoc.getMetadata().put("errorMessage", errorMessage != null ? errorMessage : "Unknown error");
            return List.of(errorDoc);
        }

        Document springDoc = new Document(content);
        springDoc.getMetadata().put("source", file.getAbsolutePath());
        springDoc.getMetadata().put("fileName", file.getName());
        springDoc.getMetadata().put("loader", getName());
        return List.of(springDoc);
    }
}