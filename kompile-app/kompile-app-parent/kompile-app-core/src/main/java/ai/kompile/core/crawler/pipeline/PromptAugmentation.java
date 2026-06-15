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

package ai.kompile.core.crawler.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A configurable content-signal prompt augmentation rule.
 *
 * <p>When chunk text matches the configured signal patterns, the {@link #augmentationText}
 * is appended to the extraction prompt. This allows domain-specific extraction guidance
 * to be injected dynamically based on content characteristics, without hardcoding
 * detection heuristics.</p>
 *
 * <h3>Example: Financial document augmentation</h3>
 * <pre>
 * PromptAugmentation.builder()
 *     .name("financial-statements")
 *     .contentSignals(List.of(
 *         "(?i)balance sheet",
 *         "(?i)income statement",
 *         "(?i)cash flow",
 *         "(?i)revenue|net income|total assets"
 *     ))
 *     .augmentationText("""
 *         FINANCIAL DOCUMENT: Extract financial entities including:
 *         - COMPANY entities with properties: ticker, sector, fiscal_year
 *         - FINANCIAL_METRIC entities: revenue, net_income, total_assets, etc.
 *         - REGULATORY_BODY entities: SEC, FASB, etc.
 *         - Relations: REPORTS_METRIC, REGULATED_BY, COMPARED_TO
 *         """)
 *     .minSignalMatches(2)
 *     .build();
 * </pre>
 *
 * <h3>Example: Email/communication augmentation</h3>
 * <pre>
 * PromptAugmentation.builder()
 *     .name("email-communication")
 *     .contentSignals(List.of("(?i)^from:", "(?i)^to:", "(?i)^subject:", "(?i)dear "))
 *     .augmentationText("""
 *         EMAIL CONTENT: Extract communication entities:
 *         - PERSON entities for senders and recipients
 *         - ACTION_ITEM entities for requested tasks
 *         - Relations: SENT_BY, SENT_TO, ASSIGNS, REFERENCES
 *         """)
 *     .minSignalMatches(1)
 *     .build();
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptAugmentation {

    /** Human-readable name for this augmentation rule */
    private String name;

    /**
     * Regex patterns that are matched against chunk text content.
     * When at least {@link #minSignalMatches} patterns match, the
     * {@link #augmentationText} is appended to the extraction prompt.
     */
    @Builder.Default
    private List<String> contentSignals = new ArrayList<>();

    /**
     * The text to append to the extraction prompt when this augmentation triggers.
     * Should contain domain-specific entity types, relationship types, or
     * extraction instructions relevant to the detected content signals.
     */
    private String augmentationText;

    /**
     * Minimum number of content signal patterns that must match for this
     * augmentation to trigger. Default: 1.
     */
    @Builder.Default
    private int minSignalMatches = 1;

    /**
     * Evaluate this augmentation against the given text.
     *
     * @param text the chunk text to evaluate against
     * @return true if enough content signals match
     */
    public boolean matches(String text) {
        if (text == null || text.isEmpty() || contentSignals == null || contentSignals.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        int matches = 0;
        for (String signal : contentSignals) {
            try {
                // Guard against ReDoS: reject overly complex patterns
                if (signal.length() > 200) continue;
                // Use case-insensitive matching with a pre-check for simple substring match
                if (lower.contains(signal.toLowerCase())) {
                    matches++;
                } else {
                    Pattern p = Pattern.compile(signal, Pattern.CASE_INSENSITIVE);
                    if (p.matcher(text).find()) {
                        matches++;
                    }
                }
                if (matches >= minSignalMatches) {
                    return true;
                }
            } catch (Exception e) {
                // Invalid regex — skip this signal
            }
        }
        return false;
    }

    /**
     * Evaluate a list of augmentations against the given text and return
     * the concatenated augmentation text for all matching rules.
     *
     * @param augmentations the augmentation rules to evaluate
     * @param text the chunk text to evaluate against
     * @return concatenated augmentation text, or empty string if none match
     */
    public static String evaluateAll(List<PromptAugmentation> augmentations, String text) {
        if (augmentations == null || augmentations.isEmpty() || text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (PromptAugmentation aug : augmentations) {
            if (aug.matches(text)) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(aug.getAugmentationText());
            }
        }
        return sb.toString();
    }
}
