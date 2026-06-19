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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gdocs;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.utils.MapUtils;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

/**
 * DocumentLoader implementation for Google Docs.
 * Loads Google Docs documents using the Google Docs API v1 for structured content
 * and the Google Drive API v3 for metadata and discovery.
 *
 * Supports configuration via DocumentSourceDescriptor metadata:
 * - accessToken (required): OAuth2 access token with drive.readonly scope
 * - documentIds: Comma-separated list of document IDs to load
 * - driveQuery: Drive API query to discover documents
 * - folderId: Drive folder ID to search within
 * - maxDocuments: Maximum number of documents to load (default: 100)
 * - daysBack: Only load documents modified in the last N days (default: 90)
 * - useDocsApi: Use structured Docs API parsing (default: true)
 * - includeComments: Include document comments (default: false)
 */
@Slf4j
@Component
public class GoogleDocsLoaderImpl implements DocumentLoader {

    private final GoogleDocsParser docsParser = new GoogleDocsParser();

    @Override
    public String getName() {
        return "Google Docs Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        return sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.GDOCS;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor descriptor) throws Exception {
        return load(descriptor, null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor descriptor,
                               Consumer<LoaderProgress> progressCallback) throws Exception {
        Map<String, Object> meta = descriptor.getMetadata();
        if (meta == null) {
            throw new IllegalArgumentException(
                    "Google Docs loader requires metadata with at least 'accessToken'");
        }

        String accessToken = (String) meta.get("accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Google Docs loader requires 'accessToken' in metadata");
        }

        boolean useDocsApi = MapUtils.getBoolean(meta, "useDocsApi", true);
        int maxDocuments = MapUtils.getInt(meta, "maxDocuments", 100);

        GoogleDocsApiClient apiClient = new GoogleDocsApiClient(accessToken);

        if (progressCallback != null) {
            progressCallback.accept(new LoaderProgress("gdocs", 5,
                    "discovering", "Discovering Google Docs...", Map.of()));
        }

        // Determine document IDs to load
        List<String> documentIds = resolveDocumentIds(meta, apiClient, maxDocuments);

        if (progressCallback != null) {
            progressCallback.accept(new LoaderProgress("gdocs", 10,
                    "loading", "Found " + documentIds.size() + " documents to load", Map.of()));
        }

        List<Document> documents = new ArrayList<>();
        int total = documentIds.size();

        for (int i = 0; i < total; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Google Docs loader interrupted after {} of {} documents", i, total);
                break;
            }

            String docId = documentIds.get(i);
            try {
                JsonNode driveMetadata = apiClient.getFileMetadata(docId);

                Document doc;
                if (useDocsApi) {
                    try {
                        JsonNode docsJson = apiClient.getDocument(docId);
                        doc = docsParser.parse(docsJson, driveMetadata);
                    } catch (Exception e) {
                        log.debug("Docs API failed for {}, falling back to text export", docId);
                        String text = apiClient.exportAsText(docId);
                        doc = docsParser.parseFromPlainText(text, driveMetadata);
                    }
                } else {
                    String text = apiClient.exportAsText(docId);
                    doc = docsParser.parseFromPlainText(text, driveMetadata);
                }

                // Resolve parent folder display name from Drive API
                String parentFolderId = doc.getMetadata().get("gdocs.folderId") instanceof String s ? s : null;
                if (parentFolderId != null && doc.getMetadata().get("gdocs.folderName") == null) {
                    try {
                        JsonNode folderMeta = apiClient.getFileMetadata(parentFolderId);
                        if (folderMeta != null && folderMeta.has("name")) {
                            doc.getMetadata().put("gdocs.folderName", folderMeta.get("name").asText());
                        }
                    } catch (Exception e) {
                        log.debug("Could not resolve folder name for {}: {}", parentFolderId, e.getMessage());
                    }
                }

                documents.add(doc);

                // Optionally load comments for this document
                boolean includeComments = MapUtils.getBoolean(meta, "includeComments", false);
                if (includeComments) {
                    try {
                        List<JsonNode> comments = apiClient.listComments(docId, 100);
                        for (JsonNode comment : comments) {
                            String commentId = comment.path("id").asText();
                            String content = comment.path("content").asText("");
                            String author = comment.path("author").path("displayName").asText("Unknown");
                            String authorEmail = comment.path("author").path("emailAddress").asText(null);
                            String createdTime = comment.path("createdTime").asText("");
                            boolean resolved = comment.path("resolved").asBoolean(false);

                            StringBuilder commentText = new StringBuilder();
                            commentText.append("Comment on: ").append(driveMetadata.path("name").asText(docId)).append("\n");
                            commentText.append("By: ").append(author).append("\n");
                            commentText.append("Date: ").append(createdTime).append("\n");
                            commentText.append("Status: ").append(resolved ? "Resolved" : "Open").append("\n\n");
                            commentText.append(content);

                            JsonNode replies = comment.get("replies");
                            List<Map<String, String>> replyMeta = new ArrayList<>();
                            if (replies != null && replies.isArray()) {
                                for (JsonNode reply : replies) {
                                    String replyId = reply.path("id").asText(null);
                                    String replyAuthor = reply.path("author").path("displayName").asText("Unknown");
                                    String replyAuthorEmail = reply.path("author").path("emailAddress").asText(null);
                                    String replyContent = reply.path("content").asText("");
                                    commentText.append("\n\nReply by ").append(replyAuthor).append(":\n").append(replyContent);
                                    Map<String, String> rm = new LinkedHashMap<>();
                                    if (replyId != null) rm.put("replyId", replyId);
                                    rm.put("author", replyAuthor);
                                    if (replyAuthorEmail != null && !replyAuthorEmail.isBlank()) {
                                        rm.put("authorEmail", replyAuthorEmail);
                                    }
                                    if (!replyContent.isBlank()) {
                                        rm.put("content", replyContent);
                                    }
                                    String replyCreatedTime = reply.path("createdTime").asText(null);
                                    if (replyCreatedTime != null) rm.put("createdTime", replyCreatedTime);
                                    String replyModifiedTime = reply.path("modifiedTime").asText(null);
                                    if (replyModifiedTime != null) rm.put("modifiedTime", replyModifiedTime);
                                    replyMeta.add(rm);
                                }
                            }

                            Map<String, Object> commentMeta = new HashMap<>();
                            commentMeta.put(GraphConstants.META_SOURCE, "gdocs://documents/" + docId + "/comments/" + commentId);
                            commentMeta.put(GraphConstants.META_SOURCE_TYPE, "gdocs_comment");
                            commentMeta.put("gdocs.documentId", docId);
                            commentMeta.put("gdocs.commentId", commentId);
                            commentMeta.put("gdocs.commentAuthor", author);
                            if (authorEmail != null && !authorEmail.isBlank()) {
                                commentMeta.put("gdocs.commentAuthorEmail", authorEmail);
                            }
                            commentMeta.put("gdocs.commentContent", content);
                            commentMeta.put("gdocs.commentCreatedTime", createdTime);
                            commentMeta.put("gdocs.commentResolved", resolved);
                            // Extract quoted text the comment is anchored to
                            String quotedText = comment.path("quotedFileContent").path("value").asText(null);
                            if (quotedText != null && !quotedText.isBlank()) {
                                commentMeta.put("gdocs.commentQuotedText", quotedText);
                            }
                            if (!replyMeta.isEmpty()) {
                                commentMeta.put("gdocs.commentReplies", replyMeta);
                            }

                            commentMeta.put(GraphConstants.META_SOURCE_PATH, "gdocs://documents/" + docId + "/comments/" + commentId);
                            commentMeta.put(GraphConstants.META_LOADER, "Google Docs Loader");
                            commentMeta.put(GraphConstants.META_DOCUMENT_TYPE, "gdocs_comment");
                            commentMeta.put(GraphConstants.META_FILE_NAME, "Comment by " + author + " on " + docId);
                            documents.add(new Document(commentText.toString(), commentMeta));
                        }
                    } catch (Exception ce) {
                        log.debug("Failed to fetch comments for doc {}: {}", docId, ce.getMessage());
                    }

                    // Also load revisions
                    try {
                        List<JsonNode> revisions = apiClient.listRevisions(docId, 50);
                        if (revisions.size() > 1) { // Skip if only one revision (the current one)
                            String prevRevisionId = null;
                            for (JsonNode revision : revisions) {
                                String revisionId = revision.path("id").asText();
                                String modifiedTime = revision.path("modifiedTime").asText("");
                                String modifierName = revision.path("lastModifyingUser").path("displayName").asText(null);
                                String modifierEmail = revision.path("lastModifyingUser").path("emailAddress").asText(null);

                                Map<String, Object> revMeta = new HashMap<>();
                                revMeta.put(GraphConstants.META_SOURCE, "gdocs://documents/" + docId + "/revisions/" + revisionId);
                                revMeta.put(GraphConstants.META_SOURCE_TYPE, "gdocs_revision");
                                revMeta.put("gdocs.documentId", docId);
                                revMeta.put("gdocs.revisionId", revisionId);
                                revMeta.put("gdocs.revisionModifiedTime", modifiedTime);
                                if (modifierName != null) revMeta.put("gdocs.revisionModifier", modifierName);
                                if (modifierEmail != null) revMeta.put("gdocs.revisionModifierEmail", modifierEmail);
                                if (prevRevisionId != null) revMeta.put("gdocs.previousRevisionId", prevRevisionId);

                                revMeta.put(GraphConstants.META_SOURCE_PATH, "gdocs://documents/" + docId + "/revisions/" + revisionId);
                                revMeta.put(GraphConstants.META_LOADER, "Google Docs Loader");
                                revMeta.put(GraphConstants.META_DOCUMENT_TYPE, "gdocs_revision");
                                revMeta.put(GraphConstants.META_FILE_NAME, "Revision " + revisionId + " of " + driveMetadata.path("name").asText(docId));
                                String revText = "Revision " + revisionId + " of " + driveMetadata.path("name").asText(docId)
                                        + " at " + modifiedTime
                                        + (modifierName != null ? " by " + modifierName : "");
                                documents.add(new Document(revText, revMeta));
                                prevRevisionId = revisionId;
                            }
                        }
                    } catch (Exception re) {
                        log.debug("Failed to fetch revisions for doc {}: {}", docId, re.getMessage());
                    }
                }

            } catch (Exception e) {
                log.warn("Failed to load Google Doc {}: {}", docId, e.getMessage());
            }

            if (progressCallback != null && (i % 20 == 0 || i == total - 1)) {
                int pct = 10 + (int) ((80.0 * i) / total);
                progressCallback.accept(new LoaderProgress("gdocs", pct,
                        "loading", "Loading document " + (i + 1) + " of " + total,
                        Map.of("loaded", i + 1, "total", total)));
            }
        }

