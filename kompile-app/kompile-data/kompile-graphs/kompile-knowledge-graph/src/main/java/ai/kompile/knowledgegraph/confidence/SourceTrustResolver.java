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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tier-1 source-trust resolver: maps a source/basis type string to a trust scalar in [0,1].
 *
 * <p>This is the first layer of the four-layer trust composition described in Pillar 5 of the
 * confidence-evidence model. All trust values come from the kompile-managed {@link KbConfig}
 * (file {@code kb-confidence-config.json}, editable via the web UI) — there are no Spring
 * {@code @Value} bindings and no hard-coded literals here. In non-Spring contexts (plain-Java
 * tests) the resolver falls back to {@link KbConfig#defaults()}.</p>
 *
 * <h3>Basis-type string conventions</h3>
 * <ul>
 *   <li>{@code email-from} — email From: header (highly authoritative)</li>
 *   <li>{@code email-to-cc} — email To:/Cc: header</li>
 *   <li>{@code structured-upload} — CSV/JSON/XML structured document</li>
 *   <li>{@code llm-extraction} — LLM extracted a relation from unstructured text</li>
 *   <li>{@code web-scrape} — web scrape or crawl from arbitrary HTML</li>
 *   <li>{@code default} — unknown or uncategorised source</li>
 * </ul>
 */
@Component
public class SourceTrustResolver {

    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    /** Spring constructor (config manager injected by field). */
    public SourceTrustResolver() {
    }

    /** Explicit constructor for tests / wiring. */
    public SourceTrustResolver(KbConfigManager kbConfigManager) {
        this.kbConfigManager = kbConfigManager;
    }

    private KbConfig cfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

    /**
     * Resolve the trust scalar for the given source/basis type from the managed config.
     *
     * <p>Matching is case-insensitive. When no recognised type matches, the configured default
     * ({@code kbTrustDefault}) is returned.</p>
     *
     * @param sourceType the basis/source type string (e.g. "email-from", "llm-extraction")
     * @return trust in [0,1]
     */
    public double trustFor(String sourceType) {
        KbConfig c = cfg();
        if (sourceType == null || sourceType.isBlank()) {
            return c.trustDefault;
        }
        switch (sourceType.trim().toLowerCase()) {
            case "email-from":
            case "email_from":
                return c.trustEmailFrom;
            case "email-to-cc":
            case "email_to_cc":
            case "email-to":
            case "email_to":
            case "email-cc":
            case "email_cc":
                return c.trustEmailToCc;
            case "structured-upload":
            case "structured_upload":
            case "structured":
                return c.trustStructuredUpload;
            case "pdf-office":
            case "pdf_office":
            case "pdf":
            case "office":
                return c.trustPdfOffice;
            case "email-body":
            case "email_body":
                return c.trustEmailBody;
            case "llm_extraction":
            case "llm-extraction":
            case "llm":
                return c.trustLlmExtraction;
            case "web-scrape":
            case "web_scrape":
            case "web":
            case "crawl":
                return c.trustWebScrape;
            default:
                return c.trustDefault;
        }
    }
}
