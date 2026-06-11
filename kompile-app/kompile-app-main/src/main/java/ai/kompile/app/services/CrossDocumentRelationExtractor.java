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

package ai.kompile.app.services;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts cross-document relationships using deterministic (no LLM) strategies.
 *
 * <p>Six strategies are applied:
 * <ul>
 *   <li><b>Strategy A: Attachment resolution</b> — matches email attachment filenames to other documents</li>
 *   <li><b>Strategy B: Version chain</b> — groups documents by normalized base name to detect versions</li>
 *   <li><b>Strategy C: Process-to-data references</b> — detects when a document references entities
 *       (sheet names, column headers) from other documents</li>
 *   <li><b>Strategy D: Hyperlink resolution</b> — matches PDF hyperlink URLs to other document URLs/filenames</li>
 *   <li><b>Strategy E: Shared author</b> — links documents written by the same author</li>
 *   <li><b>Strategy F: Shared keywords</b> — links documents with overlapping keywords/topics</li>
 * </ul>
 *
 * <p>All edges use {@code EdgeType.USER_DEFINED} with semantic type information
 * stored in {@code metadataJson} — no hardcoded enum values.
 */
@Service
public class CrossDocumentRelationExtractor {

    private static final Logger logger = LoggerFactory.getLogger(CrossDocumentRelationExtractor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Pattern to strip version/revision suffixes for base-name matching
    private static final Pattern VERSION_SUFFIX = Pattern.compile(
            "(_v\\d+|_FINAL|_final|_DRAFT|_draft|_Q\\d|_\\d{4}-\\d{2}-\\d{2}|_\\d{8})$"
    );

    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphNodeRepository graphNodeRepository;

    public CrossDocumentRelationExtractor(
            KnowledgeGraphService knowledgeGraphService,
            GraphNodeRepository graphNodeRepository) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.graphNodeRepository = graphNodeRepository;
    }

    /**
     * Extracts cross-document relationships from a batch of loaded documents.
     *
     * @param documents   The documents loaded in this batch
     * @param factSheetId The fact sheet scope (nullable)
     * @return Number of relationships created
     */
    public int extractRelations(List<Document> documents, Long factSheetId) {
        if (documents == null || documents.size() < 2) {
            return 0;
        }

        int totalCreated = 0;

        try {
            // Build lookup maps from documents
            Map<String, DocumentInfo> docsByFileName = buildFileNameIndex(documents);

            // Strategy A: Attachment resolution (email → referenced files)
            totalCreated += resolveAttachments(documents, docsByFileName, factSheetId);

            // Strategy B: Version chain detection
            totalCreated += detectVersionChains(docsByFileName, factSheetId);

            // Strategy C: Process-to-data references
            totalCreated += detectProcessDataReferences(documents, docsByFileName, factSheetId);

            // Strategy D: Hyperlink resolution (PDF hyperlinks → other documents)
            totalCreated += resolveHyperlinks(documents, docsByFileName, factSheetId);

            // Strategy E: Shared author linking
            totalCreated += detectSharedAuthors(documents, factSheetId);

            // Strategy F: Shared keywords/topics
            totalCreated += detectSharedKeywords(documents, factSheetId);

        } catch (Exception e) {
            logger.warn("Cross-document relation extraction failed (non-fatal): {}", e.getMessage());
        }

        if (totalCreated > 0) {
            logger.info("Created {} cross-document relationships for factSheet={}", totalCreated, factSheetId);
        }

        return totalCreated;
    }

    // =========================================================================
    // Strategy A: Attachment resolution
    // =========================================================================

