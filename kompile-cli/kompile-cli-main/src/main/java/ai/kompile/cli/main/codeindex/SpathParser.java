/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.codeindex;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for spath (Semantic Path) queries. Spath is a language-neutral grammar
 * for addressing code elements by semantic meaning rather than filesystem location.
 *
 * <p>Implements the spath 0.1.0 spec from
 * <a href="https://github.com/sumato-ai/spath-spec">sumato-ai/spath-spec</a>.</p>
 *
 * <h3>Path structure</h3>
 * <pre>
 *   package_path [ selector ] [ .Symbol.Member | /property ]
 * </pre>
 *
 * <h3>Examples</h3>
 * <pre>
 *   ai.kompile.cli.main.codeindex.LocalCodeIndexer          — exact class
 *   ai.kompile.cli.main.codeindex.LocalCodeIndexer.search    — method in class
 *   ai.kompile.cli.main.codeindex.*                          — all direct children
 *   ai.kompile.cli.main.codeindex.**                         — all descendants
 *   ai.kompile.cli.main.codeindex.*Indexer                   — suffix match
 *   ai.kompile.cli.main[LocalCodeIndexer.java].search        — scoped to file
 *   ai.kompile.cli.main.codeindex/imports                    — imports in package
 *   internal/service.Handler                                 — Go-style path
 * </pre>
 */
public class SpathParser {

