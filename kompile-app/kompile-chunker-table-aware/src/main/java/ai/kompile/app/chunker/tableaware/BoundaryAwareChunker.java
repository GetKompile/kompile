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

package ai.kompile.app.chunker.tableaware;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * A text chunker that preserves semantic boundaries during chunking.
 *
 * <p>This chunker prevents splitting:</p>
 * <ul>
 *   <li>URLs (http, https, ftp, mailto, etc.)</li>
 *   <li>Email addresses</li>
 *   <li>File paths (Unix and Windows)</li>
 *   <li>IP addresses (optional)</li>
 *   <li>Phone numbers (optional)</li>
 *   <li>Quoted strings (optional)</li>
 *   <li>Code identifiers like camelCase, snake_case (optional)</li>
 * </ul>
 *
 * <p>Configuration options:</p>
 * <ul>
 *   <li><b>chunkSize</b> - Maximum size for text chunks (default: 2000)</li>
 *   <li><b>overlap</b> - Overlap between text chunks (default: 200)</li>
 *   <li><b>preserveUrls</b> - Prevent splitting URLs (default: true)</li>
 *   <li><b>preserveEmails</b> - Prevent splitting email addresses (default: true)</li>
 *   <li><b>preserveFilePaths</b> - Prevent splitting file paths (default: true)</li>
 *   <li><b>preserveIpAddresses</b> - Prevent splitting IP addresses (default: false)</li>
 *   <li><b>preservePhoneNumbers</b> - Prevent splitting phone numbers (default: false)</li>
 *   <li><b>preserveQuotedStrings</b> - Prevent splitting quoted strings (default: false)</li>
 *   <li><b>preserveCodeIdentifiers</b> - Prevent splitting code identifiers (default: false)</li>
 * </ul>
 */
@Component("boundaryAwareChunker")
public class BoundaryAwareChunker implements TextChunker {

    private static final Logger logger = LoggerFactory.getLogger(BoundaryAwareChunker.class);
    private static final String CHUNKER_NAME = "boundary-aware";

    // Default configuration
    private static final int DEFAULT_CHUNK_SIZE = 2000;
    private static final int DEFAULT_OVERLAP = 200;

    // Default boundary preservation settings (can be overridden via UI options)
    private static final boolean DEFAULT_PRESERVE_URLS = true;
    private static final boolean DEFAULT_PRESERVE_EMAILS = true;
    private static final boolean DEFAULT_PRESERVE_FILE_PATHS = true;
    private static final boolean DEFAULT_PRESERVE_IP_ADDRESSES = false;
    private static final boolean DEFAULT_PRESERVE_PHONE_NUMBERS = false;
    private static final boolean DEFAULT_PRESERVE_QUOTED_STRINGS = false;
    private static final boolean DEFAULT_PRESERVE_CODE_IDENTIFIERS = false;

    // Separator hierarchy for splitting
    private static final String[] SEPARATORS = {
        "\n\n",    // Paragraph breaks
        "\n",      // Line breaks
        ". ",      // Sentence endings
        "! ",      // Exclamations
        "? ",      // Questions
        "; ",      // Semicolons
        ", ",      // Commas
        " ",       // Spaces
    };

    @Autowired
    private BoundaryDetector boundaryDetector;

    public BoundaryAwareChunker(BoundaryDetector boundaryDetector) {
        this.boundaryDetector = boundaryDetector;
    }

    /** No-arg for Spring AOT / CGLIB proxy creation. */
    public BoundaryAwareChunker() {
    }

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        validateDocument(document);
        Map<String, Object> opts = prepareOptions(options);

        String text = document.getText();
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        int chunkSize = (Integer) opts.get("chunkSize");
        int overlap = (Integer) opts.get("overlap");

        // Build boundary detection configuration
        BoundaryDetector.BoundaryConfig boundaryConfig = buildBoundaryConfig(opts);

        // Detect boundary regions
        List<BoundaryDetector.BoundaryRegion> boundaries = boundaryDetector.detectBoundaries(text, boundaryConfig);

        if (boundaries.isEmpty()) {
            logger.debug("No boundary regions detected in document {}, using standard chunking", document.getId());
            return chunkText(document, text, chunkSize, overlap, Collections.emptyList());
        }

        logger.debug("Detected {} boundary regions in document {}", boundaries.size(), document.getId());

