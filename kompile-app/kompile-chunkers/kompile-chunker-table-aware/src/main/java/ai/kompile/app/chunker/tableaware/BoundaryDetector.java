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

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects semantic boundary regions within text content that should not be split.
 *
 * <p>Supports detection of:</p>
 * <ul>
 *   <li>URLs (http, https, ftp, file, mailto, tel, etc.)</li>
 *   <li>Email addresses</li>
 *   <li>File paths (Unix and Windows)</li>
 *   <li>Quoted strings</li>
 *   <li>Code identifiers (camelCase, snake_case, kebab-case)</li>
 *   <li>IP addresses (IPv4 and IPv6)</li>
 *   <li>Phone numbers</li>
 * </ul>
 *
 * <p>This detector is designed to be fast and regex-based, avoiding ML models.</p>
 */
@Component
public class BoundaryDetector {

    // URL pattern - comprehensive URL detection
    // Matches: http://, https://, ftp://, file://, mailto:, tel:, and common URL patterns
    private static final Pattern URL_PATTERN = Pattern.compile(
        "(?:" +
            // Standard URLs with protocol
            "(?:https?|ftp|file)://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+" +
            "|" +
            // Mailto links
            "mailto:[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}" +
            "|" +
            // Tel links
            "tel:[+]?[\\d\\s\\-().]+" +
            "|" +
            // URLs without protocol but with www.
            "www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+" +
            "|" +
            // URLs ending with common TLDs (when followed by path)
            "[\\w.-]+\\.(?:com|org|net|edu|gov|io|ai|dev|app|co)(?:/[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?" +
        ")",
        Pattern.CASE_INSENSITIVE
    );

