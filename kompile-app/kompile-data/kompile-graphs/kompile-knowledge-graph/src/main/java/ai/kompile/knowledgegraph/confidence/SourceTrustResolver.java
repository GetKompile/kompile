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
package ai.kompile.knowledgegraph.confidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tier-1 source-trust resolver: maps a source/basis type string to a trust scalar in [0,1].
 *
 * <p>This is the first layer of the four-layer trust composition described in Pillar 5
 * of the confidence-evidence-model design. Only tier-1 (per-type config defaults) is
 * implemented in slice 1. Layers 2–4 (induction-emitted priors, LLM domain assessment,
 * and feedback deltas) are deferred to slice 3.</p>
 *
 * <h3>Basis-type string conventions</h3>
 * <ul>
 *   <li>{@code LLM_EXTRACTION} — LLM extracted a relation from unstructured text</li>
 *   <li>{@code STRUCTURAL} — Tika/rule-based structural extraction</li>
 *   <li>{@code email-from} — email From: header (highly authoritative)</li>
 *   <li>{@code structured-upload} — CSV/JSON/XML structured document</li>
 *   <li>{@code web-scrape} — web scrape or crawl from arbitrary HTML</li>
 *   <li>{@code default} — unknown or uncategorised source</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <p>Defaults are overridable via {@code kompile.kb.source-trust.*} properties.
 * Matching is case-insensitive on the source-type string.</p>
 */
@Component
public class SourceTrustResolver {

    // Default trust scalars — all overridable via properties.

    @Value("${kompile.kb.source-trust.email-from:0.95}")
    private double trustEmailFrom;

    @Value("${kompile.kb.source-trust.email-to-cc:0.90}")
    private double trustEmailToCC;

    @Value("${kompile.kb.source-trust.structured-upload:0.85}")
    private double trustStructuredUpload;

    @Value("${kompile.kb.source-trust.pdf-office:0.70}")
    private double trustPdfOffice;

    @Value("${kompile.kb.source-trust.email-body:0.65}")
    private double trustEmailBody;

    @Value("${kompile.kb.source-trust.llm-extraction:0.60}")
    private double trustLlmExtraction;

    @Value("${kompile.kb.source-trust.web-scrape:0.45}")
    private double trustWebScrape;

    @Value("${kompile.kb.source-trust.default:0.50}")
    private double trustDefault;

    /**
     * Resolve the trust scalar for the given source/basis type.
     *
     * <p>Matching is case-insensitive. When no recognised type matches, the default
     * ({@code kompile.kb.source-trust.default}) is returned.</p>
     *
     * @param sourceType the basis/source type string (e.g. "LLM_EXTRACTION", "email-from")
     * @return trust in [0,1]
     */
    public double trustFor(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return trustDefault;
        }
        String key = sourceType.trim().toLowerCase();
        switch (key) {
            case "email-from":
            case "email_from":
                return trustEmailFrom;
            case "email-to-cc":
            case "email_to_cc":
            case "email-to":
            case "email_to":
            case "email-cc":
            case "email_cc":
                return trustEmailToCC;
            case "structured-upload":
            case "structured_upload":
            case "structured":
                return trustStructuredUpload;
            case "pdf-office":
            case "pdf_office":
            case "pdf":
            case "office":
                return trustPdfOffice;
            case "email-body":
            case "email_body":
                return trustEmailBody;
            case "llm_extraction":
            case "llm-extraction":
            case "llm":
                return trustLlmExtraction;
            case "web-scrape":
            case "web_scrape":
            case "web":
            case "crawl":
                return trustWebScrape;
            default:
                return trustDefault;
        }
    }

    /**
     * No-arg constructor for plain-Java test contexts (no Spring; uses hardcoded defaults).
     * The Spring constructor is used in production (properties injected via @Value).
     */
    public SourceTrustResolver() {
        // Spring will inject the @Value fields; this no-arg ctor also works for tests
        // that new SourceTrustResolver() directly (fields stay at their initialised defaults).
        this.trustEmailFrom       = 0.95;
        this.trustEmailToCC       = 0.90;
        this.trustStructuredUpload = 0.85;
        this.trustPdfOffice       = 0.70;
        this.trustEmailBody       = 0.65;
        this.trustLlmExtraction   = 0.60;
        this.trustWebScrape       = 0.45;
        this.trustDefault         = 0.50;
    }
}