        if (progressCallback != null) {
            progressCallback.accept(new LoaderProgress("gdocs", 95,
                    "complete", "Loaded " + documents.size() + " documents from Google Docs",
                    Map.of("totalDocuments", documents.size())));
        }

        log.info("Google Docs loader finished: loaded {} documents", documents.size());
        return documents;
    }

    private List<String> resolveDocumentIds(Map<String, Object> meta,
                                             GoogleDocsApiClient apiClient,
                                             int maxDocuments) throws Exception {
        // Explicit document IDs
        Object docIdsRaw = meta.get("documentIds");
        if (docIdsRaw instanceof String s && !s.isBlank()) {
            List<String> ids = new ArrayList<>();
            for (String id : s.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) ids.add(trimmed);
            }
            if (!ids.isEmpty()) return ids;
        }
        if (docIdsRaw instanceof List<?> list && !list.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    String s = item.toString().trim();
                    if (!s.isEmpty()) ids.add(s);
                }
            }
            if (!ids.isEmpty()) return ids;
        }

        // Folder-based discovery
        String folderId = MapUtils.getStringNonBlank(meta, "folderId", null);
        if (folderId != null) {
            List<JsonNode> files = apiClient.listFilesInFolder(folderId, maxDocuments);
            List<String> ids = new ArrayList<>();
            for (JsonNode f : files) {
                if ("application/vnd.google-apps.document".equals(f.path("mimeType").asText(""))) {
                    ids.add(f.path("id").asText());
                }
            }
            return ids;
        }

        // Query-based discovery
        String driveQuery = MapUtils.getStringNonBlank(meta, "driveQuery", null);
        List<JsonNode> files = apiClient.listDocFiles(driveQuery, maxDocuments);
        List<String> ids = new ArrayList<>();
        for (JsonNode f : files) {
            ids.add(f.path("id").asText());
        }
        return ids;
    }

}
