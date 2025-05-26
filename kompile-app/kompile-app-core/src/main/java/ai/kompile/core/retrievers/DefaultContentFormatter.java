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

package ai.kompile.core.retrievers;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Default implementation of ContentFormatter that formats document content 
 * with optional metadata inclusion.
 */
public class DefaultContentFormatter implements ContentFormatter {
    
    private static final DefaultContentFormatter DEFAULT_INSTANCE = new DefaultContentFormatter();
    
    /**
     * Returns the default configuration instance.
     * 
     * @return the default ContentFormatter instance
     */
    public static DefaultContentFormatter defaultConfig() {
        return DEFAULT_INSTANCE;
    }
    
    @Override
    public String format(RetrievedDoc document, MetadataMode metadataMode) {
        if (document == null) {
            throw new IllegalArgumentException("document cannot be null");
        }
        if (metadataMode == null) {
            throw new IllegalArgumentException("metadataMode cannot be null");
        }
        
        StringBuilder formatted = new StringBuilder();
        
        // Add content
        if (document.isText()) {
            String text = document.getText();
            if (text != null) {
                formatted.append(text);
            }
        } else {
            Media media = document.getMedia();
            if (media != null) {
                formatted.append("[Media: ")
                          .append(media.getMediaType().getMimeType())
                          .append(", Size: ")
                          .append(media.getSize())
                          .append(" bytes]");
            }
        }
        
        // Add metadata based on mode
        if (metadataMode != MetadataMode.NONE) {
            Map<String, Object> metadata = document.getMetadata();
            if (metadata != null && !metadata.isEmpty()) {
                String metadataStr = formatMetadata(metadata, metadataMode);
                if (!metadataStr.isEmpty()) {
                    if (formatted.length() > 0) {
                        formatted.append("\n\n");
                    }
                    formatted.append("Metadata:\n").append(metadataStr);
                }
            }
        }
        
        return formatted.toString();
    }
    
    private String formatMetadata(Map<String, Object> metadata, MetadataMode mode) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        
        Map<String, Object> filteredMetadata = metadata;
        
        if (mode == MetadataMode.ESSENTIAL) {
            // Filter to only essential metadata keys
            filteredMetadata = metadata.entrySet().stream()
                .filter(entry -> isEssentialMetadata(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        
        return filteredMetadata.entrySet().stream()
            .map(entry -> entry.getKey() + ": " + entry.getValue())
            .collect(Collectors.joining("\n"));
    }
    
    private boolean isEssentialMetadata(String key) {
        // Define essential metadata keys
        String lowerKey = key.toLowerCase();
        return lowerKey.equals("source") ||
               lowerKey.equals("title") ||
               lowerKey.equals("author") ||
               lowerKey.equals("filename") ||
               lowerKey.equals("type") ||
               lowerKey.equals("id");
    }
}