        // Chunk with boundary awareness
        return chunkText(document, text, chunkSize, overlap, boundaries);
    }

    /**
     * Builds boundary detection configuration from chunker options passed from the UI.
     * All configuration is done via the UI options map.
     */
    private BoundaryDetector.BoundaryConfig buildBoundaryConfig(Map<String, Object> opts) {
        BoundaryDetector.BoundaryConfig config = new BoundaryDetector.BoundaryConfig();
        // All settings come from UI options with sensible defaults
        config.detectUrls = getBooleanOption(opts, "preserveUrls", DEFAULT_PRESERVE_URLS);
        config.detectEmails = getBooleanOption(opts, "preserveEmails", DEFAULT_PRESERVE_EMAILS);
        config.detectFilePaths = getBooleanOption(opts, "preserveFilePaths", DEFAULT_PRESERVE_FILE_PATHS);
        config.detectIpAddresses = getBooleanOption(opts, "preserveIpAddresses", DEFAULT_PRESERVE_IP_ADDRESSES);
        config.detectPhoneNumbers = getBooleanOption(opts, "preservePhoneNumbers", DEFAULT_PRESERVE_PHONE_NUMBERS);
        config.detectQuotedStrings = getBooleanOption(opts, "preserveQuotedStrings", DEFAULT_PRESERVE_QUOTED_STRINGS);
        config.detectCodeIdentifiers = getBooleanOption(opts, "preserveCodeIdentifiers", DEFAULT_PRESERVE_CODE_IDENTIFIERS);
        return config;
    }

    private boolean getBooleanOption(Map<String, Object> opts, String key, boolean defaultValue) {
        Object value = opts.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    /**
     * Chunks text while respecting boundary regions.
     */
    private List<RetrievedDoc> chunkText(RetrievedDoc original, String text, int chunkSize,
                                          int overlap, List<BoundaryDetector.BoundaryRegion> boundaries) {
        List<RetrievedDoc> chunks = new ArrayList<>();

        if (text.length() <= chunkSize) {
            chunks.add(createChunk(original, text.trim(), 0, 1, Collections.emptyMap()));
            return chunks;
        }

        List<String> textChunks = splitWithBoundaryAwareness(text, chunkSize, overlap, boundaries);

        for (int i = 0; i < textChunks.size(); i++) {
            String chunkText = textChunks.get(i).trim();
            if (!chunkText.isEmpty()) {
                Map<String, Object> extraMetadata = new HashMap<>();
                extraMetadata.put("chunk.boundaryAware", true);
                chunks.add(createChunk(original, chunkText, i, textChunks.size(), extraMetadata));
            }
        }

        return chunks;
    }

    /**
     * Splits text into chunks while respecting boundary regions.
     */
    private List<String> splitWithBoundaryAwareness(String text, int chunkSize, int overlap,
                                                     List<BoundaryDetector.BoundaryRegion> boundaries) {
        List<String> chunks = new ArrayList<>();
        int currentPos = 0;
        int textLength = text.length();

        while (currentPos < textLength) {
            // Calculate desired end position for this chunk
            int desiredEnd = Math.min(currentPos + chunkSize, textLength);

            // If we're at the end, just take the rest
            if (desiredEnd >= textLength) {
                String chunk = text.substring(currentPos);
                if (!chunk.trim().isEmpty()) {
                    chunks.add(chunk);
                }
                break;
            }

            // Find a safe split point that doesn't break boundaries
            int safeSplitPoint = findSafeSplitPoint(text, desiredEnd, boundaries, currentPos);

            // Extract the chunk
            String chunk = text.substring(currentPos, safeSplitPoint);

            if (!chunk.trim().isEmpty()) {
                chunks.add(chunk);
            }

            // Calculate next start position with overlap
            int nextStart = safeSplitPoint - overlap;
            if (nextStart <= currentPos) {
                nextStart = safeSplitPoint; // Prevent infinite loop
            }

            // Adjust next start to not break boundaries
            nextStart = adjustStartForBoundaries(text, nextStart, boundaries);

            currentPos = nextStart;
        }

        return chunks;
    }

    /**
     * Finds a safe split point that doesn't break any boundary regions.
     */
    private int findSafeSplitPoint(String text, int desiredPos,
                                   List<BoundaryDetector.BoundaryRegion> boundaries,
                                   int minPos) {
        // First, check if we're inside a boundary
        for (BoundaryDetector.BoundaryRegion boundary : boundaries) {
            if (desiredPos > boundary.startOffset() && desiredPos < boundary.endOffset()) {
                // We're inside a boundary - move to after it or before it
                int distanceToEnd = boundary.endOffset() - desiredPos;
                int distanceToStart = desiredPos - boundary.startOffset();

                if (distanceToEnd <= distanceToStart && boundary.endOffset() < text.length()) {
                    // Move to after the boundary
                    return boundary.endOffset();
                } else if (boundary.startOffset() > minPos) {
                    // Move to before the boundary
                    return boundary.startOffset();
                }
                // Boundary is too large, continue to find separator
            }
        }

        // Try to find a good separator near the desired position
        int searchStart = Math.max(minPos, desiredPos - 100);
        int searchEnd = Math.min(text.length(), desiredPos + 50);
        String searchRegion = text.substring(searchStart, searchEnd);

        // Look for separators in order of preference
        for (String separator : SEPARATORS) {
            int sepPos = searchRegion.lastIndexOf(separator, desiredPos - searchStart);
            if (sepPos >= 0) {
                int absolutePos = searchStart + sepPos + separator.length();
                // Make sure this position doesn't break a boundary
                if (!isInsideBoundary(absolutePos, boundaries) && absolutePos > minPos) {
                    return absolutePos;
                }
            }
        }

        // Try looking forward for a separator
        for (String separator : SEPARATORS) {
            int sepPos = searchRegion.indexOf(separator, desiredPos - searchStart);
            if (sepPos >= 0) {
                int absolutePos = searchStart + sepPos + separator.length();
                if (!isInsideBoundary(absolutePos, boundaries) && absolutePos > minPos) {
                    return absolutePos;
                }
            }
        }

        // No good separator found, use the desired position if it's safe
        if (!isInsideBoundary(desiredPos, boundaries)) {
            return desiredPos;
        }

        // As a last resort, find the end of the current boundary
        for (BoundaryDetector.BoundaryRegion boundary : boundaries) {
            if (desiredPos >= boundary.startOffset() && desiredPos < boundary.endOffset()) {
                return boundary.endOffset();
            }
        }

        return desiredPos;
    }

    /**
     * Adjusts start position to not be inside a boundary.
     */
    private int adjustStartForBoundaries(String text, int startPos,
                                          List<BoundaryDetector.BoundaryRegion> boundaries) {
        for (BoundaryDetector.BoundaryRegion boundary : boundaries) {
            if (startPos > boundary.startOffset() && startPos < boundary.endOffset()) {
                // Start is inside a boundary - move to the start of the boundary
                return boundary.startOffset();
            }
        }
        return startPos;
    }

    /**
     * Checks if a position is inside any boundary region.
     */
    private boolean isInsideBoundary(int position, List<BoundaryDetector.BoundaryRegion> boundaries) {
        for (BoundaryDetector.BoundaryRegion boundary : boundaries) {
            if (position > boundary.startOffset() && position < boundary.endOffset()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a chunk document with metadata.
     */
    private RetrievedDoc createChunk(RetrievedDoc original, String chunkText, int index,
                                      int total, Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new HashMap<>(original.getMetadata());
        metadata.put("chunk.strategy", CHUNKER_NAME);
        metadata.put("chunk.index", index);
        metadata.put("chunk.total", total);
        metadata.put("chunk.originalId", original.getId());
        metadata.put("chunk.size", chunkText.length());
        metadata.putAll(extraMetadata);

        return RetrievedDoc.builder()
            .id(original.getId() + "-chunk-" + index)
            .text(chunkText)
            .metadata(metadata)
            .score(original.getScore())
            .build();
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        return List.of("*"); // Language-agnostic
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("chunkSize", DEFAULT_CHUNK_SIZE);
        defaults.put("overlap", DEFAULT_OVERLAP);
        defaults.put("preserveParagraphs", true);

        // Boundary preservation options - defaults shown in UI
        defaults.put("preserveUrls", DEFAULT_PRESERVE_URLS);
        defaults.put("preserveEmails", DEFAULT_PRESERVE_EMAILS);
        defaults.put("preserveFilePaths", DEFAULT_PRESERVE_FILE_PATHS);
        defaults.put("preserveIpAddresses", DEFAULT_PRESERVE_IP_ADDRESSES);
        defaults.put("preservePhoneNumbers", DEFAULT_PRESERVE_PHONE_NUMBERS);
        defaults.put("preserveQuotedStrings", DEFAULT_PRESERVE_QUOTED_STRINGS);
        defaults.put("preserveCodeIdentifiers", DEFAULT_PRESERVE_CODE_IDENTIFIERS);

        return defaults;
    }
}