    // Email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}",
        Pattern.CASE_INSENSITIVE
    );

    // File path patterns
    // Unix paths: /path/to/file.ext, ~/path, ./relative
    private static final Pattern UNIX_PATH_PATTERN = Pattern.compile(
        "(?:^|(?<=[\\s\"'`]))(?:~|\\./|\\.\\./|/)" +
        "(?:[\\w.\\-]+/)*[\\w.\\-]+(?:\\.[a-zA-Z0-9]+)?",
        Pattern.MULTILINE
    );

    // Windows paths: C:\path\to\file, \\server\share
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile(
        "(?:^|(?<=[\\s\"'`]))" +
        "(?:[A-Za-z]:\\\\(?:[\\w.\\-]+\\\\)*[\\w.\\-]+(?:\\.[a-zA-Z0-9]+)?" +
        "|\\\\\\\\[\\w.\\-]+\\\\(?:[\\w.\\-]+\\\\)*[\\w.\\-]+(?:\\.[a-zA-Z0-9]+)?)",
        Pattern.MULTILINE
    );

    // IPv4 address pattern
    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
        "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b" +
        "(?::\\d{1,5})?" // Optional port
    );

    // IPv6 address pattern (simplified)
    private static final Pattern IPV6_PATTERN = Pattern.compile(
        "\\[?(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\]?" +
        "|\\[?(?:[0-9a-fA-F]{1,4}:)*::[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*\\]?",
        Pattern.CASE_INSENSITIVE
    );

    // Phone number pattern (international formats)
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "(?:\\+?\\d{1,3}[\\s.-]?)?" +
        "(?:\\(?\\d{2,4}\\)?[\\s.-]?)?" +
        "\\d{3,4}[\\s.-]?\\d{3,4}"
    );

    // Quoted string pattern (double quotes, single quotes, backticks)
    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile(
        "\"[^\"\\n]{1,500}\"" +
        "|'[^'\\n]{1,500}'" +
        "|`[^`\\n]{1,500}`"
    );

    // Code identifier pattern (camelCase, PascalCase, snake_case, SCREAMING_SNAKE_CASE)
    // Only matches identifiers that look like code (at least one underscore or mixed case)
    private static final Pattern CODE_IDENTIFIER_PATTERN = Pattern.compile(
        "(?<=\\s|^|[\"'`(\\[])" + // Lookbehind for word boundary
        "(?:" +
            // snake_case or SCREAMING_SNAKE_CASE (must have underscore)
            "[a-zA-Z][a-zA-Z0-9]*(?:_[a-zA-Z0-9]+)+" +
            "|" +
            // camelCase or PascalCase (lowercase followed by uppercase)
            "[a-z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]*" +
            "|" +
            // Method calls or qualified names (e.g., object.method, Class.method)
            "[a-zA-Z][a-zA-Z0-9]*(?:\\.[a-zA-Z][a-zA-Z0-9]*)+(?:\\(\\))?" +
        ")" +
        "(?=[\\s.,;:!?)\\]\"'`]|$)" // Lookahead for word boundary
    );

    // Configuration flags
    private boolean detectUrls = true;
    private boolean detectEmails = true;
    private boolean detectFilePaths = true;
    private boolean detectIpAddresses = false; // Off by default
    private boolean detectPhoneNumbers = false; // Off by default
    private boolean detectQuotedStrings = false; // Off by default
    private boolean detectCodeIdentifiers = false; // Off by default

    /**
     * Represents a detected boundary region within text.
     */
    public record BoundaryRegion(
        int startOffset,
        int endOffset,
        String boundaryType,
        String content
    ) {
        public int length() {
            return endOffset - startOffset;
        }
    }

    /**
     * Configuration class for boundary detection.
     */
    public static class BoundaryConfig {
        public boolean detectUrls = true;
        public boolean detectEmails = true;
        public boolean detectFilePaths = true;
        public boolean detectIpAddresses = false;
        public boolean detectPhoneNumbers = false;
        public boolean detectQuotedStrings = false;
        public boolean detectCodeIdentifiers = false;

        public static BoundaryConfig defaultConfig() {
            return new BoundaryConfig();
        }

        public static BoundaryConfig allEnabled() {
            BoundaryConfig config = new BoundaryConfig();
            config.detectIpAddresses = true;
            config.detectPhoneNumbers = true;
            config.detectQuotedStrings = true;
            config.detectCodeIdentifiers = true;
            return config;
        }

        public static BoundaryConfig urlsAndEmailsOnly() {
            BoundaryConfig config = new BoundaryConfig();
            config.detectFilePaths = false;
            return config;
        }
    }

    /**
     * Detects all boundary regions in the given text using default configuration.
     *
     * @param text The text to search for boundaries
     * @return List of BoundaryRegion objects, sorted by start position
     */
    public List<BoundaryRegion> detectBoundaries(String text) {
        return detectBoundaries(text, BoundaryConfig.defaultConfig());
    }

    /**
     * Detects all boundary regions in the given text using the specified configuration.
     *
     * @param text The text to search for boundaries
     * @param config Configuration for which boundaries to detect
     * @return List of BoundaryRegion objects, sorted by start position
     */
    public List<BoundaryRegion> detectBoundaries(String text, BoundaryConfig config) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<BoundaryRegion> regions = new ArrayList<>();

        // Detect URLs (must be before emails since URLs may contain emails)
        if (config.detectUrls) {
            regions.addAll(detectPattern(text, URL_PATTERN, "url"));
        }

        // Detect emails (filter out those already captured as URLs)
        if (config.detectEmails) {
            List<BoundaryRegion> emails = detectPattern(text, EMAIL_PATTERN, "email");
            for (BoundaryRegion email : emails) {
                if (!isOverlapping(email, regions)) {
                    regions.add(email);
                }
            }
        }

        // Detect file paths
        if (config.detectFilePaths) {
            regions.addAll(detectPattern(text, UNIX_PATH_PATTERN, "unix_path"));
            regions.addAll(detectPattern(text, WINDOWS_PATH_PATTERN, "windows_path"));
        }

        // Detect IP addresses
        if (config.detectIpAddresses) {
            List<BoundaryRegion> ips = detectPattern(text, IPV4_PATTERN, "ipv4");
            for (BoundaryRegion ip : ips) {
                if (!isOverlapping(ip, regions)) {
                    regions.add(ip);
                }
            }
            List<BoundaryRegion> ipv6s = detectPattern(text, IPV6_PATTERN, "ipv6");
            for (BoundaryRegion ip : ipv6s) {
                if (!isOverlapping(ip, regions)) {
                    regions.add(ip);
                }
            }
        }

        // Detect phone numbers
        if (config.detectPhoneNumbers) {
            List<BoundaryRegion> phones = detectPattern(text, PHONE_PATTERN, "phone");
            for (BoundaryRegion phone : phones) {
                if (!isOverlapping(phone, regions)) {
                    regions.add(phone);
                }
            }
        }

        // Detect quoted strings
        if (config.detectQuotedStrings) {
            List<BoundaryRegion> quotes = detectPattern(text, QUOTED_STRING_PATTERN, "quoted");
            for (BoundaryRegion quote : quotes) {
                if (!isOverlapping(quote, regions)) {
                    regions.add(quote);
                }
            }
        }

        // Detect code identifiers
        if (config.detectCodeIdentifiers) {
            List<BoundaryRegion> identifiers = detectPattern(text, CODE_IDENTIFIER_PATTERN, "identifier");
            for (BoundaryRegion id : identifiers) {
                if (!isOverlapping(id, regions)) {
                    regions.add(id);
                }
            }
        }

        // Sort by start position and merge overlapping regions
        return mergeOverlapping(regions);
    }

    /**
     * Detects patterns in text.
     */
    private List<BoundaryRegion> detectPattern(String text, Pattern pattern, String type) {
        List<BoundaryRegion> regions = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String content = matcher.group();
            // Skip very short matches (likely false positives)
            if (content.length() >= 3) {
                regions.add(new BoundaryRegion(
                    matcher.start(),
                    matcher.end(),
                    type,
                    content
                ));
            }
        }

        return regions;
    }

    /**
     * Checks if a region overlaps with any existing regions.
     */
    private boolean isOverlapping(BoundaryRegion region, List<BoundaryRegion> existingRegions) {
        for (BoundaryRegion existing : existingRegions) {
            if (region.startOffset() < existing.endOffset() &&
                region.endOffset() > existing.startOffset()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merges overlapping boundary regions and sorts by start position.
     */
    private List<BoundaryRegion> mergeOverlapping(List<BoundaryRegion> regions) {
        if (regions.isEmpty()) {
            return regions;
        }

        // Sort by start position
        List<BoundaryRegion> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(BoundaryRegion::startOffset));

        List<BoundaryRegion> merged = new ArrayList<>();
        BoundaryRegion current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            BoundaryRegion next = sorted.get(i);

            if (next.startOffset() <= current.endOffset()) {
                // Overlapping - keep the larger one
                if (next.length() > current.length()) {
                    current = next;
                }
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        return merged;
    }

    /**
     * Finds the nearest safe split point that doesn't break any boundary regions.
     *
     * @param text The text being chunked
     * @param desiredPosition The desired split position
     * @param boundaries List of detected boundary regions
     * @param searchRadius How far to search for a safe split point
     * @return The safe split position
     */
    public int findSafeSplitPoint(String text, int desiredPosition, List<BoundaryRegion> boundaries, int searchRadius) {
        // Check if the desired position is inside a boundary region
        for (BoundaryRegion boundary : boundaries) {
            if (desiredPosition > boundary.startOffset() && desiredPosition < boundary.endOffset()) {
                // We're inside a boundary region - move to after it or before it
                int distanceToStart = desiredPosition - boundary.startOffset();
                int distanceToEnd = boundary.endOffset() - desiredPosition;

                if (distanceToEnd <= searchRadius) {
                    // Move to after the boundary
                    return boundary.endOffset();
                } else if (distanceToStart <= searchRadius) {
                    // Move to before the boundary
                    return boundary.startOffset();
                } else {
                    // Boundary is too long, split at desired position anyway
                    return desiredPosition;
                }
            }
        }

        return desiredPosition;
    }

    /**
     * Determines if a position is inside any boundary region.
     */
    public boolean isInsideBoundary(int position, List<BoundaryRegion> boundaries) {
        for (BoundaryRegion boundary : boundaries) {
            if (position > boundary.startOffset() && position < boundary.endOffset()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the boundary region that contains the given position, if any.
     */
    public Optional<BoundaryRegion> getBoundaryAt(int position, List<BoundaryRegion> boundaries) {
        for (BoundaryRegion boundary : boundaries) {
            if (position >= boundary.startOffset() && position <= boundary.endOffset()) {
                return Optional.of(boundary);
            }
        }
        return Optional.empty();
    }

    // Getters and Setters for configuration

    public boolean isDetectUrls() {
        return detectUrls;
    }

    public void setDetectUrls(boolean detectUrls) {
        this.detectUrls = detectUrls;
    }

    public boolean isDetectEmails() {
        return detectEmails;
    }

    public void setDetectEmails(boolean detectEmails) {
        this.detectEmails = detectEmails;
    }

    public boolean isDetectFilePaths() {
        return detectFilePaths;
    }

    public void setDetectFilePaths(boolean detectFilePaths) {
        this.detectFilePaths = detectFilePaths;
    }

    public boolean isDetectIpAddresses() {
        return detectIpAddresses;
    }

    public void setDetectIpAddresses(boolean detectIpAddresses) {
        this.detectIpAddresses = detectIpAddresses;
    }

    public boolean isDetectPhoneNumbers() {
        return detectPhoneNumbers;
    }

    public void setDetectPhoneNumbers(boolean detectPhoneNumbers) {
        this.detectPhoneNumbers = detectPhoneNumbers;
    }

    public boolean isDetectQuotedStrings() {
        return detectQuotedStrings;
    }

    public void setDetectQuotedStrings(boolean detectQuotedStrings) {
        this.detectQuotedStrings = detectQuotedStrings;
    }

    public boolean isDetectCodeIdentifiers() {
        return detectCodeIdentifiers;
    }

    public void setDetectCodeIdentifiers(boolean detectCodeIdentifiers) {
        this.detectCodeIdentifiers = detectCodeIdentifiers;
    }
}
