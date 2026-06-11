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

package ai.kompile.loader.web;

import ai.kompile.core.graphrag.GraphConstants;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects whether an HTML document is a rendered email and extracts
 * standard {@code email.*} metadata keys from the DOM structure.
 *
 * <p>This enables the existing {@code EmailGraphExtractor} (from
 * kompile-loader-email-inbox) to create PERSON entities and relationship
 * edges (SENT_BY, SENT_TO, HAS_ATTACHMENT) from HTML-rendered emails,
 * which would otherwise be treated as plain web pages.
 *
 * <p>Detection heuristics:
 * <ul>
 *   <li>CSS class patterns: .from-line, .to-line, .subject, .date-line</li>
 *   <li>Semantic text patterns: "From:", "To:", "Cc:", "Date:" with adjacent email addresses</li>
 *   <li>Attachment links: anchors referencing .xlsx, .pdf, .doc, .pptx files</li>
 * </ul>
 */
public class HtmlEmailMetadataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(HtmlEmailMetadataExtractor.class);

    // Email address pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    // "Name <email>" pattern
    private static final Pattern NAME_EMAIL_PATTERN = Pattern.compile(
            "([^<>]+?)\\s*<\\s*([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})\\s*>");

    // Attachment file extensions
    private static final Set<String> ATTACHMENT_EXTENSIONS = Set.of(
            ".xlsx", ".xls", ".xlsm", ".csv",
            ".pdf",
            ".doc", ".docx",
            ".ppt", ".pptx",
            ".zip", ".gz", ".tar",
            ".png", ".jpg", ".jpeg", ".gif",
            ".txt", ".json", ".xml"
    );

    /**
     * Attempts to detect email metadata in an HTML document.
     * Returns true if the document appears to be a rendered email.
     */
    public boolean detectAndExtract(org.jsoup.nodes.Document doc, Map<String, Object> metadata) {
        // Strategy 1: CSS class-based detection (Outlook/webmail style)
        if (extractByCssClasses(doc, metadata)) {
            logger.debug("Detected HTML email via CSS class patterns");
            extractAttachmentLinks(doc, metadata);
            metadata.put(GraphConstants.META_CONTENT_TYPE_HINT, "email");
            return true;
        }

        // Strategy 2: Semantic text pattern detection (From:/To:/Date: labels)
        if (extractByTextPatterns(doc, metadata)) {
            logger.debug("Detected HTML email via text patterns");
            extractAttachmentLinks(doc, metadata);
            metadata.put(GraphConstants.META_CONTENT_TYPE_HINT, "email");
            return true;
        }

        return false;
    }

    /**
     * Strategy 1: Extract email metadata from CSS class conventions.
     * Looks for .from-line, .to-line, .subject, .date-line, etc.
     */
    private boolean extractByCssClasses(org.jsoup.nodes.Document doc, Map<String, Object> metadata) {
        int signals = 0;

        // Subject
        Element subject = doc.selectFirst(".subject, [class*=subject]");
        if (subject != null && !subject.text().isBlank()) {
            metadata.put("email.subject", subject.text().trim());
            signals++;
        }

        // From line
        Element fromLine = doc.selectFirst(".from-line, [class*=from-line], [class*=sender]");
        if (fromLine != null) {
            extractSenderFromElement(fromLine, metadata);
            signals++;
        }

        // To line(s)
        Elements toLines = doc.select(".to-line, [class*=to-line], [class*=recipient]");
        if (!toLines.isEmpty()) {
            extractRecipientsFromElements(toLines, metadata);
            signals++;
        }

        // Date
        Element dateLine = doc.selectFirst(".date-line, [class*=date-line], [class*=send-date]");
        if (dateLine != null && !dateLine.text().isBlank()) {
            metadata.put("email.date", dateLine.text().trim());
            signals++;
        }

        // Need at least 2 signals to confirm this is an email
        return signals >= 2;
    }

    /**
     * Strategy 2: Extract from text patterns like "From: Name &lt;email&gt;"
     */
    private boolean extractByTextPatterns(org.jsoup.nodes.Document doc, Map<String, Object> metadata) {
        Element body = doc.body();
        if (body == null) return false;

        // Use wholeText() to preserve newlines — body.text() strips them,
        // making \n terminators in regex patterns dead
        String bodyText = body.wholeText();
        int signals = 0;

        // Look for "From:" pattern — terminate at next field keyword or newline
        Pattern fromPattern = Pattern.compile("From:\\s*(.+?)(?=\\s*(?:To:|Cc:|Date:|Subject:)|\\n|$)", Pattern.CASE_INSENSITIVE);
        Matcher fromMatcher = fromPattern.matcher(bodyText);
        if (fromMatcher.find()) {
            String fromText = fromMatcher.group(1).trim();
            parseFromString(fromText, metadata);
            signals++;
        }

        // Look for "To:" pattern
        Pattern toPattern = Pattern.compile("To:\\s*(.+?)(?=\\s*(?:Cc:|Bcc:|Date:|Subject:)|\\n|$)", Pattern.CASE_INSENSITIVE);
        Matcher toMatcher = toPattern.matcher(bodyText);
        if (toMatcher.find()) {
            metadata.put("email.to", toMatcher.group(1).trim());
            signals++;
        }

        // Look for "Cc:" pattern
        Pattern ccPattern = Pattern.compile("Cc:\\s*(.+?)(?=\\s*(?:Bcc:|Date:|Subject:)|\\n|$)", Pattern.CASE_INSENSITIVE);
        Matcher ccMatcher = ccPattern.matcher(bodyText);
        if (ccMatcher.find()) {
            metadata.put("email.cc", ccMatcher.group(1).trim());
            signals++;
        }

        // Look for "Date:" or "Sent:" pattern
        Pattern datePattern = Pattern.compile("(?:Date|Sent):\\s*(.+?)(?=\\s*(?:From:|To:|Cc:|Subject:)|\\n|$)", Pattern.CASE_INSENSITIVE);
        Matcher dateMatcher = datePattern.matcher(bodyText);
        if (dateMatcher.find()) {
            metadata.put("email.date", dateMatcher.group(1).trim());
            signals++;
        }

        // Look for "Subject:" pattern (if not already set by title)
        if (!metadata.containsKey("email.subject")) {
            Pattern subjectPattern = Pattern.compile("Subject:\\s*(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);
            Matcher subjectMatcher = subjectPattern.matcher(bodyText);
            if (subjectMatcher.find()) {
                metadata.put("email.subject", subjectMatcher.group(1).trim());
                signals++;
            }
        }

        return signals >= 2;
    }

    /**
     * Extracts sender info from a DOM element containing the "from" line.
     */
    private void extractSenderFromElement(Element fromLine, Map<String, Object> metadata) {
        // Check for nested .name and .email elements
        Element nameEl = fromLine.selectFirst(".name, [class*=sender-name]");
        Element emailEl = fromLine.selectFirst(".email, [class*=sender-email], [class*=email-addr]");

        String name = nameEl != null ? nameEl.text().trim() : "";
        String email = "";

        if (emailEl != null) {
            // Extract email from text like "<s.chen@northstar.co>"
            Matcher m = EMAIL_PATTERN.matcher(emailEl.text());
            if (m.find()) {
                email = m.group();
            }
        }

        // Fallback: scan the whole from-line text
        if (email.isEmpty()) {
            Matcher m = EMAIL_PATTERN.matcher(fromLine.text());
            if (m.find()) {
                email = m.group();
            }
        }
        if (name.isEmpty()) {
            Matcher m = NAME_EMAIL_PATTERN.matcher(fromLine.text());
            if (m.find()) {
                name = m.group(1).trim();
                if (email.isEmpty()) email = m.group(2);
            }
        }

        if (!name.isEmpty() || !email.isEmpty()) {
            String fullFrom = name.isEmpty() ? email :
                    email.isEmpty() ? name : name + " <" + email + ">";
            metadata.put("email.from", fullFrom);
            if (!name.isEmpty()) metadata.put("email.fromName", name);
            if (!email.isEmpty()) metadata.put("email.fromAddress", email);
        }
    }

    /**
     * Extracts To and Cc recipients from DOM elements.
     */
    private void extractRecipientsFromElements(Elements toLines, Map<String, Object> metadata) {
        StringBuilder toBuilder = new StringBuilder();
        StringBuilder ccBuilder = new StringBuilder();

        for (Element line : toLines) {
            String text = line.text().trim();
            if (text.toLowerCase().startsWith("cc:") || text.toLowerCase().startsWith("cc ")) {
                String ccText = text.replaceFirst("(?i)^cc:?\\s*", "");
                if (ccBuilder.length() > 0) ccBuilder.append("; ");
                ccBuilder.append(ccText);
            } else {
                // Strip "To:" prefix if present
                String toText = text.replaceFirst("(?i)^to:?\\s*", "");
                if (toBuilder.length() > 0) toBuilder.append("; ");
                toBuilder.append(toText);
            }
        }

        if (toBuilder.length() > 0) {
            metadata.put("email.to", toBuilder.toString());
        }
        if (ccBuilder.length() > 0) {
            metadata.put("email.cc", ccBuilder.toString());
        }
    }

    /**
     * Parses a "From" string like "Sarah Chen <s.chen@northstar.co>" into metadata.
     */
    private void parseFromString(String fromText, Map<String, Object> metadata) {
        Matcher m = NAME_EMAIL_PATTERN.matcher(fromText);
        if (m.find()) {
            String name = m.group(1).trim();
            String email = m.group(2);
            metadata.put("email.from", name + " <" + email + ">");
            metadata.put("email.fromName", name);
            metadata.put("email.fromAddress", email);
        } else {
            Matcher emailOnly = EMAIL_PATTERN.matcher(fromText);
            if (emailOnly.find()) {
                metadata.put("email.from", fromText);
                metadata.put("email.fromAddress", emailOnly.group());
            } else {
                metadata.put("email.from", fromText);
            }
        }
    }

    /**
     * Extracts attachment file references from anchor tags in the document.
     */
    private void extractAttachmentLinks(org.jsoup.nodes.Document doc, Map<String, Object> metadata) {
        Elements links = doc.select("a[href]");
        List<String> attachmentNames = new ArrayList<>();
        Set<String> seenHrefs = new HashSet<>();

        for (Element link : links) {
            String href = link.attr("href");
            // Strip query params and fragments before checking extension
            String hrefPath = href.contains("?") ? href.substring(0, href.indexOf('?')) : href;
            hrefPath = hrefPath.contains("#") ? hrefPath.substring(0, hrefPath.indexOf('#')) : hrefPath;
            String hrefPathLower = hrefPath.toLowerCase();
            for (String ext : ATTACHMENT_EXTENSIONS) {
                if (hrefPathLower.endsWith(ext)) {
                    // Deduplicate by href path — multiple links (filename, "Download", "Open")
                    // can point to the same attachment file
                    String normalizedHref = hrefPathLower;
                    if (seenHrefs.contains(normalizedHref)) {
                        break;
                    }
                    seenHrefs.add(normalizedHref);

                    // Always extract filename from href path — link text can be
                    // "Download", "Open", or other button labels instead of the filename
                    String name;
                    if (hrefPath.contains("/")) {
                        name = hrefPath.substring(hrefPath.lastIndexOf('/') + 1);
                    } else {
                        name = hrefPath;
                    }
                    if (!name.isEmpty()) {
                        attachmentNames.add(name);
                    }
                    break;
                }
            }
        }

        if (!attachmentNames.isEmpty()) {
            metadata.put(GraphConstants.META_EMAIL_ATTACHMENT_NAMES, attachmentNames);
        }
    }
}
