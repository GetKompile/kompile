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

package ai.kompile.crawl.graph.preprocessing;

import ai.kompile.core.crawl.graph.DocumentPreprocessor;
import ai.kompile.core.crawl.graph.PreprocessingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes boilerplate content that adds noise to embeddings and degrades
 * search quality: cookie consent text, navigation menus, legal disclaimers,
 * email signatures, repeated headers/footers, etc.
 *
 * <p>Order: 310 (content filtering phase — runs after content transforms
 * like translation, so that translated boilerplate is also caught).</p>
 */
@Component
public class BoilerplateRemovalPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(BoilerplateRemovalPreprocessor.class);

    // Web boilerplate patterns
    private static final Pattern COOKIE_CONSENT = Pattern.compile(
            "(?mi)^.*(?:cookie|cookies|cookie policy|we use cookies|accept cookies|" +
                    "cookie consent|cookie preferences|manage cookies).*$");
    private static final Pattern NAV_MENU = Pattern.compile(
            "(?mi)^\\s*(?:home|about|contact|login|sign in|sign up|register|" +
                    "privacy policy|terms of service|sitemap|search|menu)\\s*$");
    private static final Pattern SOCIAL_SHARE = Pattern.compile(
            "(?mi)^.*(?:share on|follow us|like us|tweet this|pin it|" +
                    "share this|social media|facebook|twitter|linkedin|instagram).*$");
    private static final Pattern SUBSCRIBE_CTA = Pattern.compile(
            "(?mi)^.*(?:subscribe|newsletter|sign up for|get updates|" +
                    "join our mailing list|enter your email).*$");

    // Email boilerplate patterns
    private static final Pattern EMAIL_SIGNATURE_SEPARATOR = Pattern.compile(
            "(?m)^\\s*[-_=]{2,}\\s*$");
    private static final Pattern EMAIL_DISCLAIMER = Pattern.compile(
            "(?mis)(?:this (?:email|message|communication) (?:is|and any)|" +
                    "confidential(?:ity)?.*(?:intended|recipient)|" +
                    "if you (?:are not|have received).*(?:intended|error)|" +
                    "please (?:notify|delete|destroy)).*?(?:\\n\\n|$)");
    private static final Pattern EMAIL_AUTO_REPLY = Pattern.compile(
            "(?mi)^.*(?:out of office|auto.?reply|automatic reply|" +
                    "sent from my (?:iphone|android|mobile)|" +
                    "get outlook for).*$");

    // Legal boilerplate patterns
    private static final Pattern LEGAL_DISCLAIMER = Pattern.compile(
            "(?mis)(?:disclaimer|legal notice|copyright \\d{4}|" +
                    "all rights reserved|terms and conditions apply|" +
                    "this document is provided.*as[- ]is|" +
                    "no warranty|without warranty).*?(?:\\n\\n|$)");

    @Override
    public String id() {
        return "boilerplate-removal";
    }

    @Override
    public String displayName() {
        return "Boilerplate Removal";
    }

    @Override
    public int order() {
        return 310;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        if (config.getBoilerplateRemoval() == null || !config.getBoilerplateRemoval().isEnabled()) {
            return false;
        }
        return document.getText() != null && !document.getText().isBlank();
    }

    @Override
    public List<Document> process(List<Document> documents, PreprocessingConfig config) {
        PreprocessingConfig.BoilerplateRemovalConfig bpConfig = config.getBoilerplateRemoval();
        int minRemaining = bpConfig.getMinRemainingChars();

        List<Pattern> customPatterns = new ArrayList<>();
        if (bpConfig.getCustomPatterns() != null) {
            for (String p : bpConfig.getCustomPatterns()) {
                try {
                    customPatterns.add(Pattern.compile(p, Pattern.MULTILINE));
                } catch (Exception e) {
                    log.warn("Invalid custom boilerplate pattern '{}': {}", p, e.getMessage());
                }
            }
        }

        int removed = 0;
        for (int i = 0; i < documents.size(); i++) {
            if (Thread.currentThread().isInterrupted()) break;

            Document doc = documents.get(i);
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            String cleaned = text;
            String sourceType = (String) doc.getMetadata().get("source_type");
            String contentType = (String) doc.getMetadata().get("content_type_hint");

            boolean isWeb = "WEB_CRAWL".equals(sourceType) || "URL".equals(sourceType)
                    || (contentType != null && contentType.contains("html"));
            boolean isEmail = "email".equals(doc.getMetadata().get("documentType"))
                    || "GMAIL".equals(sourceType) || "IMAP".equals(sourceType);

            // Web boilerplate
            if (bpConfig.isRemoveWebBoilerplate() && isWeb) {
                cleaned = COOKIE_CONSENT.matcher(cleaned).replaceAll("");
                cleaned = NAV_MENU.matcher(cleaned).replaceAll("");
                cleaned = SOCIAL_SHARE.matcher(cleaned).replaceAll("");
                cleaned = SUBSCRIBE_CTA.matcher(cleaned).replaceAll("");
            }

            // Email boilerplate
            if (bpConfig.isRemoveEmailSignatures() && isEmail) {
                // Truncate at signature separator (keep content before it)
                java.util.regex.Matcher sigMatcher = EMAIL_SIGNATURE_SEPARATOR.matcher(cleaned);
                if (sigMatcher.find()) {
                    String beforeSig = cleaned.substring(0, sigMatcher.start()).trim();
                    if (beforeSig.length() >= minRemaining) {
                        cleaned = beforeSig;
                    }
                }
                cleaned = EMAIL_AUTO_REPLY.matcher(cleaned).replaceAll("");
                cleaned = EMAIL_DISCLAIMER.matcher(cleaned).replaceAll("");
            }

            // Legal disclaimers
            if (bpConfig.isRemoveLegalDisclaimers()) {
                cleaned = LEGAL_DISCLAIMER.matcher(cleaned).replaceAll("");
            }

            // Custom patterns
            for (Pattern p : customPatterns) {
                cleaned = p.matcher(cleaned).replaceAll("");
            }

            // Clean up resulting whitespace
            cleaned = cleaned.replaceAll("(?m)^\\s+$", "").replaceAll("\n{3,}", "\n\n").trim();

            // Safety: don't over-remove
            if (cleaned.length() < minRemaining && text.length() >= minRemaining) {
                continue; // keep original
            }

            if (!cleaned.equals(text)) {
                Document cleanedDoc = new Document(cleaned);
                cleanedDoc.getMetadata().putAll(doc.getMetadata());
                cleanedDoc.getMetadata().put("boilerplate_removed", true);
                cleanedDoc.getMetadata().put("boilerplate_chars_removed", text.length() - cleaned.length());
                documents.set(i, cleanedDoc);
                removed++;
            }
        }

        log.debug("Boilerplate removal processed {}/{} documents", removed, documents.size());
        return documents;
    }
}