    /**
     * A parsed spath query.
     */
    public record SpathQuery(
            String raw,                     // original input string
            List<PathSegment> segments,     // all path segments (package + symbol)
            String selector,                // [selector] content, null if absent
            int selectorPosition,           // segment index where selector appears
            String property,                // /property name, null if absent
            boolean hasWildcard,            // contains any * pattern
            boolean hasRecursiveWildcard    // contains **
    ) {
        /**
         * Get the package portion (segments before the first non-package
         * segment: symbol, wildcard, recursive wildcard, or pattern), joined by dots.
         */
        public String packagePath() {
            List<String> pkgParts = new ArrayList<>();
            for (PathSegment seg : segments) {
                if (seg.kind() != SegmentKind.PACKAGE) break;
                pkgParts.add(seg.name());
            }
            return String.join(".", pkgParts);
        }

        /**
         * Get the symbol portion (segments from the first non-package
         * segment onwards), joined by dots.
         */
        public String symbolPath() {
            List<String> symParts = new ArrayList<>();
            boolean inSymbol = false;
            for (PathSegment seg : segments) {
                if (!inSymbol && seg.kind() != SegmentKind.PACKAGE) {
                    inSymbol = true;
                }
                if (inSymbol) {
                    symParts.add(seg.name());
                }
            }
            return symParts.isEmpty() ? null : String.join(".", symParts);
        }

        /**
         * Convert the full path to a SQL LIKE pattern for FQN matching.
         */
        public String toFqnLikePattern() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) sb.append(".");
                PathSegment seg = segments.get(i);
                if (seg.isRecursiveWildcard()) {
                    sb.append("%");
                } else if (seg.isWildcard()) {
                    // Pure * matches one level
                    sb.append("%");
                } else if (seg.name().contains("*")) {
                    // Partial wildcard like *Handler
                    sb.append(seg.name().replace("*", "%"));
                } else {
                    sb.append(seg.name());
                }
            }
            return sb.toString();
        }

        /**
         * Whether this is a simple exact-match query (no wildcards, no property).
         */
        public boolean isExact() {
            return !hasWildcard && property == null;
        }
    }

    /**
     * A single segment in a path.
     */
    public record PathSegment(
            String name,
            SegmentKind kind
    ) {
        public boolean isWildcard() { return kind == SegmentKind.WILDCARD; }
        public boolean isRecursiveWildcard() { return kind == SegmentKind.RECURSIVE_WILDCARD; }
        public boolean isSymbol() { return kind == SegmentKind.SYMBOL; }
        public boolean isPackage() { return kind == SegmentKind.PACKAGE; }
        public boolean isLiteral() { return kind == SegmentKind.PACKAGE || kind == SegmentKind.SYMBOL; }
    }

    public enum SegmentKind {
        PACKAGE,              // lowercase identifier (package segment)
        SYMBOL,               // uppercase-starting identifier (class/type)
        WILDCARD,             // * (match one level)
        RECURSIVE_WILDCARD,   // ** (match any depth)
        PATTERN               // partial wildcard like *Handler or Get*
    }

    /**
     * Parse an spath query string.
     *
     * @param input the spath string, e.g. "pkg.Class.method" or "pkg.**"
     * @return parsed query
     * @throws IllegalArgumentException if the input is empty or malformed
     */
    public static SpathQuery parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("spath query cannot be empty");
        }

        String raw = input.trim();

        // Extract property (/name) — must be at the end
        String property = null;
        String pathPart = raw;
        int propIdx = findPropertyStart(raw);
        if (propIdx >= 0) {
            property = raw.substring(propIdx + 1);
            pathPart = raw.substring(0, propIdx);
        }

        // Extract selector ([...]) — can appear anywhere in the path
        String selector = null;
        int selectorPosition = -1;
        int selStart = pathPart.indexOf('[');
        int selEnd = pathPart.indexOf(']');
        if (selStart >= 0 && selEnd > selStart) {
            selector = pathPart.substring(selStart + 1, selEnd);
            // Count segments before selector to know its position
            String beforeSel = pathPart.substring(0, selStart);
            selectorPosition = countDotSegments(beforeSel);
            // Remove selector from path
            pathPart = pathPart.substring(0, selStart) + pathPart.substring(selEnd + 1);
            // Clean up any leading dot after selector removal
            if (pathPart.contains("..")) {
                pathPart = pathPart.replace("..", ".");
            }
            if (pathPart.endsWith(".")) {
                pathPart = pathPart.substring(0, pathPart.length() - 1);
            }
        }

        // Normalize: convert slash separators to dots for uniform handling
        // This lets Go-style paths (internal/service) work alongside Java-style (ai.kompile)
        pathPart = pathPart.replace('/', '.');

        // Handle Go subpackage enumeration: internal/... → internal.**
        if (pathPart.endsWith("...")) {
            pathPart = pathPart.substring(0, pathPart.length() - 3) + "**";
        }

        // Split on dots and classify each segment
        String[] parts = pathPart.split("\\.");
        List<PathSegment> segments = new ArrayList<>();
        boolean hasWildcard = false;
        boolean hasRecursive = false;

        for (String part : parts) {
            if (part.isEmpty()) continue;

            if ("**".equals(part)) {
                segments.add(new PathSegment("**", SegmentKind.RECURSIVE_WILDCARD));
                hasWildcard = true;
                hasRecursive = true;
            } else if ("*".equals(part)) {
                segments.add(new PathSegment("*", SegmentKind.WILDCARD));
                hasWildcard = true;
            } else if (part.contains("*")) {
                segments.add(new PathSegment(part, SegmentKind.PATTERN));
                hasWildcard = true;
            } else if (Character.isUpperCase(part.charAt(0))) {
                segments.add(new PathSegment(part, SegmentKind.SYMBOL));
            } else {
                segments.add(new PathSegment(part, SegmentKind.PACKAGE));
            }
        }

        if (segments.isEmpty() && property != null) {
            // Property-only query like "/imports" — matches all
            return new SpathQuery(raw, segments, selector, selectorPosition,
                    property, false, false);
        }

        if (segments.isEmpty()) {
            throw new IllegalArgumentException("spath query has no segments: " + input);
        }

        return new SpathQuery(raw, segments, selector, selectorPosition,
                property, hasWildcard, hasRecursive);
    }

    /**
     * Find the start index of a /property suffix. Must be after the last dot-segment
     * and not inside a selector.
     */
    private static int findPropertyStart(String path) {
        int bracket = path.indexOf('[');
        int lastSlash = path.lastIndexOf('/');
        // Don't treat slashes inside selectors as property markers
        if (lastSlash < 0) return -1;
        if (bracket >= 0 && lastSlash < path.indexOf(']')) return -1;

        // Check if this slash is a path separator or a property marker
        // A property marker has no dots after it
        String afterSlash = path.substring(lastSlash + 1);
        if (afterSlash.contains(".") || afterSlash.contains("/")) {
            // This slash is a path separator (Go-style), not a property
            return -1;
        }
        // Must be a valid identifier
        if (afterSlash.isEmpty() || !Character.isLetter(afterSlash.charAt(0))) {
            return -1;
        }
        return lastSlash;
    }

    private static int countDotSegments(String s) {
        if (s.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '.' || s.charAt(i) == '/') count++;
        }
        return count;
    }
}