    private int resolveAttachments(List<Document> documents, Map<String, DocumentInfo> docsByFileName,
                                    Long factSheetId) {
        int created = 0;

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;

            // Collect attachment file names from all supported sources
            List<String> attachmentNames = new ArrayList<>();
            String parentLabel = null;

            // --- Email attachments (plural list from HTML emails) ---
            @SuppressWarnings("unchecked")
            List<String> emailAtts = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAMES) instanceof List
                    ? (List<String>) meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAMES) : null;
            if (emailAtts != null && !emailAtts.isEmpty()) {
                attachmentNames.addAll(emailAtts);
                parentLabel = (String) meta.get(GraphConstants.META_EMAIL_SUBJECT);
                if (parentLabel == null) parentLabel = (String) meta.get("gworkspace.gmail.subject");
            }

            // Also check singular form (from MIME-parsed email attachment documents)
            if (attachmentNames.isEmpty()) {
                String singleAttachment = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAME) instanceof String
                        ? (String) meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAME) : null;
                if (singleAttachment != null && !singleAttachment.isEmpty()) {
                    attachmentNames.add(singleAttachment);
                    parentLabel = (String) meta.get(GraphConstants.META_EMAIL_SUBJECT);
                    if (parentLabel == null) parentLabel = (String) meta.get("gworkspace.gmail.subject");
                }
            }

            // --- Gmail-style attachments (List of Maps with "filename" key) ---
            if (attachmentNames.isEmpty()) {
                Object gmailAtts = meta.get("gworkspace.gmail.attachments");
                if (gmailAtts instanceof List<?> gmailAttList && !gmailAttList.isEmpty()) {
                    for (Object attObj : gmailAttList) {
                        if (attObj instanceof Map<?, ?> attMap) {
                            Object fn = attMap.get("filename");
                            if (fn != null && !fn.toString().isBlank()) {
                                attachmentNames.add(fn.toString().trim());
                            }
                        }
                    }
                    if (!attachmentNames.isEmpty()) {
                        parentLabel = (String) meta.get("gworkspace.gmail.subject");
                    }
                }
            }

            // --- Confluence attachments (List of Maps with "title" key) ---
            if (attachmentNames.isEmpty()) {
                Object confAtts = meta.get(GraphConstants.META_CONFLUENCE_ATTACHMENTS);
                if (confAtts instanceof List<?> confAttList && !confAttList.isEmpty()) {
                    for (Object attObj : confAttList) {
                        if (attObj instanceof Map<?, ?> attMap) {
                            Object title = attMap.get("title");
                            if (title != null && !title.toString().isBlank()) {
                                attachmentNames.add(title.toString().trim());
                            }
                        }
                    }
                    if (!attachmentNames.isEmpty()) {
                        parentLabel = meta.get("confluence.title") instanceof String
                                ? (String) meta.get("confluence.title") : "Confluence page";
                    }
                }
            }

            // --- Slack file shares (List of Maps with "name" key) ---
            if (attachmentNames.isEmpty()) {
                Object slackFiles = meta.get("slack.files");
                if (slackFiles instanceof List<?> slackFileList && !slackFileList.isEmpty()) {
                    for (Object fileObj : slackFileList) {
                        if (fileObj instanceof Map<?, ?> fileMap) {
                            Object name = fileMap.get("name");
                            if (name != null && !name.toString().isBlank()) {
                                attachmentNames.add(name.toString().trim());
                            }
                        }
                    }
                    if (!attachmentNames.isEmpty()) {
                        parentLabel = meta.get("slack.channelName") instanceof String
                                ? "Slack #" + meta.get("slack.channelName") : "Slack message";
                    }
                }
            }

            if (attachmentNames.isEmpty()) continue;

            String docNodeId = findDocumentNodeId(doc, factSheetId);
            if (docNodeId == null) continue;

            for (String attachmentName : attachmentNames) {
                // Try exact match first, then fuzzy
                DocumentInfo target = findDocumentByFileName(docsByFileName, attachmentName);
                if (target == null) continue;

                String targetNodeId = findDocumentNodeId(target, factSheetId);
                if (targetNodeId == null) continue;

                if (crossDocumentEdgeExists(docNodeId, targetNodeId, GraphConstants.REL_ATTACHMENT_OF, factSheetId)) {
                    continue;
                }

                try {
                    Map<String, Object> edgeMeta = new LinkedHashMap<>();
                    edgeMeta.put("semanticType", GraphConstants.REL_ATTACHMENT_OF);
                    edgeMeta.put("parentDocument", parentLabel);
                    edgeMeta.put("attachmentFilename", attachmentName);

                    knowledgeGraphService.createEdgeWithMetadata(
                            docNodeId, targetNodeId, EdgeType.USER_DEFINED,
                            0.95, GraphConstants.REL_ATTACHMENT_OF,
                            "Attachment: " + attachmentName,
                            toJson(edgeMeta), EdgeProvenance.EXTRACTED, factSheetId);
                    created++;

                    logger.debug("Created ATTACHMENT_OF edge: '{}' → '{}'",
                            parentLabel, attachmentName);
                } catch (Exception e) {
                    logger.warn("Failed to create attachment edge from '{}' to '{}': {}",
                            parentLabel, attachmentName, e.getMessage());
                }
            }
        }

        return created;
    }

    // =========================================================================
    // Strategy B: Version chain detection
    // =========================================================================

    private int detectVersionChains(Map<String, DocumentInfo> docsByFileName, Long factSheetId) {
        int created = 0;

        // Group documents by normalized base name
        Map<String, List<DocumentInfo>> groups = new LinkedHashMap<>();
        for (DocumentInfo info : docsByFileName.values()) {
            String baseName = normalizeFileName(info.fileName);
            groups.computeIfAbsent(baseName, k -> new ArrayList<>()).add(info);
        }

        for (Map.Entry<String, List<DocumentInfo>> entry : groups.entrySet()) {
            List<DocumentInfo> versions = entry.getValue();
            if (versions.size() < 2) continue;

            // Sort by version number (numeric-aware) then by file name as tiebreaker
            versions.sort(Comparator.comparingInt(this::extractVersionNumber)
                    .thenComparing(v -> v.fileName));

            for (int i = 0; i < versions.size() - 1; i++) {
                DocumentInfo older = versions.get(i);
                DocumentInfo newer = versions.get(i + 1);

                String olderNodeId = findDocumentNodeId(older, factSheetId);
                String newerNodeId = findDocumentNodeId(newer, factSheetId);
                if (olderNodeId == null || newerNodeId == null) continue;

                if (crossDocumentEdgeExists(newerNodeId, olderNodeId, GraphConstants.REL_VERSION_OF, factSheetId)) {
                    continue;
                }

                try {
                    Map<String, Object> edgeMeta = new LinkedHashMap<>();
                    edgeMeta.put("semanticType", GraphConstants.REL_VERSION_OF);
                    edgeMeta.put("olderVersion", older.fileName);
                    edgeMeta.put("newerVersion", newer.fileName);
                    edgeMeta.put("baseGroup", entry.getKey());

                    knowledgeGraphService.createEdgeWithMetadata(
                            newerNodeId, olderNodeId, EdgeType.USER_DEFINED,
                            0.85, GraphConstants.REL_VERSION_OF,
                            "Version chain: " + newer.fileName + " → " + older.fileName,
                            toJson(edgeMeta), EdgeProvenance.INFERRED, factSheetId);
                    created++;

                    logger.debug("Created VERSION_OF edge: '{}' → '{}'", newer.fileName, older.fileName);
                } catch (Exception e) {
                    logger.warn("Failed to create version edge '{}' → '{}': {}",
                            newer.fileName, older.fileName, e.getMessage());
                }
            }
        }

        return created;
    }

    // =========================================================================
    // Strategy C: Process-to-data references
    // =========================================================================

    private int detectProcessDataReferences(List<Document> documents,
                                             Map<String, DocumentInfo> docsByFileName,
                                             Long factSheetId) {
        int created = 0;

        // Collect sheet names and significant column headers from spreadsheet documents
        Map<String, DocumentInfo> sheetIndex = new LinkedHashMap<>();
        for (DocumentInfo info : docsByFileName.values()) {
            Map<String, Object> meta = info.doc.getMetadata();
            if (meta == null) continue;

            String sheetName = (String) meta.get(GraphConstants.META_SHEET_NAME);
            if (sheetName != null && sheetName.length() > 2) {
                sheetIndex.put(sheetName.toLowerCase(), info);
            }

            // Check both "headers" (List<String>) and "table_headers" (comma-separated String)
            @SuppressWarnings("unchecked")
            List<String> headers = meta.get("headers") instanceof List
                    ? (List<String>) meta.get("headers") : null;

            // Loaders write table_headers as comma-joined String
            if (headers == null) {
                String tableHeaders = (String) meta.get(GraphConstants.META_TABLE_HEADERS);
                if (tableHeaders != null && !tableHeaders.isEmpty()) {
                    headers = Arrays.asList(tableHeaders.split(","));
                }
            }

            if (headers != null) {
                // Only index distinctive headers (>4 chars, not generic)
                for (String header : headers) {
                    String h = header != null ? header.trim() : null;
                    if (h != null && h.length() > 4 && !isGenericHeader(h)) {
                        sheetIndex.putIfAbsent(h.toLowerCase(), info);
                    }
                }
            }
        }

        if (sheetIndex.isEmpty()) return 0;

        // For each non-spreadsheet document, check if its text mentions sheet names / headers
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            String contentType = meta != null ? (String) meta.get(GraphConstants.META_CONTENT_TYPE) : null;
            // Skip table documents (they *are* the data, not references to it)
            if ("table".equals(contentType)) continue;

            String content = doc.getText();
            if (content == null || content.length() < 50) continue;

            String contentLower = content.toLowerCase();
            String sourceNodeId = findDocumentNodeId(doc, factSheetId);
            if (sourceNodeId == null) continue;

            Set<String> alreadyLinked = new HashSet<>();

            for (Map.Entry<String, DocumentInfo> sheetEntry : sheetIndex.entrySet()) {
                String term = sheetEntry.getKey();
                DocumentInfo targetInfo = sheetEntry.getValue();

                // Avoid self-links
                if (targetInfo.doc == doc) continue;

                // Check if the term appears in the document content
                if (contentLower.contains(term)) {
                    String targetNodeId = findDocumentNodeId(targetInfo, factSheetId);
                    if (targetNodeId == null || alreadyLinked.contains(targetNodeId)) continue;
                    alreadyLinked.add(targetNodeId);

                    if (crossDocumentEdgeExists(sourceNodeId, targetNodeId, GraphConstants.REL_REFERENCES_DATA, factSheetId)) {
                        continue;
                    }

                    try {
                        Map<String, Object> edgeMeta = new LinkedHashMap<>();
                        edgeMeta.put("semanticType", GraphConstants.REL_REFERENCES_DATA);
                        edgeMeta.put("referencedTerm", term);
                        edgeMeta.put("referencedSheet", targetInfo.fileName);

                        knowledgeGraphService.createEdgeWithMetadata(
                                sourceNodeId, targetNodeId, EdgeType.USER_DEFINED,
                                0.7, GraphConstants.REL_REFERENCES_DATA,
                                "References data term: " + term,
                                toJson(edgeMeta), EdgeProvenance.INFERRED, factSheetId);
                        created++;

                        logger.debug("Created REFERENCES_DATA edge: document → '{}' (term: {})",
                                targetInfo.fileName, term);
                    } catch (Exception e) {
                        logger.warn("Failed to create process-data edge for term '{}': {}",
                                term, e.getMessage());
                    }
                }
            }
        }

        return created;
    }

    // =========================================================================
    // Strategy D: Hyperlink resolution (PDF hyperlinks → documents)
    // =========================================================================

    private int resolveHyperlinks(List<Document> documents, Map<String, DocumentInfo> docsByFileName,
                                   Long factSheetId) {
        int created = 0;

        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;

            // Only process annotation documents from PDF loader
            String extractionType = (String) meta.get(GraphConstants.META_PDF_EXTRACTION_TYPE);
            if (!"annotations".equals(extractionType)) continue;

            String text = doc.getText();
            if (text == null || text.isEmpty()) continue;

            String sourceNodeId = findDocumentNodeId(doc, factSheetId);
            if (sourceNodeId == null) continue;

            // Extract URLs from annotation text
            java.util.regex.Matcher urlMatcher = java.util.regex.Pattern.compile(
                    "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);

            Set<String> processedUrls = new HashSet<>();
            while (urlMatcher.find()) {
                String url = urlMatcher.group();
                if (!processedUrls.add(url.toLowerCase())) continue;

                // Try to match URL to another document by filename in the URL path
                String urlPath = url;
                int lastSlash = urlPath.lastIndexOf('/');
                String urlFileName = lastSlash >= 0 ? urlPath.substring(lastSlash + 1) : urlPath;
                // Remove query string
                int queryIdx = urlFileName.indexOf('?');
                if (queryIdx > 0) urlFileName = urlFileName.substring(0, queryIdx);
                // Decode common URL encodings
                urlFileName = urlFileName.replace("%20", " ");

                DocumentInfo target = findDocumentByFileName(docsByFileName, urlFileName);
                if (target == null) continue;

                String targetNodeId = findDocumentNodeId(target, factSheetId);
                if (targetNodeId == null || targetNodeId.equals(sourceNodeId)) continue;

                if (crossDocumentEdgeExists(sourceNodeId, targetNodeId, GraphConstants.REL_HYPERLINK_TO, factSheetId)) {
                    continue;
                }

                try {
                    Map<String, Object> edgeMeta = new LinkedHashMap<>();
                    edgeMeta.put("semanticType", GraphConstants.REL_HYPERLINK_TO);
                    edgeMeta.put("url", url);
                    edgeMeta.put("targetFileName", target.fileName);

                    knowledgeGraphService.createEdgeWithMetadata(
                            sourceNodeId, targetNodeId, EdgeType.USER_DEFINED,
                            0.9, GraphConstants.REL_HYPERLINK_TO,
                            "PDF hyperlinks to: " + target.fileName,
                            toJson(edgeMeta), EdgeProvenance.EXTRACTED, factSheetId);
                    created++;
                    logger.debug("Created HYPERLINK_TO edge: PDF → '{}' (via {})", target.fileName, url);
                } catch (Exception e) {
                    logger.warn("Failed to create hyperlink edge for URL '{}': {}", url, e.getMessage());
                }
            }
        }

        return created;
    }

    // =========================================================================
    // Strategy E: Shared author linking
    // =========================================================================

    private int detectSharedAuthors(List<Document> documents, Long factSheetId) {
        int created = 0;

        // Group documents by author — check multiple author field names used by different loaders
        Map<String, List<Document>> docsByAuthor = new LinkedHashMap<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            String author = resolveAuthor(meta);
            if (author == null || author.isBlank()) continue;
            // Normalize author name for grouping
            docsByAuthor.computeIfAbsent(author.toLowerCase().trim(), k -> new ArrayList<>()).add(doc);
        }

        // Create SHARED_AUTHOR edges between documents by the same author
        for (Map.Entry<String, List<Document>> entry : docsByAuthor.entrySet()) {
            List<Document> authorDocs = entry.getValue();
            if (authorDocs.size() < 2) continue;

            for (int i = 0; i < authorDocs.size(); i++) {
                for (int j = i + 1; j < authorDocs.size(); j++) {
                    String nodeIdA = findDocumentNodeId(authorDocs.get(i), factSheetId);
                    String nodeIdB = findDocumentNodeId(authorDocs.get(j), factSheetId);
                    if (nodeIdA == null || nodeIdB == null) continue;

                    try {
                        if (crossDocumentEdgeExists(nodeIdA, nodeIdB, GraphConstants.REL_SHARED_AUTHOR, factSheetId)) {
                            continue;
                        }

                        String author = entry.getKey();
                        Map<String, Object> edgeMeta = new LinkedHashMap<>();
                        edgeMeta.put("semanticType", GraphConstants.REL_SHARED_AUTHOR);
                        edgeMeta.put("author", author);

                        knowledgeGraphService.createEdgeWithMetadata(
                                nodeIdA, nodeIdB, EdgeType.USER_DEFINED,
                                0.75, GraphConstants.REL_SHARED_AUTHOR,
                                "Both authored by: " + author,
                                toJson(edgeMeta), EdgeProvenance.INFERRED, factSheetId);
                        created++;
                    } catch (Exception e) {
                        logger.warn("Failed to create shared author edge: {}", e.getMessage());
                    }
                }
            }
        }

        return created;
    }

    // =========================================================================
    // Strategy F: Shared keywords/topics
    // =========================================================================

    private int detectSharedKeywords(List<Document> documents, Long factSheetId) {
        int created = 0;

        // Build keyword → document index — check multiple keyword field names
        Map<String, List<Document>> docsByKeyword = new LinkedHashMap<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;
            String keywords = resolveKeywords(meta);
            if (keywords == null || keywords.isBlank()) continue;

            for (String keyword : keywords.split("[,;|]")) {
                String normalized = keyword.trim().toLowerCase();
                if (normalized.length() > 2) {
                    docsByKeyword.computeIfAbsent(normalized, k -> new ArrayList<>()).add(doc);
                }
            }
        }

        // Link documents sharing the same keyword (minimum 2 docs per keyword)
        Set<String> createdPairs = new HashSet<>();
        for (Map.Entry<String, List<Document>> entry : docsByKeyword.entrySet()) {
            List<Document> keywordDocs = entry.getValue();
            if (keywordDocs.size() < 2) continue;

            for (int i = 0; i < keywordDocs.size(); i++) {
                for (int j = i + 1; j < keywordDocs.size(); j++) {
                    String nodeIdA = findDocumentNodeId(keywordDocs.get(i), factSheetId);
                    String nodeIdB = findDocumentNodeId(keywordDocs.get(j), factSheetId);
                    if (nodeIdA == null || nodeIdB == null) continue;

                    // Avoid duplicate edges between same pair
                    String pairKey = nodeIdA.compareTo(nodeIdB) < 0
                            ? nodeIdA + "|" + nodeIdB : nodeIdB + "|" + nodeIdA;
                    if (!createdPairs.add(pairKey)) continue;

                    try {
                        if (crossDocumentEdgeExists(nodeIdA, nodeIdB, GraphConstants.REL_SHARED_KEYWORD, factSheetId)) {
                            continue;
                        }

                        String keyword = entry.getKey();
                        Map<String, Object> edgeMeta = new LinkedHashMap<>();
                        edgeMeta.put("semanticType", GraphConstants.REL_SHARED_KEYWORD);
                        edgeMeta.put("keyword", keyword);

                        knowledgeGraphService.createEdgeWithMetadata(
                                nodeIdA, nodeIdB, EdgeType.USER_DEFINED,
                                0.65, GraphConstants.REL_SHARED_KEYWORD,
                                "Shared keyword: " + keyword,
                                toJson(edgeMeta), EdgeProvenance.INFERRED, factSheetId);
                        created++;
                    } catch (Exception e) {
                        logger.warn("Failed to create shared keyword edge: {}", e.getMessage());
                    }
                }
            }
        }

        return created;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Resolves the author from metadata, checking multiple namespaced fields used by different loaders.
     * Falls back through: author → gdocs.owner → gdocs.ownerEmail → email.from → slack.userId
     */
    private static String resolveAuthor(Map<String, Object> meta) {
        String author = meta.get("author") instanceof String s ? s : null;
        if (author != null && !author.isBlank()) return author;
        author = meta.get("gdocs.owner") instanceof String s ? s : null;
        if (author != null && !author.isBlank()) return author;
        author = meta.get("gdocs.ownerEmail") instanceof String s ? s : null;
        if (author != null && !author.isBlank()) return author;
        author = meta.get("email.from") instanceof String s ? s : null;
        if (author != null && !author.isBlank()) return author;
        author = meta.get("slack.userId") instanceof String s ? s : null;
        if (author != null && !author.isBlank()) return author;
        author = meta.get("email.senderName") instanceof String s ? s : null;
        return (author != null && !author.isBlank()) ? author : null;
    }

    /**
     * Resolves keywords from metadata, checking multiple namespaced fields.
     * Falls back through: keywords → gdocs.labels → email.labels → slack.channelName
     */
    private static String resolveKeywords(Map<String, Object> meta) {
        String keywords = meta.get("keywords") instanceof String s ? s : null;
        if (keywords != null && !keywords.isBlank()) return keywords;
        // Google Docs labels/categories
        Object labels = meta.get("gdocs.labels");
        if (labels instanceof String s && !s.isBlank()) return s;
        if (labels instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        }
        // Email labels (Gmail)
        Object emailLabels = meta.get("email.labels");
        if (emailLabels instanceof String s && !s.isBlank()) return s;
        if (emailLabels instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        }
        // Tika-extracted tags/categories
        String tags = meta.get("tika.tags") instanceof String s ? s : null;
        if (tags != null && !tags.isBlank()) return tags;
        String categories = meta.get("tika.categories") instanceof String s ? s : null;
        return (categories != null && !categories.isBlank()) ? categories : null;
    }

    private Map<String, DocumentInfo> buildFileNameIndex(List<Document> documents) {
        Map<String, DocumentInfo> index = new LinkedHashMap<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;

            String fileName = (String) meta.get(GraphConstants.META_FILE_NAME);
            if (fileName == null) fileName = (String) meta.get(GraphConstants.META_SOURCE);
            if (fileName == null) fileName = (String) meta.get("file_name");
            if (fileName == null) continue;

            index.put(fileName.toLowerCase(), new DocumentInfo(doc, fileName));
        }
        return index;
    }

    private DocumentInfo findDocumentByFileName(Map<String, DocumentInfo> index, String searchName) {
        // Exact match
        DocumentInfo exact = index.get(searchName.toLowerCase());
        if (exact != null) return exact;

        // Fuzzy: strip common suffixes and try again
        String normalized = searchName.toLowerCase()
                .replaceAll("_v\\d+", "")
                .replaceAll("_final", "")
                .replaceAll("_draft", "");

        for (Map.Entry<String, DocumentInfo> entry : index.entrySet()) {
            String key = entry.getKey()
                    .replaceAll("_v\\d+", "")
                    .replaceAll("_final", "")
                    .replaceAll("_draft", "");
            if (key.equals(normalized)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private String normalizeFileName(String fileName) {
        // Remove extension
        String name = fileName;
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            name = name.substring(0, lastDot);
        }

        // Strip leading numeric prefix (e.g. "04a_", "05a_", "02_", "08_")
        name = name.replaceFirst("^\\d+[a-zA-Z]?_", "");

        // Strip version suffixes iteratively
        Matcher m = VERSION_SUFFIX.matcher(name);
        while (m.find()) {
            name = name.substring(0, m.start());
            m = VERSION_SUFFIX.matcher(name);
        }

        // Strip trailing month-day patterns like _May26, _Jun03
        name = name.replaceFirst("_(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\d{1,2}$", "");

        return name.toLowerCase();
    }

    /**
     * Extracts the highest version number from a filename for sorting.
     * Handles patterns like _v3, _v10, _FINAL (treated as MAX), leading prefix numbers (04a_).
     * Returns 0 if no version indicator is found.
     */
    private int extractVersionNumber(DocumentInfo info) {
        return extractVersionNumberFromName(info.fileName);
    }

    private int extractVersionNumberFromName(String name) {

        // Check for _FINAL suffix (always sorts last)
        if (name.toLowerCase().contains("_final")) {
            return Integer.MAX_VALUE;
        }

        // Try _v<number> patterns — find the last one
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("_v(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name);
        int lastVersion = -1;
        while (m.find()) {
            lastVersion = Integer.parseInt(m.group(1));
        }
        if (lastVersion >= 0) return lastVersion;

        // Try leading numeric prefix (e.g. "04a_" → 4, "05a_" → 5)
        java.util.regex.Matcher prefixM = java.util.regex.Pattern.compile("^(\\d+)").matcher(name);
        if (prefixM.find()) {
            return Integer.parseInt(prefixM.group(1));
        }

        return 0;
    }

    private String findDocumentNodeId(Document doc, Long factSheetId) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) return null;

        // Try to find an existing DOCUMENT node by external ID
        String externalId = (String) meta.get("documentNodeId");
        if (externalId != null) return externalId;

        // Try by source path
        String sourcePath = (String) meta.get(GraphConstants.META_SOURCE);
        if (sourcePath == null) sourcePath = (String) meta.get(GraphConstants.META_SOURCE_PATH);
        if (sourcePath == null) sourcePath = (String) meta.get(GraphConstants.META_FILE_NAME);
        if (sourcePath == null) return null;

        // Look up in the graph
        Optional<GraphNode> node;
        if (factSheetId != null) {
            node = graphNodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                    sourcePath, NodeLevel.DOCUMENT, factSheetId);
        } else {
            node = graphNodeRepository.findByExternalIdAndNodeType(sourcePath, NodeLevel.DOCUMENT);
        }

        return node.map(GraphNode::getNodeId).orElse(null);
    }

    private String findDocumentNodeId(DocumentInfo info, Long factSheetId) {
        return findDocumentNodeId(info.doc, factSheetId);
    }

    private boolean isGenericHeader(String header) {
        String lower = header.toLowerCase().trim();
        return lower.equals("name") || lower.equals("value") || lower.equals("type")
                || lower.equals("date") || lower.equals("status") || lower.equals("notes")
                || lower.equals("total") || lower.equals("description") || lower.equals("id")
                || lower.equals("count") || lower.equals("amount");
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // =========================================================================
    // Graph-node-based extraction (for subprocess mode)
    // =========================================================================

    /**
     * Extracts cross-document relationships from existing DOCUMENT-level graph nodes.
     * This is the primary entry point when documents are uploaded one at a time via
     * subprocess mode — it works entirely from persisted GraphNode metadata rather
     * than in-memory Document objects.
     *
     * @param factSheetId Fact sheet scope (nullable for global)
     * @return Number of relationships created
     */
    @Transactional
    public int extractRelationsFromGraphNodes(Long factSheetId) {
        List<GraphNode> docNodes;
        if (factSheetId != null) {
            docNodes = graphNodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.DOCUMENT);
        } else {
            docNodes = graphNodeRepository.findByNodeType(NodeLevel.DOCUMENT);
        }

        if (docNodes == null || docNodes.size() < 2) {
            logger.info("Cross-document extraction (graph-based): fewer than 2 DOCUMENT nodes, skipping");
            return 0;
        }

        logger.info("Cross-document extraction (graph-based): processing {} DOCUMENT nodes (factSheet={})",
                docNodes.size(), factSheetId);

        int totalCreated = 0;

        try {
            // Build index by file name
            Map<String, GraphNodeInfo> nodesByFileName = new LinkedHashMap<>();
            for (GraphNode node : docNodes) {
                String fileName = node.getTitle(); // title is the filename
                if (fileName != null) {
                    nodesByFileName.put(fileName.toLowerCase(), new GraphNodeInfo(node, fileName));
                }
            }

            logger.info("Cross-doc file name index has {} entries: {}",
                    nodesByFileName.size(),
                    nodesByFileName.keySet().stream().limit(10).collect(java.util.stream.Collectors.joining(", ")));

            // Strategy A: Attachment resolution
            int stratA = resolveAttachmentsFromNodes(docNodes, nodesByFileName, factSheetId);
            totalCreated += stratA;
            logger.info("Cross-doc Strategy A (attachments): {} edges created", stratA);

            // Strategy B: Version chain detection
            int stratB = detectVersionChainsFromNodes(nodesByFileName, factSheetId);
            totalCreated += stratB;
            logger.info("Cross-doc Strategy B (version chains): {} edges created", stratB);

            // Strategy C: Process-to-data references
            int stratC = detectProcessDataReferencesFromNodes(docNodes, factSheetId);
            totalCreated += stratC;
            logger.info("Cross-doc Strategy C (process-data refs): {} edges created", stratC);

            // Strategy D: Hyperlink resolution
            int stratD = resolveHyperlinksFromNodes(docNodes, nodesByFileName, factSheetId);
            totalCreated += stratD;
            logger.info("Cross-doc Strategy D (hyperlinks): {} edges created", stratD);

            // Strategy E: Shared author linking
            int stratE = detectSharedAuthorsFromNodes(docNodes, factSheetId);
            totalCreated += stratE;
            logger.info("Cross-doc Strategy E (shared authors): {} edges created", stratE);

            // Strategy F: Shared keywords/topics
            int stratF = detectSharedKeywordsFromNodes(docNodes, factSheetId);
            totalCreated += stratF;
            logger.info("Cross-doc Strategy F (shared keywords): {} edges created", stratF);

        } catch (Exception e) {
            logger.warn("Cross-document relation extraction (graph-based) failed: {}", e.getMessage(), e);
        }

        if (totalCreated > 0) {
            logger.info("Created {} cross-document relationships from graph nodes (factSheet={})",
                    totalCreated, factSheetId);
        }

        return totalCreated;
    }

    private int resolveAttachmentsFromNodes(List<GraphNode> docNodes,
                                             Map<String, GraphNodeInfo> nodesByFileName,
                                             Long factSheetId) {
        int created = 0;
        int nodesWithAttachments = 0;
        for (GraphNode node : docNodes) {
            Map<String, Object> meta = parseMetadata(node.getMetadataJson());
            if (meta == null) continue;

            // Collect attachment file names from all supported sources
            List<String> attachmentNames = new ArrayList<>();
            String parentLabel = null;

            // --- Email attachment names (plural list from HTML emails) ---
            @SuppressWarnings("unchecked")
            List<String> emailAtts = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAMES) instanceof List
                    ? (List<String>) meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAMES) : null;
            if (emailAtts != null && !emailAtts.isEmpty()) {
                attachmentNames.addAll(emailAtts);
                parentLabel = (String) meta.get(GraphConstants.META_EMAIL_SUBJECT);
                if (parentLabel == null) parentLabel = (String) meta.get("gworkspace.gmail.subject");
            }

            // Also check singular form (from MIME-parsed email attachment documents)
            if (attachmentNames.isEmpty()) {
                String singleAttachment = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAME) instanceof String
                        ? (String) meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAME) : null;
                if (singleAttachment != null && !singleAttachment.isEmpty()) {
                    attachmentNames.add(singleAttachment);
                    parentLabel = (String) meta.get(GraphConstants.META_EMAIL_SUBJECT);
                    if (parentLabel == null) parentLabel = (String) meta.get("gworkspace.gmail.subject");
                }
            }

            // --- Gmail-style attachments (List of Maps with "filename" key) ---
            if (attachmentNames.isEmpty()) {
                Object gmailAtts = meta.get("gworkspace.gmail.attachments");
                if (gmailAtts instanceof List<?> gmailAttList && !gmailAttList.isEmpty()) {
                    for (Object attObj : gmailAttList) {
                        if (attObj instanceof Map<?, ?> attMap) {
                            Object fn = attMap.get("filename");
                            if (fn != null && !fn.toString().isBlank()) {
                                attachmentNames.add(fn.toString().trim());
                            }
                        }
                    }
                    if (!attachmentNames.isEmpty()) {
                        parentLabel = (String) meta.get("gworkspace.gmail.subject");
                    }
                }
            }

            // --- Confluence attachments (List of Maps with "title" key) ---
            if (attachmentNames.isEmpty()) {
                Object confAtts = meta.get(GraphConstants.META_CONFLUENCE_ATTACHMENTS);
                if (confAtts instanceof List<?> confAttList && !confAttList.isEmpty()) {
                    for (Object attObj : confAttList) {
                        if (attObj instanceof Map<?, ?> attMap) {
                            Object title = attMap.get("title");
                            if (title != null && !title.toString().isBlank()) {
                                attachmentNames.add(title.toString().trim());
                            }
                        }
                    }
                    if (!attachmentNames.isEmpty()) {
                        parentLabel = meta.get("confluence.title") instanceof String
                                ? (String) meta.get("confluence.title") : node.getTitle();
                    }
                }
            }

            // --- Slack file shares (List of Maps with "name" key) ---
            if (attachmentNames.isEmpty()) {
                Object slackFiles = meta.get("slack.files");
                if (slackFiles instanceof List<?> slackFileList && !slackFileList.isEmpty()) {
                    for (Object fileObj : slackFileList) {
                        if (fileObj instanceof Map<?, ?> fileMap) {
                            Object name = fileMap.get("name");
                            if (name != null && !name.toString().isBlank()) {
                                attachmentNames.add(name.toString().trim());
                            }
                        }
                    }
                    if (!attachmentNames.isEmpty()) {
                        parentLabel = meta.get("slack.channelName") instanceof String
                                ? "Slack #" + meta.get("slack.channelName") : node.getTitle();
                    }
                }
            }

            if (attachmentNames.isEmpty()) continue;

            nodesWithAttachments++;
            logger.info("Cross-doc Strategy A: '{}' references attachments: {}", node.getTitle(), attachmentNames);

            for (String attachmentName : attachmentNames) {
                GraphNodeInfo target = findNodeByFileName(nodesByFileName, attachmentName);
                if (target == null) {
                    logger.info("Cross-doc Strategy A: attachment '{}' NOT found in file name index", attachmentName);
                    continue;
                }

                if (crossDocumentEdgeExists(node.getNodeId(), target.node.getNodeId(), GraphConstants.REL_ATTACHMENT_OF, factSheetId)) {
                    logger.debug("Cross-doc Strategy A: ATTACHMENT_OF edge already exists {} -> {}",
                            node.getNodeId(), target.node.getNodeId());
                    continue;
                }

                try {
                    Map<String, Object> edgeMeta = new LinkedHashMap<>();
                    edgeMeta.put("semanticType", GraphConstants.REL_ATTACHMENT_OF);
                    edgeMeta.put("parentDocument", parentLabel);
                    edgeMeta.put("attachmentFilename", attachmentName);

                    knowledgeGraphService.createEdgeWithMetadata(
                            node.getNodeId(), target.node.getNodeId(), EdgeType.USER_DEFINED,
                            0.95, GraphConstants.REL_ATTACHMENT_OF,
                            "Attachment: " + attachmentName,
                            toJson(edgeMeta), EdgeProvenance.EXTRACTED, factSheetId);
                    created++;
                    logger.info("Created ATTACHMENT_OF edge: '{}' → '{}'",
                            node.getTitle(), attachmentName);
                } catch (Exception e) {
                    logger.warn("Failed to create attachment edge from '{}' to '{}': {}",
                            node.getTitle(), attachmentName, e.getMessage());
                }
            }
        }
        return created;
    }

    private int detectVersionChainsFromNodes(Map<String, GraphNodeInfo> nodesByFileName,
                                              Long factSheetId) {
        int created = 0;

        Map<String, List<GraphNodeInfo>> groups = new LinkedHashMap<>();
        for (GraphNodeInfo info : nodesByFileName.values()) {
            String baseName = normalizeFileName(info.fileName);
            groups.computeIfAbsent(baseName, k -> new ArrayList<>()).add(info);
        }

        for (Map.Entry<String, List<GraphNodeInfo>> entry : groups.entrySet()) {
            List<GraphNodeInfo> versions = entry.getValue();
            if (versions.size() < 2) continue;

            versions.sort(Comparator.<GraphNodeInfo>comparingInt(v -> extractVersionNumberFromName(v.fileName))
                    .thenComparing(v -> v.fileName));

            for (int i = 0; i < versions.size() - 1; i++) {
                GraphNodeInfo older = versions.get(i);
                GraphNodeInfo newer = versions.get(i + 1);

                if (crossDocumentEdgeExists(newer.node.getNodeId(), older.node.getNodeId(), GraphConstants.REL_VERSION_OF, factSheetId)) {
                    continue;
                }

                try {
                    Map<String, Object> edgeMeta = new LinkedHashMap<>();
                    edgeMeta.put("semanticType", GraphConstants.REL_VERSION_OF);
                    edgeMeta.put("olderVersion", older.fileName);
                    edgeMeta.put("newerVersion", newer.fileName);
                    edgeMeta.put("baseGroup", entry.getKey());

                    knowledgeGraphService.createEdgeWithMetadata(
                            newer.node.getNodeId(), older.node.getNodeId(), EdgeType.USER_DEFINED,
                            0.85, GraphConstants.REL_VERSION_OF,
                            "Version chain: " + newer.fileName + " → " + older.fileName,
                            toJson(edgeMeta), EdgeProvenance.INFERRED, factSheetId);
                    created++;
                    logger.info("Created VERSION_OF edge: '{}' → '{}'", newer.fileName, older.fileName);
                } catch (Exception e) {
                    logger.warn("Failed to create version edge '{}' → '{}': {}",
                            newer.fileName, older.fileName, e.getMessage());
                }
            }
        }

        return created;
    }

    /**
     * Strategy C for graph-node path: detect when a DOCUMENT node's text content
     * references sheet names or column headers from TABLE nodes under other documents.
     */
    private int detectProcessDataReferencesFromNodes(List<GraphNode> docNodes, Long factSheetId) {
        // Build index of TABLE node titles and headers → parent DOCUMENT node
        Map<String, GraphNode> sheetTermIndex = new LinkedHashMap<>();

        List<GraphNode> tableNodes;
        if (factSheetId != null) {
            tableNodes = graphNodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.TABLE);
        } else {
            tableNodes = graphNodeRepository.findByNodeType(NodeLevel.TABLE);
        }

        for (GraphNode tableNode : tableNodes) {
            // Find the parent DOCUMENT node for this table
            GraphNode parentDoc = tableNode.getParent();
            if (parentDoc == null || parentDoc.getNodeType() != NodeLevel.DOCUMENT) continue;

            // Index by table title (sheet name)
            String title = tableNode.getTitle();
            if (title != null && title.length() > 2) {
                sheetTermIndex.put(title.toLowerCase(), parentDoc);
            }

            // Index by column headers from metadata
            Map<String, Object> meta = parseMetadata(tableNode.getMetadataJson());
            if (meta != null) {
                String headerStr = meta.get(GraphConstants.META_TABLE_HEADERS) instanceof String
                        ? (String) meta.get(GraphConstants.META_TABLE_HEADERS) : null;
                if (headerStr != null) {
                    for (String header : headerStr.split(",")) {
                        String h = header.trim();
                        if (h.length() > 4 && !isGenericHeader(h)) {
                            sheetTermIndex.putIfAbsent(h.toLowerCase(), parentDoc);
                        }
                    }
                }
                @SuppressWarnings("unchecked")
                List<String> headers = meta.get("headers") instanceof List
                        ? (List<String>) meta.get("headers") : null;
                if (headers != null) {
                    for (String header : headers) {
                        if (header != null && header.length() > 4 && !isGenericHeader(header)) {
                            sheetTermIndex.putIfAbsent(header.toLowerCase(), parentDoc);
                        }
                    }
                }
            }
        }

        if (sheetTermIndex.isEmpty()) return 0;

        int created = 0;

        for (GraphNode docNode : docNodes) {
            // Gather searchable text from description and contentPreview
            StringBuilder searchText = new StringBuilder();
            if (docNode.getDescription() != null) searchText.append(docNode.getDescription()).append(' ');
            if (docNode.getContentPreview() != null) searchText.append(docNode.getContentPreview());

            String content = searchText.toString();
            if (content.length() < 50) continue;

            String contentLower = content.toLowerCase();
            Set<String> alreadyLinked = new HashSet<>();

            for (Map.Entry<String, GraphNode> entry : sheetTermIndex.entrySet()) {
                String term = entry.getKey();
                GraphNode targetParentDoc = entry.getValue();

                // Avoid self-links
                if (targetParentDoc.getNodeId().equals(docNode.getNodeId())) continue;

                if (!contentLower.contains(term)) continue;

                if (alreadyLinked.contains(targetParentDoc.getNodeId())) continue;
                alreadyLinked.add(targetParentDoc.getNodeId());

                if (crossDocumentEdgeExists(docNode.getNodeId(), targetParentDoc.getNodeId(), GraphConstants.REL_REFERENCES_DATA, factSheetId)) {
                    continue;
                }

                try {
                    Map<String, Object> edgeMeta = new LinkedHashMap<>();
                    edgeMeta.put("semanticType", GraphConstants.REL_REFERENCES_DATA);
                    edgeMeta.put("referencedTerm", term);
                    edgeMeta.put("referencedDocument", targetParentDoc.getTitle());

                    knowledgeGraphService.createEdgeWithMetadata(
                            docNode.getNodeId(), targetParentDoc.getNodeId(), EdgeType.USER_DEFINED,
                            0.7, GraphConstants.REL_REFERENCES_DATA,
                            "References data term: " + term,
                            toJson(edgeMeta), EdgeProvenance.INFERRED, factSheetId);
                    created++;

                    logger.debug("Created REFERENCES_DATA edge (graph): '{}' → '{}' (term: {})",
                            docNode.getTitle(), targetParentDoc.getTitle(), term);
                } catch (Exception e) {
                    logger.warn("Failed to create process-data edge for term '{}': {}",
                            term, e.getMessage());
                }
            }
        }

        if (created > 0) {
            logger.info("Strategy C (graph-based): created {} REFERENCES_DATA edges", created);
        }

        return created;
    }

    // =========================================================================
    // Strategy D (graph-based): Hyperlink resolution
    // =========================================================================

    private int resolveHyperlinksFromNodes(List<GraphNode> docNodes,
                                            Map<String, GraphNodeInfo> nodesByFileName,
                                            Long factSheetId) {
        int created = 0;

        for (GraphNode node : docNodes) {
            Map<String, Object> meta = parseMetadata(node.getMetadataJson());
            if (meta == null) continue;

            String extractionType = meta.get(GraphConstants.META_PDF_EXTRACTION_TYPE) instanceof String
                    ? (String) meta.get(GraphConstants.META_PDF_EXTRACTION_TYPE) : null;
            if (!"annotations".equals(extractionType)) continue;

            // Use contentPreview or description for URL scanning
            String content = node.getContentPreview();
            if (content == null || content.isEmpty()) content = node.getDescription();
            if (content == null || content.isEmpty()) continue;

            java.util.regex.Matcher urlMatcher = java.util.regex.Pattern.compile(
                    "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(content);

            Set<String> processedUrls = new HashSet<>();
            while (urlMatcher.find()) {
                String url = urlMatcher.group();
                if (!processedUrls.add(url.toLowerCase())) continue;

                String urlPath = url;
                int lastSlash = urlPath.lastIndexOf('/');
                String urlFileName = lastSlash >= 0 ? urlPath.substring(lastSlash + 1) : urlPath;
                int queryIdx = urlFileName.indexOf('?');
                if (queryIdx > 0) urlFileName = urlFileName.substring(0, queryIdx);
                urlFileName = urlFileName.replace("%20", " ");

                GraphNodeInfo target = findNodeByFileName(nodesByFileName, urlFileName);
                if (target == null || target.node.getNodeId().equals(node.getNodeId())) continue;

                try {
                    if (crossDocumentEdgeExists(node.getNodeId(), target.node.getNodeId(), GraphConstants.REL_HYPERLINK_TO, factSheetId)) {
                        continue;
                    }

                    Map<String, Object> edgeMeta = new LinkedHashMap<>();
                    edgeMeta.put("semanticType", GraphConstants.REL_HYPERLINK_TO);
                    edgeMeta.put("url", url);
                    edgeMeta.put("targetFileName", target.fileName);

                    knowledgeGraphService.createEdgeWithMetadata(
                            node.getNodeId(), target.node.getNodeId(), EdgeType.USER_DEFINED,
                            0.9, GraphConstants.REL_HYPERLINK_TO,
                            "Document hyperlinks to: " + target.fileName,
                            toJson(edgeMeta), EdgeProvenance.EXTRACTED, factSheetId);
                    created++;
                } catch (Exception e) {
                    logger.warn("Failed to create hyperlink edge for URL '{}': {}", url, e.getMessage());
                }
            }
        }

        if (created > 0) {
            logger.info("Strategy D (graph-based): created {} HYPERLINK_TO edges", created);
        }
        return created;
    }

    // =========================================================================
    // Strategy E (graph-based): Shared author linking
    // =========================================================================

    private int detectSharedAuthorsFromNodes(List<GraphNode> docNodes, Long factSheetId) {
        int created = 0;

        Map<String, List<GraphNode>> docsByAuthor = new LinkedHashMap<>();
        for (GraphNode node : docNodes) {
            Map<String, Object> meta = parseMetadata(node.getMetadataJson());
            if (meta == null) continue;
            String author = resolveAuthor(meta);
            if (author == null || author.isBlank()) continue;
            docsByAuthor.computeIfAbsent(author.toLowerCase().trim(), k -> new ArrayList<>()).add(node);
        }

        for (Map.Entry<String, List<GraphNode>> entry : docsByAuthor.entrySet()) {
            List<GraphNode> authorNodes = entry.getValue();
            if (authorNodes.size() < 2) continue;

            for (int i = 0; i < authorNodes.size(); i++) {
                for (int j = i + 1; j < authorNodes.size(); j++) {
                    String nodeIdA = authorNodes.get(i).getNodeId();
                    String nodeIdB = authorNodes.get(j).getNodeId();

                    try {
                        if (crossDocumentEdgeExists(nodeIdA, nodeIdB, GraphConstants.REL_SHARED_AUTHOR, factSheetId)) {
                            continue;
                        }

                        Map<String, Object> edgeMeta = new LinkedHashMap<>();
                        edgeMeta.put("semanticType", GraphConstants.REL_SHARED_AUTHOR);
                        edgeMeta.put("author", entry.getKey());

                        knowledgeGraphService.createEdgeWithMetadata(
                                nodeIdA, nodeIdB, EdgeType.USER_DEFINED,
                                0.75, GraphConstants.REL_SHARED_AUTHOR,
                                "Both authored by: " + entry.getKey(),
                                toJson(edgeMeta), EdgeProvenance.INFERRED, factSheetId);
                        created++;
                    } catch (Exception e) {
                        logger.warn("Failed to create shared author edge: {}", e.getMessage());
                    }
                }
            }
        }

        if (created > 0) {
            logger.info("Strategy E (graph-based): created {} SHARED_AUTHOR edges", created);
        }
        return created;
    }

    // =========================================================================
    // Strategy F (graph-based): Shared keywords/topics
    // =========================================================================

    private int detectSharedKeywordsFromNodes(List<GraphNode> docNodes, Long factSheetId) {
        int created = 0;

        Map<String, List<GraphNode>> docsByKeyword = new LinkedHashMap<>();
        for (GraphNode node : docNodes) {
            Map<String, Object> meta = parseMetadata(node.getMetadataJson());
            if (meta == null) continue;
            String keywords = resolveKeywords(meta);
            if (keywords == null || keywords.isBlank()) continue;

            for (String keyword : keywords.split("[,;|]")) {
                String normalized = keyword.trim().toLowerCase();
                if (normalized.length() > 2) {
                    docsByKeyword.computeIfAbsent(normalized, k -> new ArrayList<>()).add(node);
                }
            }
        }

        Set<String> createdPairs = new HashSet<>();
        for (Map.Entry<String, List<GraphNode>> entry : docsByKeyword.entrySet()) {
            List<GraphNode> keywordNodes = entry.getValue();
            if (keywordNodes.size() < 2) continue;

            for (int i = 0; i < keywordNodes.size(); i++) {
                for (int j = i + 1; j < keywordNodes.size(); j++) {
                    String nodeIdA = keywordNodes.get(i).getNodeId();
                    String nodeIdB = keywordNodes.get(j).getNodeId();

                    String pairKey = nodeIdA.compareTo(nodeIdB) < 0
                            ? nodeIdA + "|" + nodeIdB : nodeIdB + "|" + nodeIdA;
                    if (!createdPairs.add(pairKey)) continue;

                    try {
                        if (crossDocumentEdgeExists(nodeIdA, nodeIdB, GraphConstants.REL_SHARED_KEYWORD, factSheetId)) {
                            continue;
                        }

                        Map<String, Object> edgeMeta = new LinkedHashMap<>();
                        edgeMeta.put("semanticType", GraphConstants.REL_SHARED_KEYWORD);
                        edgeMeta.put("keyword", entry.getKey());

                        knowledgeGraphService.createEdgeWithMetadata(
                                nodeIdA, nodeIdB, EdgeType.USER_DEFINED,
                                0.65, GraphConstants.REL_SHARED_KEYWORD,
                                "Shared keyword: " + entry.getKey(),
                                toJson(edgeMeta), EdgeProvenance.INFERRED, factSheetId);
                        created++;
                    } catch (Exception e) {
                        logger.warn("Failed to create shared keyword edge: {}", e.getMessage());
                    }
                }
            }
        }

        if (created > 0) {
            logger.info("Strategy F (graph-based): created {} SHARED_KEYWORD edges", created);
        }
        return created;
    }

    private GraphNodeInfo findNodeByFileName(Map<String, GraphNodeInfo> index, String searchName) {
        GraphNodeInfo exact = index.get(searchName.toLowerCase());
        if (exact != null) return exact;

        String normalized = normalizeForMatching(searchName.toLowerCase());

        for (Map.Entry<String, GraphNodeInfo> entry : index.entrySet()) {
            String key = normalizeForMatching(entry.getKey());
            if (key.equals(normalized)) {
                return entry.getValue();
            }
            // Also try matching without leading numeric prefixes (e.g. "05a_", "02_")
            // since email attachments often omit file numbering prefixes
            String keyNoPrefix = stripLeadingPrefix(key);
            String searchNoPrefix = stripLeadingPrefix(normalized);
            if (keyNoPrefix.equals(searchNoPrefix) || keyNoPrefix.equals(normalized) || key.equals(searchNoPrefix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Strips version suffixes, FINAL/DRAFT markers for fuzzy filename matching.
     */
    private String normalizeForMatching(String name) {
        return name.replaceAll("_v\\d+", "")
                .replaceAll("_final", "")
                .replaceAll("_draft", "");
    }

    /**
     * Strips leading numeric/alphanumeric prefix patterns like "05a_", "02_", "08_".
     * Common in document numbering schemes where emails reference the base name.
     */
    private String stripLeadingPrefix(String name) {
        return name.replaceFirst("^\\d+[a-z]?[ _-]+", "");
    }

    private boolean crossDocumentEdgeExists(String sourceNodeId, String targetNodeId, String label, Long factSheetId) {
        return knowledgeGraphService.edgeExists(sourceNodeId, targetNodeId, EdgeType.USER_DEFINED, label, factSheetId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return null;
        try {
            return objectMapper.readValue(metadataJson, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Internal holder for document info during extraction.
     */
    private static class DocumentInfo {
        final Document doc;
        final String fileName;

        DocumentInfo(Document doc, String fileName) {
            this.doc = doc;
            this.fileName = fileName;
        }
    }

    private static class GraphNodeInfo {
        final GraphNode node;
        final String fileName;

        GraphNodeInfo(GraphNode node, String fileName) {
            this.node = node;
            this.fileName = fileName;
        }
    }
}
